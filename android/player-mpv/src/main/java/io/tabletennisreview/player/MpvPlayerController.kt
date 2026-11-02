package io.tabletennisreview.player

import android.os.Handler
import android.os.Looper
import io.github.yuroyami.libmpvkt.*
import io.github.yuroyami.libmpvkt.view.MpvOptions
import io.github.yuroyami.libmpvkt.view.MpvView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.util.concurrent.Executors

internal class MpvPlayerController(private val video: MpvView) : PlayerController {
    private val main = Handler(Looper.getMainLooper())
    private val starter = Executors.newSingleThreadExecutor { r -> Thread(r, "Review-mpv-start") }
    private val epoch = PlayerEpoch()
    private var core: Mpv? = null
    private var scope: CoroutineScope? = null
    private var preparing = false
    private var closed = false
    private var op = 0L
    private var stateListener: ((PlayerState) -> Unit)? = null
    private var logListener: ((String) -> Unit)? = null
    override var state = PlayerState()
        private set

    private fun mainOnly() = check(Looper.myLooper() == Looper.getMainLooper()) { "PlayerController须在主线程调用" }
    private fun emit(value: PlayerState) { state = value; stateListener?.invoke(value) }
    private fun log(message: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) logListener?.invoke(message)
        else main.post { if (!closed) logListener?.invoke(message) }
    }
    override fun setStateListener(listener: ((PlayerState) -> Unit)?) { mainOnly(); stateListener = listener; listener?.invoke(state) }
    override fun setLogListener(listener: ((String) -> Unit)?) { mainOnly(); logListener = listener }

    override fun initialize(contentUri: String): CommandAdmission {
        mainOnly()
        if (closed || preparing) return CommandAdmission.Rejected("播放器已关闭或仍在初始化")
        if (!contentUri.startsWith("content://")) return CommandAdmission.Rejected("仅接受系统授权的content文档")
        release("重新初始化")
        val token = epoch.begin()
        preparing = true
        emit(PlayerState(sessionId = token, phase = PlayerPhase.Initializing, busy = true, message = "后台初始化中"))
        val options = MpvOptions(displayFps = video.display?.refreshRate?.toDouble(), extra = mapOf("pause" to "yes", "tls-verify" to "yes"))
        val app = video.context.applicationContext
        log("INIT BEGIN session=$token; using built-in content protocol")
        starter.execute {
            var stage = "create"
            val result = runCatching {
                val fresh = Mpv.create(app)
                log("INIT OK session=$token stage=$stage")
                stage = "prepare-options-and-initialize"
                // prepare owns error cleanup; do not duplicate native close or content registration.
                video.prepare(options, fresh).also { log("INIT OK session=$token stage=$stage") }
            }
            main.post {
                preparing = false
                if (closed || !epoch.isCurrent(token)) {
                    result.getOrNull()?.close()
                    if (!closed) { log("INIT STALE session=$token; async close requested"); emit(state.copy(busy = false)) }
                    return@post
                }
                result.onSuccess { ready ->
                    try {
                        video.attach(ready)
                        core = ready.core
                        emit(state.copy(phase = PlayerPhase.Ready, busy = false, message = "核心就绪，等待加载"))
                        observe(ready.core, token)
                        log("INIT READY session=$token")
                        emit(state.copy(phase = PlayerPhase.Loading, message = "正在加载本地视频"))
                        scope?.launch {
                            try {
                                if (!epoch.isCurrent(token)) return@launch
                                log("NATIVE session=$token mpv=${ready.core.getString("mpv-version")}")
                                log("NATIVE protocol-list=${runCatching { ready.core.getNode("protocol-list") }}")
                                withTimeout(15_000) { ready.core.commandAsync(MpvCommands.loadFile(contentUri)).getOrThrow() }
                                log("LOAD submitted session=$token; not a rendered-frame guarantee")
                            } catch (timeout: TimeoutCancellationException) {
                                main.post { fail(token, "load-timeout", timeout) }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Throwable) {
                                main.post { fail(token, "load", error) }
                            }
                        }
                    } catch (error: Throwable) {
                        if (video.mpv !== ready.core) ready.close()
                        fail(token, "attach/load", error)
                    }
                }.onFailure { fail(token, stage, it) }
            }
        }
        return CommandAdmission.Accepted
    }

    private fun observe(player: Mpv, token: Long) {
        val errors = CoroutineExceptionHandler { _, error -> main.post { fail(token, "observer", error) } }
        val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default + errors)
        scope = jobs
        jobs.launch(start = CoroutineStart.UNDISPATCHED) {
            player.events.collect { event ->
                if (event !is MpvEvent.PropertyChange) main.post {
                    if (epoch.isCurrent(token)) when (event) {
                        MpvEvent.FileLoaded -> { log("FILE_LOADED session=$token"); emit(state.copy(loaded = true, message = "文件已加载；画面及声音请实际确认")) }
                        is MpvEvent.EndFile -> {
                            log("END_FILE session=$token reason=${event.reason} error=${event.fileError}")
                            if (event.reason == EndFileReason.Error) fail(token, "end-file", IllegalStateException(event.fileError ?: "解码失败"))
                            else emit(state.copy(loaded = false))
                        }
                        MpvEvent.QueueOverflow -> fail(token, "events", IllegalStateException("native事件队列溢出"))
                        is MpvEvent.Unknown -> log("EVENT session=$token Unknown id=${event.id} payload=${event.node}")
                        else -> log("EVENT session=$token ${event.javaClass.simpleName}")
                    }
                }
            }
        }
        jobs.launch {
            player.playback.collect { value ->
                main.post {
                    if (epoch.isCurrent(token)) {
                        val phase = when (value.status) {
                            MpvPlaybackState.Status.Idle -> if (state.loaded) PlayerPhase.Ready else PlayerPhase.Loading
                            MpvPlaybackState.Status.Loading -> PlayerPhase.Loading
                            MpvPlaybackState.Status.Playing -> PlayerPhase.Playing
                            MpvPlaybackState.Status.Paused -> PlayerPhase.Paused
                            MpvPlaybackState.Status.Buffering -> PlayerPhase.Buffering
                            MpvPlaybackState.Status.Ended -> PlayerPhase.Ended
                        }
                        if (phase != state.phase) log("STATE session=$token $phase seekable=${value.seekable} speed=${value.speed}")
                        emit(state.copy(phase = phase, position = value.positionSeconds, duration = value.durationSeconds,
                            speed = value.speed, seekable = value.seekable))
                    }
                }
                delay(250) // State display only; never used to coalesce TourBox inputs.
            }
        }
    }

    override fun execute(command: PlayerCommand): CommandAdmission = executeGuarded(command, { true }, "screen")

    override fun executeGuarded(command: PlayerCommand, authorization: () -> Boolean, origin: String): CommandAdmission {
        mainOnly()
        val player = core ?: return CommandAdmission.Rejected("请先初始化视频")
        val jobs = scope ?: return CommandAdmission.Rejected("播放器尚未就绪")
        val token = state.sessionId
        if (closed || !epoch.isCurrent(token) || !state.loaded || state.busy) return CommandAdmission.Rejected("播放器未加载或操作进行中")
        val admissionCommand = if (command == PlayerCommand.ToggleLoop) PlayerPolicy.toggleLoop(state.loop) else command
        if (PlayerPolicy.needsSeek(admissionCommand) && !state.seekable) return CommandAdmission.Rejected("该文件未报告可seek")
        if (!runCatching { authorization() }.getOrDefault(false)) return CommandAdmission.Rejected("控制来源已失效")
        val oldLoop = state.loop
        val id = ++op
        emit(state.copy(busy = true))
        log("CONTROL BEGIN session=$token op=$id command=$command origin=$origin")
        jobs.launch {
            try {
                fun authorized() {
                    if (!epoch.isCurrent(token) || core !== player || !runCatching { authorization() }.getOrDefault(false))
                        throw CancellationException("控制来源或播放器会话已失效")
                }
                authorized()
                log("CONTROL BEFORE session=$token op=$id ${snapshot(player)}")
                val changed = withTimeout(15_000) {
                    currentCoroutineContext().ensureActive(); authorized()
                    val effective = when (command) {
                        PlayerCommand.TogglePaused -> PlayerPolicy.toggled(player.getAsync(MpvProperties.Pause).getOrThrow())
                        PlayerCommand.CycleSpeed -> PlayerPolicy.nextSpeed(player.getAsync(MpvProperties.Speed).getOrThrow())
                        PlayerCommand.ToggleLoop -> PlayerPolicy.toggleLoop(oldLoop)
                        is PlayerCommand.ShiftSpeed -> PlayerPolicy.shiftSpeed(command.steps, player.getAsync(MpvProperties.Speed).getOrThrow())
                        is PlayerCommand.SeekBySpeed -> PlayerPolicy.speedSeek(command.direction, player.getAsync(MpvProperties.Speed).getOrThrow())
                        else -> command
                    }
                    currentCoroutineContext().ensureActive(); authorized()
                    log("CONTROL RESOLVED session=$token op=$id command=$effective origin=$origin")
                    val loop = when (effective) {
                        PlayerCommand.MarkA -> oldLoop.markA(player.getAsync(MpvProperties.TimePos).getOrThrow())
                        PlayerCommand.MarkB -> oldLoop.markB(player.getAsync(MpvProperties.TimePos).getOrThrow())
                        PlayerCommand.EnableLoop -> oldLoop.enabled(player.getAsync(MpvProperties.Duration).getOrNull())
                        PlayerCommand.ClearLoop -> LoopPoints()
                        else -> oldLoop
                    }
                    val requests = PlayerPolicy.requests(effective, loop)
                    for (request in requests) {
                        currentCoroutineContext().ensureActive()
                        authorized()
                        when (request) {
                            is NativeRequest.Property -> player.setString(request.name, request.value).getOrThrow()
                            is NativeRequest.Command -> player.commandAsync(MpvCommand.of(*request.arguments.toTypedArray())).getOrThrow()
                        }
                    }
                    loop
                }
                log("CONTROL RESULT session=$token op=$id accepted-by-core; ${snapshot(player)}")
                main.post { if (epoch.isCurrent(token)) emit(state.copy(loop = changed, message = "操作已执行；以画面及状态为准")) }
                // Native completion/result above owns serialization; no fixed post-command delay.
                // The independent 250ms display observer remains unchanged.
            } catch (timeout: TimeoutCancellationException) {
                main.post { fail(token, "control-timeout", timeout) }
            } catch (cancelled: CancellationException) {
                log("CONTROL CANCEL session=$token op=$id origin=$origin; no retry"); throw cancelled
            } catch (invalid: IllegalArgumentException) {
                main.post { if (epoch.isCurrent(token)) { log("CONTROL REJECT session=$token op=$id ${invalid.message}"); emit(state.copy(message = invalid.message ?: "参数无效")) } }
            } catch (error: Throwable) {
                main.post { fail(token, "control", error) }
            } finally {
                main.post { if (epoch.isCurrent(token)) emit(state.copy(busy = false)) }
            }
        }
        return CommandAdmission.Accepted
    }

    override fun pauseForPageChange() {
        mainOnly()
        if (core == null || !state.loaded) return
        if (state.busy) release("离开播放器页，取消进行中的操作")
        else execute(PlayerCommand.SetPaused(true))
    }
    private fun snapshot(player: Mpv): String = "time=${player[MpvProperties.TimePos]} speed=${player[MpvProperties.Speed]} pause=${player[MpvProperties.Pause]} A=${player.getString("ab-loop-a")} B=${player.getString("ab-loop-b")}"
    private fun fail(token: Long, stage: String, error: Throwable) {
        if (!epoch.isCurrent(token)) return
        log("PLAYER FAILED session=$token stage=$stage ${error.stackTraceToString()}")
        release("播放器失败:$stage")
        emit(state.copy(phase = PlayerPhase.Error, message = "$stage: ${error.message}"))
    }
    override fun release(reason: String) {
        mainOnly()
        val old = state.sessionId
        epoch.invalidate()
        scope?.cancel(); scope = null; core = null
        runCatching { video.destroy() }.onFailure { log("DESTROY exception ${it.stackTraceToString()}") }
        if (old != 0L) log("RELEASE session=$old reason=$reason; async close requested, native completion unconfirmed")
        emit(PlayerState(busy = preparing, message = if (preparing) "旧初始化尚在返回，结果将回收" else "播放器已停用，请手动初始化"))
    }
    override fun close() {
        mainOnly(); if (closed) return
        release("宿主销毁"); closed = true
        starter.shutdown(); stateListener = null; logListener = null
    }
}

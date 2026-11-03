package io.tabletennisreview.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/** One process-wide serial store; no disk IO on the Activity/UI thread. */
class TourBoxMappingStore private constructor(context: Context) {
    companion object {
        @Volatile private var instance: TourBoxMappingStore? = null
        fun get(context: Context): TourBoxMappingStore = instance ?: synchronized(this) {
            instance ?: TourBoxMappingStore(context.applicationContext).also { instance = it }
        }
    }
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r,"TourBox-mapping-settings") }
    private val listeners = CopyOnWriteArrayList<AtomicReference<((MappingSnapshot) -> Unit)?>>()
    @Volatile private var current = MappingSnapshot(MappingConfig.defaults(),-1,"正在读取设置…")
    private var coordinator: MappingSaveCoordinator? = null
    init {
        worker.execute {
            val port = try {
                val prefs = app.getSharedPreferences("tourbox_mapping",Context.MODE_PRIVATE)
                object : MappingPreferencesPort {
                    override fun read(): String? = prefs.getString("configuration",null)
                    override fun write(value: String?): Boolean {
                        val edit = prefs.edit()
                        if (value == null) edit.remove("configuration") else edit.putString("configuration",value)
                        return edit.commit()
                    }
                }
            } catch(e: Exception) {
                object : MappingPreferencesPort {
                    override fun read(): String? = throw IllegalStateException("无法读取设置",e)
                    override fun write(value: String?) = false
                }
            }
            coordinator = MappingSaveCoordinator(port)
            publish(checkNotNull(coordinator).snapshot())
        }
    }
    fun snapshot() = current
    fun subscribe(callback: (MappingSnapshot) -> Unit): AutoCloseable {
        val ref = AtomicReference<((MappingSnapshot) -> Unit)?>(callback); listeners += ref
        main.post { ref.get()?.invoke(current) }
        return AutoCloseable { ref.set(null); listeners.remove(ref) }
    }
    private fun publish(snapshot: MappingSnapshot) {
        current = snapshot
        main.post { listeners.forEach { ref ->
            try { ref.get()?.invoke(snapshot) } catch(e: Exception) { android.util.Log.e("TourBoxSettings","更新设置订阅失败",e) }
        } }
    }
    fun save(expectedRevision: Long, config: MappingConfig, callback: (MappingSaveResult) -> Unit): AutoCloseable {
        val ref = AtomicReference<((MappingSaveResult) -> Unit)?>(callback)
        worker.execute {
            val result = coordinator?.save(expectedRevision,config) ?: MappingSaveResult(false,current,"设置尚未就绪")
            if (result.success) publish(result.snapshot)
            main.post { ref.getAndSet(null)?.invoke(result) }
        }
        return AutoCloseable { ref.set(null) }
    }
}

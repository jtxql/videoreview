package io.tabletennisreview.usbdiagnostics

import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.io.File

/** Whole records only; explicit eviction notice; thread-safe and memory bounded. */
class DiagnosticLog {
    private val lines = ArrayDeque<String>()
    private var dropped = 0L
    private var characters = 0
    private val important = linkedMapOf<String, String>()
    @Synchronized fun evidence(): String =
        if (important.isEmpty()) "尚无控制请求或候选发送结果；未操作不等于成功。" else important.values.joinToString("\n\n")
    private var capture: File? = null
    @Volatile var captureFailure: String? = null
        private set
    @Synchronized fun startCapture(file: File): Boolean {
        return try {
            file.parentFile?.mkdirs()
            file.writeText("实验文件：从本次候选发送前开始，以下为此前保留的日志快照（可能有淘汰提示）。\n${text()}\n--- 完整实验捕获开始 ---\n", Charsets.UTF_8)
            capture = file
            captureFailure = null
            true
        } catch (e: Exception) {
            captureFailure = "实验文件建立失败：${e.message}"
            false
        }
    }
    @Synchronized fun add(message: String) {
        val record = "${LocalTime.now().format(TIME)}  $message"
        // Keep request outcomes outside the RX ring, including failures and negatives.
        if (message.startsWith("[CDC A") || message.startsWith("[候选 TX") || message.startsWith("[配置94")) {
            val key = message.substringBefore(']')
            important[key] = record
            while (important.size > 32) important.remove(important.keys.first())
        }
        val file = capture
        if (file != null && captureFailure == null) {
            try {
                val bytes = (record + "\n").toByteArray(Charsets.UTF_8)
                check(file.length() + bytes.size <= 8L * 1024 * 1024) { "实验文件达到8MiB上限；停止采集，日志不再完整" }
                file.appendBytes(bytes)
            } catch (e: Exception) {
                captureFailure = "实验日志写入失败：${e.message}；后续文件可能不完整"
                try { file.appendText("\n[CAPTURE FAILED] $captureFailure\n", Charsets.UTF_8) } catch (_: Exception) { }
            }
        }
        lines.addLast(record)
        characters += lines.last().length
        while (lines.size > 200 || (characters > 128 * 1024 && lines.size > 1)) {
            characters -= lines.removeFirst().length
            dropped++
        }
    }
    @Synchronized fun text(): String =
        (if (dropped > 0) "[已淘汰 $dropped 条旧记录；本快照不是完整会话。单次 RX 记录未截断]\n" else "") + lines.joinToString("\n")
    private companion object {
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    }
}

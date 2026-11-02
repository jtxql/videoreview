package io.tabletennisreview.export

import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CancellationException

/** Success requires byte count, flush, BOTH closes and atomic commit, not just progress=100. */
object ClipStreamCopy {
    fun copy(input: InputStream, output: OutputStream, expectedBytes: Long,
        cancelled: () -> Boolean, commit: ClipSaveCommit): Long {
        var total = 0L
        output.use { out ->
            input.use { source ->
                check(expectedBytes > 0) { "临时片段为空" }
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    if (cancelled()) throw CancellationException()
                    val count = source.read(buffer); if (count < 0) break
                    out.write(buffer, 0, count); total += count
                }
            }
            out.flush()
        }
        check(total == expectedBytes) { "另存字节数不匹配：$total/$expectedBytes" }
        if (cancelled() || !commit.commit()) throw CancellationException()
        return total
    }
}

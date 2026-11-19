package cc.fplj.magpiemobile.core

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream

/** SSE line framing with limits enforced while bytes arrive, before UTF-8 decoding or JSON.
 * Delimiters count toward the response budget, but not the per-line payload budget. A CR ends
 * its line immediately; a following LF is skipped on the next read, so DONE never waits for it.
 */
internal class BoundedSseReader(
    private val input: InputStream,
    private val limits: StreamingChatLimits,
    private val ensureActive: () -> Unit = {},
) : Closeable {
    private val buffer = ByteArray(8192)
    private var offset = 0
    private var available = 0
    private var received = 0L
    private var skipLf = false
    private var ended = false

    fun readLine(): String? {
        ensureActive()
        val line = ByteArrayOutputStream()
        while (true) {
            val next = readByte()
            if (next == -1) return if (line.size() == 0) null else decode(line)
            if (skipLf) {
                skipLf = false
                if (next == '\n'.code) continue
            }
            when (next) {
                '\n'.code -> return decode(line)
                '\r'.code -> {
                    skipLf = true
                    return decode(line)
                }
                else -> {
                    if (line.size() == limits.maxLineBytes) throw responseLimit()
                    line.write(next)
                }
            }
        }
    }

    private fun readByte(): Int {
        ensureActive()
        if (ended) return -1
        if (offset == available) {
            // Read at most one byte beyond the response budget, enough to distinguish an exact
            // bound from an oversized body. This also bounds prefetch after an early DONE line.
            val request = minOf((buffer.size - 1).toLong(), limits.maxResponseBytes - received).toInt() + 1
            while (true) {
                available = input.read(buffer, 0, request)
                ensureActive()
                if (available != 0) break
            }
            offset = 0
            if (available == -1) {
                ended = true
                return -1
            }
        }
        if (received == limits.maxResponseBytes) throw responseLimit()
        received++
        return buffer[offset++].toInt() and 0xff
    }

    private fun decode(line: ByteArrayOutputStream): String {
        ensureActive()
        return line.toString(Charsets.UTF_8.name())
    }

    private fun responseLimit() = StreamingChatException(StreamingChatFailure.RESPONSE_LIMIT, "")

    override fun close() = input.close()
}

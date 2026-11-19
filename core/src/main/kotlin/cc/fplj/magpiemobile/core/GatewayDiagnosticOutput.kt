package cc.fplj.magpiemobile.core

/** Only complete, fully redacted lines can enter this shared bounded diagnostic buffer. */
internal class GatewayDiagnosticTail(
    private val capacity: Int = 8192,
    private val lineLimit: Int = 8192,
) {
    data class Snapshot(val revision: Long, val text: String)

    private val text = StringBuilder(capacity)
    private var revision = 0L
    private var unterminatedLine = false

    init {
        require(capacity > 0)
        require(lineLimit > 0)
    }

    /** Each drain owns a separate carry, so stderr cannot complete a partial stdout secret. */
    fun openStream(): Stream = Stream()

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(revision, text.toString())

    private fun appendLine(line: String, terminated: Boolean) {
        // Redact before truncation: dropping a raw prefix could hide a credential's field name.
        val safe = SecretRedactor.redactDiagnostic(line) + if (terminated) "\n" else ""
        if (safe.isEmpty()) return
        synchronized(this) {
            // EOF from one stream/attempt must not splice into another stream's first line.
            val retained = ((if (unterminatedLine) "\n" else "") + safe).takeLast(capacity)
            val excess = (text.length + retained.length - capacity).coerceAtLeast(0)
            if (excess > 0) text.delete(0, excess)
            text.append(retained)
            unterminatedLine = !terminated
            revision += 1
        }
    }

    inner class Stream internal constructor() {
        private val pending = StringBuilder(lineLimit)
        private var omitted = false
        private var sealed = false

        val pendingCharacters: Int
            @Synchronized get() = pending.length

        @Synchronized
        fun append(chunk: CharArray, count: Int) {
            require(count in 0..chunk.size)
            if (sealed) return
            for (index in 0 until count) {
                val char = chunk[index]
                if (char == '\n') {
                    finishLine(terminated = true)
                } else if (!omitted) {
                    if (pending.length < lineLimit) pending.append(char)
                    else {
                        // Never retain a suffix whose password/Bearer prefix has been lost.
                        pending.setLength(0)
                        omitted = true
                    }
                }
            }
        }

        /** EOF, unlike a close/read error, confirms the last unterminated line is complete. */
        @Synchronized
        fun endOfInput() {
            if (sealed) return
            if (pending.isNotEmpty() || omitted) finishLine(terminated = false)
            sealed = true
        }

        /** A late or uninterruptible old reader must never append to a later attempt's tail. */
        @Synchronized
        fun seal() {
            sealed = true
            pending.setLength(0)
            omitted = false
        }

        private fun finishLine(terminated: Boolean) {
            appendLine(if (omitted) OMITTED_LINE else pending.toString(), terminated)
            pending.setLength(0)
            omitted = false
        }
    }

    companion object {
        const val OMITTED_LINE = "[oversized diagnostic line omitted]"
    }
}

/** Used by the existing worker only; lifecycle publications bypass the monotonic throttle. */
internal class GatewayOutputThrottle(private val intervalNanos: Long) {
    private var publishedRevision = -1L
    private var publishedAt: Long? = null

    init { require(intervalNanos > 0) }

    fun changed(revision: Long, now: Long): Boolean {
        if (revision == publishedRevision) return false
        val previous = publishedAt
        if (previous != null && now - previous < intervalNanos) return false
        published(revision, now)
        return true
    }

    fun published(revision: Long, now: Long) {
        publishedRevision = revision
        publishedAt = now
    }
}

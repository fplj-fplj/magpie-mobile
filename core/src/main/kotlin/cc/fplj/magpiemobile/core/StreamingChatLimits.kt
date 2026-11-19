package cc.fplj.magpiemobile.core

import java.time.Duration

/** Receive-side budgets, independent of model-provided token limits.
 * Byte budgets include SSE framing and ignored events. Text budgets count UTF-16 code units,
 * matching the conversation store's per-field limits. A long valid event fits even when both
 * text fields are fully JSON-escaped; token-by-token streams have a separate framing allowance.
 */
data class StreamingChatLimits(
    val maxLineBytes: Int = 4 * 1024 * 1024,
    val maxResponseBytes: Long = 64L * 1024 * 1024,
    val maxContentChars: Int = 256 * 1024,
    val maxReasoningChars: Int = 256 * 1024,
    val maxCombinedChars: Int = 512 * 1024,
    val maxDuration: Duration = Duration.ofMinutes(10),
) {
    init {
        require(maxLineBytes > 0 && maxResponseBytes > 0 && maxContentChars > 0 &&
            maxReasoningChars > 0 && maxCombinedChars > 0) { "Stream limits must be positive" }
        require(!maxDuration.isNegative && !maxDuration.isZero &&
            maxDuration <= Duration.ofDays(1)) { "Stream duration must be positive and at most one day" }
    }
}

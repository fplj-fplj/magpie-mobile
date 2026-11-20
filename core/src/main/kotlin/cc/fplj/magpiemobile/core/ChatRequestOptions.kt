package cc.fplj.magpiemobile.core

/** Validated, immutable request settings; model selection remains the caller's responsibility. */
data class ChatRequestOptions(
    val systemPrompt: String = "",
    val temperature: Double? = null,
    val topP: Double? = null,
    val maxTokens: Int? = null,
) {
    init {
        require(systemPrompt.length <= 32768) { "System Prompt 长度超出上限 (32KB)" }
        require(temperature == null || temperature.isFinite() && temperature in 0.0..2.0) {
            "Temperature 必须在 0.0 .. 2.0 之间"
        }
        require(topP == null || topP.isFinite() && topP in 0.0..1.0) {
            "Top-P 必须在 0.0 .. 1.0 之间"
        }
        require(maxTokens == null || maxTokens in 1..131072) { "Max Tokens 必须在 1..131072 之间" }
    }

    companion object {
        /** The untouched built-in profile keeps the model's own existing defaults. */
        fun fromProfile(profile: ChatProfile): ChatRequestOptions =
            if (profile == ChatProfile.DEFAULT) ChatRequestOptions()
            else ChatRequestOptions(
                systemPrompt = profile.systemPrompt,
                temperature = profile.temperature,
                topP = profile.topP,
                maxTokens = profile.maxTokens,
            )
    }
}

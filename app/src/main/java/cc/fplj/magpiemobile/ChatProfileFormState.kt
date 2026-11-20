package cc.fplj.magpiemobile

import cc.fplj.magpiemobile.core.ChatProfile

/** In-memory only: prompts are retained on failure without adding another persisted copy. */
internal data class ChatProfileFormState(
    val editing: Boolean = false,
    val id: String = "",
    val name: String = "",
    val prompt: String = "",
    val temperature: String = "0.7",
    val error: String = "",
) {
    val canSubmit: Boolean get() = id.isNotBlank() && name.isNotBlank()

    fun submit(save: (String, String, String, Double) -> Boolean): ChatProfileFormState {
        val parsed = temperature.trim().toDoubleOrNull()
        if (parsed == null || !parsed.isFinite() || parsed !in 0.0..2.0) {
            return copy(error = TEMPERATURE_ERROR)
        }
        return if (save(id, name, prompt, parsed)) ChatProfileFormState() else copy(error = "")
    }

    companion object {
        const val TEMPERATURE_ERROR = "采样温度必须是 0.0 .. 2.0 之间的有限数字。"
    }
}

/** Match request option semantics by value; a customized profile named default still applies. */
internal fun chatProfileParameterSummary(profile: ChatProfile): String =
    if (profile == ChatProfile.DEFAULT) "使用模型默认参数"
    else "温度: ${profile.temperature} · Top-P: ${profile.topP}" +
        (profile.maxTokens?.let { " · 最大输出: $it" } ?: "")

internal fun chatProfilePromptPreview(profile: ChatProfile): String? =
    profile.systemPrompt.takeIf { profile != ChatProfile.DEFAULT && it.isNotBlank() }

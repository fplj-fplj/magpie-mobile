package cc.fplj.magpiemobile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import cc.fplj.magpiemobile.core.ChatProfile
import cc.fplj.magpiemobile.core.ChatProfileStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

data class ChatProfileUiState(
    val profiles: List<ChatProfile> = emptyList(),
    val selectedProfileId: String = ChatProfile.DEFAULT.id,
    val notice: String = "",
    val error: String = "",
    val storageError: String = "",
) {
    val activeProfile: ChatProfile
        get() = profiles.firstOrNull { it.id == selectedProfileId } ?: ChatProfile.DEFAULT

    val canWriteProfiles: Boolean get() = storageError.isEmpty()
}

/**
 * Manages Chat Profile & Agent Parameter Selection (P3-1/P3-2).
 */
class ChatProfileViewModel internal constructor(
    application: Application,
    private val store: ChatProfileStore,
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application, ChatProfileStore(File(application.filesDir, "profiles.json")),
    )

    private val mutable = MutableStateFlow(initialState())
    val state = mutable.asStateFlow()

    private fun initialState(): ChatProfileUiState = try {
        ChatProfileUiState(profiles = store.list())
    } catch (_: Exception) {
        ChatProfileUiState(storageError = STORAGE_ERROR)
    }

    /** Only an explicit, successful read releases the storage failure gate. */
    fun retryReload(): Boolean = try {
        val profiles = store.list()
        mutable.update {
            it.copy(
                profiles = profiles,
                selectedProfileId = it.selectedProfileId.takeIf { id -> profiles.any { profile -> profile.id == id } }
                    ?: ChatProfile.DEFAULT.id,
                storageError = "",
                error = "",
                notice = "已重新读取预设。",
            )
        }
        true
    } catch (_: Exception) {
        mutable.update { it.copy(storageError = STORAGE_ERROR, error = "", notice = "") }
        false
    }

    fun selectProfile(id: String) {
        if (state.value.profiles.any { it.id == id }) {
            mutable.update { it.copy(selectedProfileId = id, notice = "已切换当前 Profile", error = "") }
        }
    }

    fun saveProfile(
        id: String,
        name: String,
        systemPrompt: String = "",
        temperature: Double = 0.7,
        topP: Double = 1.0,
        maxTokens: Int? = null,
        modelAlias: String = "",
    ): Boolean {
        if (!state.value.canWriteProfiles) return false
        if (!temperature.isFinite() || temperature !in 0.0..2.0) {
            mutable.update { it.copy(error = ChatProfileFormState.TEMPERATURE_ERROR, notice = "") }
            return false
        }
        val profile = try {
            ChatProfile(
                id = id.trim(),
                name = name.trim(),
                systemPrompt = systemPrompt.trim(),
                temperature = temperature,
                topP = topP,
                maxTokens = maxTokens,
                modelAlias = modelAlias.trim(),
            )
        } catch (_: Exception) {
            mutable.update { it.copy(error = INVALID_PROFILE_ERROR, notice = "") }
            return false
        }
        return try {
            val profiles = store.save(profile)
            mutable.update {
                it.copy(
                    profiles = profiles,
                    selectedProfileId = profile.id,
                    notice = "已保存 Profile: " + profile.name,
                    error = "",
                )
            }
            true
        } catch (_: Exception) {
            mutable.update { it.copy(error = SAVE_ERROR, storageError = STORAGE_ERROR, notice = "") }
            false
        }
    }

    fun deleteProfile(id: String): Boolean {
        if (!state.value.canWriteProfiles) return false
        return try {
            val updated = store.deleteAndList(id)
            if (updated == null) {
                mutable.update { it.copy(error = "默认 Profile 不允许删除，或该预设已不存在。", notice = "") }
                return false
            }
            mutable.update {
                it.copy(
                    profiles = updated,
                    selectedProfileId = ChatProfile.DEFAULT.id,
                    notice = "已删除 Profile: $id",
                    error = "",
                )
            }
            true
        } catch (_: Exception) {
            mutable.update { it.copy(error = DELETE_ERROR, storageError = STORAGE_ERROR, notice = "") }
            false
        }
    }

    fun clearNotice() {
        mutable.update { it.copy(notice = "", error = "") }
    }

    companion object {
        internal const val STORAGE_ERROR = "预设文件暂时无法读取或写入；原文件已保留，重试读取成功前无法保存或删除。"
        internal const val INVALID_PROFILE_ERROR = "预设参数无效，请检查标识、名称、提示词和参数范围。"
        internal const val SAVE_ERROR = "保存预设失败；输入内容已保留，请重试读取后再保存。"
        internal const val DELETE_ERROR = "删除预设失败；已有预设已保留，请重试读取后再操作。"
    }
}

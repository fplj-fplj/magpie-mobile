package cc.fplj.magpiemobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import cc.fplj.magpiemobile.theme.UiSpacing

data class ExternalEditorState(val opening: Boolean = false, val returnRevision: Int = 0, val error: String = "")

/** The one-use link is delivered directly to ACTION_VIEW; it is never saved in state. */
class ExternalEditorViewModel(
    private val readLink: suspend () -> String = { withContext(Dispatchers.IO) { NativeCoreSession.browserEditorLink() } },
) : ViewModel() {
    private val mutable = MutableStateFlow(ExternalEditorState())
    val state = mutable.asStateFlow()
    var awaitingReturn: Boolean = false
        private set

    fun open(launchBrowser: (String) -> Unit) {
        if (mutable.value.opening) return
        mutable.update { it.copy(opening = true, error = "") }
        viewModelScope.launch {
            try {
                val link = readLink()
                awaitingReturn = true
                launchBrowser(link)
            } catch (e: CancellationException) {
                awaitingReturn = false
                throw e
            } catch (_: Exception) {
                awaitingReturn = false
                mutable.update { it.copy(error = "未能打开官方编辑器，请确认网关运行且浏览器可用后重试。") }
            } finally {
                mutable.update { it.copy(opening = false) }
            }
        }
    }

    fun returned(): Boolean {
        if (!awaitingReturn) return false
        awaitingReturn = false
        mutable.update { it.copy(returnRevision = it.returnRevision + 1) }
        return true
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EditorChoiceCard(ready: Boolean, state: ExternalEditorState, onNative: () -> Unit, onBrowser: () -> Unit, nativeSelected: Boolean = true) {
    ContentCard {
        Text("选择编辑方式", style = MaterialTheme.typography.titleMedium)
        Text("常用连接在原生页编辑，完整配置在官方网页编辑。返回应用后自动刷新数据。", style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(UiSpacing.small)) {
            FilterChip(selected = nativeSelected, onClick = onNative, label = { Text("原生提供商") })
            OutlinedButton(enabled = ready && !state.opening, onClick = onBrowser) {
                Text(if (state.opening) "正在打开…" else "浏览器编辑")
            }
        }
        if (!ready) Text("连接网关后可打开官方编辑器。", style = MaterialTheme.typography.bodySmall)
        if (state.error.isNotBlank()) Text(state.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

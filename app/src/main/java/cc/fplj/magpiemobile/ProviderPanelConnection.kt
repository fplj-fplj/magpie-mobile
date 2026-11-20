package cc.fplj.magpiemobile

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.StateFlow

/** One connection source owns both the observed session and every management request lease. */
internal interface ProviderPanelConnection {
    val status: StateFlow<NativeCoreSession.Snapshot>
    fun currentSession(): ProviderSessionKey?
    fun acquireLease(key: ProviderSessionKey): ProviderRequestLease
}

private object NativeProviderPanelConnection : ProviderPanelConnection {
    override val status: StateFlow<NativeCoreSession.Snapshot> get() = NativeCoreSession.status
    override fun currentSession(): ProviderSessionKey? = NativeCoreSession.providerSessionKey()
    override fun acquireLease(key: ProviderSessionKey): ProviderRequestLease = NativeCoreSession.providerRequest(key)
}

internal val LocalProviderPanelConnection = staticCompositionLocalOf<ProviderPanelConnection> {
    NativeProviderPanelConnection
}

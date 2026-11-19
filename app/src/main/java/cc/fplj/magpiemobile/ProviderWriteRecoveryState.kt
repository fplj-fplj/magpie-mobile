package cc.fplj.magpiemobile

import cc.fplj.magpiemobile.core.ProviderListSnapshot

/** Only this nonsecret marker survives recreation; editable snapshots and drafts never do. */
internal data class ProviderWriteRecoveryState(val pendingProviderId: String? = null) {
    val required: Boolean get() = pendingProviderId != null

    fun savedMarker(): String = pendingProviderId.orEmpty()

    /** Captures the marker before choosing a fresh recovery GET or an ordinary cached read. */
    suspend fun read(
        cache: ProviderListCacheViewModel,
        session: ProviderSessionKey,
        sequence: Long,
        force: Boolean = false,
        fetch: suspend () -> ProviderListSnapshot,
    ): ProviderPanelListRead {
        val snapshot = if (required) cache.readForRecovery(session, fetch)
        else cache.read(session, force = force, fetch = fetch)
        return ProviderPanelListRead(session, sequence, this, snapshot)
    }

    companion object {
        fun restore(marker: String): ProviderWriteRecoveryState =
            ProviderWriteRecoveryState(marker.takeIf { it.isNotEmpty() })
    }
}

/** A successful list RPC cannot clear a newer marker, another epoch, or a newer UI operation. */
internal class ProviderPanelListRead(
    private val session: ProviderSessionKey,
    private val sequence: Long,
    private val recovery: ProviderWriteRecoveryState,
    val snapshot: ProviderListSnapshot,
) {
    fun resolve(
        currentSession: ProviderSessionKey?,
        currentSequence: Long,
        currentRecovery: ProviderWriteRecoveryState,
        editorOpen: Boolean,
    ): ProviderPanelListResolution? {
        if (session != currentSession || sequence != currentSequence || recovery != currentRecovery) return null
        val recovered = recovery.required && !snapshot.hasFileError
        return ProviderPanelListResolution(
            recovery = if (recovered) ProviderWriteRecoveryState() else recovery,
            recoveryChecked = recovered,
            retireEditor = recovered && editorOpen,
        )
    }
}

internal data class ProviderPanelListResolution(
    val recovery: ProviderWriteRecoveryState,
    val recoveryChecked: Boolean,
    val retireEditor: Boolean,
)

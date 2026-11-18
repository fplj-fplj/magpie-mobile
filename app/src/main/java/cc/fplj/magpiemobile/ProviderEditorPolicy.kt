package cc.fplj.magpiemobile

/** Pure selection policy: discovery is metadata, not permission to rewrite saved picks. */
class ProviderEditorPolicy(
    private val creating: Boolean,
    initialIds: List<String> = emptyList(),
    private val initiallyDisabled: Boolean = false,
) {
    private val selected = initialIds.toMutableSet()
    private var catalog = emptyList<String>()
    private var touched = false
    private var selectAllIntent = creating || (initialIds.isEmpty() && !initiallyDisabled)

    fun applyCatalog(ids: List<String>): List<String> {
        catalog = ids.filter { it.isNotBlank() }.distinct()
        if (selectAllIntent) { selected.clear(); selected.addAll(catalog) }
        return selected.toList()
    }
    fun toggle(id: String, checked: Boolean): List<String> {
        touched = true; selectAllIntent = false
        if (checked) selected.add(id) else selected.remove(id)
        return selected.toList()
    }
    fun chooseAll(): List<String> {
        touched = true; selectAllIntent = true; selected.clear(); selected.addAll(catalog)
        return selected.toList()
    }
    fun chooseNone(): List<String> {
        touched = true; selectAllIntent = false; selected.clear(); return emptyList()
    }
    fun manual(ids: List<String>): List<String> {
        touched = true; selectAllIntent = false; selected.clear(); selected.addAll(ids); return selected.toList()
    }
    fun visibleIds(): List<String> = (catalog + selected).distinct()
    fun isAvailable(id: String): Boolean = id in catalog
    fun selectedIds(): List<String> = selected.toList()
    fun editedModels(): List<String>? = if (touched) selected.toList() else null
}

/**
 * Keeps discovery responses bound to the exact form state that produced them.
 *
 * A sequence mismatch catches normal recomposition races. The remaining comparisons close the
 * smaller gap where form state changes before LaunchedEffect has restarted. Name and ID are not
 * inputs to discovery and are intentionally absent: catalog responses may only update models and
 * one guarded Base URL adoption.
 */
internal object ProviderCatalogSyncPolicy {
    fun requestIsCurrent(
        requestedSequence: Long,
        currentSequence: Long,
        requestedBaseUrl: String,
        currentBaseUrl: String,
        requestedKeyFingerprint: String,
        currentKeyFingerprint: String,
        requestedLocalHttp: Boolean,
        currentLocalHttp: Boolean,
        requestedProviderId: String?,
        currentProviderId: String?,
        requestedRevision: String?,
        currentRevision: String?,
        editorOpen: Boolean,
        sessionCurrent: Boolean,
    ): Boolean {
        if (!editorOpen || !sessionCurrent || requestedSequence != currentSequence ||
            requestedKeyFingerprint != currentKeyFingerprint ||
            requestedLocalHttp != currentLocalHttp ||
            requestedProviderId != currentProviderId ||
            requestedRevision != currentRevision
        ) return false
        val requested = normalizeCatalogBase(requestedBaseUrl) ?: return false
        val current = normalizeCatalogBase(currentBaseUrl) ?: return false
        return requested == current
    }

    /**
     * Returns a form value that normalizes back to the discovered Base URL, or null when adoption
     * is stale, redundant, already attempted, or not one of the core-supported root/-v1
     * alternatives. A bare root Base URL is represented by its Chat Completions endpoint because
     * ProviderBaseUrl.normalize intentionally turns a bare origin into /v1.
     */
    fun adoptedBaseUrl(
        requestedBaseUrl: String,
        currentBaseUrl: String,
        returnedBaseUrl: String,
        requestStillCurrent: Boolean,
        alreadyAdopted: Boolean,
    ): String? {
        if (!requestStillCurrent || alreadyAdopted) return null
        val requested = normalizeCatalogBase(requestedBaseUrl) ?: return null
        val current = normalizeCatalogBase(currentBaseUrl) ?: return null
        if (requested != current) return null

        val returned = returnedBaseUrl.trim().trimEnd('/')
        if (returned.isEmpty() || returned == current) return null
        val currentUri = runCatching { java.net.URI(current) }.getOrNull() ?: return null
        val returnedUri = runCatching { java.net.URI(returned) }.getOrNull() ?: return null
        if (currentUri.rawQuery != null || currentUri.rawFragment != null ||
            returnedUri.rawQuery != null || returnedUri.rawFragment != null ||
            !currentUri.scheme.equals(returnedUri.scheme, ignoreCase = true) ||
            currentUri.host?.equals(returnedUri.host, ignoreCase = true) != true ||
            catalogPort(currentUri) != catalogPort(returnedUri)
        ) return null

        val currentPath = currentUri.rawPath.orEmpty().trimEnd('/')
        val returnedPath = returnedUri.rawPath.orEmpty().trimEnd('/')
        val alternatePath =
            if (currentPath.endsWith("/v1")) currentPath.removeSuffix("/v1")
            else currentPath + "/v1"
        if (returnedPath != alternatePath) return null

        val formValue = if (returnedPath.isEmpty()) {
            returned + "/chat/completions"
        } else {
            returned
        }
        return formValue.takeIf { normalizeCatalogBase(it) != null }
    }

    private fun normalizeCatalogBase(value: String): String? =
        runCatching { cc.fplj.magpiemobile.core.ProviderBaseUrl.normalize(value) }.getOrNull()

    private fun catalogPort(uri: java.net.URI): Int = when {
        uri.port != -1 -> uri.port
        uri.scheme.equals("https", ignoreCase = true) -> 443
        else -> 80
    }
}

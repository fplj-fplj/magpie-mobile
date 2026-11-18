package cc.fplj.magpiemobile

import cc.fplj.magpiemobile.core.ProviderBaseUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderEditorPolicyTest {
    @Test fun newCatalogDefaultsToAllWithoutManualIds() {
        val p = ProviderEditorPolicy(true)
        assertEquals(listOf("a", "b"), p.applyCatalog(listOf("a", "b", "a")))
    }
    @Test fun editRefreshPreservesSavedMissingModelsAndDoesNotDirty() {
        val p = ProviderEditorPolicy(false, listOf("old"))
        assertEquals(listOf("old"), p.applyCatalog(listOf("new")))
        assertEquals(listOf("new", "old"), p.visibleIds())
        assertFalse(p.isAvailable("old"))
        assertEquals(null, p.editedModels())
    }
    @Test fun implicitAllCanDisplayCatalogWithoutChangingSavedSemantics() {
        val p = ProviderEditorPolicy(false, emptyList(), false)
        assertEquals(listOf("a"), p.applyCatalog(listOf("a")))
        assertEquals(null, p.editedModels())
        p.chooseNone(); assertEquals(emptyList<String>(), p.editedModels())
    }
    @Test fun disabledProviderIsNotSilentlyEnabledByDiscovery() {
        val p = ProviderEditorPolicy(false, emptyList(), true)
        assertEquals(emptyList<String>(), p.applyCatalog(listOf("a")))
        assertEquals(null, p.editedModels())
    }
    @Test fun touchedSelectionDoesNotGainNewModelsOnRefresh() {
        val p = ProviderEditorPolicy(true)
        p.applyCatalog(listOf("a", "b")); p.toggle("b", false)
        assertEquals(listOf("a"), p.applyCatalog(listOf("a", "b", "c")))
    }
    @Test fun manualSelectionSurvivesRefreshWithoutAddingDiscoveredModels() {
        val p = ProviderEditorPolicy(true)
        p.applyCatalog(listOf("catalog-a", "catalog-b"))
        p.manual(listOf("catalog-a", "manual-only"))

        assertEquals(
            listOf("catalog-a", "manual-only"),
            p.applyCatalog(listOf("catalog-a", "newly-discovered")),
        )
        assertEquals(listOf("catalog-a", "newly-discovered", "manual-only"), p.visibleIds())
        assertFalse(p.isAvailable("manual-only"))
    }
    @Test fun touchedEmptySelectionStaysEmptyAfterDirectoryRefresh() {
        val p = ProviderEditorPolicy(true)
        p.applyCatalog(listOf("a", "b"))
        p.chooseNone()

        assertEquals(emptyList<String>(), p.applyCatalog(listOf("a", "b", "c")))
        assertEquals(emptyList<String>(), p.selectedIds())
    }
    @Test fun exactCatalogRequestIsCurrentAcrossEquivalentBaseForms() {
        assertTrue(requestIsCurrent(
            requestedBaseUrl = "https://api.example/v1/",
            currentBaseUrl = "https://API.EXAMPLE/v1",
        ))
        assertTrue(requestIsCurrent(
            requestedBaseUrl = "https://api.example/chat/completions",
            currentBaseUrl = "https://api.example/chat/completions/",
        ))
    }
    @Test fun changedFormOrSessionMakesCatalogResponseStale() {
        assertFalse(requestIsCurrent(currentBaseUrl = "https://api.example/v2"))
        assertFalse(requestIsCurrent(currentKeyFingerprint = "new-key"))
        assertFalse(requestIsCurrent(currentLocalHttp = true))
        assertFalse(requestIsCurrent(currentProviderId = "other-provider"))
        assertFalse(requestIsCurrent(currentRevision = "b".repeat(64)))
        assertFalse(requestIsCurrent(currentSequence = 8))
        assertFalse(requestIsCurrent(editorOpen = false))
        assertFalse(requestIsCurrent(sessionCurrent = false))
    }
    @Test fun currentCatalogFallbackAdoptsCanonicalV1BaseOnce() {
        val adopted = ProviderCatalogSyncPolicy.adoptedBaseUrl(
            requestedBaseUrl = "https://api.example/chat/completions",
            currentBaseUrl = "https://api.example/chat/completions",
            returnedBaseUrl = "https://api.example/v1",
            requestStillCurrent = true,
            alreadyAdopted = false,
        )

        assertEquals("https://api.example/v1", adopted)
        assertNull(ProviderCatalogSyncPolicy.adoptedBaseUrl(
            requestedBaseUrl = "https://api.example/v1",
            currentBaseUrl = "https://api.example/v1",
            returnedBaseUrl = "https://api.example/v1",
            requestStillCurrent = true,
            alreadyAdopted = false,
        ))
        assertNull(ProviderCatalogSyncPolicy.adoptedBaseUrl(
            requestedBaseUrl = "https://api.example/chat/completions",
            currentBaseUrl = "https://api.example/chat/completions",
            returnedBaseUrl = "https://api.example/v1",
            requestStillCurrent = true,
            alreadyAdopted = true,
        ))
    }
    @Test fun rootCatalogBaseUsesEndpointFormThatStillNormalizesToRoot() {
        val adopted = ProviderCatalogSyncPolicy.adoptedBaseUrl(
            requestedBaseUrl = "https://api.example/v1",
            currentBaseUrl = "https://api.example/v1",
            returnedBaseUrl = "https://api.example",
            requestStillCurrent = true,
            alreadyAdopted = false,
        )

        assertEquals("https://api.example/chat/completions", adopted)
        assertEquals("https://api.example", ProviderBaseUrl.normalize(adopted!!))
    }
    @Test fun staleChangedOrUnrelatedCatalogBaseIsNeverAdopted() {
        assertNull(ProviderCatalogSyncPolicy.adoptedBaseUrl(
            requestedBaseUrl = "https://api.example/v1",
            currentBaseUrl = "https://api.example/v1",
            returnedBaseUrl = "https://api.example",
            requestStillCurrent = false,
            alreadyAdopted = false,
        ))
        assertNull(ProviderCatalogSyncPolicy.adoptedBaseUrl(
            requestedBaseUrl = "https://api.example/v1",
            currentBaseUrl = "https://api.example/v2",
            returnedBaseUrl = "https://api.example",
            requestStillCurrent = true,
            alreadyAdopted = false,
        ))
        assertNull(ProviderCatalogSyncPolicy.adoptedBaseUrl(
            requestedBaseUrl = "https://api.example/v1",
            currentBaseUrl = "https://api.example/v1",
            returnedBaseUrl = "https://api.example/arbitrary",
            requestStillCurrent = true,
            alreadyAdopted = false,
        ))
        assertNull(ProviderCatalogSyncPolicy.adoptedBaseUrl(
            requestedBaseUrl = "https://api.example/v1",
            currentBaseUrl = "https://api.example/v1",
            returnedBaseUrl = "https://other.example",
            requestStillCurrent = true,
            alreadyAdopted = false,
        ))
    }

    private fun requestIsCurrent(
        requestedBaseUrl: String = "https://api.example/v1",
        currentBaseUrl: String = requestedBaseUrl,
        requestedKeyFingerprint: String = "same-key",
        currentKeyFingerprint: String = requestedKeyFingerprint,
        requestedLocalHttp: Boolean = false,
        currentLocalHttp: Boolean = requestedLocalHttp,
        requestedProviderId: String? = "provider-a",
        currentProviderId: String? = requestedProviderId,
        requestedRevision: String? = "a".repeat(64),
        currentRevision: String? = requestedRevision,
        requestedSequence: Long = 7,
        currentSequence: Long = requestedSequence,
        editorOpen: Boolean = true,
        sessionCurrent: Boolean = true,
    ): Boolean = ProviderCatalogSyncPolicy.requestIsCurrent(
        requestedSequence = requestedSequence,
        currentSequence = currentSequence,
        requestedBaseUrl = requestedBaseUrl,
        currentBaseUrl = currentBaseUrl,
        requestedKeyFingerprint = requestedKeyFingerprint,
        currentKeyFingerprint = currentKeyFingerprint,
        requestedLocalHttp = requestedLocalHttp,
        currentLocalHttp = currentLocalHttp,
        requestedProviderId = requestedProviderId,
        currentProviderId = currentProviderId,
        requestedRevision = requestedRevision,
        currentRevision = currentRevision,
        editorOpen = editorOpen,
        sessionCurrent = sessionCurrent,
    )
}

package cc.fplj.magpiemobile

import cc.fplj.magpiemobile.core.ProviderBaseUrl
import cc.fplj.magpiemobile.core.ProviderModelDiscoveryRequest
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
            requestedCanonicalBaseUrl = ProviderBaseUrl.normalize("https://api.example/chat/completions"),
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
            requestedCanonicalBaseUrl = ProviderBaseUrl.normalize("https://api.example/chat/completions"),
            currentBaseUrl = "https://api.example/chat/completions",
            returnedBaseUrl = "https://api.example/v1",
            requestStillCurrent = true,
            alreadyAdopted = false,
        )

        assertEquals("https://api.example/v1", adopted)
        assertNull(ProviderCatalogSyncPolicy.adoptedBaseUrl(
            requestedCanonicalBaseUrl = ProviderBaseUrl.normalize("https://api.example/v1"),
            currentBaseUrl = "https://api.example/v1",
            returnedBaseUrl = "https://api.example/v1",
            requestStillCurrent = true,
            alreadyAdopted = false,
        ))
        assertNull(ProviderCatalogSyncPolicy.adoptedBaseUrl(
            requestedCanonicalBaseUrl = ProviderBaseUrl.normalize("https://api.example/chat/completions"),
            currentBaseUrl = "https://api.example/chat/completions",
            returnedBaseUrl = "https://api.example/v1",
            requestStillCurrent = true,
            alreadyAdopted = true,
        ))
    }
    @Test fun rootCatalogBaseUsesEndpointFormThatStillNormalizesToRoot() {
        val adopted = ProviderCatalogSyncPolicy.adoptedBaseUrl(
            requestedCanonicalBaseUrl = ProviderBaseUrl.normalize("https://api.example/v1"),
            currentBaseUrl = "https://api.example/v1",
            returnedBaseUrl = "https://api.example",
            requestStillCurrent = true,
            alreadyAdopted = false,
        )

        assertEquals("https://api.example/chat/completions", adopted)
        assertEquals("https://api.example", ProviderBaseUrl.normalize(adopted!!))
    }
    @Test fun reopenedRootDiscoveryUsesCanonicalRequestBaseAndAppliesReturnedModels() {
        val root = "https://api.example"
        val form = ProviderBaseUrl.storedBaseFormValue(root)
        val request = ProviderModelDiscoveryRequest(form)
        val selection = ProviderEditorPolicy(false, listOf("saved-model"))

        val current = requestIsCurrent(
            requestedCanonicalBaseUrl = request.baseUrl,
            currentBaseUrl = form,
        )
        assertEquals(root, request.baseUrl)
        assertTrue(current)
        if (current) selection.applyCatalog(listOf("saved-model", "discovered-model"))

        assertEquals(listOf("saved-model", "discovered-model"), selection.visibleIds())
        assertEquals(listOf("saved-model"), selection.selectedIds())
        assertNull(selection.editedModels())
        assertNull(ProviderCatalogSyncPolicy.adoptedBaseUrl(
            requestedCanonicalBaseUrl = request.baseUrl,
            currentBaseUrl = form,
            returnedBaseUrl = root,
            requestStillCurrent = current,
            alreadyAdopted = false,
        ))
        // A bare origin entered while this request was in flight now means /v1, not root.
        assertFalse(requestIsCurrent(
            requestedCanonicalBaseUrl = request.baseUrl,
            currentBaseUrl = root,
        ))
    }
    @Test fun rootDiscoveryFallbackAdoptsV1AndFollowupCatalogUsesTheNewRoute() {
        val form = ProviderBaseUrl.storedBaseFormValue("https://api.example")
        val request = ProviderModelDiscoveryRequest(form)
        val current = requestIsCurrent(
            requestedCanonicalBaseUrl = request.baseUrl,
            currentBaseUrl = form,
        )

        assertTrue(current)
        val adopted = ProviderCatalogSyncPolicy.adoptedBaseUrl(
            requestedCanonicalBaseUrl = request.baseUrl,
            currentBaseUrl = form,
            returnedBaseUrl = "https://api.example/v1",
            requestStillCurrent = current,
            alreadyAdopted = false,
        )
        assertEquals("https://api.example/v1", adopted)
        val followup = ProviderModelDiscoveryRequest(adopted!!)
        assertTrue(requestIsCurrent(
            requestedCanonicalBaseUrl = followup.baseUrl,
            currentBaseUrl = adopted,
        ))
        assertFalse(requestIsCurrent(
            requestedCanonicalBaseUrl = request.baseUrl,
            currentBaseUrl = adopted,
        ))
        assertNull(ProviderCatalogSyncPolicy.adoptedBaseUrl(
            requestedCanonicalBaseUrl = followup.baseUrl,
            currentBaseUrl = adopted,
            returnedBaseUrl = "https://api.example",
            requestStillCurrent = true,
            alreadyAdopted = true,
        ))
    }
    @Test fun v1FallbackToRootKeepsReversibleFormForTheFollowupCatalog() {
        val form = "https://api.example/v1"
        val request = ProviderModelDiscoveryRequest(form)
        val adopted = ProviderCatalogSyncPolicy.adoptedBaseUrl(
            requestedCanonicalBaseUrl = request.baseUrl,
            currentBaseUrl = form,
            returnedBaseUrl = "https://api.example",
            requestStillCurrent = requestIsCurrent(
                requestedCanonicalBaseUrl = request.baseUrl,
                currentBaseUrl = form,
            ),
            alreadyAdopted = false,
        )

        assertEquals("https://api.example/chat/completions", adopted)
        val followup = ProviderModelDiscoveryRequest(adopted!!)
        assertEquals("https://api.example", followup.baseUrl)
        assertTrue(requestIsCurrent(
            requestedCanonicalBaseUrl = followup.baseUrl,
            currentBaseUrl = adopted,
        ))
        assertNull(ProviderCatalogSyncPolicy.adoptedBaseUrl(
            requestedCanonicalBaseUrl = followup.baseUrl,
            currentBaseUrl = adopted,
            returnedBaseUrl = "https://api.example/v1",
            requestStillCurrent = true,
            alreadyAdopted = true,
        ))
    }
    @Test fun staleChangedOrUnrelatedCatalogBaseIsNeverAdopted() {
        assertNull(ProviderCatalogSyncPolicy.adoptedBaseUrl(
            requestedCanonicalBaseUrl = ProviderBaseUrl.normalize("https://api.example/v1"),
            currentBaseUrl = "https://api.example/v1",
            returnedBaseUrl = "https://api.example",
            requestStillCurrent = false,
            alreadyAdopted = false,
        ))
        assertNull(ProviderCatalogSyncPolicy.adoptedBaseUrl(
            requestedCanonicalBaseUrl = ProviderBaseUrl.normalize("https://api.example/v1"),
            currentBaseUrl = "https://api.example/v2",
            returnedBaseUrl = "https://api.example",
            requestStillCurrent = true,
            alreadyAdopted = false,
        ))
        assertNull(ProviderCatalogSyncPolicy.adoptedBaseUrl(
            requestedCanonicalBaseUrl = ProviderBaseUrl.normalize("https://api.example/v1"),
            currentBaseUrl = "https://api.example/v1",
            returnedBaseUrl = "https://api.example/arbitrary",
            requestStillCurrent = true,
            alreadyAdopted = false,
        ))
        assertNull(ProviderCatalogSyncPolicy.adoptedBaseUrl(
            requestedCanonicalBaseUrl = ProviderBaseUrl.normalize("https://api.example/v1"),
            currentBaseUrl = "https://api.example/v1",
            returnedBaseUrl = "https://other.example",
            requestStillCurrent = true,
            alreadyAdopted = false,
        ))
    }

    private fun requestIsCurrent(
        requestedBaseUrl: String = "https://api.example/v1",
        currentBaseUrl: String = requestedBaseUrl,
        requestedCanonicalBaseUrl: String = ProviderBaseUrl.normalize(requestedBaseUrl),
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
        requestedCanonicalBaseUrl = requestedCanonicalBaseUrl,
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

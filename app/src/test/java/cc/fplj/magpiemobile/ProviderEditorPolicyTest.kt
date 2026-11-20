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
    @Test fun catalogPinNormalizationKeepsExplicitSystemTlsAndRejectsMalformedPins() {
        val pin = "a9".repeat(32)
        assertEquals("", ProviderCatalogSyncPolicy.normalizedServerPin(""))
        assertEquals("", ProviderCatalogSyncPolicy.normalizedServerPin("  "))
        assertEquals(pin, ProviderCatalogSyncPolicy.normalizedServerPin("  $pin  "))
        for (invalid in listOf("a".repeat(63), "a".repeat(65), "A".repeat(64), "g".repeat(64), "$pin\n", "\t$pin")) {
            assertNull(ProviderCatalogSyncPolicy.normalizedServerPin(invalid))
            assertTrue(ProviderCatalogSyncPolicy.serverPinError("https://api.example/v1", invalid) != null)
        }
        assertNull(ProviderCatalogSyncPolicy.serverPinError("https://api.example/v1", ""))
        assertNull(ProviderCatalogSyncPolicy.serverPinError("https://api.example/v1", pin))
    }
    @Test fun httpCatalogRejectsEveryNonemptyPinAndAllowsExplicitEmptyPin() {
        val pin = "ab".repeat(32)
        for (base in listOf("http://127.0.0.1:3425/v1", "http://[::1]:3425/v1")) {
            assertTrue(ProviderCatalogSyncPolicy.serverPinError(base, pin)?.contains("HTTP") == true)
            assertNull(ProviderCatalogSyncPolicy.serverPinError(base, ""))
            assertFalse(requestIsCurrent(
                requestedBaseUrl = base,
                requestedLocalHttp = true,
                requestedServerPin = pin,
            ))
            assertTrue(requestIsCurrent(
                requestedBaseUrl = base,
                requestedLocalHttp = true,
                requestedServerPin = "",
            ))
        }
    }
    @Test fun canonicalPinMatchesOnlyTheVisibleTlsChoiceBeforeEffectRestarts() {
        val pin = "ab".repeat(32)
        assertTrue(requestIsCurrent(requestedServerPin = pin, currentServerPin = " $pin "))
        assertTrue(requestIsCurrent(requestedServerPin = "", currentServerPin = ""))
        assertFalse(requestIsCurrent(requestedServerPin = pin, currentServerPin = ""))
        assertFalse(requestIsCurrent(requestedServerPin = "", currentServerPin = pin))
        assertFalse(requestIsCurrent(requestedServerPin = pin, currentServerPin = "cd".repeat(32)))
        assertFalse(requestIsCurrent(requestedServerPin = pin, currentServerPin = pin.uppercase()))
        assertFalse(requestIsCurrent(requestedServerPin = "", currentServerPin = "\n"))
    }
    @Test fun pinChangeRejectsOldSuccessErrorAndBaseAdoptionBeforeRecomposition() {
        val requestedPin = "ab".repeat(32)
        for (currentPin in listOf("", "cd".repeat(32), "invalid")) {
            val selection = ProviderEditorPolicy(true)
            selection.applyCatalog(listOf("current-model"))
            var error = "current error"
            val oldRequestCurrent = requestIsCurrent(
                requestedServerPin = requestedPin,
                currentServerPin = currentPin,
                // The effect has not restarted and cannot help invalidate this callback yet.
                requestedSequence = 7,
                currentSequence = 7,
            )

            if (oldRequestCurrent) selection.applyCatalog(listOf("stale-model"))
            if (oldRequestCurrent) error = "stale failure"

            assertFalse(oldRequestCurrent)
            assertEquals(listOf("current-model"), selection.visibleIds())
            assertEquals(listOf("current-model"), selection.selectedIds())
            assertEquals("current error", error)
            assertNull(ProviderCatalogSyncPolicy.adoptedBaseUrl(
                requestedCanonicalBaseUrl = ProviderBaseUrl.normalize("https://api.example/v1"),
                currentBaseUrl = "https://api.example/v1",
                returnedBaseUrl = "https://api.example",
                requestStillCurrent = oldRequestCurrent,
                alreadyAdopted = false,
            ))
        }
    }
    @Test fun changingThenRestoringPinCannotReviveTheInvalidatedSequence() {
        val pin = "ab".repeat(32)
        // Each onValueChange fences synchronously, even if both edits precede recomposition.
        assertFalse(requestIsCurrent(
            requestedServerPin = pin,
            currentServerPin = pin,
            requestedSequence = 7,
            currentSequence = 9,
        ))
        assertTrue(requestIsCurrent(
            requestedServerPin = pin,
            currentServerPin = pin,
            requestedSequence = 9,
            currentSequence = 9,
        ))
    }
    @Test fun replacementPinRequestCanApplyItsCatalogErrorAndCanonicalBase() {
        val previousPin = "ab".repeat(32)
        for (currentPin in listOf("", "cd".repeat(32))) {
            assertFalse(requestIsCurrent(requestedServerPin = previousPin, currentServerPin = currentPin))
            val selection = ProviderEditorPolicy(true)
            var error = ""
            val replacementCurrent = requestIsCurrent(
                requestedServerPin = currentPin,
                currentServerPin = currentPin,
                requestedSequence = 8,
                currentSequence = 8,
            )
            if (replacementCurrent) selection.applyCatalog(listOf("replacement-model"))
            if (replacementCurrent) error = "replacement failure"

            assertTrue(replacementCurrent)
            assertEquals(listOf("replacement-model"), selection.selectedIds())
            assertEquals("replacement failure", error)
            assertEquals("https://api.example/chat/completions", ProviderCatalogSyncPolicy.adoptedBaseUrl(
                requestedCanonicalBaseUrl = ProviderBaseUrl.normalize("https://api.example/v1"),
                currentBaseUrl = "https://api.example/v1",
                returnedBaseUrl = "https://api.example",
                requestStillCurrent = replacementCurrent,
                alreadyAdopted = false,
            ))
        }
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
        requestedServerPin: String = "",
        currentServerPin: String = requestedServerPin,
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
        requestedServerPin = requestedServerPin,
        currentServerPin = currentServerPin,
        requestedProviderId = requestedProviderId,
        currentProviderId = currentProviderId,
        requestedRevision = requestedRevision,
        currentRevision = currentRevision,
        editorOpen = editorOpen,
        sessionCurrent = sessionCurrent,
    )
}

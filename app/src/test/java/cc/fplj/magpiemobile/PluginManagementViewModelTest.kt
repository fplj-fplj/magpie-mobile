package cc.fplj.magpiemobile

import androidx.lifecycle.SavedStateHandle
import cc.fplj.magpiemobile.core.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PluginManagementViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }

    @Test fun importCanDiscardAnUnregisteredSnapshotWhenCoreIsUnavailableOrBusy() = runTest(dispatcher) {
        val write = CompletableDeferred<PluginSnapshot>()
        val vm = PluginManagementViewModel(readPlugins = { PluginSnapshot() }, readProviders = { emptyList() },
            performEdit = { write.await() })
        assertFalse(vm.edit(PluginEdit.Add("unregistered")))
        vm.attach(1)
        assertTrue(vm.edit(PluginEdit.Add("registered")))
        assertFalse(vm.edit(PluginEdit.Add("unregistered-second")))
        write.complete(PluginSnapshot(entries = listOf(ManagedPlugin("registered"))))
    }

    @Test fun credentialBearingSpecsAreRejectedBeforeSavedStateOrWritesForEveryOperation() = runTest(dispatcher) {
        val spec = "git+https://fixture-user:fixture-password@example.invalid/plugin.git"
        val saved = SavedStateHandle()
        var writes = 0
        val vm = PluginManagementViewModel(savedState = saved, readPlugins = { PluginSnapshot() }, readProviders = { emptyList() },
            performEdit = { writes++; PluginSnapshot() })
        vm.attach(1)
        val edits = listOf<PluginEdit>(
            PluginEdit.Add(spec), PluginEdit.Remove(spec), PluginEdit.Upgrade(spec), PluginEdit.Off(spec, true),
            PluginEdit.Options(spec, kotlinx.serialization.json.JsonObject(emptyMap())), PluginEdit.Prefer(spec, "fixture"),
        )
        for (edit in edits) {
            assertFalse(vm.edit(edit))
            assertTrue(saved.keys().isEmpty())
            assertEquals("", vm.state.value.pendingSpec)
            assertFalse(vm.state.value.error.contains("fixture-password"))
        }
        assertEquals(0, writes)
    }

    @Test fun invalidRecoverySpecsFromAnOlderVersionAreDiscardedOnRestore() = runTest(dispatcher) {
        val saved = SavedStateHandle(mapOf(
            "plugin.pending" to true,
            "plugin.spec" to "https://fixture-user:fixture-password@example.invalid/plugin.git",
            "plugin.operation" to "add",
            "plugin.started" to 1000L,
        ))
        val vm = PluginManagementViewModel(savedState = saved, readPlugins = { PluginSnapshot() }, readProviders = { emptyList() })
        assertFalse(vm.state.value.pending)
        assertEquals("", vm.state.value.pendingSpec)
        assertEquals("", saved.get<String>("plugin.spec"))
        assertFalse(saved.get<Boolean>("plugin.pending")!!)
        assertFalse(saved.keys().contains("plugin.operation"))
    }

    @Test fun unknownInstallIsNotRepeatedOrClearedByAnUnchangedRead() = runTest(dispatcher) {
        val write = CompletableDeferred<PluginSnapshot>()
        var snapshot = PluginSnapshot()
        var writes = 0
        val saved = SavedStateHandle()
        val vm = PluginManagementViewModel(savedState = saved, readPlugins = { snapshot }, readProviders = { emptyList() },
            performEdit = { writes++; write.await() }, clock = { 1000 })
        vm.attach(1)
        vm.edit(PluginEdit.Add("@scope/plugin@1"))
        vm.edit(PluginEdit.Add("second"))
        assertEquals(1, writes)
        write.completeExceptionally(PluginWriteUnknownException())
        assertTrue(vm.state.value.pending)
        vm.refresh()
        assertTrue(vm.state.value.pending)
        vm.edit(PluginEdit.Add("second"))
        assertEquals(1, writes)
        snapshot = PluginSnapshot(entries = listOf(ManagedPlugin(spec = "@scope/plugin@1", name = "Plugin")))
        vm.refresh()
        assertFalse(vm.state.value.pending)
        assertEquals(1, writes)
        assertFalse(saved.get<Boolean>("plugin.pending")!!)
    }

    @Test fun pendingMarkerSurvivesRecreationWithoutSavingOptionsOrCredentials() = runTest(dispatcher) {
        val saved = SavedStateHandle()
        val first = PluginManagementViewModel(savedState = saved, readPlugins = { PluginSnapshot() }, readProviders = { emptyList() },
            performEdit = { throw PluginWriteUnknownException() }, clock = { 1000 })
        first.attach(1)
        first.edit(PluginEdit.Add("fixture"))
        assertTrue(first.state.value.pending)
        var writes = 0
        val restored = PluginManagementViewModel(savedState = saved, readPlugins = { PluginSnapshot() }, readProviders = { emptyList() },
            performEdit = { writes++; PluginSnapshot() }, clock = { 2000 })
        restored.attach(2)
        assertTrue(restored.state.value.pending)
        restored.edit(PluginEdit.Add("fixture"))
        assertEquals(0, writes)
        assertEquals(setOf("plugin.pending", "plugin.spec", "plugin.started", "plugin.operation"), saved.keys())
    }

    @Test fun rejectedImportIsDeletedOnlyAfterAReadProvesItUnreferenced() = runTest(dispatcher) {
        val cleaned = mutableListOf<String>()
        var readable = true
        var snapshot = PluginSnapshot()
        val vm = PluginManagementViewModel(readPlugins = {
            if (!readable) throw PluginReadException()
            snapshot
        }, readProviders = { emptyList() }, performEdit = { throw IllegalArgumentException() }, removeImport = { spec, _ -> cleaned.add(spec) })
        vm.attach(1)
        vm.edit(PluginEdit.Add("/private/import-a/plugin.ts"))
        assertEquals(listOf("/private/import-a/plugin.ts"), cleaned)
        readable = false
        vm.edit(PluginEdit.Add("/private/import-b/plugin.ts"))
        assertEquals(1, cleaned.size)
        readable = true
        snapshot = PluginSnapshot(entries = listOf(ManagedPlugin("/private/import-c/plugin.ts")))
        vm.edit(PluginEdit.Add("/private/import-c/plugin.ts"))
        assertEquals(1, cleaned.size)
    }

    @Test fun unknownRemoveSurvivesRecreationAndCleansOnlyOnConfirmedAbsence() = runTest(dispatcher) {
        val spec = "/private/import/plugin.ts"
        val saved = SavedStateHandle()
        var snapshot = PluginSnapshot(entries = listOf(ManagedPlugin(spec)))
        val cleaned = mutableListOf<String>()
        val first = PluginManagementViewModel(savedState = saved, readPlugins = { snapshot }, readProviders = { emptyList() },
            performEdit = { throw PluginWriteUnknownException() }, removeImport = { value, _ -> cleaned.add(value) }, clock = { 1000 })
        first.attach(1)
        first.edit(PluginEdit.Remove(spec))
        first.refresh()
        assertTrue(cleaned.isEmpty())
        val restored = PluginManagementViewModel(savedState = saved, readPlugins = { snapshot }, readProviders = { emptyList() },
            removeImport = { value, _ -> cleaned.add(value) }, clock = { 2000 })
        restored.attach(2)
        assertTrue(restored.state.value.pending)
        snapshot = PluginSnapshot()
        restored.refresh()
        restored.refresh()
        assertEquals(listOf(spec), cleaned)
        assertFalse(restored.state.value.pending)
    }

    @Test fun unknownAddAndFailedRefreshNeverTriggerSnapshotReconciliation() = runTest(dispatcher) {
        var readable = true
        var reconciliations = 0
        val vm = PluginManagementViewModel(readPlugins = {
            if (!readable) throw PluginReadException()
            PluginSnapshot()
        }, readProviders = { emptyList() }, performEdit = { throw PluginWriteUnknownException() },
            reconcileImports = { reconciliations++ }, clock = { 1000 })
        vm.attach(1)
        assertEquals(1, reconciliations)
        vm.edit(PluginEdit.Add("/private/import/plugin.ts"))
        vm.refresh()
        assertEquals(1, reconciliations)
        readable = false
        vm.refresh()
        assertEquals(1, reconciliations)
    }

    @Test fun previousCoreReadCannotReplaceCurrentCoreState() = runTest(dispatcher) {
        val old = CompletableDeferred<PluginSnapshot>()
        var reads = 0
        val vm = PluginManagementViewModel(readPlugins = {
            if (++reads == 1) old.await() else PluginSnapshot(entries = listOf(ManagedPlugin("current")))
        }, readProviders = { emptyList() })
        vm.attach(1)
        vm.attach(2)
        old.complete(PluginSnapshot(entries = listOf(ManagedPlugin("obsolete"))))
        assertEquals(listOf("current"), vm.state.value.snapshot.entries.map { it.spec })
    }

    @Test fun marketInstalledBadgesFollowSuccessfulInstallAndRemoval() = runTest(dispatcher) {
        val listing = PluginMarketListing(packageId = "@fixture/tools-example")
        val vm = PluginManagementViewModel(readPlugins = { PluginSnapshot() }, readProviders = { emptyList() },
            readMarket = { PluginMarketSnapshot(listings = listOf(listing)) }, performEdit = { edit ->
                if (edit is PluginEdit.Add) PluginSnapshot(entries = listOf(ManagedPlugin(edit.spec))) else PluginSnapshot()
            })
        vm.attach(1)
        vm.market("")
        assertFalse(vm.state.value.market.isInstalled(listing))

        assertTrue(vm.edit(PluginEdit.Add(listing.packageId)))
        assertTrue(vm.state.value.market.isInstalled(listing))
        assertEquals(listOf(listing.packageId), vm.state.value.market.matchedSpecs)
        assertEquals(listOf(listing), vm.state.value.market.listings)

        assertTrue(vm.edit(PluginEdit.Remove(listing.packageId)))
        assertFalse(vm.state.value.market.isInstalled(listing))
        assertTrue(vm.state.value.market.matchedSpecs.isEmpty())
    }

    @Test fun refreshReconcilesMarketBadgesAndIgnoresEarlierMarketResponse() = runTest(dispatcher) {
        val listing = PluginMarketListing(packageId = "@fixture/tools-example")
        var snapshot = PluginSnapshot()
        var searches = 0
        val old = CompletableDeferred<PluginMarketSnapshot>()
        val vm = PluginManagementViewModel(readPlugins = { snapshot }, readProviders = { emptyList() }, readMarket = {
            if (++searches == 1) PluginMarketSnapshot(listings = listOf(listing)) else old.await()
        })
        vm.attach(1)
        vm.market("")
        vm.market("old-query")
        assertTrue(vm.state.value.loading)

        snapshot = PluginSnapshot(entries = listOf(ManagedPlugin(listing.packageId)))
        vm.refresh()
        assertTrue(vm.state.value.market.isInstalled(listing))
        assertFalse(vm.state.value.loading)
        old.complete(PluginMarketSnapshot(query = "old-query", listings = listOf(listing)))
        assertTrue(vm.state.value.market.isInstalled(listing))
        assertEquals("", vm.state.value.market.query)
        assertEquals(listOf(listing.packageId), vm.state.value.market.matchedSpecs)

        snapshot = PluginSnapshot()
        vm.refresh()
        assertFalse(vm.state.value.market.isInstalled(listing))
        assertTrue(vm.state.value.market.matchedSpecs.isEmpty())
    }

    @Test fun earlierMarketResponseCannotUndoSuccessfulEditOrKeepLoading() = runTest(dispatcher) {
        val listing = PluginMarketListing(packageId = "@fixture/tools-example")
        var searches = 0
        val old = CompletableDeferred<PluginMarketSnapshot>()
        val vm = PluginManagementViewModel(readPlugins = { PluginSnapshot() }, readProviders = { emptyList() },
            readMarket = { if (++searches == 1) PluginMarketSnapshot(listings = listOf(listing)) else old.await() },
            performEdit = { PluginSnapshot(entries = listOf(ManagedPlugin(listing.packageId))) })
        vm.attach(1)
        vm.market("")
        vm.market("old-query")
        assertTrue(vm.state.value.loading)
        assertTrue(vm.edit(PluginEdit.Add(listing.packageId)))
        assertFalse(vm.state.value.loading)
        assertTrue(vm.state.value.market.isInstalled(listing))
        old.complete(PluginMarketSnapshot(query = "old-query", listings = listOf(listing)))
        assertTrue(vm.state.value.market.isInstalled(listing))
        assertEquals("", vm.state.value.market.query)
    }

    @Test fun switchingCoreClearsOldMarketEvenWhenNewCoreReadFails() = runTest(dispatcher) {
        val listing = PluginMarketListing(packageId = "@fixture/tools-example")
        var readable = true
        var searches = 0
        val old = CompletableDeferred<PluginMarketSnapshot>()
        val result = PluginMarketSnapshot(listings = listOf(listing), installed = listOf(ManagedPlugin(listing.packageId)))
        val vm = PluginManagementViewModel(readPlugins = {
            if (!readable) throw PluginReadException()
            PluginSnapshot()
        }, readProviders = { emptyList() }, readMarket = { if (++searches == 1) result else old.await() })
        vm.attach(1)
        vm.market("")
        assertTrue(vm.state.value.market.isInstalled(listing))
        vm.market("old-query")
        readable = false
        vm.attach(2)
        old.complete(result)
        assertTrue(vm.state.value.market.listings.isEmpty())
        assertTrue(vm.state.value.market.installed.isEmpty())
        assertFalse(vm.state.value.loading)
    }

    @Test fun loginAnswersAndOAuthLinkNeverEnterSavedOrPublicState() = runTest(dispatcher) {
        val token = "private-api-token-12345"
        val link = "https://example.com/authorize?state=private-oauth-state"
        val saved = SavedStateHandle()
        var received = ""
        val provider = PluginProvider(pid = "original-plugin-id", name = "Fixture", methods = listOf(PluginSignInMethod(type = "oauth")))
        val vm = PluginManagementViewModel(savedState = saved, readPlugins = { PluginSnapshot() }, readProviders = { listOf(provider) },
            performAuth = { step -> when (step) {
                is PluginAuthStep.Prompt -> PluginAuthReply(prompt = PluginPromptResult(inputs = step.inputs))
                is PluginAuthStep.Start -> { received = step.key; PluginAuthReply(state = PluginSignInState(id = "session", url = link, state = "waiting")) }
                is PluginAuthStep.Cancel -> PluginAuthReply()
                else -> PluginAuthReply()
            } })
        vm.attach(1)
        vm.selectLogin(provider, 0)
        vm.startLogin(token)
        assertEquals(token, received)
        assertFalse(vm.state.value.toString().contains(token))
        assertEquals("", vm.state.value.signIn!!.url)
        var launched = ""
        vm.openLoginBrowser { launched = it }
        assertEquals(link, launched)
        assertTrue(saved.keys().isEmpty())
        vm.cancelLogin()
        assertNull(vm.state.value.authProvider)
    }

    @Test fun callbackFollowupReadKeepsItsBusyFlagUntilItCompletes() = runTest(dispatcher) {
        val pendingRead = CompletableDeferred<PluginSignInState>()
        var callbacks = 0
        val provider = PluginProvider(pid = "fixture", methods = listOf(PluginSignInMethod(type = "oauth")))
        val vm = PluginManagementViewModel(readPlugins = { PluginSnapshot() }, readProviders = { listOf(provider) }, performAuth = { step ->
            when (step) {
                is PluginAuthStep.Prompt -> PluginAuthReply(prompt = PluginPromptResult())
                is PluginAuthStep.Start -> PluginAuthReply(state = PluginSignInState(id = "session", state = "waiting", pasteCode = true))
                is PluginAuthStep.Callback -> { callbacks++; PluginAuthReply() }
                is PluginAuthStep.Read -> PluginAuthReply(state = pendingRead.await())
                else -> PluginAuthReply()
            }
        })
        vm.attach(1)
        vm.selectLogin(provider, 0)
        vm.startLogin()
        vm.submitCallback("code")
        assertTrue(vm.state.value.authBusy)
        vm.submitCallback("duplicate")
        assertEquals(1, callbacks)
        pendingRead.complete(PluginSignInState(id = "session", state = "done"))
        assertFalse(vm.state.value.authBusy)
        assertEquals("done", vm.state.value.signIn!!.state)
        vm.cancelLogin()
    }
}

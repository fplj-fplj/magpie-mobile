package cc.fplj.magpiemobile

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import cc.fplj.magpiemobile.core.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
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
    private val viewModels = mutableListOf<PluginManagementViewModel>()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() {
        viewModels.forEach { it.viewModelScope.cancel() }
        dispatcher.scheduler.runCurrent()
        assertEquals("All authentication workers must have actually exited", 0, PluginAuthWorkTracker.active.value)
        Dispatchers.resetMain()
    }

    @Test fun importCanDiscardAnUnregisteredSnapshotWhenCoreIsUnavailableOrBusy() = runTest(dispatcher) {
        val write = CompletableDeferred<PluginSnapshot>()
        val vm = testViewModel(readPlugins = { PluginSnapshot() }, readProviders = { emptyList() },
            performEdit = { write.await() })
        assertFalse(vm.edit(PluginEdit.Add("unregistered")))
        vm.attach(ProviderSessionKey(1, 1))
        assertTrue(vm.edit(PluginEdit.Add("registered")))
        assertFalse(vm.edit(PluginEdit.Add("unregistered-second")))
        write.complete(PluginSnapshot(entries = listOf(ManagedPlugin("registered"))))
    }

    @Test fun credentialBearingSpecsAreRejectedBeforeSavedStateOrWritesForEveryOperation() = runTest(dispatcher) {
        val spec = "git+https://fixture-user:fixture-password@example.invalid/plugin.git"
        val saved = SavedStateHandle()
        var writes = 0
        val vm = testViewModel(savedState = saved, readPlugins = { PluginSnapshot() }, readProviders = { emptyList() },
            performEdit = { writes++; PluginSnapshot() })
        vm.attach(ProviderSessionKey(1, 1))
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
        val vm = testViewModel(savedState = saved, readPlugins = { PluginSnapshot() }, readProviders = { emptyList() })
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
        val vm = testViewModel(savedState = saved, readPlugins = { snapshot }, readProviders = { emptyList() },
            performEdit = { writes++; write.await() }, clock = { 1000 })
        vm.attach(ProviderSessionKey(1, 1))
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
        val first = testViewModel(savedState = saved, readPlugins = { PluginSnapshot() }, readProviders = { emptyList() },
            performEdit = { throw PluginWriteUnknownException() }, clock = { 1000 })
        first.attach(ProviderSessionKey(1, 1))
        first.edit(PluginEdit.Add("fixture"))
        assertTrue(first.state.value.pending)
        var writes = 0
        val restored = testViewModel(savedState = saved, readPlugins = { PluginSnapshot() }, readProviders = { emptyList() },
            performEdit = { writes++; PluginSnapshot() }, clock = { 2000 })
        restored.attach(ProviderSessionKey(2, 1))
        assertTrue(restored.state.value.pending)
        restored.edit(PluginEdit.Add("fixture"))
        assertEquals(0, writes)
        assertEquals(setOf("plugin.pending", "plugin.spec", "plugin.started", "plugin.operation"), saved.keys())
    }

    @Test fun rejectedImportIsDeletedOnlyAfterAReadProvesItUnreferenced() = runTest(dispatcher) {
        val cleaned = mutableListOf<String>()
        var readable = true
        var snapshot = PluginSnapshot()
        val vm = testViewModel(readPlugins = {
            if (!readable) throw PluginReadException()
            snapshot
        }, readProviders = { emptyList() }, performEdit = { throw IllegalArgumentException() }, removeImport = { spec, _ -> cleaned.add(spec) })
        vm.attach(ProviderSessionKey(1, 1))
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
        val first = testViewModel(savedState = saved, readPlugins = { snapshot }, readProviders = { emptyList() },
            performEdit = { throw PluginWriteUnknownException() }, removeImport = { value, _ -> cleaned.add(value) }, clock = { 1000 })
        first.attach(ProviderSessionKey(1, 1))
        first.edit(PluginEdit.Remove(spec))
        first.refresh()
        assertTrue(cleaned.isEmpty())
        val restored = testViewModel(savedState = saved, readPlugins = { snapshot }, readProviders = { emptyList() },
            removeImport = { value, _ -> cleaned.add(value) }, clock = { 2000 })
        restored.attach(ProviderSessionKey(2, 1))
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
        val vm = testViewModel(readPlugins = {
            if (!readable) throw PluginReadException()
            PluginSnapshot()
        }, readProviders = { emptyList() }, performEdit = { throw PluginWriteUnknownException() },
            reconcileImports = { reconciliations++ }, clock = { 1000 })
        vm.attach(ProviderSessionKey(1, 1))
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
        val vm = testViewModel(readPlugins = {
            if (++reads == 1) old.await() else PluginSnapshot(entries = listOf(ManagedPlugin("current")))
        }, readProviders = { emptyList() })
        vm.attach(ProviderSessionKey(1, 1))
        vm.attach(ProviderSessionKey(2, 1))
        old.complete(PluginSnapshot(entries = listOf(ManagedPlugin("obsolete"))))
        assertEquals(listOf("current"), vm.state.value.snapshot.entries.map { it.spec })
    }

    @Test fun marketInstalledBadgesFollowSuccessfulInstallAndRemoval() = runTest(dispatcher) {
        val listing = PluginMarketListing(packageId = "@fixture/tools-example")
        val vm = testViewModel(readPlugins = { PluginSnapshot() }, readProviders = { emptyList() },
            readMarket = { PluginMarketSnapshot(listings = listOf(listing)) }, performEdit = { edit ->
                if (edit is PluginEdit.Add) PluginSnapshot(entries = listOf(ManagedPlugin(edit.spec))) else PluginSnapshot()
            })
        vm.attach(ProviderSessionKey(1, 1))
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
        val vm = testViewModel(readPlugins = { snapshot }, readProviders = { emptyList() }, readMarket = {
            if (++searches == 1) PluginMarketSnapshot(listings = listOf(listing)) else old.await()
        })
        vm.attach(ProviderSessionKey(1, 1))
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
        val vm = testViewModel(readPlugins = { PluginSnapshot() }, readProviders = { emptyList() },
            readMarket = { if (++searches == 1) PluginMarketSnapshot(listings = listOf(listing)) else old.await() },
            performEdit = { PluginSnapshot(entries = listOf(ManagedPlugin(listing.packageId))) })
        vm.attach(ProviderSessionKey(1, 1))
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
        val vm = testViewModel(readPlugins = {
            if (!readable) throw PluginReadException()
            PluginSnapshot()
        }, readProviders = { emptyList() }, readMarket = { if (++searches == 1) result else old.await() })
        vm.attach(ProviderSessionKey(1, 1))
        vm.market("")
        assertTrue(vm.state.value.market.isInstalled(listing))
        vm.market("old-query")
        readable = false
        vm.attach(ProviderSessionKey(2, 1))
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
        val vm = testViewModel(savedState = saved, readPlugins = { PluginSnapshot() }, readProviders = { listOf(provider) },
            performAuth = { step -> when (step) {
                is PluginAuthStep.Prompt -> PluginAuthReply(prompt = PluginPromptResult(inputs = step.inputs))
                is PluginAuthStep.Start -> { received = step.key; PluginAuthReply(state = PluginSignInState(id = "session", url = link, state = "waiting")) }
                is PluginAuthStep.Cancel -> PluginAuthReply()
                is PluginAuthStep.Read -> PluginAuthReply(state = PluginSignInState(id = "session", state = "canceled"))
                else -> PluginAuthReply()
            } })
        vm.attach(ProviderSessionKey(1, 1))
        vm.selectLogin(provider, 0)
        vm.startLogin(token)
        assertEquals(token, received)
        assertFalse(vm.state.value.toString().contains(token))
        assertEquals("", vm.state.value.signIn!!.url)
        var launched = ""
        vm.openLoginBrowser { launched = it }
        assertEquals(link, launched)
        assertEquals(setOf("plugin.auth.provider", "plugin.auth.stage", "plugin.auth.cancel"), saved.keys())
        assertFalse(saved.keys().any { it in setOf("plugin.auth.id", "plugin.auth.owner", "plugin.auth.epoch", "plugin.auth.url", "plugin.auth.key") })
        vm.cancelLogin()
        assertEquals("canceled", vm.state.value.signIn!!.state)
        assertNull(vm.state.value.loginRecovery)
        assertTrue(saved.keys().isEmpty())
        vm.cancelLogin()
        assertNull(vm.state.value.authProvider)
    }

    @Test fun callbackFollowupReadKeepsItsBusyFlagUntilItCompletes() = runTest(dispatcher) {
        val pendingRead = CompletableDeferred<PluginSignInState>()
        var callbacks = 0
        val provider = PluginProvider(pid = "fixture", methods = listOf(PluginSignInMethod(type = "oauth")))
        val vm = testViewModel(readPlugins = { PluginSnapshot() }, readProviders = { listOf(provider) }, performAuth = { step ->
            when (step) {
                is PluginAuthStep.Prompt -> PluginAuthReply(prompt = PluginPromptResult())
                is PluginAuthStep.Start -> PluginAuthReply(state = PluginSignInState(id = "session", state = "waiting", pasteCode = true))
                is PluginAuthStep.Callback -> { callbacks++; PluginAuthReply() }
                is PluginAuthStep.Read -> PluginAuthReply(state = pendingRead.await())
                else -> PluginAuthReply()
            }
        })
        vm.attach(ProviderSessionKey(1, 1))
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

    @Test fun cancellingBeforeStartReturnsUsesTheSameOperationOnceAndWaitsForConfirmation() = runTest(dispatcher) {
        val start = CompletableDeferred<PluginSignInState>()
        val operations = mutableListOf<TestPluginOperation>()
        val provider = oauthProvider()
        val steps = mutableListOf<PluginAuthStep>()
        val vm = testViewModel(captured = operations, performAuth = { step ->
            steps += step
            when (step) {
                is PluginAuthStep.Start -> PluginAuthReply(state = start.await())
                is PluginAuthStep.Read -> PluginAuthReply(state = PluginSignInState(id = step.id, state = "canceled"))
                else -> PluginAuthReply(prompt = PluginPromptResult())
            }
        })
        vm.attach(ProviderSessionKey(1, 1))
        vm.selectLogin(provider, 0)
        vm.startLogin()
        vm.cancelLogin()
        vm.cancelLogin()
        vm.endLoginTracking()
        assertTrue(vm.state.value.authBusy)
        assertTrue(vm.state.value.loginRecovery!!.cancelRequested)
        assertEquals(1, PluginAuthWorkTracker.active.value)
        assertEquals(0, steps.count { it is PluginAuthStep.Cancel })

        start.complete(PluginSignInState(id = "late-session", state = "waiting", url = "https://example.invalid/auth"))

        val startOperation = operations.single { operation -> operation.authSteps.any { it is PluginAuthStep.Start } }
        assertEquals(listOf("Start", "Cancel", "Read"), startOperation.authSteps.map { it.javaClass.simpleName })
        assertEquals(1, startOperation.closes)
        assertEquals(1, steps.count { it is PluginAuthStep.Start })
        assertEquals(1, steps.count { it is PluginAuthStep.Cancel })
        assertEquals(1, steps.count { it is PluginAuthStep.Read })
        assertEquals("canceled", vm.state.value.signIn!!.state)
        assertEquals("核心已确认登录任务取消", vm.state.value.notice)
        assertNull(vm.state.value.loginRecovery)
        assertFalse(vm.state.value.authBusy)
        assertEquals(0, PluginAuthWorkTracker.active.value)
    }

    @Test fun aLateSynchronousDoneWithoutAnIdIsNeverReportedAsCanceled() = runTest(dispatcher) {
        val start = CompletableDeferred<PluginSignInState>()
        val steps = mutableListOf<PluginAuthStep>()
        val vm = testViewModel(performAuth = { step ->
            steps += step
            if (step is PluginAuthStep.Start) PluginAuthReply(state = start.await())
            else PluginAuthReply(prompt = PluginPromptResult())
        })
        vm.attach(ProviderSessionKey(1, 1))
        vm.selectLogin(oauthProvider(), 0)
        vm.startLogin()
        vm.cancelLogin()
        start.complete(PluginSignInState(state = "done"))

        assertEquals("done", vm.state.value.signIn!!.state)
        assertTrue(vm.state.value.notice.contains("登录已经完成"))
        assertNull(vm.state.value.loginRecovery)
        assertEquals(0, steps.count { it is PluginAuthStep.Cancel })
        assertEquals(0, steps.count { it is PluginAuthStep.Read })
        assertFalse(vm.state.value.authBusy)
    }

    @Test fun aLateCancelReadThatSaysDoneWinsOverTheCancellationIntent() = runTest(dispatcher) {
        val cancel = CompletableDeferred<Unit>()
        var cancelPosts = 0
        val vm = testViewModel(performAuth = { step -> when (step) {
            is PluginAuthStep.Start -> PluginAuthReply(state = PluginSignInState(id = "session", state = "waiting"))
            is PluginAuthStep.Cancel -> { cancelPosts++; cancel.await(); PluginAuthReply() }
            is PluginAuthStep.Read -> PluginAuthReply(state = PluginSignInState(id = "session", state = "done"))
            else -> PluginAuthReply(prompt = PluginPromptResult())
        } })
        vm.attach(ProviderSessionKey(1, 1))
        vm.selectLogin(oauthProvider(), 0)
        vm.startLogin()
        vm.cancelLogin()
        vm.cancelLogin()
        vm.endLoginTracking()
        assertEquals(1, cancelPosts)
        assertTrue(vm.state.value.authBusy)
        assertNotNull(vm.state.value.loginRecovery)
        cancel.complete(Unit)
        assertEquals("done", vm.state.value.signIn!!.state)
        assertTrue(vm.state.value.notice.contains("登录已经完成"))
        assertNull(vm.state.value.loginRecovery)
        assertFalse(vm.state.value.authBusy)
    }

    @Test fun unknownCancelAndReadKeepTheMarkerAndNeverAutomaticallyRepeatThePost() = runTest(dispatcher) {
        var cancelPosts = 0
        var readable = false
        val vm = testViewModel(performAuth = { step -> when (step) {
            is PluginAuthStep.Start -> PluginAuthReply(state = PluginSignInState(id = "session", state = "waiting"))
            is PluginAuthStep.Cancel -> { cancelPosts++; throw PluginWriteUnknownException() }
            is PluginAuthStep.Read -> if (readable) PluginAuthReply(state = PluginSignInState(id = "session", state = "canceled")) else throw PluginReadException()
            else -> PluginAuthReply(prompt = PluginPromptResult())
        } })
        vm.attach(ProviderSessionKey(1, 1))
        vm.selectLogin(oauthProvider(), 0)
        vm.startLogin()
        vm.cancelLogin()
        assertEquals(PluginLoginRecovery.Stage.CANCEL_UNKNOWN, vm.state.value.loginRecovery!!.stage)
        assertTrue(vm.state.value.loginRecovery!!.cancelAttempted)
        assertFalse(vm.state.value.authBusy)
        vm.cancelLogin()
        vm.refreshLogin()
        assertEquals(1, cancelPosts)
        assertNotNull(vm.state.value.loginRecovery)
        readable = true
        vm.refreshLogin()
        assertEquals("canceled", vm.state.value.signIn!!.state)
        assertNull(vm.state.value.loginRecovery)
        assertEquals(1, cancelPosts)
    }

    @Test fun unknownStartWithoutAnIdCannotBeClearedByAnAlreadySignedInProvider() = runTest(dispatcher) {
        var starts = 0
        var providers = 0
        val provider = oauthProvider().copy(signedIn = true)
        val vm = testViewModel(readProviders = { providers++; listOf(provider) }, performAuth = { step ->
            if (step is PluginAuthStep.Start) { starts++; throw PluginWriteUnknownException() }
            PluginAuthReply(prompt = PluginPromptResult())
        })
        vm.attach(ProviderSessionKey(1, 1))
        vm.selectLogin(provider, 0)
        vm.startLogin()
        assertEquals(PluginLoginRecovery.Stage.START_UNKNOWN, vm.state.value.loginRecovery!!.stage)
        vm.startLogin()
        vm.refreshLogin()
        assertTrue(providers >= 2)
        assertEquals(1, starts)
        assertNotNull(vm.state.value.loginRecovery)
        vm.endLoginTracking()
        assertNull(vm.state.value.loginRecovery)
        assertTrue(vm.state.value.notice.contains("不撤销原核心登录"))
        assertEquals(1, starts)
    }

    @Test fun savedLoginRestoresOnlyADetachedNonsecretMarkerAndNeverReadsAnOldTask() = runTest(dispatcher) {
        val saved = SavedStateHandle()
        val first = testViewModel(savedState = saved, performAuth = { step ->
            if (step is PluginAuthStep.Start) PluginAuthReply(state = PluginSignInState(id = "private-task", state = "waiting", url = "https://example.invalid/?state=private-state"))
            else PluginAuthReply(prompt = PluginPromptResult())
        })
        first.attach(ProviderSessionKey(1, 1))
        first.selectLogin(oauthProvider(), 0)
        first.startLogin("private-key")
        first.attach(null)
        assertEquals(setOf("plugin.auth.provider", "plugin.auth.stage", "plugin.auth.cancel"), saved.keys())
        assertFalse(saved.keys().map { saved.get<Any>(it).toString() }.any { it.contains("private-") })
        val steps = mutableListOf<PluginAuthStep>()
        val restored = testViewModel(savedState = saved, readProviders = { listOf(oauthProvider().copy(signedIn = true)) }, performAuth = { step ->
            steps += step
            PluginAuthReply()
        })
        restored.attach(ProviderSessionKey(1, 2))
        restored.refreshLogin()
        restored.selectLogin(oauthProvider(), 0)
        restored.startLogin()
        assertEquals(PluginLoginRecovery.Stage.DETACHED, restored.state.value.loginRecovery!!.stage)
        assertNull(restored.state.value.authProvider)
        assertTrue(steps.isEmpty())
        restored.endLoginTracking()
        assertNull(restored.state.value.loginRecovery)
        assertTrue(steps.isEmpty())
    }

    @Test fun replacingTheEpochCancelsOldLoginAndItsCompletionCannotClearNewRefreshBusy() = runTest(dispatcher) {
        val start = CompletableDeferred<PluginSignInState>()
        val newRead = CompletableDeferred<PluginSnapshot>()
        val captured = mutableListOf<TestPluginOperation>()
        var reads = 0
        val vm = testViewModel(captured = captured, readPlugins = {
            if (++reads == 1) PluginSnapshot() else newRead.await()
        }, performAuth = { step ->
            if (step is PluginAuthStep.Start) PluginAuthReply(state = start.await())
            else PluginAuthReply(prompt = PluginPromptResult())
        })
        vm.attach(ProviderSessionKey(1, 1))
        vm.selectLogin(oauthProvider(), 0)
        vm.startLogin()
        val old = captured.last()
        vm.attach(ProviderSessionKey(1, 2))
        assertTrue(old.cancelled)
        assertTrue(vm.state.value.loading)
        assertFalse(vm.state.value.authBusy)
        assertEquals(PluginLoginRecovery.Stage.DETACHED, vm.state.value.loginRecovery!!.stage)
        start.complete(PluginSignInState(id = "old", state = "done"))
        assertTrue(vm.state.value.loading)
        assertNull(vm.state.value.signIn)
        assertEquals(1, old.closes)
        newRead.complete(PluginSnapshot(entries = listOf(ManagedPlugin("current"))))
        assertFalse(vm.state.value.loading)
        assertEquals(listOf("current"), vm.state.value.snapshot.entries.map { it.spec })
    }

    @Test fun endingTrackingWaitsForAnOldVmWorkerToActuallyLeaveItsFinally() = runTest(dispatcher) {
        val start = CompletableDeferred<PluginSignInState>()
        val saved = SavedStateHandle()
        val first = testViewModel(savedState = saved, performAuth = { step ->
            if (step is PluginAuthStep.Start) withContext(NonCancellable) { PluginAuthReply(state = start.await()) }
            else PluginAuthReply(prompt = PluginPromptResult())
        })
        first.attach(ProviderSessionKey(1, 1))
        first.selectLogin(oauthProvider(), 0)
        first.startLogin()
        first.viewModelScope.cancel()
        val replacement = testViewModel(savedState = saved)
        replacement.attach(ProviderSessionKey(1, 2))
        assertEquals(1, PluginAuthWorkTracker.active.value)
        replacement.endLoginTracking()
        assertNotNull(replacement.state.value.loginRecovery)
        start.complete(PluginSignInState(id = "old", state = "waiting"))
        assertEquals(0, PluginAuthWorkTracker.active.value)
        replacement.endLoginTracking()
        assertNull(replacement.state.value.loginRecovery)
    }

    @Test fun queuedStartCapturesBeforeLaunchAndAnEpochChangeSendsNoAuthRequest() = runTest(dispatcher) {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        val captured = mutableListOf<TestPluginOperation>()
        var starts = 0
        val vm = testViewModel(captured = captured, performAuth = { step ->
            if (step is PluginAuthStep.Start) starts++
            PluginAuthReply(prompt = PluginPromptResult())
        })
        vm.attach(ProviderSessionKey(1, 1))
        runCurrent()
        vm.selectLogin(oauthProvider(), 0)
        runCurrent()
        vm.startLogin()
        val queued = captured.last()
        assertEquals(ProviderSessionKey(1, 1), queued.key)
        assertTrue(vm.state.value.authBusy)
        assertEquals(0, starts)
        vm.attach(ProviderSessionKey(1, 2))
        runCurrent()
        assertEquals(0, starts)
        assertTrue(queued.cancelled)
        assertEquals(1, queued.closes)
        assertFalse(vm.state.value.authBusy)
        assertEquals(PluginLoginRecovery.Stage.DETACHED, vm.state.value.loginRecovery!!.stage)
        assertEquals(0, PluginAuthWorkTracker.active.value)
    }

    @Test fun cancellationBeforeTheStartWorkerRunsStillRecordsIntentAndCancelsALateIdOnce() = runTest(dispatcher) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val steps = mutableListOf<PluginAuthStep>()
        val vm = testViewModel(performAuth = { step ->
            steps += step
            when (step) {
                is PluginAuthStep.Start -> PluginAuthReply(state = PluginSignInState(id = "late", state = "waiting"))
                is PluginAuthStep.Read -> PluginAuthReply(state = PluginSignInState(id = "late", state = "canceled"))
                else -> PluginAuthReply(prompt = PluginPromptResult())
            }
        })
        vm.attach(ProviderSessionKey(1, 1)); runCurrent()
        vm.selectLogin(oauthProvider(), 0); runCurrent()
        vm.startLogin()
        vm.cancelLogin()
        vm.cancelLogin()
        assertTrue(vm.state.value.authBusy)
        assertEquals(0, steps.count { it is PluginAuthStep.Start })
        runCurrent()
        assertEquals(1, steps.count { it is PluginAuthStep.Start })
        assertEquals(1, steps.count { it is PluginAuthStep.Cancel })
        assertEquals("canceled", vm.state.value.signIn!!.state)
        assertNull(vm.state.value.loginRecovery)
    }

    @Test fun alreadyDisposedScopeReleasesCapturedLoginLeaseAndBusyStateWithoutARequest() = runTest(dispatcher) {
        val captured = mutableListOf<TestPluginOperation>()
        val vm = testViewModel(captured = captured)
        vm.attach(ProviderSessionKey(1, 1))
        vm.selectLogin(oauthProvider(), 0)
        vm.viewModelScope.cancel()
        vm.startLogin()
        val unstarted = captured.last()
        assertTrue(unstarted.authSteps.isEmpty())
        assertEquals(1, unstarted.closes)
        assertFalse(vm.state.value.authBusy)
        assertEquals(PluginLoginRecovery.Stage.START_UNKNOWN, vm.state.value.loginRecovery!!.stage)
        assertEquals(0, PluginAuthWorkTracker.active.value)
    }

    @Test fun importedFilesCannotSubmitToANewEpochAfterStaging() = runTest(dispatcher) {
        var writes = 0
        val vm = testViewModel(performEdit = { writes++; PluginSnapshot() })
        vm.attach(ProviderSessionKey(1, 1))
        val beforeCopy = vm.currentSession()
        vm.attach(ProviderSessionKey(1, 2))
        assertFalse(vm.edit(PluginEdit.Add("/private/copied/plugin.ts"), expectedSession = beforeCopy))
        assertEquals(0, writes)
        assertFalse(vm.state.value.pending)
    }

    @Test fun aFailedCancellationOfALateStartKeepsCancelUnknownAndTheKnownIdForReadOnlyRecovery() = runTest(dispatcher) {
        val start = CompletableDeferred<PluginSignInState>()
        var readable = false
        var cancelPosts = 0
        val vm = testViewModel(performAuth = { step -> when (step) {
            is PluginAuthStep.Start -> PluginAuthReply(state = start.await())
            is PluginAuthStep.Cancel -> { cancelPosts++; throw PluginWriteUnknownException() }
            is PluginAuthStep.Read -> if (readable) PluginAuthReply(state = PluginSignInState(id = step.id, state = "canceled")) else throw PluginReadException()
            else -> PluginAuthReply(prompt = PluginPromptResult())
        } })
        vm.attach(ProviderSessionKey(1, 1))
        vm.selectLogin(oauthProvider(), 0)
        vm.startLogin()
        vm.cancelLogin()
        start.complete(PluginSignInState(id = "late", state = "waiting"))
        assertEquals(PluginLoginRecovery.Stage.CANCEL_UNKNOWN, vm.state.value.loginRecovery!!.stage)
        assertTrue(vm.state.value.loginRecovery!!.cancelAttempted)
        vm.cancelLogin()
        assertEquals(1, cancelPosts)
        readable = true
        vm.refreshLogin()
        assertEquals("canceled", vm.state.value.signIn!!.state)
        assertNull(vm.state.value.loginRecovery)
        assertEquals(1, cancelPosts)
    }

    @Test fun cancellationRequestedDuringAReadWaitsForTheReadThenPostsOnce() = runTest(dispatcher) {
        val read = CompletableDeferred<PluginSignInState>()
        var reads = 0
        var cancels = 0
        val vm = testViewModel(performAuth = { step -> when (step) {
            is PluginAuthStep.Start -> PluginAuthReply(state = PluginSignInState(id = "session", state = "waiting"))
            is PluginAuthStep.Read -> PluginAuthReply(state = if (++reads == 1) read.await() else PluginSignInState(id = step.id, state = "canceled"))
            is PluginAuthStep.Cancel -> { cancels++; PluginAuthReply() }
            else -> PluginAuthReply(prompt = PluginPromptResult())
        } })
        vm.attach(ProviderSessionKey(1, 1))
        vm.selectLogin(oauthProvider(), 0)
        vm.startLogin()
        vm.refreshLogin()
        vm.cancelLogin()
        vm.cancelLogin()
        assertTrue(vm.state.value.authBusy)
        assertEquals(0, cancels)
        read.complete(PluginSignInState(id = "session", state = "waiting"))
        assertEquals(1, cancels)
        assertEquals(2, reads)
        assertEquals("canceled", vm.state.value.signIn!!.state)
        assertNull(vm.state.value.loginRecovery)
    }

    @Test fun unknownCallbackCannotRepeatUntilAReadConfirmsWaiting() = runTest(dispatcher) {
        var callbacks = 0
        var reads = 0
        val vm = testViewModel(performAuth = { step -> when (step) {
            is PluginAuthStep.Start -> PluginAuthReply(state = PluginSignInState(id = "session", state = "waiting", pasteCode = true))
            is PluginAuthStep.Callback -> { callbacks++; if (callbacks == 1) throw PluginWriteUnknownException() else PluginAuthReply() }
            is PluginAuthStep.Read -> { reads++; PluginAuthReply(state = PluginSignInState(id = step.id, state = if (reads == 1) "waiting" else "done")) }
            else -> PluginAuthReply(prompt = PluginPromptResult())
        } })
        vm.attach(ProviderSessionKey(1, 1))
        vm.selectLogin(oauthProvider(), 0)
        vm.startLogin()
        vm.submitCallback("private-code")
        assertEquals(PluginLoginRecovery.Stage.CALLBACK_UNKNOWN, vm.state.value.loginRecovery!!.stage)
        vm.submitCallback("private-code")
        assertEquals(1, callbacks)
        assertEquals(0, reads)
        vm.refreshLogin()
        assertEquals(1, reads)
        assertEquals(1, callbacks)
        assertEquals(PluginLoginRecovery.Stage.WAITING, vm.state.value.loginRecovery!!.stage)
        vm.submitCallback("new-code-after-confirmed-read")
        assertEquals(2, reads)
        assertEquals(2, callbacks)
        assertEquals("done", vm.state.value.signIn!!.state)
        assertNull(vm.state.value.loginRecovery)
        vm.submitCallback("cannot-submit-after-done")
        assertEquals(2, callbacks)
    }

    @Test fun queuedRefreshMarketAndEditAreCapturedBeforeLaunchAndEpochChangesClearBusyFlags() = runTest(dispatcher) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val captured = mutableListOf<TestPluginOperation>()
        val vm = testViewModel(captured = captured)
        vm.attach(ProviderSessionKey(1, 1))
        val read = captured.last()
        assertTrue(vm.state.value.loading)
        vm.attach(ProviderSessionKey(1, 2))
        runCurrent()
        assertEquals(0, read.pluginReads)
        assertEquals(1, read.closes)
        assertFalse(vm.state.value.loading)

        vm.market("queued")
        val market = captured.last()
        assertTrue(vm.state.value.loading)
        vm.attach(ProviderSessionKey(1, 3))
        runCurrent()
        assertEquals(0, market.marketReads)
        assertEquals(1, market.closes)
        assertFalse(vm.state.value.loading)

        assertTrue(vm.edit(PluginEdit.Add("queued-plugin")))
        val edit = captured.last()
        assertTrue(vm.state.value.busy)
        vm.attach(ProviderSessionKey(1, 4))
        runCurrent()
        assertEquals(0, edit.writes)
        assertEquals(1, edit.closes)
        assertFalse(vm.state.value.loading)
        assertFalse(vm.state.value.busy)
    }

    @Test fun waitingRepliesCannotRestartTheBoundedAutomaticPollingBudget() = runTest(dispatcher) {
        var reads = 0
        val vm = testViewModel(performAuth = { step -> when (step) {
            is PluginAuthStep.Start -> PluginAuthReply(state = PluginSignInState(id = "session", state = "waiting"))
            is PluginAuthStep.Read -> { reads++; PluginAuthReply(state = PluginSignInState(id = step.id, state = "waiting")) }
            else -> PluginAuthReply(prompt = PluginPromptResult())
        } })
        vm.attach(ProviderSessionKey(1, 1))
        vm.selectLogin(oauthProvider(), 0)
        vm.startLogin()
        advanceTimeBy(602_000)
        runCurrent()
        assertEquals(300, reads)
        advanceTimeBy(602_000)
        runCurrent()
        assertEquals(300, reads)
        assertNotNull(vm.state.value.loginRecovery)
        vm.endLoginTracking()
    }

    @Test fun anOldSuspendedImportCleanupCannotClearANewEpochPendingMarker() = runTest(dispatcher) {
        val oldCleanup = CompletableDeferred<Unit>()
        val newWrite = CompletableDeferred<PluginSnapshot>()
        val saved = SavedStateHandle(mapOf(
            "plugin.pending" to true, "plugin.spec" to "old-import", "plugin.operation" to "remove", "plugin.started" to 1000L,
        ))
        var cleanups = 0
        var reconciliations = 0
        val vm = testViewModel(savedState = saved, performEdit = { newWrite.await() },
            removeImport = { _, _ -> if (++cleanups == 1) oldCleanup.await() },
            reconcileImports = { reconciliations++ })
        vm.attach(ProviderSessionKey(1, 1))
        assertTrue(vm.state.value.loading)
        vm.attach(ProviderSessionKey(1, 2))
        assertFalse(vm.state.value.pending)
        assertTrue(vm.edit(PluginEdit.Add("new-import")))
        oldCleanup.complete(Unit)
        assertTrue(vm.state.value.busy)
        assertEquals("new-import", saved.get<String>("plugin.spec"))
        assertTrue(saved.get<Boolean>("plugin.pending")!!)
        assertEquals(1, reconciliations)
        newWrite.completeExceptionally(PluginWriteUnknownException())
        assertTrue(vm.state.value.pending)
    }

    private fun oauthProvider() = PluginProvider(pid = "fixture", methods = listOf(PluginSignInMethod(type = "oauth")))

    /** Compatibility fixture: production always captures the whole operation before dispatch. */
    private fun testViewModel(
        savedState: SavedStateHandle = SavedStateHandle(),
        readPlugins: suspend () -> PluginSnapshot = { PluginSnapshot() },
        readProviders: suspend () -> List<PluginProvider> = { emptyList() },
        readMarket: suspend (String) -> PluginMarketSnapshot = { PluginMarketSnapshot() },
        performEdit: suspend (PluginEdit) -> PluginSnapshot = { PluginSnapshot() },
        performAuth: suspend (PluginAuthStep) -> PluginAuthReply = { PluginAuthReply(prompt = PluginPromptResult()) },
        clock: () -> Long = { 1000L },
        reconcileImports: suspend (PluginSnapshot) -> Unit = {},
        removeImport: suspend (String, PluginSnapshot) -> Unit = { _, _ -> },
        rejectImport: suspend (String) -> Unit = {},
        captured: MutableList<TestPluginOperation> = mutableListOf(),
    ): PluginManagementViewModel = PluginManagementViewModel(
        savedState = savedState,
        capture = { key -> TestPluginOperation(key, readPlugins, readProviders, readMarket, performEdit, performAuth).also(captured::add) },
        clock = clock,
        reconcileImports = reconcileImports,
        removeImport = removeImport,
        rejectImport = rejectImport,
    ).also(viewModels::add)

    private class TestPluginOperation(
        val key: ProviderSessionKey,
        private val readPlugins: suspend () -> PluginSnapshot,
        private val readProviders: suspend () -> List<PluginProvider>,
        private val readMarket: suspend (String) -> PluginMarketSnapshot,
        private val performEdit: suspend (PluginEdit) -> PluginSnapshot,
        private val performAuth: suspend (PluginAuthStep) -> PluginAuthReply,
    ) : PluginSessionOperation {
        var cancelled = false
        var closes = 0
        var pluginReads = 0
        var marketReads = 0
        var writes = 0
        val authSteps = mutableListOf<PluginAuthStep>()
        override fun ensureCurrent() { if (cancelled || closes != 0) throw CancellationException("Superseded test session") }
        override fun cancel() { cancelled = true }
        override fun close() { closes++ }
        private suspend fun <T> request(block: suspend () -> T): T {
            ensureCurrent()
            return block().also { ensureCurrent() }
        }
        override suspend fun plugins() = request { pluginReads++; readPlugins() }
        override suspend fun providers() = request(readProviders)
        override suspend fun market(query: String) = request { marketReads++; readMarket(query) }
        override suspend fun edit(edit: PluginEdit) = request { writes++; performEdit(edit) }
        override suspend fun auth(step: PluginAuthStep) = request { authSteps += step; performAuth(step) }
    }
}

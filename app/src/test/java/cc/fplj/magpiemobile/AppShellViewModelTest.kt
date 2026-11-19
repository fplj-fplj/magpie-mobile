package cc.fplj.magpiemobile

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import cc.fplj.magpiemobile.core.ModelEntry
import cc.fplj.magpiemobile.preferences.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class AppShellViewModelTest {
    private lateinit var app: Application
    private val dispatcher = UnconfinedTestDispatcher()
    private val scopes = mutableListOf<CoroutineScope>()
    private val stores = mutableListOf<ViewModelStore>()
    private val entries = listOf(ModelEntry("p/model-a", "A", "p", null), ModelEntry("p/model-b", "B", "p", null))

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        app = ApplicationProvider.getApplicationContext()
    }

    @After fun teardown() {
        stores.forEach { it.clear() }
        scopes.forEach { it.cancel() }
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    private fun repo(store: DataStore<Preferences>, dispatcher: CoroutineDispatcher = Dispatchers.IO): AppUiPreferences {
        val scope = CoroutineScope(SupervisorJob() + dispatcher).also(scopes::add)
        return AppUiPreferences(store, scope)
    }

    private fun vm(repo: AppUiPreferences, connection: () -> CatalogFixture? = { null }): AppShellViewModel =
        createVm(repo, { connection()?.key }) { expected ->
            val fixture = connection() ?: throw CancellationException("Catalog unavailable")
            if (fixture.key != expected) throw CancellationException("Catalog epoch changed")
            TestCatalogConnection(expected, fixture.read, fixture.isCurrent)
        }

    private fun createVm(
        repo: AppUiPreferences,
        current: () -> ProviderSessionKey?,
        capture: (ProviderSessionKey) -> CatalogConnection,
    ): AppShellViewModel {
        val store = ViewModelStore().also(stores::add)
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return AppShellViewModel(app, repo, current, capture) as T
            }
        }
        return ViewModelProvider(store, factory)[AppShellViewModel::class.java]
    }

    private fun await(vm: AppShellViewModel, predicate: (AppShellState) -> Boolean): AppShellState = runBlocking {
        withTimeout(10000) { vm.state.first(predicate) }
    }

    private fun stored(page: AppPage = AppPage.CHAT, theme: ThemeMode = ThemeMode.DARK, model: String = "p/model-b") =
        mutablePreferencesOf(UiPreferencesCodec.schema to 1, UiPreferencesCodec.page to page.name,
            UiPreferencesCodec.theme to theme.name, UiPreferencesCodec.modelId to model)

    @Test fun restoresPreferencesButDoesNotReadCatalogOrStartGatewayOnItsOwn() {
        val calls = AtomicInteger()
        val repo = repo(FakeStore(stored().also {
            it[UiPreferencesCodec.gatewayIntent] = GatewayIntent.START.name
            it[UiPreferencesCodec.intentRevision] = 7L
        }))
        val vm = vm(repo) { calls.incrementAndGet(); CatalogFixture(ProviderSessionKey(1, 1), { entries }) { true } }
        val state = await(vm) { it.preferences.ready }
        assertEquals(AppPage.CHAT, state.page)
        assertEquals(ThemeMode.DARK, state.theme)
        assertEquals(GatewayIntent.START, state.preferences.value.gatewayIntent)
        assertEquals(0, calls.get())
        assertFalse(state.catalogValid)
    }

    @Test fun savedModelIsRestoredOnlyAfterFreshCatalogAndTabsDoNotReadAgain() {
        val reads = AtomicInteger()
        val vm = vm(repo(FakeStore(stored()))) { CatalogFixture(ProviderSessionKey(1, 1), { reads.incrementAndGet(); entries }) { true } }
        await(vm) { it.preferences.ready }
        vm.connectionChanged()
        await(vm) { it.catalogValid && !it.loadingModels && it.selectedModel == "p/model-b" }
        vm.selectPage(AppPage.MORE); vm.selectPage(AppPage.GATEWAY); vm.selectPage(AppPage.CHAT)
        vm.connectionChanged()
        assertEquals(1, reads.get())
        assertEquals("p/model-b", vm.state.value.selectedModel)
    }

    @Test fun missingSavedModelRequiresExplicitChoiceRatherThanSilentFallback() {
        val vm = vm(repo(FakeStore(stored(model = "p/missing")))) { CatalogFixture(ProviderSessionKey(1, 1), { entries }) { true } }
        await(vm) { it.preferences.ready }; vm.connectionChanged()
        val state = await(vm) { it.catalogValid && !it.loadingModels }
        assertEquals("", state.selectedModel)
        assertTrue(state.modelsNotice.contains("没有自动换模型"))
        vm.selectModel("p/model-a")
        await(vm) { it.preferences.value.modelId == "p/model-a" }
        assertEquals("p/model-a", vm.state.value.selectedModel)
    }

    @Test fun latePreferenceReadCannotOverwriteNewPageThemeOrModelChoice() {
        val store = FakeStore(stored(), blocked = true)
        val vm = vm(repo(store)) { CatalogFixture(ProviderSessionKey(1, 1), { entries }) { true } }
        vm.selectPage(AppPage.MORE)
        vm.selectTheme(ThemeMode.LIGHT)
        vm.connectionChanged(); await(vm) { it.catalogValid && !it.loadingModels }
        vm.selectModel("p/model-a")
        store.unblock()
        val state = await(vm) { it.preferences.ready && it.preferences.value.modelId == "p/model-a" }
        assertEquals(AppPage.MORE, state.page)
        assertEquals(ThemeMode.LIGHT, state.theme)
        assertEquals("p/model-a", state.selectedModel)
    }

    @Test fun validSelectionSurvivesRefreshAndOwnerChanges() {
        var owner = 1L
        val vm = vm(repo(FakeStore(stored()))) { val captured = owner; CatalogFixture(ProviderSessionKey(captured, 1), { entries }) { owner == captured } }
        await(vm) { it.preferences.ready }; vm.connectionChanged()
        await(vm) { it.catalogValid && !it.loadingModels }
        vm.selectModel("p/model-a"); vm.refreshModels()
        await(vm) { it.catalogValid && !it.loadingModels }
        assertEquals("p/model-a", vm.state.value.selectedModel)
        owner = 2; vm.connectionChanged()
        await(vm) { it.catalogValid && !it.loadingModels }
        assertEquals("p/model-a", vm.state.value.selectedModel)
    }

    @Test fun removedSelectedModelIsClearedAndNotPersistentlyReplaced() {
        var catalog = entries
        val vm = vm(repo(FakeStore(stored()))) { CatalogFixture(ProviderSessionKey(1, 1), { catalog }) { true } }
        await(vm) { it.preferences.ready }; vm.connectionChanged()
        await(vm) { it.catalogValid && !it.loadingModels }
        vm.selectModel("p/model-b")
        await(vm) { it.preferences.value.modelId == "p/model-b" }
        catalog = entries.take(1); vm.refreshModels()
        val state = await(vm) { it.catalogValid && !it.loadingModels }
        assertEquals("", state.selectedModel)
        assertEquals("p/model-b", state.preferences.value.modelId)
        assertTrue(state.modelsNotice.contains("没有自动换模型"))
    }

    @Test fun ownerChangedDuringReadCannotApplyStaleCatalog() {
        var owner = 1L
        val vm = vm(repo(FakeStore(stored()))) {
            val captured = owner
            CatalogFixture(ProviderSessionKey(captured, 1), { if (captured == 1L) owner = 2L; entries }) { owner == captured }
        }
        await(vm) { it.preferences.ready }; vm.connectionChanged()
        val stale = await(vm) { !it.loadingModels }
        assertFalse(stale.catalogValid)
        assertTrue(stale.models.isEmpty())
        vm.connectionChanged()
        await(vm) { it.catalogValid && !it.loadingModels }
    }

    @Test fun failedPreferenceReadIsNotHydrationAndRetryCanRestoreHealthyData() {
        val store = FakeStore(mutablePreferencesOf(intPreferencesKey("schema") to 99))
        val repo = repo(store)
        val vm = vm(repo)
        val failure = await(vm) { it.preferences.warning != null }
        assertFalse(failure.preferences.ready)
        assertEquals(AppPage.GATEWAY, failure.page)
        store.replace(stored())
        vm.retryPreferences()
        val state = await(vm) { it.preferences.ready && it.page == AppPage.CHAT }
        assertEquals(ThemeMode.DARK, state.theme)
    }

    @Test fun retainedViewModelPreservesLocalPageAndDoesNotRepeatCatalogRead() {
        val reads = AtomicInteger()
        val vm = vm(repo(FakeStore(emptyPreferences()))) { CatalogFixture(ProviderSessionKey(1, 1), { reads.incrementAndGet(); entries }) { true } }
        await(vm) { it.preferences.ready }; vm.connectionChanged()
        await(vm) { it.catalogValid && !it.loadingModels }; vm.selectPage(AppPage.MORE)
        val retained = ViewModelProvider(stores.last(), object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T = error("must retain")
        })[AppShellViewModel::class.java]
        assertSame(vm, retained)
        retained.connectionChanged()
        assertEquals(AppPage.MORE, retained.state.value.page)
        assertEquals(1, reads.get())
    }

    @Test fun sameOwnerNewEpochInvalidatesWithoutAnIntermediateDisconnectAndRefreshesOnce() = runTest(dispatcher) {
        var key = ProviderSessionKey(1, 1)
        val fresh = CompletableDeferred<List<ModelEntry>>()
        val captured = mutableListOf<TestCatalogConnection>()
        val vm = createVm(repo(FakeStore(stored()), dispatcher), { key }) { expected ->
            TestCatalogConnection(expected, { if (expected.epoch == 1L) entries else fresh.await() }, { key == expected }).also(captured::add)
        }
        vm.connectionChanged()
        assertEquals("p/model-b", vm.state.value.selectedModel)
        assertEquals(key, vm.state.value.catalogSession)
        key = ProviderSessionKey(1, 2)
        vm.connectionChanged()
        assertFalse(vm.state.value.catalogValid)
        assertNull(vm.state.value.catalogSession)
        assertTrue(vm.state.value.loadingModels)
        assertEquals("p/model-b", vm.state.value.selectedModel)
        vm.connectionChanged()
        assertEquals(2, captured.size)
        val changed = entries.map { it.copy(displayName = "New ${it.displayName}") }
        fresh.complete(changed)
        assertTrue(vm.state.value.catalogValid)
        assertFalse(vm.state.value.loadingModels)
        assertEquals(key, vm.state.value.catalogSession)
        assertEquals(changed, vm.state.value.models)
        assertEquals("p/model-b", vm.state.value.selectedModel)
        vm.selectPage(AppPage.MORE)
        vm.selectPage(AppPage.CHAT)
        vm.connectionChanged()
        assertEquals(2, captured.size)
    }

    @Test fun lateOldEpochSuccessCannotApplyModelsOrClearTheNewLoadingFlag() = runTest(dispatcher) {
        assertOldCompletionIgnored(fail = false)
    }

    @Test fun lateOldEpochFailureCannotReplaceTheNewNoticeOrClearTheNewLoadingFlag() = runTest(dispatcher) {
        assertOldCompletionIgnored(fail = true)
    }

    private suspend fun assertOldCompletionIgnored(fail: Boolean) {
        var key = ProviderSessionKey(1, 1)
        val old = CompletableDeferred<List<ModelEntry>>()
        val fresh = CompletableDeferred<List<ModelEntry>>()
        val captured = mutableListOf<TestCatalogConnection>()
        val vm = createVm(repo(FakeStore(stored()), dispatcher), { key }) { expected ->
            TestCatalogConnection(expected, {
                if (expected.epoch == 1L) withContext(NonCancellable) {
                    val result = old.await()
                    if (fail) throw IllegalStateException("Old catalog failed")
                    result
                } else fresh.await()
            }, { key == expected }).also(captured::add)
        }
        try {
            vm.connectionChanged()
            key = ProviderSessionKey(1, 2)
            vm.connectionChanged()
            val newNotice = vm.state.value.modelsNotice
            old.complete(entries)
            dispatcher.scheduler.runCurrent()
            assertTrue(vm.state.value.loadingModels)
            assertFalse(vm.state.value.catalogValid)
            assertTrue(vm.state.value.models.isEmpty())
            assertEquals("", vm.state.value.selectedModel)
            assertEquals(newNotice, vm.state.value.modelsNotice)
            assertEquals(1, captured.first().closes)
            assertEquals(0, captured.last().closes)
            fresh.complete(entries.take(1))
            dispatcher.scheduler.runCurrent()
            assertEquals(entries.take(1), vm.state.value.models)
            assertEquals(key, vm.state.value.catalogSession)
            assertTrue(vm.state.value.catalogValid)
            assertFalse(vm.state.value.loadingModels)
        } finally { old.complete(entries); fresh.complete(entries) }
    }

    @Test fun aSupersededReadOnTheSameEpochCannotClearItsReplacementLoadingFlag() = runTest(dispatcher) {
        val key = ProviderSessionKey(1, 1)
        val first = CompletableDeferred<List<ModelEntry>>()
        val second = CompletableDeferred<List<ModelEntry>>()
        var captures = 0
        val vm = createVm(repo(FakeStore(stored()), dispatcher), { key }) { expected ->
            val number = ++captures
            TestCatalogConnection(expected, { if (number == 1) withContext(NonCancellable) { first.await() } else second.await() })
        }
        try {
            vm.connectionChanged()
            vm.refreshModels()
            first.complete(entries)
            dispatcher.scheduler.runCurrent()
            assertTrue(vm.state.value.loadingModels)
            assertFalse(vm.state.value.catalogValid)
            second.complete(entries.take(1))
            dispatcher.scheduler.runCurrent()
            assertFalse(vm.state.value.loadingModels)
            assertEquals(entries.take(1), vm.state.value.models)
        } finally { first.complete(entries); second.complete(entries) }
    }

    @Test fun successfulNewEpochClearsAMissingSelectionWithoutPersistingAFallback() = runTest(dispatcher) {
        var key = ProviderSessionKey(1, 1)
        var catalog = entries
        val vm = createVm(repo(FakeStore(stored()), dispatcher), { key }) { expected ->
            TestCatalogConnection(expected, { catalog }, { key == expected })
        }
        vm.connectionChanged()
        vm.selectModel("p/model-b")
        assertEquals("p/model-b", vm.state.value.preferences.value.modelId)
        catalog = entries.take(1)
        key = ProviderSessionKey(1, 2)
        vm.connectionChanged()
        assertEquals("", vm.state.value.selectedModel)
        assertEquals("p/model-b", vm.state.value.preferences.value.modelId)
        assertTrue(vm.state.value.modelsNotice.contains("没有自动换模型"))
        vm.refreshModels()
        assertEquals("", vm.state.value.selectedModel)
        assertEquals("p/model-b", vm.state.value.preferences.value.modelId)
    }

    @Test fun aSuccessfulEmptyNewCatalogClearsSelectionAndDoesNotInventAnotherModel() = runTest(dispatcher) {
        var key = ProviderSessionKey(1, 1)
        val vm = createVm(repo(FakeStore(stored()), dispatcher), { key }) { expected ->
            TestCatalogConnection(expected, { if (expected.epoch == 1L) entries else emptyList() }, { key == expected })
        }
        vm.connectionChanged()
        key = ProviderSessionKey(1, 2)
        vm.connectionChanged()
        assertTrue(vm.state.value.catalogValid)
        assertEquals(key, vm.state.value.catalogSession)
        assertEquals("", vm.state.value.selectedModel)
        assertEquals("p/model-b", vm.state.value.preferences.value.modelId)
        assertTrue(vm.state.value.models.isEmpty())
    }

    @Test fun failedNewEpochKeepsTheCachedChoiceUnconfirmedUntilAnExplicitSuccessfulRefresh() = runTest(dispatcher) {
        var key: ProviderSessionKey? = ProviderSessionKey(1, 1)
        var readable = true
        var captures = 0
        val vm = createVm(repo(FakeStore(stored()), dispatcher), { key }) { expected ->
            captures++
            TestCatalogConnection(expected, { if (!readable) throw IllegalStateException("Read failed") else entries }, { key == expected })
        }
        vm.connectionChanged()
        key = null
        vm.connectionChanged()
        assertFalse(vm.state.value.catalogValid)
        assertEquals("p/model-b", vm.state.value.selectedModel)
        assertEquals(1, captures)
        key = ProviderSessionKey(1, 2)
        readable = false
        vm.connectionChanged()
        assertFalse(vm.state.value.catalogValid)
        assertFalse(vm.state.value.loadingModels)
        assertEquals(entries, vm.state.value.models)
        assertEquals("p/model-b", vm.state.value.selectedModel)
        vm.selectModel("p/model-a")
        assertEquals("p/model-b", vm.state.value.preferences.value.modelId)
        vm.connectionChanged()
        assertEquals(2, captures)
        readable = true
        vm.refreshModels()
        assertEquals(3, captures)
        assertTrue(vm.state.value.catalogValid)
        assertEquals(key, vm.state.value.catalogSession)
        assertEquals("p/model-b", vm.state.value.selectedModel)
    }

    @Test fun selectionCannotWritePreferencesWhenTheEpochChangedBeforeTheNavigationEffect() = runTest(dispatcher) {
        var key = ProviderSessionKey(1, 1)
        var captures = 0
        val vm = createVm(repo(FakeStore(stored()), dispatcher), { key }) { expected ->
            captures++
            TestCatalogConnection(expected, { entries }, { key == expected })
        }
        vm.connectionChanged()
        key = ProviderSessionKey(1, 2)
        vm.selectModel("p/model-a")
        assertFalse(vm.state.value.catalogValid)
        assertEquals("p/model-b", vm.state.value.selectedModel)
        assertEquals("p/model-b", vm.state.value.preferences.value.modelId)
        assertEquals(1, captures)
        vm.connectionChanged()
        assertEquals(2, captures)
        assertEquals(key, vm.state.value.catalogSession)
    }

    @Test fun latePreferenceHydrationCannotRestoreAModelFromAnEpochThatIsAlreadyStale() = runTest(dispatcher) {
        val raw = FakeStore(stored(), blocked = true)
        var key = ProviderSessionKey(1, 1)
        val fresh = CompletableDeferred<List<ModelEntry>>()
        val vm = createVm(repo(raw, dispatcher), { key }) { expected ->
            TestCatalogConnection(expected, { if (expected.epoch == 1L) entries else fresh.await() }, { key == expected })
        }
        vm.connectionChanged()
        assertTrue(vm.state.value.catalogValid)
        assertEquals("", vm.state.value.selectedModel)
        key = ProviderSessionKey(1, 2)
        raw.unblock()
        dispatcher.scheduler.runCurrent()
        assertTrue(vm.state.value.preferences.ready)
        assertFalse(vm.state.value.catalogValid)
        assertEquals("", vm.state.value.selectedModel)
        vm.connectionChanged()
        assertEquals("", vm.state.value.selectedModel)
        fresh.complete(entries)
        assertEquals(key, vm.state.value.catalogSession)
        assertEquals("p/model-b", vm.state.value.selectedModel)
    }

    @Test fun aResultFromAnEpochThatChangedWithoutAnEffectCannotBecomeHealthy() = runTest(dispatcher) {
        var key = ProviderSessionKey(1, 1)
        val reply = CompletableDeferred<List<ModelEntry>>()
        val operation = TestCatalogConnection(key, { reply.await() })
        val vm = createVm(repo(FakeStore(stored()), dispatcher), { key }) { operation }
        vm.connectionChanged()
        key = ProviderSessionKey(1, 2)
        reply.complete(entries)
        assertFalse(vm.state.value.catalogValid)
        assertFalse(vm.state.value.loadingModels)
        assertTrue(vm.state.value.models.isEmpty())
        assertEquals("", vm.state.value.selectedModel)
        assertEquals(1, operation.closes)
    }

    @Test fun aLateFailureAfterAnUnobservedEpochChangeCannotPublishAnOldError() = runTest(dispatcher) {
        var key = ProviderSessionKey(1, 1)
        val reply = CompletableDeferred<List<ModelEntry>>()
        val operation = TestCatalogConnection(key, { reply.await() })
        val vm = createVm(repo(FakeStore(stored()), dispatcher), { key }) { operation }
        vm.connectionChanged()
        key = ProviderSessionKey(1, 2)
        reply.completeExceptionally(IllegalStateException("Old failure"))
        assertFalse(vm.state.value.catalogValid)
        assertNull(vm.state.value.catalogSession)
        assertFalse(vm.state.value.loadingModels)
        assertEquals("核心连接已变化，模型目录尚未确认。", vm.state.value.modelsNotice)
        assertEquals(1, operation.closes)
    }

    @Test fun lateHydrationCannotReplaceAUserChoiceThatTheNewEpochAlreadyProvedMissing() = runTest(dispatcher) {
        val raw = FakeStore(stored(), blocked = true)
        var key = ProviderSessionKey(1, 1)
        var catalog = entries
        var captures = 0
        val vm = createVm(repo(raw, dispatcher), { key }) { expected ->
            captures++
            TestCatalogConnection(expected, { catalog }, { key == expected })
        }
        vm.connectionChanged()
        vm.selectModel("p/model-a")
        catalog = entries.drop(1)
        key = ProviderSessionKey(1, 2)
        vm.connectionChanged()
        assertEquals("", vm.state.value.selectedModel)
        raw.unblock()
        dispatcher.scheduler.runCurrent()
        assertTrue(vm.state.value.preferences.ready)
        assertEquals("p/model-a", vm.state.value.preferences.value.modelId)
        assertEquals("", vm.state.value.selectedModel)
        assertEquals(key, vm.state.value.catalogSession)
        assertEquals(2, captures)
        vm.refreshModels()
        assertEquals("", vm.state.value.selectedModel)
    }

    @Test fun queuedCatalogCapturesSynchronouslyAndAnEpochReplacementSendsNoOldRead() = runTest(dispatcher) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var key = ProviderSessionKey(1, 1)
        val captured = mutableListOf<TestCatalogConnection>()
        val vm = createVm(repo(FakeStore(stored()), dispatcher), { key }) { expected ->
            TestCatalogConnection(expected, { entries }, { key == expected }).also(captured::add)
        }
        vm.connectionChanged()
        assertEquals(1, captured.size)
        assertEquals(ProviderSessionKey(1, 1), captured.single().key)
        assertEquals(0, captured.single().reads)
        key = ProviderSessionKey(1, 2)
        vm.connectionChanged()
        runCurrent()
        assertEquals(0, captured.first().reads)
        assertTrue(captured.first().cancelled)
        assertEquals(1, captured.first().closes)
        assertEquals(1, captured.last().reads)
        assertEquals(key, vm.state.value.catalogSession)
        assertTrue(vm.state.value.catalogValid)
    }

    @Test fun disposingBeforeTheLazyCatalogWorkerStartsClosesExactlyOnceAndUnwindsLoading() = runTest(dispatcher) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val key = ProviderSessionKey(1, 1)
        val operation = TestCatalogConnection(key, { entries })
        val vm = createVm(repo(FakeStore(stored()), dispatcher), { key }) { operation }
        vm.connectionChanged()
        assertTrue(vm.state.value.loadingModels)
        stores.last().clear()
        runCurrent()
        assertEquals(0, operation.reads)
        assertTrue(operation.cancelled)
        assertEquals(1, operation.closes)
        assertFalse(vm.state.value.loadingModels)
        assertFalse(vm.state.value.catalogValid)
    }

    @Test fun refreshingAnAlreadyDisposedScopeStillReleasesTheSynchronousCapture() = runTest(dispatcher) {
        val key = ProviderSessionKey(1, 1)
        val operation = TestCatalogConnection(key, { entries })
        val vm = createVm(repo(FakeStore(stored()), dispatcher), { key }) { operation }
        stores.last().clear()
        vm.refreshModels()
        assertEquals(0, operation.reads)
        assertTrue(operation.cancelled)
        assertEquals(1, operation.closes)
        assertFalse(vm.state.value.loadingModels)
        assertFalse(vm.state.value.catalogValid)
    }

    @Test fun aCaptureRejectedByANewEpochDoesNotFallBackToTheNewCredentials() = runTest(dispatcher) {
        var key = ProviderSessionKey(1, 1)
        var captures = 0
        val vm = createVm(repo(FakeStore(stored()), dispatcher), { key }) { expected ->
            captures++
            assertEquals(ProviderSessionKey(1, 1), expected)
            key = ProviderSessionKey(1, 2)
            throw CancellationException("Changed inside atomic capture")
        }
        vm.connectionChanged()
        assertEquals(1, captures)
        assertFalse(vm.state.value.catalogValid)
        assertFalse(vm.state.value.loadingModels)
        assertTrue(vm.state.value.models.isEmpty())
    }

    @Test fun aCaptureThatReturnsTheWrongKeyIsClosedWithoutAnyRead() = runTest(dispatcher) {
        val key = ProviderSessionKey(1, 1)
        val operation = TestCatalogConnection(ProviderSessionKey(1, 2), { entries })
        val vm = createVm(repo(FakeStore(stored()), dispatcher), { key }) { operation }
        vm.connectionChanged()
        assertEquals(0, operation.reads)
        assertEquals(1, operation.closes)
        assertFalse(vm.state.value.catalogValid)
        assertFalse(vm.state.value.loadingModels)
    }

    @Test fun disposingTheProductionCatalogVmClosesSilentSocketsBeforeHeadersAndDuringTheBody() = runBlocking {
        for (sendHeaders in listOf(false, true)) CatalogTestPeer(silent = true, sendHeaders = sendHeaders).use { peer ->
            val key = ProviderSessionKey(1, 1)
            val leases = GatewayRequestLeases()
            val released = CountDownLatch(1)
            val controller = CatalogOperationController(acquire = { expected ->
                assertEquals(key, expected)
                val lease = leases.acquire(peer.config) { true }
                object : GatewayRequestLease by lease {
                    override fun close() { try { lease.close() } finally { released.countDown() } }
                }
            })
            val vm = createVm(repo(FakeStore(stored())), { key }, controller::capture)
            try {
                vm.connectionChanged()
                assertTrue(peer.arrived.await(5, TimeUnit.SECONDS))
                assertTrue(vm.state.value.loadingModels)
                stores.last().clear()
                assertTrue("VM disposal must disconnect the actual silent socket", peer.peerClosed.await(5, TimeUnit.SECONDS))
                assertTrue(released.await(5, TimeUnit.SECONDS))
                await(vm) { !it.loadingModels }
                assertFalse(vm.state.value.catalogValid)
                assertNull(vm.state.value.catalogSession)
                assertTrue(vm.state.value.models.isEmpty())
                assertEquals("", vm.state.value.selectedModel)
                assertEquals(listOf("GET /v1/models"), peer.requests.map { it.line })
                assertNull(peer.workerFailure.get())
            } finally { stores.last().clear(); leases.invalidate() }
        }
    }

    /** A controlled in-memory DataStore; real file corruption/atomicity tests are in preferences/. */
    private class FakeStore(initial: Preferences, blocked: Boolean = false) : DataStore<Preferences> {
        private val value = MutableStateFlow(initial)
        private val gate = CompletableDeferred<Unit>().also { if (!blocked) it.complete(Unit) }
        private val lock = Mutex()
        override val data: Flow<Preferences> = flow { gate.await(); emitAll(value) }
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = lock.withLock {
            gate.await(); transform(value.value).also { value.value = it }
        }
        fun unblock() { gate.complete(Unit) }
        fun replace(raw: Preferences) { value.value = raw }
    }

    private class CatalogFixture(
        val key: ProviderSessionKey,
        val read: suspend () -> List<ModelEntry>,
        val isCurrent: () -> Boolean,
    )

    private class TestCatalogConnection(
        override val key: ProviderSessionKey,
        private val reader: suspend () -> List<ModelEntry>,
        private val isCurrent: () -> Boolean = { true },
    ) : CatalogConnection {
        var cancelled = false
        var closes = 0
        var reads = 0
        override fun ensureCurrent() {
            if (cancelled || closes != 0 || !isCurrent()) throw CancellationException("Catalog epoch changed")
        }
        override fun cancel() { cancelled = true }
        override fun close() { closes++ }
        override suspend fun read(): List<ModelEntry> {
            ensureCurrent()
            reads++
            return reader().also { ensureCurrent() }
        }
    }
}

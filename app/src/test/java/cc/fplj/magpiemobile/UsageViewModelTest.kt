package cc.fplj.magpiemobile

import cc.fplj.magpiemobile.core.UsageRecord
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class UsageViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val sample1 = UsageRecord(
        id = "req-1",
        timestamp = "2026-11-08T15:00:00Z",
        model = "gpt-4o",
        provider = "openai",
        statusCode = 200,
        durationMs = 450,
        totalTokens = 120
    )

    private val sample2 = UsageRecord(
        id = "req-2",
        timestamp = "2026-11-08T15:05:00Z",
        model = "claude-3-5-sonnet",
        provider = "anthropic",
        statusCode = 200,
        durationMs = 600,
        totalTokens = 85
    )

    @Test
    fun refreshLoadsRequestsSuccessfully() = runTest(testDispatcher) {
        val vm = UsageViewModel(
            requestsReader = { listOf(sample1, sample2) }
        )

        vm.refresh(5)
        advanceUntilIdle()

        val state = vm.state.value
        assertFalse(state.loading)
        assertEquals(2, state.requests.size)
        assertEquals("req-1", state.requests[0].id)
        assertEquals("gpt-4o", state.requests[0].model)
        assertEquals(200, state.requests[0].statusCode)
        assertEquals("", state.error)
    }

    @Test
    fun refreshHandlesErrorGracefully() = runTest(testDispatcher) {
        val vm = UsageViewModel(
            requestsReader = { error("管理接口超时") }
        )

        vm.refresh(5)
        advanceUntilIdle()

        val state = vm.state.value
        assertFalse(state.loading)
        assertTrue(state.requests.isEmpty())
        assertTrue(state.error.contains("管理接口超时"))
    }

    @Test
    fun limitIsCoercedWithinBounds() = runTest(testDispatcher) {
        val seenLimit = AtomicInteger(0)
        val vm = UsageViewModel(
            requestsReader = { limit ->
                seenLimit.set(limit)
                emptyList()
            }
        )

        vm.refresh(0)
        advanceUntilIdle()
        assertEquals(1, seenLimit.get())

        vm.refresh(100)
        advanceUntilIdle()
        assertEquals(50, seenLimit.get())
    }

    @Test
    fun clearResetsState() = runTest(testDispatcher) {
        val vm = UsageViewModel(
            requestsReader = { listOf(sample1) }
        )

        vm.refresh(5)
        advanceUntilIdle()
        assertEquals(1, vm.state.value.requests.size)

        vm.clear()
        val cleared = vm.state.value
        assertFalse(cleared.loading)
        assertTrue(cleared.requests.isEmpty())
        assertEquals("", cleared.error)
    }

    @Test
    fun clearRejectsLateRequestSuccessEvenIfReaderIgnoresCancellation() = runTest(testDispatcher) {
        val response = CompletableDeferred<List<UsageRecord>>()
        val vm = UsageViewModel(requestsReader = { withContext(NonCancellable) { response.await() } })
        vm.refresh()
        assertTrue(vm.state.value.loading)

        vm.clear()
        response.complete(listOf(sample1))
        advanceUntilIdle()

        assertEquals(UsageUiState(), vm.state.value)
    }

    @Test
    fun clearRejectsLateRequestFailure() = runTest(testDispatcher) {
        val response = CompletableDeferred<Unit>()
        val vm = UsageViewModel(requestsReader = {
            withContext(NonCancellable) { response.await() }
            error("old connection failed")
        })
        vm.refresh()
        vm.clear()
        response.complete(Unit)
        advanceUntilIdle()

        assertEquals(UsageUiState(), vm.state.value)
    }

    @Test
    fun newestRefreshWinsWhenEarlierReaderFinishesLast() = runTest(testDispatcher) {
        val older = CompletableDeferred<List<UsageRecord>>()
        val newer = CompletableDeferred<List<UsageRecord>>()
        var reads = 0
        val vm = UsageViewModel(requestsReader = {
            val response = if (++reads == 1) older else newer
            withContext(NonCancellable) { response.await() }
        })
        vm.refresh()
        vm.refresh()
        newer.complete(listOf(sample2))
        advanceUntilIdle()
        assertEquals(listOf(sample2), vm.state.value.requests)

        older.complete(listOf(sample1))
        advanceUntilIdle()
        assertEquals(listOf(sample2), vm.state.value.requests)
        assertFalse(vm.state.value.loading)
    }

    @Test
    fun clearCancelsOldInsightsBeforeTheyCanMixWithReconnectedSession() = runTest(testDispatcher) {
        val older = CompletableDeferred<String>()
        var reads = 0
        val vm = UsageViewModel(
            insightsReader = {
                if (++reads == 1) withContext(NonCancellable) { older.await() } else "new-session"
            },
            summaryReader = { "new-summary" },
            speedsReader = { emptyMap() },
            catalogReader = { emptyList() },
            ioDispatcher = testDispatcher,
        )
        vm.loadInsights()
        assertTrue(vm.state.value.insightsLoading)
        vm.clear()
        vm.loadInsights()
        advanceUntilIdle()
        assertEquals("new-session", vm.state.value.heatmapJson)

        older.complete("old-session")
        advanceUntilIdle()
        assertEquals("new-session", vm.state.value.heatmapJson)
        assertEquals("new-summary", vm.state.value.summaryJson)
        assertEquals(4, reads) // Old session never proceeds to quotas or history.
        assertFalse(vm.state.value.insightsLoading)
        assertEquals("", vm.state.value.insightsError)
    }

    @Test
    fun reattachingSameCoreRetainsLoadedDataAndPendingInsightsAcrossActivityRecreation() = runTest(testDispatcher) {
        val heatmap = CompletableDeferred<String>()
        var reads = 0
        val vm = UsageViewModel(
            requestsReader = { listOf(sample1) },
            insightsReader = { if (++reads == 1) heatmap.await() else "quota-data" },
            summaryReader = { "summary" },
            speedsReader = { emptyMap() },
            catalogReader = { emptyList() },
            ioDispatcher = testDispatcher,
        )
        vm.attach(1L)
        vm.refresh()
        vm.loadInsights()
        val pending = vm.state.value
        assertTrue(pending.insightsLoading)
        assertEquals(listOf(sample1), pending.requests)

        vm.attach(1L) // Dashboard LaunchedEffect runs again after Activity recreation.
        assertEquals(pending, vm.state.value)
        heatmap.complete("heatmap")
        advanceUntilIdle()
        val loaded = vm.state.value
        assertEquals("heatmap", loaded.heatmapJson)
        assertEquals("summary", loaded.summaryJson)
        assertFalse(loaded.insightsLoading)
        vm.attach(1L)
        assertEquals(loaded, vm.state.value)

        vm.attach(2L) // Direct owner changes also invalidate the old session.
        assertEquals(UsageUiState(), vm.state.value)
        vm.refresh()
        vm.attach(null)
        assertEquals(UsageUiState(), vm.state.value)
    }

    @Test
    fun loadInsightsStoresRawJsonAndRecoversFromError() = runBlocking {
        val calls = AtomicInteger()
        val vm = UsageViewModel(
            requestsReader = { emptyList() },
            insightsReader = { endpoint ->
                calls.incrementAndGet()
                when (endpoint) {
                    cc.fplj.magpiemobile.core.UsageInsightEndpoint.HEATMAP -> """{"days":[1,2,3]}"""
                    cc.fplj.magpiemobile.core.UsageInsightEndpoint.QUOTAS -> """{"openai":{"remaining":100}}"""
                    else -> """{"history":[1,2]}"""
                }
            },
            summaryReader = { period ->
                assertEquals("today", period)
                """{"totals":{"requests":10,"tokens":320},"period":"today"}"""
            },
        )
        vm.loadInsights()
        withTimeout(10000) { vm.state.first { !it.insightsLoading } }
        assertEquals(3, calls.get())
        assertEquals("""{"days":[1,2,3]}""", vm.state.value.heatmapJson)
        assertEquals("""{"openai":{"remaining":100}}""", vm.state.value.quotasJson)
        assertEquals("""{"history":[1,2]}""", vm.state.value.historyJson)
        assertEquals("""{"totals":{"requests":10,"tokens":320},"period":"today"}""", vm.state.value.summaryJson)
        assertEquals("", vm.state.value.insightsError)

        val failing = UsageViewModel(
            requestsReader = { emptyList() },
            insightsReader = { throw IllegalStateException("HTTP 503") },
            summaryReader = { throw IllegalStateException("HTTP 503") },
        )
        failing.loadInsights()
        withTimeout(10000) { failing.state.first { !it.insightsLoading } }
        assertFalse(failing.state.value.insightsLoading)
        assertTrue(failing.state.value.insightsError.contains("503"))
        // 主请求状态不受洞察失败影响。
        assertTrue(failing.state.value.requests.isEmpty())
        assertEquals("", failing.state.value.error)
    }
}

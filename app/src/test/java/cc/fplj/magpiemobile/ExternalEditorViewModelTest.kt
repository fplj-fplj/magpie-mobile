package cc.fplj.magpiemobile

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
class ExternalEditorViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }

    @Test fun repeatedTapDoesNotMintOrLaunchTwiceAndReturnRefreshesOnce() = runTest(dispatcher) {
        val pending = CompletableDeferred<String>()
        var reads = 0
        var launches = 0
        val vm = ExternalEditorViewModel { reads++; pending.await() }
        vm.open { launches++ }
        vm.open { launches++ }
        assertEquals(1, reads)
        assertTrue(vm.state.value.opening)
        val ticket = "private-single-use-link"
        pending.complete(ticket)
        assertEquals(1, launches)
        assertFalse(vm.state.value.toString().contains(ticket))
        assertTrue(vm.returned())
        assertFalse(vm.returned())
        assertEquals(1, vm.state.value.returnRevision)
    }

    @Test fun missingBrowserReportsFailureWithoutKeepingTheLink() = runTest(dispatcher) {
        val ticket = "private-single-use-link"
        val vm = ExternalEditorViewModel { ticket }
        vm.open { throw IllegalStateException(ticket) }
        assertFalse(vm.awaitingReturn)
        assertFalse(vm.state.value.opening)
        assertTrue(vm.state.value.error.isNotBlank())
        assertFalse(vm.state.value.toString().contains(ticket))
    }
}

package cc.fplj.magpiemobile

import cc.fplj.magpiemobile.core.CallerKeyAction
import cc.fplj.magpiemobile.core.CallerKeyChange
import cc.fplj.magpiemobile.core.ChatRequestControl
import cc.fplj.magpiemobile.core.CoreManagementClient
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CallerKeyOperationControllerTest {
    @Test fun queuedReadCreateCopyAndUpdateCannotUseANewEpochOfTheSameOwner() = runBlocking {
        assertQueuedReplacement(NEXT_EPOCH)
    }

    @Test fun queuedReadCreateCopyAndUpdateCannotUseAReplacementOwner() = runBlocking {
        assertQueuedReplacement(NEXT_OWNER)
    }

    private suspend fun assertQueuedReplacement(replacement: ProviderSessionKey) {
        for (call in Call.entries) {
            CallerKeyHttpPeer().use { first ->
                CallerKeyHttpPeer().use { second ->
                    val source = LeaseSource(first, second)
                    val ui = StandardTestDispatcher()
                    val io = StandardTestDispatcher()
                    val scope = CoroutineScope(SupervisorJob() + ui)
                    val operation = CallerKeyOperationController(source::acquire, io).capture(SESSION)
                    assertEquals(1, source.leases.size) // Capture precedes even the UI job.
                    val completed = AtomicInteger()
                    val job = scope.launch {
                        try { call.execute(operation); completed.incrementAndGet() }
                        finally { operation.close() }
                    }
                    try {
                        ui.scheduler.runCurrent() // Actual client work is still queued on IO.
                        assertFalse(job.isCompleted)
                        assertTrue(first.requests.isEmpty())
                        source.changeSession(replacement, invalidate = false)
                        finish(job, ui, io)

                        assertTrue(job.isCancelled)
                        assertEquals(0, completed.get())
                        assertTrue(first.requests.isEmpty())
                        assertTrue(second.requests.isEmpty())
                        assertEquals(1, source.leases.size) // No replacement credential capture.
                        assertCancelled(source.leases.single().control)
                        operation.close()
                        assertEquals(1, source.leases.single().closeCount.get())
                        first.assertHealthy(); second.assertHealthy()
                    } finally {
                        scope.cancel()
                        operation.cancel()
                        operation.close()
                        finish(job, ui, io)
                    }
                }
            }
        }
    }

    @Test fun cancellationBeforeIoStartsCancelsCapturedControlWithoutSendingARequest() = runBlocking {
        CallerKeyHttpPeer().use { peer ->
            val source = LeaseSource(peer)
            val ui = StandardTestDispatcher()
            val io = StandardTestDispatcher()
            val scope = CoroutineScope(SupervisorJob() + ui)
            val operation = CallerKeyOperationController(source::acquire, io).capture(SESSION)
            val job = scope.launch {
                try { operation.create(CallerKeyHttpPeer.CREATED_NAME) }
                finally { operation.close() }
            }
            try {
                ui.scheduler.runCurrent()
                job.cancel()
                finish(job, ui, io)
                assertTrue(job.isCancelled)
                assertTrue(peer.requests.isEmpty())
                assertCancelled(source.leases.single().control)
                operation.close()
                assertEquals(1, source.leases.single().closeCount.get())
                peer.assertHealthy()
            } finally {
                scope.cancel()
                operation.cancel()
                operation.close()
                finish(job, ui, io)
            }
        }
    }

    @Test fun createCannotPostWhenItsPreflightGetReturnsAfterTheEpochChanged() = runBlocking {
        CallerKeyHttpPeer(holdList = true).use { first ->
            CallerKeyHttpPeer().use { second ->
                val source = LeaseSource(first, second)
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val operation = CallerKeyOperationController(source::acquire).capture(SESSION)
                val completed = AtomicInteger()
                val unexpected = AtomicReference<Throwable?>()
                val job = scope.launch {
                    try { operation.create(CallerKeyHttpPeer.CREATED_NAME); completed.incrementAndGet() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Throwable) { unexpected.set(failure) }
                    finally { operation.close() }
                }
                try {
                    assertTrue(first.listArrived.await(5, TimeUnit.SECONDS))
                    assertEquals(listOf("GET ${CallerKeyHttpPeer.LIST_PATH}"), first.requestLines())
                    source.changeSession(NEXT_EPOCH, invalidate = false)
                    // A valid GET response is now delivered. Per-request guards must prevent
                    // createCallerKey's internal next add-key POST, without a cancel signal.
                    first.releaseList.countDown()
                    withTimeout(2000) { job.join() }

                    assertTrue(job.isCancelled)
                    assertNull(unexpected.get())
                    assertEquals(0, completed.get())
                    assertEquals(listOf("GET ${CallerKeyHttpPeer.LIST_PATH}"), first.requestLines())
                    assertTrue(second.requests.isEmpty())
                    assertEquals(1, source.leases.size)
                    assertCancelled(source.leases.single().control)
                    assertEquals(1, source.leases.single().closeCount.get())
                    first.assertHealthy(); second.assertHealthy()
                } finally {
                    first.releaseList.countDown()
                    scope.cancel()
                    operation.cancel()
                    operation.close()
                    withTimeout(5000) { job.join() }
                }
            }
        }
    }

    @Test fun invalidationClosesSilentListAndCreatePostBeforeHeadersAndMidBody() = runBlocking {
        for (write in listOf(false, true)) for (headers in listOf(false, true)) {
            assertSilentCancellation(write, headers, invalidate = true)
        }
    }

    @Test fun coroutineCancellationClosesSilentListAndCreatePostBeforeHeadersAndMidBody() = runBlocking {
        for (write in listOf(false, true)) for (headers in listOf(false, true)) {
            assertSilentCancellation(write, headers, invalidate = false)
        }
    }

    private suspend fun assertSilentCancellation(write: Boolean, headers: Boolean, invalidate: Boolean) {
        val path = if (write) CallerKeyHttpPeer.ADD_PATH else CallerKeyHttpPeer.LIST_PATH
        CallerKeyHttpPeer(silentPath = path, sendHeaders = headers).use { first ->
            CallerKeyHttpPeer().use { second ->
                val source = LeaseSource(first, second)
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val operation = CallerKeyOperationController(source::acquire).capture(SESSION)
                val completed = AtomicInteger()
                val unexpected = AtomicReference<Throwable?>()
                val job = scope.launch {
                    try {
                        if (write) operation.create(CallerKeyHttpPeer.CREATED_NAME) else operation.keys()
                        completed.incrementAndGet()
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Throwable) { unexpected.set(failure) }
                    finally { operation.close() }
                }
                try {
                    assertTrue(first.silentArrived.await(5, TimeUnit.SECONDS))
                    if (invalidate) source.changeSession(NEXT_EPOCH) else job.cancel()
                    assertTrue("Cancellation must close the real socket", first.peerClosed.await(2, TimeUnit.SECONDS))
                    withTimeout(2000) { job.join() }

                    assertTrue(job.isCancelled)
                    assertNull(unexpected.get())
                    assertEquals(0, completed.get())
                    val expected = if (write) listOf("GET ${CallerKeyHttpPeer.LIST_PATH}", "POST $path")
                    else listOf("GET $path")
                    assertEquals(expected, first.requestLines()) // In particular, no POST replay.
                    assertTrue(first.requests.all { it.cookie == first.credential.cookieHeader() && !it.chunked })
                    assertTrue(second.requests.isEmpty())
                    operation.close()
                    assertEquals(1, source.leases.single().closeCount.get())
                    assertCancelled(source.leases.single().control)
                    first.assertHealthy(); second.assertHealthy()
                } finally {
                    scope.cancel()
                    operation.cancel()
                    operation.close()
                    withTimeout(5000) { job.join() }
                }
            }
        }
    }

    @Test fun successfulListCreateCopyAndUpdateUseOneCapturedCredentialAndNeverReplay() = runBlocking {
        CallerKeyHttpPeer().use { peer ->
            val source = LeaseSource(peer)
            val operation = CallerKeyOperationController(source::acquire).capture(SESSION)
            try {
                val listed = operation.keys()
                assertEquals(listOf(CallerKeyHttpPeer.EXISTING_ID), listed.map { it.id })
                assertTrue(listed.all { it.secret.isEmpty() })
                val created = operation.create(CallerKeyHttpPeer.CREATED_NAME)
                assertEquals(CallerKeyHttpPeer.CREATED_ID, created.id)
                assertEquals(CallerKeyHttpPeer.CREATED_SECRET, created.secret)
                val copied = operation.copy(CallerKeyHttpPeer.EXISTING_ID)
                assertEquals(CallerKeyHttpPeer.EXISTING_ID, copied.id)
                assertEquals(CallerKeyHttpPeer.COPIED_SECRET, copied.secret)
                val updated = operation.update(CallerKeyAction.MODELS, CallerKeyChange(
                    key = CallerKeyHttpPeer.EXISTING_ID, models = listOf(CallerKeyHttpPeer.MODEL_ID),
                ))
                assertTrue(updated.all { it.secret.isEmpty() })
                assertEquals(listOf(CallerKeyHttpPeer.MODEL_ID), updated.single { it.id == CallerKeyHttpPeer.EXISTING_ID }.models)
                source.leases.single().control.ensureActive() // Normal watcher disposal cannot cancel it.
                assertEquals(listOf(
                    "GET ${CallerKeyHttpPeer.LIST_PATH}",
                    "GET ${CallerKeyHttpPeer.LIST_PATH}",
                    "POST ${CallerKeyHttpPeer.ADD_PATH}",
                    "POST ${CallerKeyHttpPeer.COPY_PATH}",
                    "POST ${CallerKeyHttpPeer.MODELS_PATH}",
                ), peer.requestLines())
                assertTrue(peer.requests.all { it.cookie == peer.credential.cookieHeader() && !it.chunked })
                assertEquals(Json.parseToJsonElement("""{"name":"${CallerKeyHttpPeer.CREATED_NAME}"}"""),
                    Json.parseToJsonElement(peer.requests.single { it.line.endsWith(CallerKeyHttpPeer.ADD_PATH) }.body))
                assertEquals(Json.parseToJsonElement("""{"key":"${CallerKeyHttpPeer.EXISTING_ID}"}"""),
                    Json.parseToJsonElement(peer.requests.single { it.line.endsWith(CallerKeyHttpPeer.COPY_PATH) }.body))
                assertEquals(Json.parseToJsonElement("""{"key":"${CallerKeyHttpPeer.EXISTING_ID}","models":["${CallerKeyHttpPeer.MODEL_ID}"]}"""),
                    Json.parseToJsonElement(peer.requests.single { it.line.endsWith(CallerKeyHttpPeer.MODELS_PATH) }.body))
                operation.close()
                operation.close()
                source.invalidate()
                source.leases.single().control.ensureActive() // Released successfully, not invalidated.
                assertEquals(1, source.leases.size)
                assertEquals(1, source.leases.single().closeCount.get())
                peer.assertHealthy()
            } finally { operation.close() }
        }
    }

    private suspend fun finish(job: Job, vararg dispatchers: TestDispatcher) {
        withTimeout(5000) {
            while (!job.isCompleted) {
                dispatchers.forEach { it.scheduler.runCurrent() }
                yield()
            }
            job.join()
        }
    }

    private fun assertCancelled(control: ChatRequestControl) {
        assertThrows(CancellationException::class.java) { control.ensureActive() }
    }

    private enum class Call {
        LIST, CREATE, COPY, UPDATE;

        suspend fun execute(operation: CallerKeySessionOperation) {
            when (this) {
                LIST -> operation.keys()
                CREATE -> operation.create(CallerKeyHttpPeer.CREATED_NAME)
                COPY -> operation.copy(CallerKeyHttpPeer.EXISTING_ID)
                UPDATE -> operation.update(CallerKeyAction.MODELS, CallerKeyChange(
                    key = CallerKeyHttpPeer.EXISTING_ID, models = listOf(CallerKeyHttpPeer.MODEL_ID),
                ))
            }
        }
    }

    private class LeaseSource(private val first: CallerKeyHttpPeer, private val second: CallerKeyHttpPeer = first) {
        private val lock = Any()
        private val registry = ManagementRequestLeases()
        private var session = SESSION
        val leases = CopyOnWriteArrayList<RecordedLease>()

        fun acquire(expected: ProviderSessionKey): ManagementRequestLease = synchronized(lock) {
            if (session != expected) throw CancellationException("Synthetic management session changed")
            val peer = if (expected == SESSION) first else second
            RecordedLease(registry.acquire(peer.credential) { synchronized(lock) { session == expected } })
                .also { leases += it }
        }

        fun changeSession(next: ProviderSessionKey, invalidate: Boolean = true) = synchronized(lock) {
            session = next
            if (invalidate) registry.invalidate()
        }

        fun invalidate() = registry.invalidate()
    }

    private class RecordedLease(private val delegate: ManagementRequestLease) : ManagementRequestLease {
        override val client: CoreManagementClient get() = delegate.client
        override val control: ChatRequestControl get() = delegate.control
        val closeCount = AtomicInteger()
        override fun ensureCurrent() = delegate.ensureCurrent()
        override fun close() { closeCount.incrementAndGet(); delegate.close() }
    }

    companion object {
        private val SESSION = ProviderSessionKey(41, 101)
        private val NEXT_EPOCH = ProviderSessionKey(41, 102)
        private val NEXT_OWNER = ProviderSessionKey(42, 103)
    }
}

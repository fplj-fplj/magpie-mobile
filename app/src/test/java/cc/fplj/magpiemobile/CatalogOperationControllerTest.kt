package cc.fplj.magpiemobile

import cc.fplj.magpiemobile.core.ChatRequestControl
import cc.fplj.magpiemobile.core.GatewayConfig
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
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
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogOperationControllerTest {
    @Test fun queuedCatalogCannotReadAfterTheSameOwnerChangesEpoch() = runBlocking {
        assertQueuedReplacement(SESSION_A_NEXT)
    }

    @Test fun queuedCatalogCannotReadAfterTheOwnerChanges() = runBlocking {
        assertQueuedReplacement(SESSION_B)
    }

    private suspend fun assertQueuedReplacement(next: ProviderSessionKey) {
        CatalogTestPeer().use { first -> CatalogTestPeer().use { second ->
            val source = LeaseSource(first, second)
            val ui = StandardTestDispatcher()
            val io = StandardTestDispatcher()
            val scope = CoroutineScope(SupervisorJob() + ui)
            val operation = CatalogOperationController(source::acquire, io).capture(SESSION_A)
            val completed = AtomicInteger()
            val job = scope.launch {
                try { operation.read(); completed.incrementAndGet() } finally { operation.close() }
            }
            try {
                assertEquals(1, source.leases.size) // Acquired on caller, before dispatch.
                ui.scheduler.runCurrent()
                assertTrue(first.requests.isEmpty())
                source.changeSession(next, invalidate = false)
                finish(job, ui, io)
                assertTrue(job.isCancelled)
                assertEquals(0, completed.get())
                assertTrue(first.requests.isEmpty())
                assertTrue(second.requests.isEmpty())
                assertEquals(1, source.leases.size)
                assertReleasedOnce(source)
                assertCancelled(source.leases.single().control)
            } finally {
                scope.cancel(); operation.cancel(); operation.close()
                finish(job, ui, io)
            }
        } }
    }

    @Test fun cancellationBeforeTheIoBodyStartsClosesTheCapturedLeaseOnceAndSendsNothing() = runBlocking {
        CatalogTestPeer().use { peer ->
            val source = LeaseSource(peer)
            val ui = StandardTestDispatcher()
            val io = StandardTestDispatcher()
            val scope = CoroutineScope(SupervisorJob() + ui)
            val operation = CatalogOperationController(source::acquire, io).capture(SESSION_A)
            val job = scope.launch { try { operation.read() } finally { operation.close() } }
            try {
                ui.scheduler.runCurrent()
                job.cancel()
                finish(job, ui, io)
                operation.close()
                assertTrue(job.isCancelled)
                assertTrue(peer.requests.isEmpty())
                assertCancelled(source.leases.single().control)
                assertReleasedOnce(source)
            } finally {
                scope.cancel(); operation.cancel(); operation.close()
                finish(job, ui, io)
            }
        }
    }

    @Test fun closingBeforeTheIoBodyStartsRejectsTheQueuedReadAndIsIdempotent() = runBlocking {
        CatalogTestPeer().use { peer ->
            val source = LeaseSource(peer)
            val ui = StandardTestDispatcher()
            val io = StandardTestDispatcher()
            val scope = CoroutineScope(SupervisorJob() + ui)
            val operation = CatalogOperationController(source::acquire, io).capture(SESSION_A)
            val job = scope.launch { try { operation.read() } finally { operation.close() } }
            try {
                ui.scheduler.runCurrent()
                operation.close()
                operation.close()
                finish(job, ui, io)
                assertTrue(job.isCancelled)
                assertTrue(peer.requests.isEmpty())
                assertCancelled(source.leases.single().control)
                assertReleasedOnce(source)
            } finally {
                scope.cancel(); operation.cancel(); operation.close()
                finish(job, ui, io)
            }
        }
    }

    @Test fun invalidationClosesTheActualSilentSocketBeforeHeaders() = runBlocking {
        assertSilentCancellation(sendHeaders = false, invalidate = true)
    }

    @Test fun invalidationClosesTheActualSilentSocketDuringTheBody() = runBlocking {
        assertSilentCancellation(sendHeaders = true, invalidate = true)
    }

    @Test fun coroutineCancellationClosesTheActualSilentSocketBeforeHeaders() = runBlocking {
        assertSilentCancellation(sendHeaders = false, invalidate = false)
    }

    @Test fun coroutineCancellationClosesTheActualSilentSocketDuringTheBody() = runBlocking {
        assertSilentCancellation(sendHeaders = true, invalidate = false)
    }

    private suspend fun assertSilentCancellation(sendHeaders: Boolean, invalidate: Boolean) {
        CatalogTestPeer(silent = true, sendHeaders = sendHeaders).use { first -> CatalogTestPeer().use { second ->
            val source = LeaseSource(first, second)
            val operation = CatalogOperationController(source::acquire).capture(SESSION_A)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val completed = AtomicInteger()
            val failures = CopyOnWriteArrayList<Exception>()
            val job = scope.launch {
                try { operation.read(); completed.incrementAndGet() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { failures += failure }
                finally { operation.close() }
            }
            try {
                assertTrue(first.arrived.await(5, TimeUnit.SECONDS))
                if (invalidate) source.changeSession(SESSION_A_NEXT) else job.cancel()
                assertTrue("The actual silent socket must be closed", first.peerClosed.await(5, TimeUnit.SECONDS))
                withTimeout(5000) { job.join() }
                assertTrue(job.isCancelled)
                assertEquals(0, completed.get())
                assertTrue(failures.isEmpty())
                assertEquals(listOf("GET /v1/models"), first.requests.map { it.line })
                assertEquals(listOf("Bearer ${first.config.gatewayKey}"), first.requests.map { it.authorization })
                assertTrue(first.requests.all { it.cookie.isEmpty() })
                assertTrue(second.requests.isEmpty())
                assertNull(first.workerFailure.get())
                assertReleasedOnce(source)
            } finally {
                scope.cancel(); operation.cancel(); operation.close()
                first.close()
                withTimeout(5000) { job.join() }
            }
        } }
    }

    @Test fun aChangedEpochIsRejectedEvenWhenTheExactConfigAndBearerAreReused() = runBlocking {
        CatalogTestPeer().use { peer ->
            val source = LeaseSource(peer)
            val operation = CatalogOperationController(source::acquire).capture(SESSION_A)
            try {
                assertEquals(listOf("fixture/model"), operation.read().map { it.id })
                source.changeSession(SESSION_A_NEXT, invalidate = false)
                try { operation.read(); fail("Old epoch unexpectedly read again") }
                catch (_: CancellationException) { /* Identity, not credential equality, is decisive. */ }
                assertEquals(listOf("GET /v1/models"), peer.requests.map { it.line })
                assertEquals(1, source.leases.size)
            } finally { operation.close() }
            assertReleasedOnce(source)
        }
    }

    @Test fun aStaleFullKeyCannotAcquireReplacementConfiguration() {
        CatalogTestPeer().use { first -> CatalogTestPeer().use { second ->
            val source = LeaseSource(first, second)
            source.changeSession(SESSION_A_NEXT)
            assertThrows(CancellationException::class.java) { CatalogOperationController(source::acquire).capture(SESSION_A) }
            assertTrue(source.leases.isEmpty())
            assertTrue(first.requests.isEmpty())
            assertTrue(second.requests.isEmpty())
        } }
    }

    @Test fun successfulReadUnregistersWithoutCancelingSuccessfulOrUnrelatedControls() = runBlocking {
        CatalogTestPeer().use { peer ->
            val source = LeaseSource(peer)
            val operation = CatalogOperationController(source::acquire).capture(SESSION_A)
            try {
                assertEquals(listOf("fixture/model"), operation.read().map { it.id })
                val completed = source.leases.single().control
                val unrelated = source.acquire(SESSION_A)
                operation.close()
                operation.close()
                completed.ensureActive()
                unrelated.control.ensureActive()
                source.invalidate()
                completed.ensureActive()
                assertCancelled(unrelated.control)
                unrelated.close()
                assertReleasedOnce(source)
            } finally { operation.close() }
        }
    }

    private suspend fun finish(job: Job, vararg dispatchers: TestDispatcher) {
        withTimeout(5000) {
            while (!job.isCompleted) { dispatchers.forEach { it.scheduler.runCurrent() }; yield() }
            job.join()
        }
    }

    private fun assertReleasedOnce(source: LeaseSource) {
        assertTrue(source.leases.isNotEmpty())
        source.leases.forEach { assertEquals(1, it.closeCount.get()) }
    }

    private fun assertCancelled(control: ChatRequestControl) {
        assertThrows(CancellationException::class.java) { control.ensureActive() }
    }

    private class LeaseSource(private val first: CatalogTestPeer, private val second: CatalogTestPeer = first) {
        private val lock = Any()
        private val registry = GatewayRequestLeases()
        private var current = SESSION_A
        val leases = CopyOnWriteArrayList<RecordedLease>()
        fun acquire(expected: ProviderSessionKey): GatewayRequestLease = synchronized(lock) {
            if (current != expected) throw CancellationException("Catalog epoch changed")
            val config = if (expected == SESSION_A) first.config else second.config
            RecordedLease(registry.acquire(config) { synchronized(lock) { current == expected } }).also { leases += it }
        }
        fun changeSession(next: ProviderSessionKey, invalidate: Boolean = true) = synchronized(lock) {
            current = next
            if (invalidate) registry.invalidate()
        }
        fun invalidate() = registry.invalidate()
    }

    private class RecordedLease(private val lease: GatewayRequestLease) : GatewayRequestLease by lease {
        val closeCount = AtomicInteger()
        override fun close() { closeCount.incrementAndGet(); lease.close() }
    }

    private companion object {
        val SESSION_A = ProviderSessionKey(11, 1)
        val SESSION_A_NEXT = ProviderSessionKey(11, 2)
        val SESSION_B = ProviderSessionKey(12, 3)
    }
}

/** Shared only by catalog controller/VM tests. All credentials and payloads are synthetic. */
internal class CatalogTestPeer(private val silent: Boolean = false, private val sendHeaders: Boolean = false) : Closeable {
    private val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    val config = GatewayConfig(gatewayBaseUrl = "http://127.0.0.1:${listener.localPort}", gatewayKey = "catalog-synthetic-key")
    val requests = CopyOnWriteArrayList<CatalogRecordedRequest>()
    val arrived = CountDownLatch(1)
    val peerClosed = CountDownLatch(1)
    val workerFailure = AtomicReference<Throwable?>()
    private val worker = thread(isDaemon = true, name = "catalog-fixture") {
        try {
            while (!listener.isClosed) {
                val socket = listener.accept()
                sockets += socket
                try { socket.use { serve(it) } }
                catch (_: IOException) { /* Client cancellation or fixture shutdown. */ }
                finally { sockets -= socket }
            }
        } catch (failure: Throwable) { if (!listener.isClosed) workerFailure.set(failure) }
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = 15_000
        val input = socket.getInputStream()
        val line = readLine(input) ?: return
        var authorization = ""
        var cookie = ""
        while (true) {
            val header = readLine(input) ?: return
            if (header.isEmpty()) break
            when (header.substringBefore(':').lowercase()) {
                "authorization" -> authorization = header.substringAfter(':').trim()
                "cookie" -> cookie = header.substringAfter(':').trim()
            }
        }
        requests += CatalogRecordedRequest(line.substringBeforeLast(' '), authorization, cookie)
        val output = socket.getOutputStream()
        if (silent) {
            if (sendHeaders) {
                output.write("HTTP/1.1 200 OK\r\nContent-Length: 4096\r\nConnection: close\r\n\r\nx".toByteArray(Charsets.US_ASCII))
                output.flush()
            }
            arrived.countDown()
            try { if (input.read() == -1) peerClosed.countDown() } catch (_: SocketException) { peerClosed.countDown() }
            return
        }
        arrived.countDown()
        val body = "{\"data\":[{\"id\":\"fixture/model\",\"display_name\":\"Fixture\",\"owned_by\":\"fixture\"}]}".toByteArray(Charsets.UTF_8)
        output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
        output.write(body)
        output.flush()
    }

    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        while (true) {
            val next = input.read()
            if (next == -1) return null
            if (next == '\n'.code) return line.toString().removeSuffix("\r")
            check(line.length < 8192)
            line.append(next.toChar())
        }
    }

    override fun close() {
        listener.close()
        sockets.forEach { runCatching { it.close() } }
        worker.join(5000)
    }
}

internal data class CatalogRecordedRequest(val line: String, val authorization: String, val cookie: String) {
    override fun toString() = "CatalogRecordedRequest(line=$line,credentials=<redacted>)"
}

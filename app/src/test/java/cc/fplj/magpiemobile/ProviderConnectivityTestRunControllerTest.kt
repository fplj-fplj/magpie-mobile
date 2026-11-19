package cc.fplj.magpiemobile

import cc.fplj.magpiemobile.core.ChatRequestControl
import cc.fplj.magpiemobile.core.GatewayConfig
import cc.fplj.magpiemobile.core.ProviderCapabilityReport
import cc.fplj.magpiemobile.core.ProviderCapabilityStatus
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderConnectivityTestRunControllerTest {
    @Test fun successfulRunsReleaseTheirLeasesWithoutCancellingOrReusingControls() = runBlocking {
        CapabilityProbe().use { probe ->
            val source = LeaseSource(probe.config)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val controller = ProviderConnectivityTestRunController(scope, source::acquire)
            val reports = CopyOnWriteArrayList<ProviderCapabilityReport>()
            val failures = CopyOnWriteArrayList<Exception>()
            try {
                repeat(2) {
                    val job = controller.start(OWNER_A, "saved", "model",
                        onSuccess = reports::add, onFailure = failures::add)
                    withTimeout(5000) { job.join() }
                    assertFalse(job.isCancelled)
                }
                assertEquals(2, reports.size)
                assertTrue(failures.isEmpty())
                assertTrue(reports.all { report ->
                    report.results.size == 3 && report.results.all {
                        it.status == ProviderCapabilityStatus.PASSED
                    }
                })
                assertEquals(8, probe.requests.get()) // Live catalog + all three checks, twice.
                assertEquals(0, source.registeredCount())
                assertEquals(2, source.leases.size)
                assertTrue(source.leases[0].control !== source.leases[1].control)
                source.leases.forEach { lease ->
                    assertEquals(1, lease.closeCalls.get())
                    lease.control.ensureActive()
                }
                // A later dialog dispose must not cancel a completed run's control.
                controller.cancel()
                source.leases.forEach { it.control.ensureActive() }
            } finally {
                controller.cancel()
                scope.cancel()
            }
        }
    }

    @Test fun closeCancelsASilentCatalogConnectionAndNeverStartsChatChecks() = runBlocking {
        assertSilentRunStops(SilentAt.CATALOG_HEADERS, expectedRequests = 1) { controller, _, source ->
            controller.cancel()
            assertCancelled(source.leases.single().control)
        }
    }

    @Test fun disposingTheParentScopeClosesASilentBodyAndStopsLaterChecks() = runBlocking {
        assertSilentRunStops(SilentAt.PLAIN_BODY, expectedRequests = 2) { _, scope, _ ->
            scope.cancel()
        }
    }

    @Test fun ownerBecomingNullCancelsWithoutWaitingForComposition() = runBlocking {
        assertSilentRunStops(SilentAt.PLAIN_HEADERS, expectedRequests = 2) { _, _, source ->
            source.changeOwner(null)
            assertCancelled(source.leases.single().control)
        }
    }

    @Test fun ownerChangingToAnotherSessionStopsStreamingBeforeToolCheck() = runBlocking {
        assertSilentRunStops(SilentAt.STREAM_BODY, expectedRequests = 3) { _, _, source ->
            source.changeOwner(OWNER_B)
            assertCancelled(source.leases.single().control)
        }
    }

    @Test fun ownerChangingAfterSynchronousCaptureRejectsRunBeforeFirstRequest() = runBlocking {
        CapabilityProbe().use { probe ->
            val dispatcher = StandardTestDispatcher()
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val source = LeaseSource(probe.config)
            val controller = ProviderConnectivityTestRunController(scope, source::acquire, dispatcher)
            val reports = AtomicInteger()
            val failures = AtomicInteger()
            try {
                val job = controller.start(OWNER_A, "saved", "model",
                    onSuccess = { reports.incrementAndGet() },
                    onFailure = { failures.incrementAndGet() })
                // start() already owns a registered lease, although its Job has not been dispatched.
                assertEquals(1, source.registeredCount())
                source.changeOwner(OWNER_B)
                withTimeout(5000) {
                    while (!job.isCompleted) {
                        dispatcher.scheduler.runCurrent()
                        delay(1)
                    }
                }
                assertTrue(job.isCancelled)
                assertEquals(0, probe.requests.get())
                assertEquals(0, reports.get())
                assertEquals(0, failures.get())
                assertEquals(0, source.registeredCount())
                assertEquals(1, source.leases.single().closeCalls.get())
            } finally {
                controller.cancel()
                scope.cancel()
                dispatcher.scheduler.runCurrent()
            }
        }
    }

    @Test fun cancellingBeforeJobBodyStartsStillCancelsAndUnregistersLeaseExactlyOnce() = runBlocking {
        assertPrestartCleanup(cancelScopeBeforeStart = false)
    }

    @Test fun startingInsideAnAlreadyDisposedScopeStillReleasesCapturedLease() = runBlocking {
        assertPrestartCleanup(cancelScopeBeforeStart = true)
    }

    private suspend fun assertPrestartCleanup(cancelScopeBeforeStart: Boolean) {
        CapabilityProbe().use { probe ->
            val dispatcher = StandardTestDispatcher()
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val source = LeaseSource(probe.config)
            val controller = ProviderConnectivityTestRunController(scope, source::acquire, dispatcher)
            val callbacks = AtomicInteger()
            try {
                if (cancelScopeBeforeStart) scope.cancel()
                val job = controller.start(OWNER_A, "saved", "model",
                    onProgress = { callbacks.incrementAndGet() },
                    onSuccess = { callbacks.incrementAndGet() },
                    onFailure = { callbacks.incrementAndGet() })
                if (!cancelScopeBeforeStart) job.cancel()
                withTimeout(5000) {
                    while (!job.isCompleted) {
                        dispatcher.scheduler.runCurrent()
                        delay(1)
                    }
                }
                assertTrue(job.isCancelled)
                assertEquals(0, probe.requests.get())
                assertEquals(0, callbacks.get())
                assertEquals(0, source.registeredCount())
                val lease = source.leases.single()
                assertEquals(1, lease.closeCalls.get())
                assertCancelled(lease.control)
                controller.cancel()
                assertEquals(1, lease.closeCalls.get())
            } finally {
                controller.cancel()
                scope.cancel()
                dispatcher.scheduler.runCurrent()
            }
        }
    }

    private suspend fun assertSilentRunStops(
        silentAt: SilentAt,
        expectedRequests: Int,
        stop: (ProviderConnectivityTestRunController, CoroutineScope, LeaseSource) -> Unit,
    ) {
        CapabilityProbe(silentAt).use { probe ->
            val source = LeaseSource(probe.config)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val controller = ProviderConnectivityTestRunController(scope, source::acquire)
            val reports = AtomicInteger()
            val failures = AtomicInteger()
            var job: Job? = null
            try {
                val activeJob = controller.start(OWNER_A, "saved", "model",
                    onSuccess = { reports.incrementAndGet() },
                    onFailure = { failures.incrementAndGet() })
                job = activeJob
                assertTrue("Silent connection was not opened", probe.started.await(5, TimeUnit.SECONDS))
                assertEquals(expectedRequests, probe.requests.get())
                stop(controller, scope, source)
                assertTrue("Cancellation did not close the real connection",
                    probe.peerClosed.await(5, TimeUnit.SECONDS))
                withTimeout(5000) { activeJob.join() }
                assertTrue(activeJob.isCancelled)
                assertEquals(expectedRequests, probe.requests.get())
                assertEquals(0, reports.get())
                assertEquals(0, failures.get())
                assertEquals(0, source.registeredCount())
                val lease = source.leases.single()
                assertEquals(1, lease.closeCalls.get())
                assertCancelled(lease.control)
                assertTrue(probe.workerFailure.get()?.toString(), probe.workerFailure.get() == null)
            } finally {
                controller.cancel()
                scope.cancel()
                // Close the fixture before joining a failed regression's still-blocking request.
                probe.close()
                withTimeout(5000) { job?.join() }
            }
        }
    }

    private fun assertCancelled(control: ChatRequestControl) {
        var stopped = false
        try { control.ensureActive() } catch (_: CancellationException) { stopped = true }
        assertTrue("Run control remained active", stopped)
    }

    /** Injectable owner/config lease source; the production binding registry is tested separately. */
    private class LeaseSource(private val config: GatewayConfig) {
        private val lock = Any()
        private var currentOwner: Long? = OWNER_A
        private val registered = mutableSetOf<TestLease>()
        val leases = CopyOnWriteArrayList<TestLease>()

        fun acquire(owner: Long): GatewayRequestLease = synchronized(lock) {
            if (currentOwner != owner) throw CancellationException("Session changed")
            TestLease(config, owner).also { registered += it; leases += it }
        }

        fun changeOwner(owner: Long?) = synchronized(lock) {
            currentOwner = owner
            registered.forEach { it.control.cancel() }
        }

        fun registeredCount(): Int = synchronized(lock) { registered.size }

        inner class TestLease(
            override val config: GatewayConfig,
            private val owner: Long,
        ) : GatewayRequestLease {
            override val control = ChatRequestControl()
            val closeCalls = AtomicInteger()

            override fun ensureCurrent() = synchronized(lock) {
                if (this !in registered || currentOwner != owner) {
                    throw CancellationException("Session changed")
                }
                control.ensureActive()
            }

            override fun close() {
                closeCalls.incrementAndGet()
                synchronized(lock) { registered.remove(this) }
            }
        }
    }

    private enum class SilentAt { CATALOG_HEADERS, PLAIN_HEADERS, PLAIN_BODY, STREAM_BODY }

    /** Synthetic local gateway: no public network or provider credential is used. */
    private class CapabilityProbe(private val silentAt: SilentAt? = null) : AutoCloseable {
        private val server = ServerSocket(0)
        @Volatile private var accepted: Socket? = null
        val requests = AtomicInteger()
        val started = CountDownLatch(1)
        val peerClosed = CountDownLatch(1)
        val workerFailure = AtomicReference<Throwable?>()
        val config = GatewayConfig(
            gatewayKey = "connectivity-fixture-key",
            gatewayBaseUrl = "http://127.0.0.1:${server.localPort}",
            readTimeout = Duration.ofSeconds(30),
        )
        private val worker = thread(isDaemon = true, name = "provider-connectivity-fixture") {
            try {
                while (!server.isClosed) {
                    server.accept().use { client ->
                        accepted = client
                        client.soTimeout = 15000
                        val input = client.getInputStream().buffered()
                        val headers = StringBuilder()
                        while (!headers.endsWith("\r\n\r\n")) {
                            val next = input.read()
                            if (next < 0) return@use
                            headers.append(next.toChar())
                        }
                        val length = headers.lineSequence()
                            .firstOrNull { it.startsWith("Content-Length:", true) }
                            ?.substringAfter(':')?.trim()?.toInt() ?: 0
                        val bytes = ByteArray(length)
                        var offset = 0
                        while (offset < length) {
                            val count = input.read(bytes, offset, length - offset)
                            check(count > 0)
                            offset += count
                        }
                        requests.incrementAndGet()
                        val body = bytes.toString(Charsets.UTF_8)
                        val catalog = headers.startsWith("GET /v1/models ")
                        val streaming = body.contains("\"stream\":true")
                        val tool = body.contains("\"tools\"")
                        val plain = !catalog && !streaming && !tool
                        val silent = when (silentAt) {
                            SilentAt.CATALOG_HEADERS -> catalog
                            SilentAt.PLAIN_HEADERS, SilentAt.PLAIN_BODY -> plain
                            SilentAt.STREAM_BODY -> streaming
                            null -> false
                        }
                        val output = client.getOutputStream()
                        if (silent) {
                            if (silentAt == SilentAt.PLAIN_BODY || silentAt == SilentAt.STREAM_BODY) {
                                val type = if (streaming) "text/event-stream" else "application/json"
                                output.write(("HTTP/1.1 200 OK\r\nContent-Type: $type\r\n" +
                                    "Content-Length: 4096\r\nConnection: close\r\n\r\n")
                                    .toByteArray(Charsets.UTF_8))
                                output.flush()
                            }
                            started.countDown()
                            try {
                                if (input.read() < 0) peerClosed.countDown()
                            } catch (_: SocketException) {
                                peerClosed.countDown()
                            }
                        } else {
                            val response = when {
                                catalog -> """{"data":[{"id":"saved/model","owned_by":"saved"}]}"""
                                streaming -> "data: {\"choices\":[{\"delta\":{\"content\":\"stream ok\"}}]}\n\ndata: [DONE]\n\n"
                                tool -> """{"choices":[{"message":{"content":null,"tool_calls":[{"type":"function","function":{"name":"get_current_time","arguments":"{}"}}]}}]}"""
                                else -> """{"choices":[{"message":{"content":"plain ok","reasoning_content":"thought"}}]}"""
                            }.toByteArray(Charsets.UTF_8)
                            val type = if (streaming) "text/event-stream" else "application/json"
                            output.write(("HTTP/1.1 200 OK\r\nContent-Type: $type\r\n" +
                                "Content-Length: ${response.size}\r\nConnection: close\r\n\r\n")
                                .toByteArray(Charsets.UTF_8))
                            output.write(response)
                            output.flush()
                        }
                        accepted = null
                    }
                }
            } catch (error: Exception) {
                if (!server.isClosed) workerFailure.set(error)
            }
        }

        override fun close() {
            server.close()
            accepted?.close()
            worker.join(5000)
        }
    }

    private companion object {
        const val OWNER_A = 41L
        const val OWNER_B = 42L
    }
}

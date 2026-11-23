package cc.fplj.magpiemobile

import cc.fplj.magpiemobile.core.ChatRequestControl
import cc.fplj.magpiemobile.core.ManagerCredential
import cc.fplj.magpiemobile.core.PluginManagementClient
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
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PluginOperationControllerTest {
    @Test fun queuedEditCannotUseANewEpochOfTheSameOwner() = runBlocking {
        assertQueuedReplacement(SESSION_A_NEXT, startSignIn = false)
    }

    @Test fun queuedEditCannotUseAReplacementOwner() = runBlocking {
        assertQueuedReplacement(SESSION_B, startSignIn = false)
    }

    @Test fun queuedSignInCannotUseANewEpochOfTheSameOwner() = runBlocking {
        assertQueuedReplacement(SESSION_A_NEXT, startSignIn = true)
    }

    @Test fun queuedSignInCannotUseAReplacementOwner() = runBlocking {
        assertQueuedReplacement(SESSION_B, startSignIn = true)
    }

    private suspend fun assertQueuedReplacement(replacement: ProviderSessionKey, startSignIn: Boolean) {
        ManagementPeer().use { first ->
            ManagementPeer().use { second ->
                val source = LeaseSource(first, second)
                val ui = StandardTestDispatcher()
                val io = StandardTestDispatcher()
                val scope = CoroutineScope(SupervisorJob() + ui)
                val operation = PluginOperationController(source::acquire, io).capture(SESSION_A)
                // Capture is synchronous and happens before even the UI coroutine starts.
                assertEquals(1, source.leases.size)
                val completed = AtomicInteger()
                val job = scope.launch {
                    try {
                        if (startSignIn) operation.auth(start()) else operation.edit(PluginEdit.Add(SPEC))
                        completed.incrementAndGet()
                    } finally { operation.close() }
                }
                try {
                    ui.scheduler.runCurrent() // Queue the actual request on IO without running it.
                    assertFalse(job.isCompleted)
                    assertTrue(first.requests.isEmpty())
                    // Exercise the full identity guard even if the invalidation signal is delayed.
                    source.changeSession(replacement, invalidate = false)
                    finish(job, ui, io)

                    assertTrue(job.isCancelled)
                    assertEquals(0, completed.get())
                    assertTrue(first.requests.isEmpty())
                    assertTrue(second.requests.isEmpty())
                    assertEquals(1, source.leases.size) // Never reacquired replacement credentials.
                    assertCancelled(source.leases.single().control)
                    assertReleasedOnce(source)
                } finally {
                    scope.cancel()
                    operation.cancel()
                    operation.close()
                    first.close()
                    second.close()
                    finish(job, ui, io)
                }
            }
        }
    }

    @Test fun cancellingBeforeIoBodyStartsCancelsTheCapturedControlAndReleasesOnce() = runBlocking {
        ManagementPeer().use { peer ->
            val source = LeaseSource(peer)
            val ui = StandardTestDispatcher()
            val io = StandardTestDispatcher()
            val scope = CoroutineScope(SupervisorJob() + ui)
            val operation = PluginOperationController(source::acquire, io).capture(SESSION_A)
            val job = scope.launch {
                try { operation.edit(PluginEdit.Add(SPEC)) } finally { operation.close() }
            }
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
                scope.cancel()
                operation.cancel()
                operation.close()
                peer.close()
                finish(job, ui, io)
            }
        }
    }

    @Test fun closingBeforeIoBodyStartsRejectsTheQueuedRequestAndIsIdempotent() = runBlocking {
        ManagementPeer().use { peer ->
            val source = LeaseSource(peer)
            val ui = StandardTestDispatcher()
            val io = StandardTestDispatcher()
            val scope = CoroutineScope(SupervisorJob() + ui)
            val operation = PluginOperationController(source::acquire, io).capture(SESSION_A)
            val job = scope.launch {
                try { operation.auth(start()) } finally { operation.close() }
            }
            try {
                ui.scheduler.runCurrent()
                operation.close()
                operation.close()
                finish(job, ui, io)

                assertTrue(job.isCancelled)
                assertTrue(peer.requests.isEmpty())
                assertReleasedOnce(source)
            } finally {
                scope.cancel()
                operation.cancel()
                operation.close()
                peer.close()
                finish(job, ui, io)
            }
        }
    }

    @Test fun invalidationClosesSilentReadBeforeHeadersAndDuringBody() = runBlocking {
        for (sendHeaders in listOf(false, true)) {
            assertSilentCancellation(sendHeaders, write = false, invalidate = true)
        }
    }

    @Test fun invalidationClosesSilentWriteBeforeHeadersAndDuringBodyWithoutReplay() = runBlocking {
        for (sendHeaders in listOf(false, true)) {
            assertSilentCancellation(sendHeaders, write = true, invalidate = true)
        }
    }

    @Test fun coroutineCancellationClosesSilentReadBeforeHeadersAndDuringBody() = runBlocking {
        for (sendHeaders in listOf(false, true)) {
            assertSilentCancellation(sendHeaders, write = false, invalidate = false)
        }
    }

    @Test fun coroutineCancellationClosesSilentWriteBeforeHeadersAndDuringBodyWithoutReplay() = runBlocking {
        for (sendHeaders in listOf(false, true)) {
            assertSilentCancellation(sendHeaders, write = true, invalidate = false)
        }
    }

    private suspend fun assertSilentCancellation(sendHeaders: Boolean, write: Boolean, invalidate: Boolean) {
        val path = if (write) "/api/plugins/add" else "/api/plugins"
        ManagementPeer(silentPath = path, sendHeaders = sendHeaders).use { first ->
            ManagementPeer().use { second ->
                val source = LeaseSource(first, second)
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val operation = PluginOperationController(source::acquire).capture(SESSION_A)
                val completed = AtomicInteger()
                val failures = CopyOnWriteArrayList<Exception>()
                val job = scope.launch {
                    try {
                        if (write) operation.edit(PluginEdit.Add(SPEC)) else operation.plugins()
                        completed.incrementAndGet()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        failures += failure
                    } finally { operation.close() }
                }
                try {
                    assertTrue(first.silentArrived.await(5, TimeUnit.SECONDS))
                    if (invalidate) source.changeSession(SESSION_A_NEXT) else job.cancel()
                    assertTrue("Cancellation must close the actual silent socket", first.peerClosed.await(5, TimeUnit.SECONDS))
                    withTimeout(5000) { job.join() }

                    assertTrue(job.isCancelled)
                    assertEquals(0, completed.get())
                    assertTrue(failures.isEmpty())
                    assertEquals(listOf("${if (write) "POST" else "GET"} $path"), first.requestLines())
                    assertTrue(first.requests.all { it.managerHeader == first.credential.managerHeaderValue() })
                    assertTrue(second.requests.isEmpty())
                    assertTrue(first.workerFailure.get()?.toString(), first.workerFailure.get() == null)
                    operation.close()
                    assertReleasedOnce(source)
                } finally {
                    scope.cancel()
                    operation.cancel()
                    operation.close()
                    first.close()
                    withTimeout(5000) { job.join() }
                }
            }
        }
    }

    @Test fun aReadCannotAuthorizeAnEditAfterTheSameOwnerChangesEpoch() = runBlocking {
        assertSequentialReplacement(startSignIn = false)
    }

    @Test fun aSignInCannotAuthorizeACancelPostAfterTheSameOwnerChangesEpoch() = runBlocking {
        assertSequentialReplacement(startSignIn = true)
    }

    private suspend fun assertSequentialReplacement(startSignIn: Boolean) {
        ManagementPeer().use { first ->
            ManagementPeer().use { second ->
                val source = LeaseSource(first, second)
                val ui = StandardTestDispatcher()
                val io = StandardTestDispatcher()
                val scope = CoroutineScope(SupervisorJob() + ui)
                val operation = PluginOperationController(source::acquire, io).capture(SESSION_A)
                val firstComplete = CompletableDeferred<Unit>()
                val next = CompletableDeferred<Unit>()
                val job = scope.launch {
                    try {
                        if (startSignIn) operation.auth(start()) else operation.plugins()
                        firstComplete.complete(Unit)
                        next.await()
                        if (startSignIn) operation.auth(PluginAuthStep.Cancel(SIGNIN_ID))
                        else operation.edit(PluginEdit.Add(SPEC))
                    } finally { operation.close() }
                }
                try {
                    withTimeout(5000) {
                        while (!firstComplete.isCompleted) {
                            ui.scheduler.runCurrent()
                            io.scheduler.runCurrent()
                            yield()
                        }
                    }
                    source.leases.single().control.ensureActive()
                    source.changeSession(SESSION_A_NEXT, invalidate = false)
                    next.complete(Unit)
                    finish(job, ui, io)

                    assertTrue(job.isCancelled)
                    assertEquals(listOf(if (startSignIn) "POST /api/plugin-signin" else "GET /api/plugins"), first.requestLines())
                    assertTrue(second.requests.isEmpty())
                    assertEquals(1, source.leases.size)
                    assertReleasedOnce(source)
                } finally {
                    next.complete(Unit)
                    scope.cancel()
                    operation.cancel()
                    operation.close()
                    first.close()
                    second.close()
                    finish(job, ui, io)
                }
            }
        }
    }

    @Test fun everyOperationUsesTheCapturedClientAndSuccessfulCallsLeaveItsControlReusable() = runBlocking {
        ManagementPeer().use { peer ->
            val source = LeaseSource(peer)
            val operation = PluginOperationController(source::acquire).capture(SESSION_A)
            try {
                assertTrue(operation.plugins().bun)
                assertTrue(operation.providers().isEmpty())
                operation.market()
                operation.market("fixture")
                val edits = listOf(
                    PluginEdit.Add(SPEC), PluginEdit.Remove(SPEC), PluginEdit.Upgrade(SPEC),
                    PluginEdit.Off(SPEC, true), PluginEdit.Options(SPEC, JsonObject(emptyMap())),
                    PluginEdit.Prefer(SPEC, "fixture-provider"), PluginEdit.UpdateAll,
                    PluginEdit.RestoreGatewayAccess,
                )
                for (edit in edits) assertTrue(operation.edit(edit).bun)
                assertTrue(operation.auth(PluginAuthStep.Prompt("fixture-provider", 0, emptyMap())).prompt != null)
                assertEquals(SIGNIN_ID, operation.auth(start()).state?.id)
                assertEquals(SIGNIN_ID, operation.auth(PluginAuthStep.Read(SIGNIN_ID)).state?.id)
                operation.auth(PluginAuthStep.Callback(SIGNIN_ID, "http://127.0.0.1/callback?code=fixture"))
                operation.auth(PluginAuthStep.Cancel(SIGNIN_ID))
                source.leases.single().control.ensureActive()
                operation.close()
                operation.close()
                source.invalidate()
                source.leases.single().control.ensureActive() // Closed leases were unregistered.

                assertEquals(listOf(
                    "GET /api/plugins", "GET /api/providers", "GET /api/plugins/market",
                    "GET /api/plugins", "GET /api/plugins/search?q=fixture",
                    "POST /api/plugins/add", "POST /api/plugins/remove", "POST /api/plugins/upgrade",
                    "POST /api/plugins/off", "POST /api/plugins/options", "POST /api/plugins/prefer",
                    "POST /api/plugins/update", "POST $RESTORE_PATH", "GET /api/plugins",
                    "POST /api/plugin-signin/prompt", "POST /api/plugin-signin", "GET /api/signin/$SIGNIN_ID",
                    "POST /api/signin/$SIGNIN_ID/callback", "POST /api/signin/$SIGNIN_ID/cancel",
                ), peer.requestLines())
                assertTrue(peer.requests.all { it.managerHeader == peer.credential.managerHeaderValue() })
                assertEquals(1, source.leases.size)
                assertReleasedOnce(source)
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

    private fun assertReleasedOnce(source: LeaseSource) {
        assertTrue(source.leases.isNotEmpty())
        source.leases.forEach { assertEquals(1, it.closeCount.get()) }
    }

    private fun assertCancelled(control: ChatRequestControl) {
        try {
            control.ensureActive()
            throw AssertionError("Request control stayed active")
        } catch (_: CancellationException) { /* Expected stop reason. */ }
    }

    private class LeaseSource(private val first: ManagementPeer, private val second: ManagementPeer = first) {
        private val lock = Any()
        private val registry = PluginRequestLeases()
        private var session = SESSION_A
        val leases = CopyOnWriteArrayList<RecordedLease>()

        fun acquire(expected: ProviderSessionKey): PluginRequestLease = synchronized(lock) {
            if (session != expected) throw CancellationException("Plugin session changed")
            val peer = if (expected == SESSION_A) first else second
            RecordedLease(registry.acquire(peer.credential) { synchronized(lock) { session == expected } })
                .also { leases += it }
        }

        fun changeSession(next: ProviderSessionKey, invalidate: Boolean = true) = synchronized(lock) {
            session = next
            if (invalidate) registry.invalidate()
        }

        fun invalidate() = registry.invalidate()
    }

    private class RecordedLease(private val lease: PluginRequestLease) : PluginRequestLease {
        override val client: PluginManagementClient get() = lease.client
        override val control: ChatRequestControl get() = lease.control
        val closeCount = AtomicInteger()
        override fun ensureCurrent() = lease.ensureCurrent()
        override fun close() {
            closeCount.incrementAndGet()
            lease.close()
        }
    }

    private class ManagementPeer(
        private val silentPath: String? = null,
        private val sendHeaders: Boolean = false,
    ) : Closeable {
        private val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val sockets = ConcurrentHashMap.newKeySet<Socket>()
        val credential = ManagerCredential.fromKey("plugin-operation-synthetic-key", listener.localPort)
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        val silentArrived = CountDownLatch(1)
        val peerClosed = CountDownLatch(1)
        val workerFailure = AtomicReference<Throwable?>()
        private val worker = thread(isDaemon = true, name = "plugin-operation-fixture") {
            try {
                while (!listener.isClosed) {
                    val socket = listener.accept()
                    sockets += socket
                    try {
                        socket.use { serve(it) }
                    } catch (_: IOException) {
                        // Fixture shutdown or the cancelled client can close a socket at any time.
                    } finally { sockets -= socket }
                }
            } catch (failure: Throwable) {
                if (!listener.isClosed) workerFailure.set(failure)
            }
        }

        fun requestLines(): List<String> = requests.map { it.line }

        private fun serve(socket: Socket) {
            socket.soTimeout = 15_000
            val input = socket.getInputStream()
            val request = readLine(input) ?: return
            var length = 0
            var managerHeader = ""
            while (true) {
                val header = readLine(input) ?: return
                if (header.isEmpty()) break
                val name = header.substringBefore(':')
                val value = header.substringAfter(':').trim()
                when {
                    name.equals("Content-Length", true) -> length = value.toInt()
                    name.equals("X-Magpie-Android-Manager", true) -> managerHeader = value
                }
            }
            val body = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val count = input.read(body, offset, length - offset)
                check(count > 0)
                offset += count
            }
            val path = request.split(' ')[1]
            requests += RecordedRequest(request.substringBeforeLast(' '), managerHeader)
            val output = socket.getOutputStream()
            if (path == silentPath) {
                if (sendHeaders) {
                    output.write(("HTTP/1.1 200 OK\r\nContent-Length: 4096\r\nConnection: close\r\n\r\nx")
                        .toByteArray(Charsets.US_ASCII))
                    output.flush()
                }
                silentArrived.countDown()
                try { if (input.read() == -1) peerClosed.countDown() } catch (_: SocketException) { peerClosed.countDown() }
                return
            }
            val response = when (path) {
                RESTORE_PATH -> "{\"ok\":true}"
                "/api/plugins/market" -> "{\"listings\":[],\"state\":$STATE}"
                "/api/plugins/search?q=fixture" -> "{\"hits\":[]}"
                "/api/providers" -> "{\"plugins\":[]}"
                "/api/plugin-signin/prompt" -> "{\"prompt\":null,\"inputs\":{}}"
                "/api/plugin-signin", "/api/signin/$SIGNIN_ID" -> "{\"id\":\"$SIGNIN_ID\",\"state\":\"waiting\"}"
                "/api/signin/$SIGNIN_ID/callback", "/api/signin/$SIGNIN_ID/cancel" -> "{}"
                else -> STATE
            }.toByteArray(Charsets.UTF_8)
            output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                "Content-Length: ${response.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
            output.write(response)
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

    private data class RecordedRequest(val line: String, val managerHeader: String) {
        override fun toString() = "RecordedRequest(line=$line,credentials=<redacted>)"
    }

    private companion object {
        val SESSION_A = ProviderSessionKey(11L, 1L)
        val SESSION_A_NEXT = ProviderSessionKey(11L, 2L)
        val SESSION_B = ProviderSessionKey(12L, 3L)
        const val SPEC = "@fixture/plugin"
        const val SIGNIN_ID = "fixture-signin"
        const val RESTORE_PATH = "/api/androidhost/plugin-gateway-access/restore"
        const val STATE = "{\"plugins\":[],\"bun\":true,\"bunVersion\":\"fixture\"}"
        fun start() = PluginAuthStep.Start("fixture-provider", 0, emptyMap(), "")
    }
}

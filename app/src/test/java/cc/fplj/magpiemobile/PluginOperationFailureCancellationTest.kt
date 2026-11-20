package cc.fplj.magpiemobile

import cc.fplj.magpiemobile.core.ChatRequestControl
import cc.fplj.magpiemobile.core.ManagementHttpException
import cc.fplj.magpiemobile.core.ManagerCredential
import cc.fplj.magpiemobile.core.PluginWriteUnknownException
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real controller, cancellation bridge and bounded authenticated HTTP; no fake operation result. */
class PluginOperationFailureCancellationTest {
    @Test fun http400LeavesTheCapturedLeaseUsableForReadAndAnExplicitSecondSave() = runBlocking {
        FailurePeer(Reply.REJECT, Reply.ACCEPT).use { peer ->
            val registry = PluginRequestLeases()
            val lease = registry.acquire(peer.credential) { true }
            val operation = PluginOperationController(acquire = { lease }).capture(SESSION)
            try {
                val failure = failedSave(operation, B)
                assertTrue("A definite HTTP rejection must stay definite", failure is ManagementHttpException)
                assertEquals(400, (failure as ManagementHttpException).httpCode)
                assertNull(failure.cause)
                operation.ensureCurrent()
                lease.control.ensureActive()
                assertEquals(A, operation.plugins().entries.single().options.toString())
                assertEquals(listOf(B), peer.optionsWrites())

                // This second POST is an explicit caller action, never an automatic retry.
                assertEquals(C, operation.edit(options(C)).entries.single().options.toString())
                operation.ensureCurrent()
                lease.control.ensureActive()
                assertEquals(listOf("POST /api/plugins/options", "GET /api/plugins", "POST /api/plugins/options"), peer.requests)
                assertEquals(listOf(B, C), peer.optionsWrites())
                peer.assertHealthy()
            } finally {
                operation.close()
                registry.invalidate()
            }
        }
    }

    @Test fun droppedPostRemainsUnknownWithoutCancellingTheLeaseOrReplayingIt() = runBlocking {
        FailurePeer(Reply.DROP).use { peer ->
            val registry = PluginRequestLeases()
            val lease = registry.acquire(peer.credential) { true }
            val operation = PluginOperationController(acquire = { lease }).capture(SESSION)
            try {
                val failure = failedSave(operation, B)
                assertTrue("A missing POST response must remain unknown", failure is PluginWriteUnknownException)
                // Coroutine stacktrace recovery can wrap a copy around the original safe failure.
                // Every cause must still be the same sanitized category, with no raw I/O attached.
                val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
                var current: Throwable? = failure
                while (current != null) {
                    val diagnostic = current
                    assertTrue("Unknown-write exception chain must be acyclic", seen.add(diagnostic))
                    assertTrue("Unknown-write causes must keep their sanitized category",
                        diagnostic.javaClass == PluginWriteUnknownException::class.java)
                    assertTrue("Unknown-write diagnostics must contain only the fixed safe message",
                        diagnostic.message == "插件写入结果未知；不得重放，请重新只读核对")
                    assertTrue("Unknown-write diagnostics must not retain suppressed transport failures", diagnostic.suppressed.isEmpty())
                    current = diagnostic.cause
                }
                operation.ensureCurrent()
                lease.control.ensureActive()
                assertEquals(A, operation.plugins().entries.single().options.toString())
                assertEquals(listOf("POST /api/plugins/options", "GET /api/plugins"), peer.requests)
                assertEquals(listOf(B), peer.optionsWrites())
                peer.assertHealthy()
            } finally {
                operation.close()
                registry.invalidate()
            }
        }
    }

    @Test fun externalJobCancellationClosesTheActualPostSocketBeforeHeadersAndDuringBody() = runBlocking {
        for (reply in listOf(Reply.SILENT_HEADERS, Reply.SILENT_BODY)) {
            FailurePeer(reply).use { peer ->
                val registry = PluginRequestLeases()
                val lease = registry.acquire(peer.credential) { true }
                val operation = PluginOperationController(acquire = { lease }).capture(SESSION)
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val failures = CopyOnWriteArrayList<Exception>()
                val job = scope.launch {
                    try {
                        operation.edit(options(B))
                        throw AssertionError("A cancelled silent POST must not return a snapshot")
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        failures.add(failure)
                    } finally {
                        operation.close()
                    }
                }
                try {
                    assertTrue("The actual POST did not reach the peer", peer.arrived.await(5, TimeUnit.SECONDS))
                    job.cancel()
                    assertTrue("External cancellation must close the actual socket", peer.disconnected.await(5, TimeUnit.SECONDS))
                    withTimeout(5000) { job.join() }
                    assertTrue(job.isCancelled)
                    assertTrue("Cancellation must not become an HTTP or unknown-write failure", failures.isEmpty())
                    assertCancelled(lease.control)
                    assertEquals(listOf("POST /api/plugins/options"), peer.requests)
                    assertEquals(listOf(B), peer.optionsWrites())
                    peer.assertHealthy()
                } finally {
                    scope.cancel()
                    operation.cancel()
                    peer.close()
                    withTimeout(5000) { job.join() }
                    operation.close()
                    registry.invalidate()
                }
            }
        }
    }

    private suspend fun failedSave(operation: PluginSessionOperation, value: String): Exception {
        try {
            operation.edit(options(value))
        } catch (failure: Exception) {
            return failure
        }
        throw AssertionError("The fixture response must fail the save")
    }

    private fun assertCancelled(control: ChatRequestControl) {
        try {
            control.ensureActive()
        } catch (_: CancellationException) {
            return
        }
        throw AssertionError("Externally cancelled control stayed active")
    }

    private enum class Reply { REJECT, ACCEPT, DROP, SILENT_HEADERS, SILENT_BODY }

    private class FailurePeer(vararg replies: Reply) : Closeable {
        private val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val sockets = CopyOnWriteArrayList<Socket>()
        private val writeReplies = ConcurrentLinkedQueue(replies.toList())
        private val canonical = AtomicReference(A)
        private val failure = AtomicReference<Throwable?>()
        private val writes = CopyOnWriteArrayList<String>()
        val requests = CopyOnWriteArrayList<String>()
        val arrived = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val credential = ManagerCredential.fromKey("plugin-failure-synthetic-manager", listener.localPort)
        private val worker = thread(isDaemon = true, name = "plugin-failure-http-fixture") {
            try {
                while (!listener.isClosed) {
                    val socket = listener.accept()
                    sockets.add(socket)
                    try {
                        socket.use { serve(it) }
                    } finally {
                        sockets.remove(socket)
                    }
                }
            } catch (error: Throwable) {
                if (!listener.isClosed) failure.compareAndSet(null, error)
            }
        }

        fun optionsWrites(): List<String> = writes.map { Json.parseToJsonElement(it).jsonObject["options"].toString() }
        fun assertHealthy() { assertNull("Unexpected fixture request or failure", failure.get()) }

        private fun serve(socket: Socket) {
            socket.soTimeout = 15_000
            val input = socket.getInputStream()
            val request = line(input).split(' ')
            check(request.size == 3)
            val headers = mutableMapOf<String, String>()
            while (true) {
                val header = line(input)
                if (header.isEmpty()) break
                check(headers.size < 40)
                headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim()
            }
            check(headers["cookie"] == credential.cookieHeader())
            check(headers["authorization"] == null && headers["origin"] == null && headers["transfer-encoding"] == null)
            val length = headers["content-length"]?.toInt() ?: 0
            check(length in 0..524_288)
            val bytes = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val count = input.read(bytes, offset, bytes.size - offset)
                check(count > 0)
                offset += count
            }
            val route = request[0] + " " + request[1]
            requests.add(route)
            when (route) {
                "GET /api/plugins" -> respond(socket, 200, snapshot())
                "POST /api/plugins/options" -> {
                    val body = bytes.toString(Charsets.UTF_8)
                    val parsed = Json.parseToJsonElement(body).jsonObject
                    check(parsed["spec"]?.jsonPrimitive?.content == SPEC)
                    writes.add(body)
                    when (checkNotNull(writeReplies.poll()) { "Options POST was not explicitly requested by the test" }) {
                        Reply.REJECT -> respond(socket, 400, "{}")
                        Reply.ACCEPT -> {
                            canonical.set(parsed["options"].toString())
                            respond(socket, 200, snapshot())
                        }
                        Reply.DROP -> Unit
                        Reply.SILENT_HEADERS -> awaitDisconnect(input)
                        Reply.SILENT_BODY -> {
                            socket.getOutputStream().apply {
                                write("HTTP/1.1 200 OK\r\nContent-Length: 4096\r\nConnection: close\r\n\r\nx".toByteArray(Charsets.US_ASCII))
                                flush()
                            }
                            awaitDisconnect(input)
                        }
                    }
                }
                else -> error("Unexpected fixture route")
            }
        }

        private fun awaitDisconnect(input: InputStream) {
            arrived.countDown()
            try {
                check(input.read() == -1)
                disconnected.countDown()
            } catch (_: SocketException) {
                disconnected.countDown()
            }
        }

        private fun snapshot() = "{\"bun\":true,\"plugins\":[{\"spec\":\"$SPEC\",\"options\":${canonical.get()}}]}"

        private fun respond(socket: Socket, status: Int, body: String) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            val reason = if (status == 200) "OK" else "Bad Request"
            socket.getOutputStream().apply {
                write(("HTTP/1.1 $status $reason\r\nContent-Type: application/json\r\n" +
                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII) + bytes)
                flush()
            }
        }

        private fun line(input: InputStream): String {
            val result = StringBuilder()
            while (result.length < 16_384) {
                val next = input.read()
                check(next >= 0)
                if (next == 10) return result.toString().removeSuffix("\r")
                result.append(next.toChar())
            }
            error("Fixture request header exceeded limit")
        }

        override fun close() {
            listener.close()
            sockets.forEach { runCatching { it.close() } }
            worker.join(5000)
        }
    }

    private companion object {
        const val SPEC = "@fixture/options-cancellation"
        const val A = "{\"value\":\"a\"}"
        const val B = "{\"value\":\"b\"}"
        const val C = "{\"value\":\"c\"}"
        val SESSION = ProviderSessionKey(61, 2)
        fun options(value: String) = PluginEdit.Options(SPEC, Json.parseToJsonElement(value).jsonObject)
    }
}

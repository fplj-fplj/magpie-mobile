package cc.fplj.magpiemobile.core

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Cancellation is complete only when a real silent management peer observes EOF or reset. */
class PluginRequestCancellationTest {
    @Test fun cancelReadsBeforeHeadersClosesSocketAndSendsNoFollowup() =
        assertCancelled(Operation.entries.filter { !it.write }, false)

    @Test fun cancelReadsDuringBodyClosesSocketAndSendsNoFollowup() =
        assertCancelled(Operation.entries.filter { !it.write }, true)

    @Test fun cancelWritesBeforeHeadersClosesSocketWithoutReplay() =
        assertCancelled(Operation.entries.filter { it.write }, false)

    @Test fun cancelWritesDuringBodyClosesSocketWithoutReplay() =
        assertCancelled(Operation.entries.filter { it.write }, true)

    @Test fun precancelledClientRejectsEveryPublicOperationBeforeAnyHttp() {
        Peer("/api/plugins").use { peer ->
            val client = peer.client(ChatRequestControl().apply { cancel() })
            Operation.entries.forEach { operation ->
                assertFailsWith<CancellationException>(operation.name) { operation.perform(client) }
            }
            // Cancellation also precedes input validation, including methods which build JSON.
            invalidOperations(client).forEach { operation -> assertFailsWith<CancellationException> { operation() } }
            assertTrue(peer.requests.isEmpty())
        }
    }

    @Test fun staleOwnerRejectsEveryPublicOperationBeforeAnyHttp() {
        Peer("/api/plugins").use { peer ->
            val stale = CancellationException("Session replaced")
            val client = peer.client(ensureActive = { throw stale })
            Operation.entries.forEach { operation ->
                val error = assertFailsWith<CancellationException>(operation.name) { operation.perform(client) }
                assertTrue(error === stale)
            }
            assertTrue(peer.requests.isEmpty())
        }
    }

    @Test fun sessionReplacementAfterInstalledGetPreventsSearchGet() {
        Peer().use { peer ->
            val client = peer.client(ensureActive = {
                if (peer.requests.contains("GET /api/plugins")) throw CancellationException("Session replaced")
            })
            assertFailsWith<CancellationException> { client.market("multi") }
            assertEquals(listOf("GET /api/plugins"), peer.requests.toList())
        }
    }

    @Test fun sessionReplacementAfterRestorePostPreventsStateGetAndRemainsCancellation() {
        Peer().use { peer ->
            val client = peer.client(ensureActive = {
                if (peer.requests.contains("POST $RESTORE_PATH")) throw CancellationException("Session replaced")
            })
            val error = assertFailsWith<CancellationException> { client.restoreGatewayAccess() }
            assertNull(error.cause)
            assertEquals(listOf("POST $RESTORE_PATH"), peer.requests.toList())
        }
    }

    @Test fun failedWriteStatusDoesNotDrainSilentBodyOrReplayPost() {
        for (status in listOf(401, 408, 503)) {
            Peer("/api/plugins/add", sendHeaders = true, status = status).use { peer ->
                val error = assertFailsWith<ManagementHttpException> { peer.client().add(SPEC) }
                assertEquals(status, error.httpCode)
                assertNull(error.cause)
                assertTrue(peer.closed.await(2, TimeUnit.SECONDS))
                assertEquals(listOf("POST /api/plugins/add"), peer.requests.toList())
            }
        }
    }

    @Test fun droppedWriteReplyIsUnknownWithoutReplayAndReadDropIsReadFailure() {
        Peer("/api/plugins/add", dropReply = true).use { peer ->
            val error = assertFailsWith<PluginWriteUnknownException> { peer.client().add(SPEC) }
            assertNull(error.cause)
            assertEquals(listOf("POST /api/plugins/add"), peer.requests.toList())
        }
        Peer("/api/plugins", dropReply = true).use { peer ->
            val error = assertFailsWith<PluginReadException> { peer.client().plugins() }
            assertNull(error.cause)
            assertEquals(listOf("GET /api/plugins"), peer.requests.toList())
        }
    }

    @Test fun completedRequestsDoNotCancelAReusableControlOrClient() {
        Peer().use { peer ->
            val control = ChatRequestControl()
            val client = peer.client(control)
            assertTrue(client.plugins().bun)
            control.ensureActive()
            assertTrue(client.add(SPEC).bun)
            control.ensureActive()
            assertTrue(client.restoreGatewayAccess().bun)
            control.ensureActive()
            assertEquals(listOf("GET /api/plugins", "POST /api/plugins/add", "POST $RESTORE_PATH", "GET /api/plugins"), peer.requests.toList())
            assertFalse(peer.cookies.isEmpty())
            assertTrue(peer.cookies.all { it == peer.credential.cookieHeader() })
            assertTrue(peer.authorizations.all { it == null })
        }
    }

    private fun invalidOperations(client: PluginManagementClient): List<() -> Unit> = listOf(
        { client.market("\n".repeat(300)); Unit },
        { client.add(""); Unit },
        { client.remove(""); Unit },
        { client.upgrade(""); Unit },
        { client.setOff("", true); Unit },
        { client.saveOptions("", JsonObject(emptyMap())); Unit },
        { client.prefer("", ""); Unit },
        { client.nextPrompt("", -1, emptyMap()); Unit },
        { client.signIn("", -1, emptyMap()); Unit },
        { client.signInState("../other"); Unit },
        { client.submitCallback("../other", "value") },
        { client.cancelSignIn("../other") },
    )

    private fun assertCancelled(operations: List<Operation>, sendHeaders: Boolean) {
        for (operation in operations) {
            Peer(operation.path, sendHeaders).use { peer ->
                val control = ChatRequestControl()
                val client = peer.client(control)
                val worker = Executors.newSingleThreadExecutor()
                val result = worker.submit(Callable<Throwable?> {
                    try { operation.perform(client); null } catch (failure: Throwable) { failure }
                })
                try {
                    assertTrue(peer.arrived.await(5, TimeUnit.SECONDS), operation.name)
                    control.cancel()
                    val error = assertIs<CancellationException>(result.get(2, TimeUnit.SECONDS), operation.name)
                    assertNull(error.cause)
                    assertTrue(peer.closed.await(2, TimeUnit.SECONDS), operation.name)
                    assertEquals(operation.expectedRequests, peer.requests.toList(), operation.name)
                } finally {
                    control.cancel()
                    result.cancel(true)
                    worker.shutdownNow()
                    worker.awaitTermination(5, TimeUnit.SECONDS)
                }
            }
        }
    }

    private enum class Operation(val path: String, val write: Boolean = false) {
        PLUGINS("/api/plugins"), MARKET("/api/plugins/market"),
        SEARCH_INSTALLED("/api/plugins"), SEARCH_RESULTS("/api/plugins/search?q=multi"),
        PROVIDERS("/api/providers"), SIGNIN_STATE("/api/signin/sign-in-id"),
        ADD("/api/plugins/add", true), REMOVE("/api/plugins/remove", true),
        UPGRADE("/api/plugins/upgrade", true), UPDATE("/api/plugins/update", true),
        OFF("/api/plugins/off", true), OPTIONS("/api/plugins/options", true), PREFER("/api/plugins/prefer", true),
        PROMPT("/api/plugin-signin/prompt", true), SIGNIN("/api/plugin-signin", true),
        CALLBACK("/api/signin/sign-in-id/callback", true), CANCEL("/api/signin/sign-in-id/cancel", true),
        RESTORE_POST(RESTORE_PATH, true), RESTORE_STATE("/api/plugins", true);

        val expectedRequests: List<String> get() = when (this) {
            SEARCH_RESULTS -> listOf("GET /api/plugins", "GET $path")
            RESTORE_STATE -> listOf("POST $RESTORE_PATH", "GET $path")
            else -> listOf("${if (write) "POST" else "GET"} $path")
        }

        fun perform(client: PluginManagementClient) {
            when (this) {
                PLUGINS -> client.plugins()
                MARKET -> client.market()
                SEARCH_INSTALLED, SEARCH_RESULTS -> client.market("multi")
                PROVIDERS -> client.providers()
                SIGNIN_STATE -> client.signInState("sign-in-id")
                ADD -> client.add(SPEC)
                REMOVE -> client.remove(SPEC)
                UPGRADE -> client.upgrade(SPEC)
                UPDATE -> client.updateAll()
                OFF -> client.setOff(SPEC, true)
                OPTIONS -> client.saveOptions(SPEC, JsonObject(emptyMap()))
                PREFER -> client.prefer(SPEC, "fakeco")
                PROMPT -> client.nextPrompt("fakeco", 0, emptyMap())
                SIGNIN -> client.signIn("fakeco", 0, emptyMap())
                CALLBACK -> client.submitCallback("sign-in-id", "http://127.0.0.1/callback?code=synthetic")
                CANCEL -> client.cancelSignIn("sign-in-id")
                RESTORE_POST, RESTORE_STATE -> client.restoreGatewayAccess()
            }
        }
    }

    /** Fully consumes a POST once; stalled responses wait for client EOF, never a test latch. */
    private class Peer(
        private val silentPath: String? = null,
        private val sendHeaders: Boolean = false,
        private val status: Int = 200,
        private val dropReply: Boolean = false,
    ) : Closeable {
        private val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val worker = Executors.newSingleThreadExecutor()
        private val sockets = ConcurrentHashMap.newKeySet<Socket>()
        val requests = CopyOnWriteArrayList<String>()
        val cookies = CopyOnWriteArrayList<String?>()
        val authorizations = CopyOnWriteArrayList<String?>()
        val arrived = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val baseUrl = "http://127.0.0.1:${listener.localPort}"
        val credential = ManagerCredential.fromKey("c".repeat(32), listener.localPort)

        fun client(control: ChatRequestControl? = null, ensureActive: () -> Unit = {}) =
            PluginManagementClient(credential, baseUrl, control, ensureActive)

        init {
            worker.submit {
                try {
                    while (!listener.isClosed) {
                        val socket = listener.accept()
                        sockets += socket
                        try { socket.use { serve(it) } } catch (_: IOException) {
                            // Closing the fixture or a canceled client may terminate the peer.
                        } finally { sockets -= socket }
                    }
                } catch (_: IOException) { /* Listener shutdown. */ }
            }
        }

        private fun serve(socket: Socket) {
            socket.soTimeout = 15_000
            val input = socket.getInputStream()
            val line = readLine(input) ?: return
            var length = 0
            var cookie: String? = null
            var authorization: String? = null
            while (true) {
                val header = readLine(input) ?: return
                if (header.isEmpty()) break
                val name = header.substringBefore(':')
                val value = header.substringAfter(':').trim()
                when {
                    name.equals("Content-Length", true) -> length = value.toInt()
                    name.equals("Cookie", true) -> cookie = value
                    name.equals("Authorization", true) -> authorization = value
                }
            }
            input.readNBytes(length)
            val path = line.split(' ')[1]
            cookies += cookie
            authorizations += authorization
            requests += line.substringBeforeLast(' ')
            val output = socket.getOutputStream()
            if (path == silentPath) {
                if (dropReply) return
                if (sendHeaders) {
                    output.write(("HTTP/1.1 $status Response\r\nContent-Length: 100\r\n" +
                        "Retry-After: 0\r\nConnection: close\r\n\r\nx").toByteArray(Charsets.US_ASCII))
                    output.flush()
                }
                arrived.countDown()
                try { if (input.read() == -1) closed.countDown() } catch (_: SocketException) { closed.countDown() }
                return
            }
            val body = when (path) {
                RESTORE_PATH -> "{\"ok\":true}"
                "/api/plugins/market" -> "{\"listings\":[],\"state\":$STATE}"
                "/api/plugins/search?q=multi" -> "{\"hits\":[]}"
                "/api/providers" -> "{\"plugins\":[]}"
                "/api/plugin-signin/prompt" -> "{\"prompt\":null,\"inputs\":{}}"
                "/api/plugin-signin", "/api/signin/sign-in-id" -> "{\"id\":\"sign-in-id\",\"state\":\"waiting\"}"
                "/api/signin/sign-in-id/callback", "/api/signin/sign-in-id/cancel" -> "{}"
                else -> STATE
            }.toByteArray(Charsets.UTF_8)
            output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
            output.write(body)
            output.flush()
        }

        private fun readLine(input: InputStream): String? {
            val line = StringBuilder()
            while (true) {
                val next = input.read()
                if (next == -1) return null
                if (next == '\n'.code) return line.toString().removeSuffix("\r")
                if (line.length >= 8192) throw IOException("Fixture line exceeded limit")
                line.append(next.toChar())
            }
        }

        override fun close() {
            listener.close()
            sockets.forEach { runCatching { it.close() } }
            worker.shutdownNow()
            worker.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    private companion object {
        const val RESTORE_PATH = "/api/androidhost/plugin-gateway-access/restore"
        const val SPEC = "@magpie-community/test-plugin"
        const val STATE = "{\"plugins\":[],\"bun\":true,\"bunVersion\":\"fixture\"}"
    }
}

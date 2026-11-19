package cc.fplj.magpiemobile.core

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.net.URI
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** The request has really exited only when the silent TCP peer observes EOF or reset. */
class ProviderManagementCancellationTest {
    @Test fun cancelListBeforeHeadersClosesSocket() = assertCancelled(Operation.LIST, false)
    @Test fun cancelListDuringBodyClosesSocket() = assertCancelled(Operation.LIST, true)
    @Test fun cancelEditableBeforeHeadersClosesSocket() = assertCancelled(Operation.EDITABLE, false)
    @Test fun cancelEditableDuringBodyClosesSocket() = assertCancelled(Operation.EDITABLE, true)
    @Test fun cancelCapabilityNegotiationSendsNoCreate() = assertCancelled(Operation.CAPABILITIES, false)
    @Test fun cancelCapabilityBodySendsNoCreate() = assertCancelled(Operation.CAPABILITIES, true)
    @Test fun cancelCreateAfterBodyClosesSocketWithoutReplay() = assertCancelled(Operation.CREATE, false)
    @Test fun cancelCreateDuringReplyClosesSocketWithoutReplay() = assertCancelled(Operation.CREATE, true)
    @Test fun cancelEditAfterBodyClosesSocketWithoutReplay() = assertCancelled(Operation.EDIT, false)
    @Test fun cancelEditDuringReplyClosesSocketWithoutReplay() = assertCancelled(Operation.EDIT, true)
    @Test fun cancelDiscoveryBeforeHeadersPreservesCancellation() = assertCancelled(Operation.DISCOVERY, false)
    @Test fun cancelDiscoveryDuringBodyPreservesCancellation() = assertCancelled(Operation.DISCOVERY, true)

    @Test fun precancelledClientSendsNoReadWriteOrDiscoveryRequest() {
        Peer(Operation.LIST.path).use { peer ->
            val client = peer.client(ChatRequestControl().apply { cancel() })
            assertFailsWith<CancellationException> { client.getProviders() }
            assertFailsWith<CancellationException> { client.editable("saved") }
            assertFailsWith<CancellationException> { client.create(draft()) }
            assertFailsWith<CancellationException> {
                client.discoverModels(ProviderModelDiscoveryRequest("https://api.example/v1", "test-key"))
            }
            assertTrue(peer.requests.isEmpty())
        }
    }

    @Test fun ownerGuardRejectsCachedCapabilitiesBeforeAnotherWrite() {
        ProviderApiFixture().use { api ->
            var current = true
            val client = ProviderManagementClient(api.credential, api.baseUrl, ensureActive = {
                if (!current) throw CancellationException("Stale owner")
            })
            client.create(draft())
            current = false
            assertFailsWith<CancellationException> { client.create(draft("another")) }
            assertFailsWith<CancellationException> { client.getProviders() }
            assertEquals(1, api.createRequests.get())
            assertEquals(1, api.requests.count { it.path == "/api/mobile/capabilities" })
        }
    }

    @Test fun cancellationBetweenCapabilitiesAndPostSendsNoWrite() {
        ProviderApiFixture().use { api ->
            val client = ProviderManagementClient(api.credential, api.baseUrl, ensureActive = {
                if (api.requests.any { it.path == "/api/mobile/capabilities" }) {
                    throw CancellationException("Session replaced during negotiation")
                }
            })
            assertFailsWith<CancellationException> { client.create(draft()) }
            assertEquals(listOf("/api/mobile/capabilities"), api.requests.map { it.path })
            assertEquals(0, api.createRequests.get())
        }
    }

    @Test fun errorStatusDoesNotDrainSilentBodyOrRetryGetAndRetainsOnlySafeCode() {
        for (code in listOf(401, 408, 503)) {
            Peer("/api/providers", true, code, "provider_models_dns").use { peer ->
                val transport = BoundedCoreHttp(LoopbackEndpoint(peer.baseUrl), Duration.ofSeconds(10))
                val error = assertFailsWith<ManagementHttpException> { transport.request("GET", "/api/providers") }
                assertEquals(code, error.httpCode)
                assertEquals("provider_models_dns", error.safeErrorCode)
                assertNull(error.cause)
                assertTrue(peer.closed.await(2, TimeUnit.SECONDS))
                assertEquals(listOf("GET /api/providers"), peer.requests)
            }
        }
    }

    @Test fun unrecognizedErrorHeaderIsNotRetained() {
        Peer("/api/providers", true, 503, "server-secret-value").use { peer ->
            val transport = BoundedCoreHttp(LoopbackEndpoint(peer.baseUrl), Duration.ofSeconds(10))
            val error = assertFailsWith<ManagementHttpException> { transport.request("GET", "/api/providers") }
            assertNull(error.safeErrorCode)
            assertFalse(error.stackTraceToString().contains("server-secret-value"))
            assertTrue(peer.closed.await(2, TimeUnit.SECONDS))
        }
    }

    @Test fun exactResponseByteLimitRemainsAValidList() {
        ProviderApiFixture().use { api ->
            val json = "{\"providers\":[]}"
            api.listOverride = json + " ".repeat(BoundedCoreHttp.MAX_RESPONSE_BYTES - json.toByteArray(Charsets.UTF_8).size)
            assertFalse(api.client().getProviders().hasFileError)
            api.listOverride += " "
            assertFailsWith<ManagementProtocolException> { api.client().getProviders() }
        }
    }

    @Test fun managementDeadlineClosesSilentPeerAndReusableClientIsNotPoisoned() {
        Peer("/api/providers", true, silenceOnce = true).use { peer ->
            val client = peer.client(timeout = Duration.ofMillis(500))
            assertFailsWith<ProviderMetadataReadException> { client.getProviders() }
            assertTrue(peer.closed.await(2, TimeUnit.SECONDS))
            repeat(2) { assertFalse(client.getProviders().hasFileError) }
            assertEquals(3, peer.requests.size)
        }
    }

    @Test fun managementRequestsBypassThrowingSystemProxySelector() {
        ProviderApiFixture().use { api ->
            val original = ProxySelector.getDefault()
            val selected = AtomicInteger()
            val client = api.client()
            try {
                ProxySelector.setDefault(object : ProxySelector() {
                    override fun select(uri: URI): List<Proxy> {
                        selected.incrementAndGet()
                        throw IllegalStateException("System proxy must not receive management credentials")
                    }
                    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
                })
                assertFalse(client.getProviders().hasFileError)
                client.create(draft())
            } finally { ProxySelector.setDefault(original) }
            assertEquals(0, selected.get())
            assertTrue(api.requests.all { it.cookie == api.credential.cookieHeader() && it.authorization == null })
        }
    }

    private fun assertCancelled(operation: Operation, sendHeaders: Boolean) {
        Peer(operation.path, sendHeaders).use { peer ->
            val control = ChatRequestControl()
            val client = peer.client(control)
            val worker = Executors.newSingleThreadExecutor()
            val result = worker.submit(Callable<Throwable?> {
                try {
                    when (operation) {
                        Operation.LIST -> client.getProviders()
                        Operation.EDITABLE -> client.editable("saved")
                        Operation.EDIT -> client.edit(ProviderEditDraft(client.editable("saved"), name = "Edited"))
                        Operation.DISCOVERY -> client.discoverModels(ProviderModelDiscoveryRequest("https://api.example/v1", "test-key"))
                        Operation.CAPABILITIES, Operation.CREATE -> client.create(draft())
                    }
                    null
                } catch (failure: Throwable) { failure }
            })
            try {
                assertTrue(peer.arrived.await(5, TimeUnit.SECONDS))
                control.cancel()
                val failure = assertIs<CancellationException>(result.get(2, TimeUnit.SECONDS))
                assertNull(failure.cause)
                assertTrue(peer.closed.await(2, TimeUnit.SECONDS))
                assertEquals(1, peer.requests.count { it.endsWith(" " + operation.path) })
                if (operation == Operation.CAPABILITIES) assertEquals(1, peer.requests.size)
            } finally {
                control.cancel()
                result.cancel(true)
                worker.shutdownNow()
                worker.awaitTermination(5, TimeUnit.SECONDS)
            }
        }
    }

    private enum class Operation(val path: String) {
        LIST("/api/providers"), EDITABLE("/api/mobile/providers/editable/saved"),
        CAPABILITIES("/api/mobile/capabilities"), CREATE("/api/mobile/providers/create"),
        EDIT("/api/mobile/providers/edit"), DISCOVERY("/api/mobile/providers/models"),
    }

    private fun draft(id: String = "new-provider") = ProviderCreateDraft(
        id, "New provider", "https://api.example/v1", "test-provider-key", listOf("model"),
    )

    /** Each reply closes its connection; a stalled reply waits for client EOF, never a test latch. */
    private class Peer(
        private val silentPath: String,
        private val sendHeaders: Boolean = false,
        private val status: Int = 200,
        private val errorCode: String? = null,
        private val silenceOnce: Boolean = false,
    ) : Closeable {
        private val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val worker = Executors.newSingleThreadExecutor()
        private val sockets = ConcurrentHashMap.newKeySet<Socket>()
        private var silenced = false
        val requests = CopyOnWriteArrayList<String>()
        val arrived = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val baseUrl = "http://127.0.0.1:${listener.localPort}"
        private val credential = ManagerCredential.fromKey("c".repeat(32), listener.localPort)

        fun client(control: ChatRequestControl? = null, timeout: Duration = Duration.ofSeconds(15)) =
            ProviderManagementClient(credential, baseUrl, timeout, timeout, control)

        init {
            // Resolve classes and shared pools before measuring short network budgets.
            ChatHttpTransport.client(Duration.ofSeconds(15), Duration.ofSeconds(15))
            worker.submit {
                try {
                    while (!listener.isClosed) {
                        val socket = listener.accept()
                        sockets += socket
                        try { socket.use { serve(it) } } catch (_: IOException) {
                            // Closing a fixture or canceled client may terminate the peer.
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
            while (true) {
                val header = readLine(input) ?: return
                if (header.isEmpty()) break
                if (header.substringBefore(':').equals("Content-Length", true)) length = header.substringAfter(':').trim().toInt()
            }
            input.readNBytes(length)
            val path = line.split(' ')[1]
            requests += line.substringBeforeLast(' ')
            val output = socket.getOutputStream()
            if (path == silentPath && (!silenceOnce || !silenced)) {
                silenced = true
                if (sendHeaders) {
                    output.write(("HTTP/1.1 $status Response\r\nContent-Length: 100\r\n" +
                        "Retry-After: 0\r\nConnection: close\r\n" +
                        (errorCode?.let { "X-Magpie-Mobile-Error-Code: $it\r\n" } ?: "") + "\r\nx")
                        .toByteArray(Charsets.US_ASCII))
                    output.flush()
                }
                arrived.countDown()
                try {
                    if (input.read() == -1) closed.countDown()
                } catch (_: SocketException) { closed.countDown() }
                return
            }
            val text = when (path) {
                "/api/mobile/capabilities" -> ProviderApiFixture.CAPABILITIES
                "/api/providers" -> "{\"providers\":[]}"
                else -> """{"id":"saved","revision":"${"a".repeat(64)}","name":"Saved","chat":"https://api.example/v1","models":["model"],"noModels":false,"key":{"set":true,"optional":false,"masked":"***"}}"""
            }.toByteArray(Charsets.UTF_8)
            output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                "Content-Length: ${text.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
            output.write(text)
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
}

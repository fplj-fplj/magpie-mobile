package cc.fplj.magpiemobile.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GatewayTransportSafetyTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { start() }
    private fun cfg() = GatewayConfig(
        gatewayKey = "local-client-test-key",
        gatewayBaseUrl = "http://127.0.0.1:" + server.address.port,
        connectTimeout = Duration.ofSeconds(2),
        readTimeout = Duration.ofSeconds(2),
    )
    @AfterEach fun close() { server.stop(0) }

    @Test fun gatewayClientsNeverSendLocalCredentialsViaSystemProxy() {
        val original = ProxySelector.getDefault()
        val selected = AtomicInteger()
        ProxySelector.setDefault(object : ProxySelector() {
            override fun select(uri: URI): List<Proxy> { selected.incrementAndGet(); throw AssertionError("System proxy must not receive a gateway request") }
            override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) = Unit
        })
        try {
            assertAllGatewayClientsReachOnlyTheirLoopbackEndpoint()
            assertEquals(0, selected.get())
        } finally { ProxySelector.setDefault(original) }
    }

    @Test fun gatewayClientsIgnoreSystemHttpProxyAndKeepCredentialsAtTheirGateway() {
        assertGatewayClientsIgnoreProxy(Proxy.Type.HTTP)
    }

    @Test fun gatewayClientsIgnoreSystemSocksProxyAndKeepCredentialsAtTheirGateway() {
        assertGatewayClientsIgnoreProxy(Proxy.Type.SOCKS)
    }

    @Test fun everySocketFactoryOverloadBypassesThrowingHttpAndSocksSelectors() {
        val factory = ChatHttpTransport.client(cfg()).socketFactory
        val loopback = InetAddress.getByName("127.0.0.1")
        val remote = InetSocketAddress(loopback, server.address.port)
        for (type in listOf(null, Proxy.Type.HTTP, Proxy.Type.SOCKS)) {
            ProxyTrap().use { trap ->
                val selected = AtomicInteger()
                val original = ProxySelector.getDefault()
                ProxySelector.setDefault(trap.selector(type, selected))
                try {
                    factory.createSocket().use { socket ->
                        assertFalse(socket.isConnected)
                        assertFalse(socket.isBound)
                        // This is the path OkHttp uses: create an unconnected socket, then apply
                        // its route and configured connect timeout without consulting SOCKS.
                        socket.connect(remote, 2_000)
                        assertDirectSocket(socket)
                    }
                    factory.createSocket("127.0.0.1", remote.port).use(::assertDirectSocket)
                    factory.createSocket(loopback, remote.port).use(::assertDirectSocket)
                    factory.createSocket("127.0.0.1", remote.port, loopback, 0).use(::assertDirectSocket)
                    factory.createSocket(loopback, remote.port, loopback, 0).use(::assertDirectSocket)
                    assertEquals(0, selected.get())
                    trap.assertUnused()
                } finally { ProxySelector.setDefault(original) }
            }
        }
    }

    @Test fun failedFactoryConnectionsReleaseTheirExplicitLocalBindings() {
        val factory = ChatHttpTransport.client(cfg()).socketFactory
        val loopback = InetAddress.getByName("127.0.0.1")
        for (addressOverload in listOf(false, true)) {
            // Reserve distinct ports simultaneously, then leave the remote endpoint closed.
            ServerSocket(0, 1, loopback).use { refused ->
                ServerSocket(0, 1, loopback).use { reservedLocal ->
                    val remotePort = refused.localPort
                    val localPort = reservedLocal.localPort
                    refused.close()
                    reservedLocal.close()
                    assertFailsWith<IOException> {
                        if (addressOverload) factory.createSocket(loopback, remotePort, loopback, localPort)
                        else factory.createSocket("127.0.0.1", remotePort, loopback, localPort)
                    }
                    // A factory that leaked its bound socket would keep this port occupied.
                    ServerSocket().use { rebound ->
                        rebound.bind(InetSocketAddress(loopback, localPort))
                        assertEquals(localPort, rebound.localPort)
                    }
                }
            }
        }
    }

    @Test fun nonStreamingChatDoesNotFollowRedirectOrReplayPost() {
        val calls = AtomicInteger()
        server.createContext("/v1/chat/completions") { ex ->
            calls.incrementAndGet(); ex.requestBody.readBytes()
            ex.responseHeaders.add("Location", "/elsewhere"); ex.sendResponseHeaders(307, -1); ex.close()
        }
        server.createContext("/elsewhere") { ex -> calls.incrementAndGet(); ex.sendResponseHeaders(200, -1); ex.close() }
        assertFailsWith<ChatException> { ChatClient(cfg()).complete("m", listOf(ChatClient.Message("user", "probe"))) }
        assertEquals(1, calls.get())
    }

    private fun assertGatewayClientsIgnoreProxy(type: Proxy.Type) {
        ProxyTrap().use { trap ->
            val original = ProxySelector.getDefault()
            val selected = AtomicInteger()
            ProxySelector.setDefault(trap.selector(type, selected))
            try {
                assertAllGatewayClientsReachOnlyTheirLoopbackEndpoint()
                assertEquals(0, selected.get())
                trap.assertUnused()
            } finally { ProxySelector.setDefault(original) }
        }
    }

    private fun assertAllGatewayClientsReachOnlyTheirLoopbackEndpoint() {
        val config = cfg()
        val requests = CopyOnWriteArrayList<ReceivedRequest>()
        val catalogs = AtomicInteger()
        val plain = """{"choices":[{"message":{"content":"plain ok"}}]}"""
        server.createContext("/v1/models") { exchange ->
            requests += exchange.receive()
            // Retain the original empty-catalog assertion, then offer a real provider route
            // for the separate capability run's fresh catalog and three chat checks.
            val catalog = if (catalogs.getAndIncrement() == 0) """{"data":[]}"""
            else """{"data":[{"id":"saved/vendor/model","owned_by":"saved"}]}"""
            exchange.respond(catalog)
        }
        server.createContext("/v1/chat/completions") { exchange ->
            val request = exchange.receive()
            requests += request
            when {
                request.body.contains("\"stream\":true") -> exchange.respond(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"stream ok\"}}]}\n\n" +
                        "data: [DONE]\n\n",
                    "text/event-stream",
                )
                request.body.contains("\"tools\"") -> exchange.respond(
                    """{"choices":[{"message":{"content":null,"tool_calls":[{"type":"function","function":{"name":"get_current_time","arguments":"{}"}}]}}]}""",
                )
                else -> exchange.respond(plain)
            }
        }

        assertEquals(emptyList<ModelEntry>(), ModelsClient(config).list())
        val messages = listOf(ChatClient.Message("user", "probe"))
        assertEquals(plain, ChatClient(config).complete("saved/vendor/model", messages))
        assertEquals("stream ok", StreamingChatClient(config).stream("saved/vendor/model", messages))
        val report = ProviderModelCapabilityClient(config).run("saved", "vendor/model")
        assertEquals("saved/vendor/model", report.route?.gatewayModelId)
        assertEquals(ProviderCapabilityCheck.entries.toList(), report.results.map { it.check })
        assertTrue(report.results.all { it.status == ProviderCapabilityStatus.PASSED })

        assertEquals(2, catalogs.get())
        assertEquals(
            listOf("/v1/models", "/v1/chat/completions", "/v1/chat/completions", "/v1/models") +
                List(3) { "/v1/chat/completions" },
            requests.map { it.path },
        )
        assertTrue(requests.all { it.peerIsLoopback })
        assertTrue(requests.all { it.authorization == listOf("Bearer " + config.gatewayKey) })
    }

    private fun assertDirectSocket(socket: Socket) {
        assertTrue(socket.isConnected)
        assertTrue(socket.inetAddress.isLoopbackAddress)
        assertTrue(socket.localAddress.isLoopbackAddress)
        assertEquals(server.address.port, socket.port)
    }

    private data class ReceivedRequest(
        val path: String,
        val body: String,
        val authorization: List<String>,
        val peerIsLoopback: Boolean,
    )

    private fun HttpExchange.receive() = ReceivedRequest(
        path = requestURI.path,
        body = requestBody.use { String(it.readBytes(), Charsets.UTF_8) },
        authorization = requestHeaders["Authorization"]?.toList().orEmpty(),
        peerIsLoopback = remoteAddress.address.isLoopbackAddress,
    )

    private fun HttpExchange.respond(body: String, contentType: String = "application/json") {
        val bytes = body.toByteArray(Charsets.UTF_8)
        responseHeaders.add("Content-Type", contentType)
        // Every call must create a socket; a pooled connection cannot hide a bad factory.
        responseHeaders.add("Connection", "close")
        try {
            sendResponseHeaders(200, bytes.size.toLong())
            responseBody.use { it.write(bytes) }
        } finally { close() }
    }

    /** A real listening proxy endpoint: even a SOCKS handshake or HTTP CONNECT is a failure. */
    private class ProxyTrap : Closeable {
        private val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val arrived = CountDownLatch(1)
        private val connections = AtomicInteger()
        private val failure = AtomicReference<IOException?>()
        private val worker = thread(name = "gateway-proxy-trap", isDaemon = true) {
            try {
                while (!listener.isClosed) listener.accept().use {
                    connections.incrementAndGet()
                    arrived.countDown()
                    // Closing immediately bounds any accidentally proxied client's failure.
                }
            } catch (error: IOException) {
                if (!listener.isClosed) failure.set(error)
            }
        }

        fun selector(type: Proxy.Type?, selected: AtomicInteger): ProxySelector = object : ProxySelector() {
            override fun select(uri: URI): List<Proxy> {
                selected.incrementAndGet()
                if (type == null) throw AssertionError("System proxy must not receive a gateway request")
                return listOf(Proxy(type, InetSocketAddress("127.0.0.1", listener.localPort)))
            }

            override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) = Unit
        }

        fun assertUnused() {
            assertFalse(arrived.await(150, TimeUnit.MILLISECONDS), "Gateway contacted a system proxy")
            assertEquals(0, connections.get())
            assertNull(failure.get())
        }

        override fun close() {
            listener.close()
            worker.join(2_000)
            assertFalse(worker.isAlive, "Proxy trap worker did not stop")
        }
    }
}

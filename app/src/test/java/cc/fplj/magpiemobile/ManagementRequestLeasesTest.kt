package cc.fplj.magpiemobile

import cc.fplj.magpiemobile.core.ManagerCredential
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagementRequestLeasesTest {
    private val credential = ManagerCredential.fromKey("management-lease-synthetic-key")

    @Test fun invalidationCancelsAllCapturedLeasesAndAllowsAFreshLease() {
        val registry = ManagementRequestLeases()
        val first = registry.acquire(credential) { true }
        val second = registry.acquire(credential) { true }
        try {
            registry.invalidate()
            assertThrows(CancellationException::class.java) { first.control.ensureActive() }
            assertThrows(CancellationException::class.java) { second.client.listCallerKeys() }
            registry.acquire(credential) { true }.use { fresh -> fresh.ensureCurrent() }
        } finally { first.close(); second.close() }
    }

    @Test fun closingSuccessfulLeaseUnregistersItWithoutCancellingItsControl() {
        val registry = ManagementRequestLeases()
        val completed = registry.acquire(credential) { true }
        val active = registry.acquire(credential) { true }
        try {
            completed.close()
            completed.close()
            registry.invalidate()
            completed.control.ensureActive()
            assertThrows(CancellationException::class.java) { active.ensureCurrent() }
            assertThrows(CancellationException::class.java) { completed.client.listCallerKeys() }
        } finally { completed.close(); active.close() }
    }

    @Test fun boundClientChecksCapturedIdentityBeforeAnySocketRequest() {
        CallerKeyHttpPeer().use { peer ->
            val registry = ManagementRequestLeases()
            val current = AtomicBoolean(true)
            registry.acquire(peer.credential, current::get).use { lease ->
                lease.ensureCurrent()
                current.set(false) // The invalidation notification has not arrived yet.
                assertThrows(CancellationException::class.java) { lease.client.listCallerKeys() }
                assertThrows(CancellationException::class.java) { lease.control.ensureActive() }
                assertTrue(peer.requests.isEmpty())
            }
            peer.assertHealthy()
        }
    }

    @Test fun invalidationClosesRealReadAndPostBeforeHeadersAndMidBodyWithoutReplay() {
        for (write in listOf(false, true)) for (sendHeaders in listOf(false, true)) {
            val path = if (write) CallerKeyHttpPeer.COPY_PATH else CallerKeyHttpPeer.LIST_PATH
            CallerKeyHttpPeer(silentPath = path, sendHeaders = sendHeaders).use { peer ->
                val registry = ManagementRequestLeases()
                val lease = registry.acquire(peer.credential) { true }
                val worker = Executors.newSingleThreadExecutor()
                val result = worker.submit(Callable<Throwable?> {
                    try {
                        if (write) lease.client.copyCallerKey(CallerKeyHttpPeer.EXISTING_ID)
                        else lease.client.listCallerKeys()
                        null
                    } catch (failure: Throwable) { failure }
                })
                try {
                    assertTrue("Request did not reach the silent peer", peer.silentArrived.await(5, TimeUnit.SECONDS))
                    registry.invalidate()
                    val failure = result.get(2, TimeUnit.SECONDS)
                    assertTrue("Expected cancellation rather than a transport timeout", failure is CancellationException)
                    assertNull(failure?.cause)
                    assertTrue("The actual socket must close", peer.peerClosed.await(2, TimeUnit.SECONDS))
                    assertEquals(listOf("${if (write) "POST" else "GET"} $path"), peer.requestLines())
                    assertTrue(peer.requests.all { it.managerHeader == peer.credential.managerHeaderValue() })
                    assertThrows(CancellationException::class.java) { lease.client.listCallerKeys() }
                    assertEquals(1, peer.requests.size)
                    peer.assertHealthy()
                } finally {
                    registry.invalidate()
                    lease.close()
                    result.cancel(true)
                    worker.shutdownNow()
                    worker.awaitTermination(5, TimeUnit.SECONDS)
                }
            }
        }
    }
}

/** Real loopback HTTP used by both lease and caller-key operation tests; all data is synthetic. */
internal class CallerKeyHttpPeer(
    private val silentPath: String? = null,
    private val sendHeaders: Boolean = false,
    private val holdList: Boolean = false,
) : Closeable {
    data class Request(val line: String, val managerHeader: String, val body: String, val chunked: Boolean)

    private val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val closed = AtomicBoolean()
    private val failure = AtomicReference<Throwable?>()
    private var created = false
    val credential = ManagerCredential.fromKey("caller-key-synthetic-manager-${listener.localPort}", listener.localPort)
    val requests = CopyOnWriteArrayList<Request>()
    val listArrived = CountDownLatch(1)
    val releaseList = CountDownLatch(1)
    val silentArrived = CountDownLatch(1)
    val peerClosed = CountDownLatch(1)

    private val worker = thread(isDaemon = true, name = "caller-key-http-fixture") {
        try {
            while (!listener.isClosed) {
                val socket = listener.accept()
                sockets += socket
                try {
                    socket.use { serve(it) }
                } catch (problem: IOException) {
                    // A stale held GET may be cancelled immediately after its headers arrive.
                    if (!closed.get() && silentPath == null && !holdList) failure.compareAndSet(null, problem)
                } finally { sockets -= socket }
            }
        } catch (problem: Throwable) {
            if (!closed.get()) failure.compareAndSet(null, problem)
        }
    }

    fun requestLines(): List<String> = requests.map { it.line }

    fun assertHealthy() {
        failure.get()?.let { throw AssertionError("Caller-key HTTP fixture failed", it) }
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = 15_000
        val input = socket.getInputStream()
        val line = readLine(input) ?: return
        var length = 0
        var managerHeader = ""
        var chunked = false
        while (true) {
            val header = readLine(input) ?: return
            if (header.isEmpty()) break
            val name = header.substringBefore(':')
            val value = header.substringAfter(':').trim()
            when {
                name.equals("Content-Length", true) -> length = value.toInt().also { require(it in 0..65536) }
                name.equals("X-Magpie-Android-Manager", true) -> managerHeader = value
                name.equals("Transfer-Encoding", true) -> chunked = value.equals("chunked", true)
            }
        }
        val body = input.readNBytes(length)
        check(body.size == length) { "Incomplete caller-key request body" }
        val path = line.split(' ')[1]
        val request = Request(line.substringBeforeLast(' '), managerHeader, body.toString(Charsets.UTF_8), chunked)
        requests += request
        val output = socket.getOutputStream()
        if (path == silentPath) {
            if (sendHeaders) {
                output.write(("HTTP/1.1 200 OK\r\nContent-Length: 4096\r\nConnection: close\r\n\r\n{")
                    .toByteArray(Charsets.US_ASCII))
                output.flush()
            }
            silentArrived.countDown()
            try { if (input.read() == -1) peerClosed.countDown() }
            catch (_: SocketException) { peerClosed.countDown() }
            return
        }
        if (path == LIST_PATH && holdList) {
            listArrived.countDown()
            check(releaseList.await(10, TimeUnit.SECONDS)) { "Held preflight GET was not released" }
        }
        val response = when (request.line) {
            "GET $LIST_PATH" -> buildJsonObject { put("keys", rows()) }
            "POST $ADD_PATH" -> {
                val name = Json.parseToJsonElement(request.body) as JsonObject
                check(name["name"]?.jsonPrimitive?.content == CREATED_NAME)
                created = true
                buildJsonObject { put("keys", rows()); put("secret", CREATED_SECRET) }
            }
            "POST $COPY_PATH" -> {
                val copy = Json.parseToJsonElement(request.body) as JsonObject
                check(copy["key"]?.jsonPrimitive?.content == EXISTING_ID)
                buildJsonObject { put("keys", rows()); put("secret", COPIED_SECRET) }
            }
            "POST $MODELS_PATH" -> buildJsonObject {
                put("keys", rows(modelsUpdated = true))
                put("secret", "")
            }
            else -> error("Unexpected caller-key fixture route: ${request.line}")
        }.toString().toByteArray(Charsets.UTF_8)
        output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
            "Content-Length: ${response.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
        output.write(response)
        output.flush()
    }

    private fun rows(modelsUpdated: Boolean = false): JsonArray = JsonArray(buildList {
        add(buildJsonObject {
            put("id", EXISTING_ID)
            put("name", "Existing caller")
            put("secret", "synthetic-list-secret-must-be-stripped")
            if (modelsUpdated) put("models", JsonArray(listOf(JsonPrimitive(MODEL_ID))))
        })
        if (created) add(buildJsonObject {
            put("id", CREATED_ID)
            put("name", CREATED_NAME)
            put("secret", "synthetic-list-secret-must-be-stripped")
        })
    })

    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        while (true) {
            val next = input.read()
            if (next == -1) return null
            if (next == '\n'.code) return line.toString().removeSuffix("\r")
            check(line.length < 8192) { "Caller-key fixture header exceeded limit" }
            line.append(next.toChar())
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        releaseList.countDown()
        listener.close()
        sockets.forEach { runCatching { it.close() } }
        worker.join(5000)
        check(!worker.isAlive) { "Caller-key fixture did not stop" }
    }

    companion object {
        const val LIST_PATH = "/api/caller-keys"
        const val ADD_PATH = "/api/caller-keys/add-key"
        const val COPY_PATH = "/api/caller-keys/copy-key"
        const val MODELS_PATH = "/api/caller-keys/models-key"
        const val EXISTING_ID = "synthetic-existing-caller"
        const val CREATED_ID = "synthetic-created-caller"
        const val CREATED_NAME = "Synthetic mobile client"
        const val MODEL_ID = "synthetic-provider/model-1"
        val CREATED_SECRET = "sk-magpie-key-" + "a".repeat(48)
        val COPIED_SECRET = "sk-magpie-key-" + "b".repeat(48)
    }
}

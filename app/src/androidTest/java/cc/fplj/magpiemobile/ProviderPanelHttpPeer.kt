package cc.fplj.magpiemobile

import cc.fplj.magpiemobile.core.ManagerCredential
import java.io.BufferedInputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject

/** A real loopback management peer; only synthetic metadata and credentials are ever used. */
internal class ProviderPanelHttpPeer(
    savedModels: List<String> = listOf("saved-model"),
    noModels: Boolean = false,
    off: Boolean = false,
    private val listedModels: Map<String, Boolean>? = null,
    private val discoveryModels: List<String> = listOf("saved-model"),
) : Closeable {
    private val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val workers = Executors.newCachedThreadPool { task ->
        Thread(task, "provider-panel-http-fixture").apply { isDaemon = true }
    }
    private val sockets = CopyOnWriteArrayList<Socket>()
    private val closing = AtomicBoolean()
    private val failure = AtomicReference<Throwable?>()
    private val canonical = AtomicReference(Canonical(models = savedModels, noModels = noModels, off = off))
    private val listGates = ConcurrentLinkedQueue<Gate>()
    private val editGates = ConcurrentLinkedQueue<EditGate>()
    private val allGates = CopyOnWriteArrayList<Gate>()
    val requests = CopyOnWriteArrayList<Request>()
    val credential = ManagerCredential.fromKey("provider-panel-synthetic-manager", listener.localPort)
    val listCount: Int get() = requests.count { it.method == "GET" && it.path == LIST }
    val modelDirectoryCount: Int get() = requests.count { it.method == "POST" && it.path == MODELS }
    val editRequests: List<Request> get() = requests.filter { it.method == "POST" && it.path == EDIT }

    init {
        workers.execute {
            try {
                while (!closing.get()) {
                    val socket = listener.accept()
                    sockets.add(socket)
                    workers.execute {
                        socket.use {
                            try { serve(it) }
                            catch (error: Throwable) { if (!closing.get()) failure.compareAndSet(null, error) }
                            finally { sockets.remove(it) }
                        }
                    }
                }
            } catch (error: Throwable) {
                if (!closing.get()) failure.compareAndSet(null, error)
            }
        }
    }

    fun holdNextList(): Gate = Gate().also { allGates.add(it); listGates.add(it) }

    fun holdNextEdit(status: Int): Gate = Gate().also { gate ->
        require(status in listOf(200, 400))
        allGates.add(gate)
        editGates.add(EditGate(status, gate))
    }

    fun externalRename(name: String) {
        canonical.updateAndGet { it.copy(name = name, revision = "c".repeat(64)) }
    }

    fun assertHealthy() {
        check(failure.get() == null) { "Loopback provider fixture encountered an unexpected request or transport failure" }
        check(requests.all { it.authenticated && !it.hasAuthorization && !it.hasOrigin }) {
            "Provider management requests must use the fixture's own cookie and no gateway authorization"
        }
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = 15_000
        val input = BufferedInputStream(socket.getInputStream())
        fun line(): String {
            val bytes = ArrayList<Byte>()
            while (bytes.size < 16_384) {
                val next = input.read()
                check(next >= 0) { "Truncated management request" }
                if (next == 10) return bytes.toByteArray().toString(Charsets.US_ASCII).trimEnd('\r')
                bytes.add(next.toByte())
            }
            error("Management request header line exceeded the fixture limit")
        }
        val first = line().split(' ')
        check(first.size == 3)
        val headers = mutableMapOf<String, String>()
        while (true) {
            val header = line()
            if (header.isEmpty()) break
            check(headers.size < 40)
            headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim()
        }
        check(headers["transfer-encoding"] == null)
        val length = headers["content-length"]?.toInt() ?: 0
        check(length in 0..524_288)
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < bytes.size) {
            val count = input.read(bytes, offset, bytes.size - offset)
            check(count > 0)
            offset += count
        }
        val request = Request(
            method = first[0], path = first[1], body = bytes.toString(Charsets.UTF_8),
            authenticated = headers["cookie"] == credential.cookieHeader(),
            hasAuthorization = headers["authorization"] != null, hasOrigin = headers["origin"] != null,
        )
        requests.add(request)
        check(request.authenticated && !request.hasAuthorization && !request.hasOrigin)
        when {
            request.method == "GET" && request.path == LIST -> {
                // Capture before the latch, so an external edit can make this response stale.
                val body = listBody(canonical.get())
                listGates.poll()?.hold()
                respond(socket, 200, body)
            }
            request.method == "GET" && request.path == EDITABLE -> respond(socket, 200, editable(canonical.get()))
            request.method == "GET" && request.path == CAPABILITIES -> respond(socket, 200, CAPABILITIES_BODY)
            request.method == "POST" && request.path == MODELS -> respond(socket, 200,
                JSONObject().put("baseUrl", CHAT_URL).put("models", JSONArray(discoveryModels.map { id ->
                    JSONObject().put("id", id).put("name", id)
                })).toString())
            request.method == "POST" && request.path == EDIT -> {
                val next = checkNotNull(editGates.poll()) { "A provider write occurred without an explicit test action" }
                next.gate.hold()
                if (next.status == 200) {
                    val body = JSONObject(request.body)
                    val old = canonical.get()
                    check(body.getString("id") == ID && body.getString("revision") == old.revision)
                    val models = body.optJSONArray("models")?.let { ids ->
                        (0 until ids.length()).map(ids::getString)
                    } ?: old.models
                    val updated = old.copy(models = models, revision = "b".repeat(64))
                    canonical.set(updated)
                    respond(socket, 200, editable(updated))
                } else respond(socket, next.status, "")
            }
            else -> error("Unexpected provider fixture route")
        }
    }

    private fun respond(socket: Socket, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val label = if (status == 200) "OK" else "Bad Request"
        val header = "HTTP/1.1 $status $label\r\nContent-Type: application/json\r\n" +
            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().apply {
            write(header.toByteArray(Charsets.US_ASCII) + bytes)
            flush()
        }
    }

    private fun editable(value: Canonical): String = JSONObject()
        .put("id", ID).put("revision", value.revision).put("name", value.name).put("chat", CHAT_URL)
        .put("models", JSONArray(value.models)).put("noModels", value.noModels).put("key", keyMetadata()).toString()

    private fun listBody(value: Canonical): String = JSONObject().put("providers", JSONArray().put(JSONObject()
        .put("id", ID).put("name", value.name).put("chat", CHAT_URL).put("preset", "")
        .put("baseAPI", "chat").put("ready", true).put("off", value.off).put("noModels", value.noModels)
        .put("key", keyMetadata()).put("chosen", JSONArray(value.models))
        .put("models", JSONArray((listedModels ?: value.models.associateWith { true }).map { (id, on) ->
            JSONObject().put("id", id).put("name", id).put("on", on)
        })))).toString()

    private fun keyMetadata() = JSONObject().put("set", true).put("optional", false).put("masked", "******")

    override fun close() {
        if (!closing.compareAndSet(false, true)) return
        allGates.forEach { it.release.countDown() }
        listener.close()
        sockets.forEach { runCatching { it.close() } }
        workers.shutdownNow()
        check(workers.awaitTermination(5, TimeUnit.SECONDS)) { "Provider HTTP fixture did not stop" }
    }

    internal class Gate {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fun hold() {
            entered.countDown()
            check(release.await(25, TimeUnit.SECONDS)) { "Provider fixture response was never released" }
        }
    }

    internal data class Request(
        val method: String, val path: String, val body: String,
        val authenticated: Boolean, val hasAuthorization: Boolean, val hasOrigin: Boolean,
    ) {
        override fun toString() = "ProviderPanelRequest(method=$method,path=$path,metadata=<synthetic>,credentials=<redacted>)"
    }
    private data class EditGate(val status: Int, val gate: Gate)
    private data class Canonical(
        val name: String = ORIGINAL_NAME,
        val models: List<String> = listOf("saved-model"),
        val noModels: Boolean = false,
        val off: Boolean = false,
        val revision: String = "a".repeat(64),
    )

    companion object {
        const val ID = "panel-fixture"
        const val ORIGINAL_NAME = "Panel fixture"
        private const val CHAT_URL = "https://api.example.test/v1"
        private const val LIST = "/api/providers"
        private const val EDITABLE = "/api/mobile/providers/editable/$ID"
        private const val EDIT = "/api/mobile/providers/edit"
        private const val MODELS = "/api/mobile/providers/models"
        private const val CAPABILITIES = "/api/mobile/capabilities"
        private const val CAPABILITIES_BODY = """{"schemaVersion":1,"coreVersion":"panel-http-fixture","mobile":false,"androidHost":true,"fullAdmin":true,"loopbackKeys":true,"debugEndpoints":false,"adminLoopbackOnly":true,"nativeAutoUpdate":false,"providerSchemaVersion":1,"providerManagement":true,"providerModelDiscovery":true}"""
    }
}

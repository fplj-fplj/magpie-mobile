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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginRequestLeasesTest {
    private val credential = ManagerCredential.fromKey("a".repeat(32))

    @Test fun invalidateCancelsEveryLeaseAndAllowsFreshSession() {
        val requests = PluginRequestLeases()
        val first = requests.acquire(credential) { true }
        val second = requests.acquire(credential) { true }
        requests.invalidate()
        assertThrows(CancellationException::class.java) { first.control.ensureActive() }
        assertThrows(CancellationException::class.java) { second.client.plugins() }
        val next = requests.acquire(credential) { true }
        next.ensureCurrent()
        next.close()
        first.close()
        second.close()
    }

    @Test fun completedLeaseUnregistersWithoutCancellingSuccessfulControl() {
        val requests = PluginRequestLeases()
        val completed = requests.acquire(credential) { true }
        val running = requests.acquire(credential) { true }
        completed.close()
        completed.close()
        requests.invalidate()
        completed.control.ensureActive()
        assertThrows(CancellationException::class.java) { running.ensureCurrent() }
        assertThrows(CancellationException::class.java) { completed.client.plugins() }
        running.close()
    }

    @Test fun boundClientChecksCapturedIdentityWithoutExplicitLeaseCheck() {
        val requests = PluginRequestLeases()
        var current = true
        val lease = requests.acquire(credential) { current }
        lease.ensureCurrent()
        current = false
        assertThrows(CancellationException::class.java) { lease.client.plugins() }
        assertThrows(CancellationException::class.java) { lease.control.ensureActive() }
        lease.close()
    }

    @Test fun invalidationClosesRealSilentReadAndWriteBeforeHeadersAndDuringBody() {
        for (write in listOf(false, true)) for (sendHeaders in listOf(false, true)) {
            SilentPeer(sendHeaders).use { peer ->
                val requests = PluginRequestLeases()
                val lease = requests.acquire(peer.credential) { true }
                val worker = Executors.newSingleThreadExecutor()
                val result = worker.submit(Callable<Throwable?> {
                    try {
                        if (write) lease.client.add("@magpie-community/test-plugin") else lease.client.plugins()
                        null
                    } catch (failure: Throwable) { failure }
                })
                try {
                    assertTrue(peer.arrived.await(5, TimeUnit.SECONDS))
                    requests.invalidate()
                    val error = result.get(2, TimeUnit.SECONDS)
                    assertTrue(error is CancellationException)
                    assertNull(error?.cause)
                    assertTrue(peer.closed.await(2, TimeUnit.SECONDS))
                    assertEquals(if (write) "POST /api/plugins/add" else "GET /api/plugins", peer.request)
                    assertThrows(CancellationException::class.java) { lease.client.plugins() }
                } finally {
                    requests.invalidate()
                    lease.close()
                    result.cancel(true)
                    worker.shutdownNow()
                    worker.awaitTermination(5, TimeUnit.SECONDS)
                }
            }
        }
    }

    private class SilentPeer(private val sendHeaders: Boolean) : Closeable {
        private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val worker = Executors.newSingleThreadExecutor()
        @Volatile private var socket: Socket? = null
        val credential = ManagerCredential.fromKey("b".repeat(32), listener.localPort)
        val arrived = CountDownLatch(1)
        val closed = CountDownLatch(1)
        @Volatile var request: String? = null
            private set

        init {
            worker.submit {
                try {
                    listener.accept().use { accepted ->
                        socket = accepted
                        accepted.soTimeout = 15_000
                        val input = accepted.getInputStream()
                        val line = readLine(input) ?: return@submit
                        var length = 0
                        while (true) {
                            val header = readLine(input) ?: return@submit
                            if (header.isEmpty()) break
                            if (header.substringBefore(':').equals("Content-Length", true)) {
                                length = header.substringAfter(':').trim().toInt()
                            }
                        }
                        input.readNBytes(length)
                        request = line.substringBeforeLast(' ')
                        if (sendHeaders) {
                            accepted.getOutputStream().apply {
                                write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\nConnection: close\r\n\r\nx".toByteArray(Charsets.US_ASCII))
                                flush()
                            }
                        }
                        arrived.countDown()
                        try { if (input.read() == -1) closed.countDown() } catch (_: SocketException) { closed.countDown() }
                    }
                } catch (_: IOException) { /* Fixture shutdown. */ }
            }
        }

        private fun readLine(input: InputStream): String? {
            val text = StringBuilder()
            while (true) {
                val next = input.read()
                if (next == -1) return null
                if (next == '\n'.code) return text.toString().removeSuffix("\r")
                if (text.length >= 8192) throw IOException("Fixture line exceeded limit")
                text.append(next.toChar())
            }
        }

        override fun close() {
            listener.close()
            runCatching { socket?.close() }
            worker.shutdownNow()
            worker.awaitTermination(5, TimeUnit.SECONDS)
        }
    }
}

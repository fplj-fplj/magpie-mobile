package cc.fplj.magpiemobile.core

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** Real TCP peers make a completed cancellation insufficient unless the socket also closes. */
class ProviderCapabilityCancellationTest {
    @Test fun cancellingDuringCatalogHeadersClosesSocketAndSendsNoChecks() =
        assertSilentRequestCancelled(Phase.CATALOG, sendHeaders = false)

    @Test fun cancellingDuringCatalogBodyClosesSocketAndSendsNoChecks() =
        assertSilentRequestCancelled(Phase.CATALOG, sendHeaders = true)

    @Test fun cancellingDuringOrdinaryHeadersClosesSocketAndSendsNoLaterChecks() =
        assertSilentRequestCancelled(Phase.ORDINARY, sendHeaders = false)

    @Test fun cancellingDuringOrdinaryBodyClosesSocketAndSendsNoLaterChecks() =
        assertSilentRequestCancelled(Phase.ORDINARY, sendHeaders = true)

    @Test fun cancellingDuringStreamHeadersClosesSocketAndSendsNoToolCheck() =
        assertSilentRequestCancelled(Phase.STREAM, sendHeaders = false)

    @Test fun cancellingDuringStreamBodyClosesSocketAndSendsNoToolCheck() =
        assertSilentRequestCancelled(Phase.STREAM, sendHeaders = true)

    @Test fun cancellingDuringToolHeadersClosesSocketAndNeverPublishesItsResult() =
        assertSilentRequestCancelled(Phase.TOOL, sendHeaders = false)

    @Test fun cancellingDuringToolBodyClosesSocketAndNeverPublishesItsResult() =
        assertSilentRequestCancelled(Phase.TOOL, sendHeaders = true)

    @Test
    fun aPrecancelledRunSendsNoCatalogOrChatRequest() {
        SocketGateway().use { gateway ->
            val control = ChatRequestControl().apply { cancel() }
            val progress = CopyOnWriteArrayList<ProviderCapabilityResult>()

            assertFailsWith<CancellationException> {
                ProviderModelCapabilityClient(gateway.config, control)
                    .run("saved", "vendor/model", progress::add)
            }

            assertTrue(gateway.requests.isEmpty())
            assertTrue(progress.isEmpty())
        }
    }

    @Test
    fun cancellingInAnyRunningCallbackSendsNoPendingOrLaterHttpRequest() {
        for (check in ProviderCapabilityCheck.entries) {
            SocketGateway().use { gateway ->
                val control = ChatRequestControl()
                val progress = CopyOnWriteArrayList<ProviderCapabilityResult>()

                assertFailsWith<CancellationException> {
                    ProviderModelCapabilityClient(gateway.config, control).run(
                        "saved", "vendor/model",
                        onProgress = { row ->
                            progress += row
                            if (row.check == check && row.status == ProviderCapabilityStatus.RUNNING) {
                                control.cancel()
                            }
                        },
                    )
                }

                assertEquals(Phase.entries.take(check.ordinal + 1), gateway.requests)
                assertEquals(check, progress.last().check)
                assertEquals(ProviderCapabilityStatus.RUNNING, progress.last().status)
            }
        }
    }

    @Test
    fun invalidOwnerInRunningCallbackSendsNoPendingOrLaterHttpRequest() {
        SocketGateway().use { gateway ->
            var ownerValid = true
            val progress = CopyOnWriteArrayList<ProviderCapabilityResult>()

            assertFailsWith<CancellationException> {
                ProviderModelCapabilityClient(gateway.config).run(
                    "saved", "vendor/model",
                    onProgress = { row ->
                        progress += row
                        if (row.status == ProviderCapabilityStatus.RUNNING) ownerValid = false
                    },
                    ensureActive = {
                        if (!ownerValid) throw CancellationException("Owner is no longer active")
                    },
                )
            }

            assertEquals(listOf(Phase.CATALOG), gateway.requests)
            assertEquals(1, progress.size)
            assertEquals(ProviderCapabilityStatus.RUNNING, progress.single().status)
        }
    }

    @Test
    fun ordinaryPhaseTimeoutClosesOnlyThatSocketAndRemainingChecksStillRun() =
        assertPhaseTimeout(Phase.ORDINARY)

    @Test fun streamPhaseTimeoutClosesOnlyThatSocketAndToolCheckStillRuns() =
        assertPhaseTimeout(Phase.STREAM)

    @Test fun toolPhaseTimeoutKeepsEarlierSuccessfulChecks() = assertPhaseTimeout(Phase.TOOL)

    @Test
    fun catalogPhaseTimeoutReturnsUnavailableAndSendsNoChatChecks() {
        SocketGateway(silentPhase = Phase.CATALOG, sendHeaders = true).use { gateway ->
            val report = ProviderModelCapabilityClient(
                gateway.config,
                maxDuration = Duration.ofSeconds(8),
                requestDuration = Duration.ofSeconds(1),
            ).run("saved", "vendor/model")

            assertEquals(null, report.route)
            assertTrue(report.results.all { it.status == ProviderCapabilityStatus.UNAVAILABLE })
            assertTrue(gateway.silentPeerClosed.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(Phase.CATALOG), gateway.requests)
        }
    }

    private fun assertPhaseTimeout(phase: Phase) {
        SocketGateway(silentPhase = phase, sendHeaders = true).use { gateway ->
            val worker = Executors.newSingleThreadExecutor()
            val progress = CopyOnWriteArrayList<ProviderCapabilityResult>()
            val result = worker.submit(Callable {
                ProviderModelCapabilityClient(
                    gateway.config,
                    maxDuration = Duration.ofSeconds(8),
                    requestDuration = Duration.ofSeconds(1),
                ).run("saved", "vendor/model", progress::add)
            })
            try {
                assertTrue(gateway.silentRequestArrived.await(5, TimeUnit.SECONDS))
                val report = result.get(5, TimeUnit.SECONDS)

                assertTrue(gateway.silentPeerClosed.await(2, TimeUnit.SECONDS))
                assertEquals(Phase.entries, gateway.requests)
                assertEquals(
                    ProviderCapabilityCheck.entries.map {
                        if (it.ordinal == phase.ordinal - 1) ProviderCapabilityStatus.FAILED
                        else ProviderCapabilityStatus.PASSED
                    },
                    report.results.map { it.status },
                )
                val failed = report.results.single { it.status == ProviderCapabilityStatus.FAILED }
                assertEquals("请求超时", failed.safeMessage)
                assertEquals("", failed.preview)
                assertEquals("", failed.reasoning)
                assertEquals(6, progress.size)
            } finally {
                result.cancel(true)
                worker.shutdownNow()
                worker.awaitTermination(5, TimeUnit.SECONDS)
            }
        }
    }

    @Test
    fun overallDeadlineClosesActiveSocketAndSendsNoRemainingChecks() {
        SocketGateway(silentPhase = Phase.ORDINARY, sendHeaders = true).use { gateway ->
            val worker = Executors.newSingleThreadExecutor()
            val progress = CopyOnWriteArrayList<ProviderCapabilityResult>()
            val result = worker.submit(Callable<Throwable?> {
                try {
                    ProviderModelCapabilityClient(
                        gateway.config,
                        maxDuration = Duration.ofSeconds(2),
                        requestDuration = Duration.ofSeconds(10),
                    ).run("saved", "vendor/model", progress::add)
                    null
                } catch (failure: Throwable) { failure }
            })
            try {
                assertTrue(gateway.silentRequestArrived.await(5, TimeUnit.SECONDS))
                val failure = assertIs<StreamingChatException>(result.get(3, TimeUnit.SECONDS))

                assertEquals(StreamingChatFailure.DEADLINE_EXCEEDED, failure.failure)
                assertEquals("Stream deadline exceeded", failure.message)
                assertTrue(gateway.silentPeerClosed.await(2, TimeUnit.SECONDS))
                assertEquals(listOf(Phase.CATALOG, Phase.ORDINARY), gateway.requests)
                assertEquals(1, progress.size)
                assertEquals(ProviderCapabilityStatus.RUNNING, progress.single().status)
            } finally {
                result.cancel(true)
                worker.shutdownNow()
                worker.awaitTermination(5, TimeUnit.SECONDS)
            }
        }
    }

    @Test
    fun overallDeadlineDuringCatalogDoesNotBecomeUnavailableOrSendAnyCheck() {
        SocketGateway(silentPhase = Phase.CATALOG, sendHeaders = false).use { gateway ->
            val progress = CopyOnWriteArrayList<ProviderCapabilityResult>()
            val failure = assertFailsWith<StreamingChatException> {
                ProviderModelCapabilityClient(
                    gateway.config,
                    maxDuration = Duration.ofSeconds(2),
                    requestDuration = Duration.ofSeconds(10),
                ).run("saved", "vendor/model", progress::add)
            }

            assertEquals(StreamingChatFailure.DEADLINE_EXCEEDED, failure.failure)
            assertTrue(gateway.silentPeerClosed.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(Phase.CATALOG), gateway.requests)
            assertTrue(progress.isEmpty())
        }
    }

    @Test
    fun aTimedOutRunDoesNotPoisonDefaultControlsInTheSameClient() {
        SocketGateway(silentPhase = Phase.ORDINARY, sendHeaders = true, silenceOnce = true).use { gateway ->
            val client = ProviderModelCapabilityClient(
                gateway.config,
                maxDuration = Duration.ofSeconds(2),
                requestDuration = Duration.ofSeconds(10),
            )
            assertEquals(StreamingChatFailure.DEADLINE_EXCEEDED,
                assertFailsWith<StreamingChatException> { client.run("saved", "vendor/model") }.failure)

            repeat(2) {
                val report = client.run("saved", "vendor/model")
                assertTrue(report.results.all { it.status == ProviderCapabilityStatus.PASSED })
            }

            assertEquals(listOf(Phase.CATALOG, Phase.ORDINARY) + Phase.entries + Phase.entries,
                gateway.requests)
        }
    }

    private fun assertSilentRequestCancelled(phase: Phase, sendHeaders: Boolean) {
        SocketGateway(silentPhase = phase, sendHeaders = sendHeaders).use { gateway ->
            val control = ChatRequestControl()
            val progress = CopyOnWriteArrayList<ProviderCapabilityResult>()
            val worker = Executors.newSingleThreadExecutor()
            val result = worker.submit(Callable<Throwable?> {
                try {
                    ProviderModelCapabilityClient(gateway.config, control)
                        .run("saved", "vendor/model", progress::add)
                    null
                } catch (failure: Throwable) { failure }
            })
            try {
                assertTrue(gateway.silentRequestArrived.await(5, TimeUnit.SECONDS),
                    "The selected phase must reach its silent TCP peer")

                control.cancel()
                val failure = assertIs<CancellationException>(result.get(2, TimeUnit.SECONDS))

                assertEquals("Chat request cancelled", failure.message)
                assertTrue(gateway.silentPeerClosed.await(2, TimeUnit.SECONDS),
                    "Cancellation must close the peer socket, not merely suppress the result")
                assertEquals(Phase.entries.take(phase.ordinal + 1), gateway.requests)
                assertEquals(phase.ordinal * 2 - if (phase == Phase.CATALOG) 0 else 1, progress.size)
                assertFalse(progress.any {
                    it.status != ProviderCapabilityStatus.RUNNING &&
                        it.check.ordinal >= phase.ordinal - 1
                })
            } finally {
                control.cancel()
                result.cancel(true)
                worker.shutdownNow()
                worker.awaitTermination(5, TimeUnit.SECONDS)
            }
        }
    }

    private enum class Phase { CATALOG, ORDINARY, STREAM, TOOL }

    /** One request per connection; held peers wait for EOF rather than a test-controlled release. */
    private class SocketGateway(
        private val silentPhase: Phase? = null,
        private val sendHeaders: Boolean = false,
        private val silenceOnce: Boolean = false,
    ) : Closeable {
        private val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val worker = Executors.newSingleThreadExecutor()
        private val sockets = ConcurrentHashMap.newKeySet<Socket>()
        private var hasSilenced = false
        val requests = CopyOnWriteArrayList<Phase>()
        val silentRequestArrived = CountDownLatch(1)
        val silentPeerClosed = CountDownLatch(1)
        val config = GatewayConfig(
            gatewayKey = "capability-cancellation-test-key",
            gatewayBaseUrl = "http://127.0.0.1:${listener.localPort}",
            readTimeout = Duration.ofSeconds(15),
        )

        init {
            // Load the common HTTP implementation before measuring short request budgets.
            ChatHttpTransport.client(config)
            worker.submit {
                try {
                    while (!listener.isClosed) {
                        val socket = listener.accept()
                        sockets += socket
                        try {
                            socket.use { serve(it) }
                        } catch (_: IOException) {
                            // Socket cancellation or fixture shutdown can terminate an active peer.
                        } finally {
                            sockets -= socket
                        }
                    }
                } catch (_: IOException) {
                    // Closing the listener stops accept().
                }
            }
        }

        private fun serve(socket: Socket) {
            socket.soTimeout = 15_000
            val input = socket.getInputStream()
            val requestLine = readLine(input) ?: return
            var length = 0
            while (true) {
                val line = readLine(input) ?: return
                if (line.isEmpty()) break
                if (line.substringBefore(':').equals("Content-Length", ignoreCase = true)) {
                    length = line.substringAfter(':').trim().toInt()
                }
            }
            val body = String(input.readNBytes(length), Charsets.UTF_8)
            val phase = when {
                requestLine.startsWith("GET /v1/models ") -> Phase.CATALOG
                body.contains("\"tools\"") -> Phase.TOOL
                body.contains("\"stream\":true") -> Phase.STREAM
                else -> Phase.ORDINARY
            }
            requests += phase
            val output = socket.getOutputStream()
            val type = if (phase == Phase.STREAM) "text/event-stream" else "application/json"
            if (phase == silentPhase && (!silenceOnce || !hasSilenced)) {
                hasSilenced = true
                if (sendHeaders) {
                    output.write(("HTTP/1.1 200 OK\r\nContent-Type: $type\r\n" +
                        "Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n")
                        .toByteArray(Charsets.US_ASCII))
                    output.flush()
                }
                silentRequestArrived.countDown()
                try {
                    if (input.read() == -1) silentPeerClosed.countDown()
                } catch (_: SocketException) {
                    silentPeerClosed.countDown() // A TCP reset also proves that the peer closed.
                }
                return
            }
            val response = when (phase) {
                Phase.CATALOG -> """{"data":[{"id":"saved/vendor/model","owned_by":"saved"}]}"""
                Phase.ORDINARY -> """{"choices":[{"message":{"content":"plain ok"}}]}"""
                Phase.STREAM -> "data: {\"choices\":[{\"delta\":{\"content\":\"stream ok\"}}]}\n\n" +
                    "data: [DONE]\n\n"
                Phase.TOOL -> """{"choices":[{"message":{"content":null,"tool_calls":[{"type":"function","function":{"name":"get_current_time","arguments":"{}"}}]}}]}"""
            }.toByteArray(Charsets.UTF_8)
            output.write(("HTTP/1.1 200 OK\r\nContent-Type: $type\r\n" +
                "Content-Length: ${response.size}\r\nConnection: close\r\n\r\n")
                .toByteArray(Charsets.US_ASCII))
            output.write(response)
            output.flush()
        }

        private fun readLine(input: InputStream): String? {
            val line = StringBuilder()
            while (true) {
                val next = input.read()
                if (next == -1) return null
                if (next == '\n'.code) return line.toString().removeSuffix("\r")
                if (line.length >= 8192) throw IOException("Fixture request line exceeded limit")
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

package cc.fplj.magpiemobile

import cc.fplj.magpiemobile.core.ManagementExecutionUnknown
import cc.fplj.magpiemobile.core.ManagerCredential
import cc.fplj.magpiemobile.core.ProviderCreateDraft
import cc.fplj.magpiemobile.core.ProviderListSnapshot
import cc.fplj.magpiemobile.core.ProviderManagementClient
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the same ticket gate as ProvidersPanel and the actual Activity-owned cache. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProviderExternalRefreshStateTest {
    @Test fun openEditorCoalescesReturnsAndClosingMakesOneFreshReadOfLatestNamesAndModels() = runTest {
        ManagementPeer().use { peer ->
            CacheFixture(StandardTestDispatcher(testScheduler)).use { fixture ->
                var fetches = 0
                val initial = fixture.cache.read(SESSION) { fetches++; peer.client().getProviders() }
                var state = confirmed(SESSION, 0)
                assertEquals("Before external edit", initial.providers.single().name)
                assertEquals(listOf("before-model"), initial.providers.single().chosen)
                assertEquals(1, fetches) // Initial panel GET is separate from return-driven reads.

                for (revision in 1..3) {
                    fixture.cache.invalidate() // Real ON_RESUME order: invalidate, then revision.
                    assertNull(state.automaticTicket(true, SESSION, revision, editorOpen = true, busy = false))
                    assertTrue(state.needsAttempt(SESSION, revision))
                    assertTrue(state.needsConfirmation(SESSION, revision))
                    assertFalse(state.isConfirmed(SESSION, revision))
                }
                runCurrent()
                assertEquals(1, fetches)
                assertEquals(ProviderExternalRefreshTicket(SESSION, 0), state.attempted)
                peer.afterExternalEdit.set(true)
                val ticket = checkNotNull(state.automaticTicket(true, SESSION, 3, editorOpen = false, busy = false))
                assertEquals(3, ticket.revision)
                state = state.readStarted(ticket, SESSION, 3)
                fixture.cache.invalidate()
                val recovery = ProviderWriteRecoveryState()
                val read = recovery.read(fixture.cache, SESSION, 2L, force = true) {
                    fetches++; peer.client().getProviders()
                }
                assertTrue(read.resolve(SESSION, 2L, recovery, editorOpen = false) != null)
                state = state.readConfirmed(ticket, SESSION, 3)

                assertEquals("After external edit", read.snapshot.providers.single().name)
                assertEquals(listOf("after-model"), read.snapshot.providers.single().chosen)
                assertEquals(2, fetches)
                assertEquals(2, peer.requests.count { it == "GET $LIST_PATH" })
                assertTrue(peer.requests.none { it.startsWith("POST ") })
                assertTrue(state.isConfirmed(SESSION, 3))
                assertFalse(state.needsConfirmation(SESSION, 3))
                for (open in listOf(false, true, false)) {
                    for (busy in listOf(false, true, false)) {
                        assertNull(state.automaticTicket(true, SESSION, 3, open, busy))
                    }
                }
                assertSame(read.snapshot, fixture.cache.read(SESSION) { error("Healthy confirmed cache was discarded") })
                assertEquals(2, fetches)
                assertNull(peer.failure.get())
            }
        }
    }

    @Test fun returnDuringBusyListReadRejectsOldGenerationAndDrainsExactlyOneFreshRead() = runTest {
        CacheFixture(StandardTestDispatcher(testScheduler)).use { fixture ->
            val oldReply = CompletableDeferred<ProviderListSnapshot>()
            val oldSnapshot = ProviderListSnapshot()
            val freshSnapshot = ProviderListSnapshot()
            var fetches = 0
            var state = ProviderExternalRefreshState()
            val oldTicket = ProviderExternalRefreshTicket(SESSION, 0)
            state = state.readStarted(oldTicket, SESSION, 0)
            val oldRead = async {
                runCatching { fixture.cache.read(SESSION) { fetches++; oldReply.await() } }
            }
            runCurrent()
            assertEquals(1, fetches)
            fixture.cache.invalidate()
            assertNull(state.automaticTicket(true, SESSION, 1, editorOpen = false, busy = true))
            assertTrue(state.needsAttempt(SESSION, 1))
            oldReply.complete(oldSnapshot)
            runCurrent()
            assertTrue(oldRead.await().exceptionOrNull() is CancellationException)
            assertSame(state, state.readConfirmed(oldTicket, SESSION, 1))

            val ticket = checkNotNull(state.automaticTicket(true, SESSION, 1, false, false))
            state = state.readStarted(ticket, SESSION, 1)
            fixture.cache.invalidate()
            val read = ProviderWriteRecoveryState().read(fixture.cache, SESSION, 2L, force = true) {
                fetches++; freshSnapshot
            }
            state = state.readConfirmed(ticket, SESSION, 1)
            assertSame(freshSnapshot, read.snapshot)
            assertEquals(2, fetches)
            assertTrue(state.isConfirmed(SESSION, 1))
            assertNull(state.automaticTicket(true, SESSION, 1, false, false))
            assertSame(freshSnapshot, fixture.cache.read(SESSION) { error("Old generation poisoned fresh cache") })
        }
    }

    @Test fun higherRevisionsArrivingDuringSupplementalReadRemainPendingAndCoalesceToLatest() = runTest {
        CacheFixture(StandardTestDispatcher(testScheduler)).use { fixture ->
            var state = confirmed(SESSION, 0)
            val firstReply = CompletableDeferred<ProviderListSnapshot>()
            val firstSnapshot = ProviderListSnapshot()
            val latestSnapshot = ProviderListSnapshot()
            var fetches = 0
            fixture.cache.invalidate()
            val firstTicket = checkNotNull(state.automaticTicket(true, SESSION, 1, false, false))
            state = state.readStarted(firstTicket, SESSION, 1)
            val firstRead = async {
                runCatching { fixture.cache.read(SESSION, force = true) { fetches++; firstReply.await() } }
            }
            runCurrent()
            assertEquals(1, fetches)
            for (revision in 2..4) {
                fixture.cache.invalidate()
                assertNull(state.automaticTicket(true, SESSION, revision, false, busy = true))
            }
            firstReply.complete(firstSnapshot)
            runCurrent()
            assertTrue(firstRead.await().exceptionOrNull() is CancellationException)
            assertSame(state, state.readConfirmed(firstTicket, SESSION, 4))
            assertFalse(state.isConfirmed(SESSION, 4))

            val latestTicket = checkNotNull(state.automaticTicket(true, SESSION, 4, false, false))
            assertEquals(4, latestTicket.revision)
            state = state.readStarted(latestTicket, SESSION, 4)
            fixture.cache.invalidate()
            val latest = fixture.cache.read(SESSION, force = true) { fetches++; latestSnapshot }
            state = state.readConfirmed(latestTicket, SESSION, 4)
            assertSame(latestSnapshot, latest)
            assertEquals(2, fetches) // No serial requests for intermediate returns R2/R3.
            assertTrue(state.isConfirmed(SESSION, 4))
            assertNull(state.automaticTicket(true, SESSION, 4, false, false))
        }
    }

    @Test fun failedAutomaticReadDoesNotSpinOrReuseOldHealthyCacheAndExplicitRetryCanConfirm() = runTest {
        CacheFixture(StandardTestDispatcher(testScheduler)).use { fixture ->
            var fetches = 0
            fixture.cache.read(SESSION) { fetches++; ProviderListSnapshot() }
            var state = confirmed(SESSION, 0)
            fixture.cache.invalidate()
            val ticket = checkNotNull(state.automaticTicket(true, SESSION, 1, false, false))
            state = state.readStarted(ticket, SESSION, 1)
            val failed = async {
                runCatching { fixture.cache.read(SESSION, force = true) { fetches++; throw IOException("Synthetic list failure") } }
            }
            runCurrent()
            assertTrue(failed.await().exceptionOrNull() is IOException)
            assertTrue(state.needsConfirmation(SESSION, 1))
            assertFalse(state.isConfirmed(SESSION, 1))
            for (open in listOf(true, false)) {
                for (busy in listOf(true, false)) {
                    assertNull(state.automaticTicket(true, SESSION, 1, open, busy))
                }
            }
            runCurrent()
            assertEquals(2, fetches)
            assertEquals(ProviderExternalRefreshTicket(SESSION, 2),
                state.automaticTicket(true, SESSION, 2, false, false))
            assertEquals(ticket, state.attempted) // Merely querying a newer ticket cannot consume it.
            // An explicit user retry is allowed despite the already-attempted automatic ticket.
            state = state.readStarted(ticket, SESSION, 1)
            val fresh = ProviderListSnapshot()
            assertSame(fresh, fixture.cache.read(SESSION) { fetches++; fresh })
            state = state.readConfirmed(ticket, SESSION, 1)
            assertEquals(3, fetches)
            assertTrue(state.isConfirmed(SESSION, 1))
            assertNull(state.automaticTicket(true, SESSION, 1, false, false))
        }
    }

    @Test fun fileErrorKeepsConfirmationAndRecoveryPendingWithoutAnotherAutomaticAttempt() = runTest {
        CacheFixture(StandardTestDispatcher(testScheduler)).use { fixture ->
            var state = confirmed(SESSION, 0)
            var recovery = ProviderWriteRecoveryState("saved-provider")
            var fetches = 0
            val ticket = checkNotNull(state.automaticTicket(true, SESSION, 1, false, false))
            state = state.readStarted(ticket, SESSION, 1)
            val failed = recovery.read(fixture.cache, SESSION, 1L, force = true) {
                fetches++; ProviderListSnapshot(hasFileError = true)
            }
            val unresolved = checkNotNull(failed.resolve(SESSION, 1L, recovery, editorOpen = true))
            assertTrue(failed.snapshot.hasFileError)
            assertTrue(unresolved.recovery.required)
            assertFalse(unresolved.recoveryChecked)
            assertFalse(unresolved.retireEditor)
            assertTrue(state.needsConfirmation(SESSION, 1))
            assertNull(state.automaticTicket(true, SESSION, 1, false, false))
            runCurrent()
            assertEquals(1, fetches)

            val fresh = ProviderListSnapshot()
            val retry = recovery.read(fixture.cache, SESSION, 2L, force = true) { fetches++; fresh }
            val resolved = checkNotNull(retry.resolve(SESSION, 2L, recovery, editorOpen = true))
            recovery = resolved.recovery
            state = state.readConfirmed(ticket, SESSION, 1)
            assertSame(fresh, retry.snapshot)
            assertTrue(resolved.recoveryChecked)
            assertTrue(resolved.retireEditor)
            assertFalse(recovery.required)
            assertTrue(state.isConfirmed(SESSION, 1))
            assertEquals(2, fetches)
        }
    }

    @Test fun disabledOrMissingSessionDoesNotConsumeAndReadyDrainsLatestReturn() = runTest {
        CacheFixture(StandardTestDispatcher(testScheduler)).use { fixture ->
            var state = ProviderExternalRefreshState()
            var fetches = 0
            for (revision in 1..3) {
                fixture.cache.invalidate()
                assertNull(state.automaticTicket(false, SESSION, revision, false, false))
                assertNull(state.automaticTicket(true, null, revision, false, false))
            }
            runCurrent()
            assertNull(state.attempted)
            assertEquals(0, fetches)
            val ticket = checkNotNull(state.automaticTicket(true, SESSION, 3, false, false))
            state = state.readStarted(ticket, SESSION, 3)
            val fresh = fixture.cache.read(SESSION, force = true) { fetches++; ProviderListSnapshot() }
            state = state.readConfirmed(ticket, SESSION, 3)
            assertFalse(fresh.hasFileError)
            assertEquals(1, fetches)
            assertTrue(state.isConfirmed(SESSION, 3))
            assertNull(state.automaticTicket(true, SESSION, 3, false, false))
        }
    }

    @Test fun replacingOwnerOrEpochWhileReadingCannotConfirmOrConsumeReplacementTicket() = runTest {
        for (replacement in listOf(NEXT_EPOCH, NEXT_OWNER)) {
            CacheFixture(StandardTestDispatcher(testScheduler)).use { fixture ->
                var state = confirmed(SESSION, 0)
                var fetches = 0
                val oldResponse = CompletableDeferred<ProviderListSnapshot>()
                val oldTicket = checkNotNull(state.automaticTicket(true, SESSION, 2, false, false))
                state = state.readStarted(oldTicket, SESSION, 2)
                val old = async {
                    runCatching { fixture.cache.read(SESSION, force = true) { fetches++; oldResponse.await() } }
                }
                runCurrent()
                fixture.cache.invalidate()
                val replacementTicket = checkNotNull(state.automaticTicket(true, replacement, 2, false, false))
                state = state.readStarted(replacementTicket, replacement, 2)
                val newSnapshot = ProviderListSnapshot()
                val fresh = fixture.cache.read(replacement, force = true) { fetches++; newSnapshot }
                state = state.readConfirmed(replacementTicket, replacement, 2)
                val confirmedNew = state
                oldResponse.complete(ProviderListSnapshot())
                runCurrent()
                assertTrue(old.await().exceptionOrNull() is CancellationException)
                assertSame(confirmedNew, state.readStarted(oldTicket, replacement, 2))
                assertSame(confirmedNew, state.readConfirmed(oldTicket, replacement, 2))
                assertNull(state.automaticTicket(true, SESSION, 2, false, false))
                assertSame(newSnapshot, fresh)
                assertTrue(state.isConfirmed(replacement, 2))
                assertFalse(state.isConfirmed(SESSION, 2))
                assertEquals(2, fetches)
                assertSame(newSnapshot, fixture.cache.read(replacement) { error("Old session populated replacement cache") })
            }
        }
    }

    @Test fun pendingTicketMovesToNewSessionWhileClosedGatesNeverAttemptTheOldOwner() = runTest {
        CacheFixture(StandardTestDispatcher(testScheduler)).use { fixture ->
            var state = confirmed(SESSION, 0)
            fixture.cache.invalidate()
            val neverStarted = checkNotNull(state.automaticTicket(true, SESSION, 1, false, false))
            assertNull(state.automaticTicket(true, SESSION, 1, true, false))
            assertNull(state.automaticTicket(true, NEXT_EPOCH, 1, true, false))
            assertSame(state, state.readStarted(neverStarted, NEXT_EPOCH, 1))
            assertSame(state, state.readConfirmed(neverStarted, NEXT_EPOCH, 1))
            val replacement = checkNotNull(state.automaticTicket(true, NEXT_EPOCH, 1, false, false))
            state = state.readStarted(replacement, NEXT_EPOCH, 1)
            var fetches = 0
            fixture.cache.read(NEXT_EPOCH, force = true) { fetches++; ProviderListSnapshot() }
            state = state.readConfirmed(replacement, NEXT_EPOCH, 1)
            assertEquals(1, fetches)
            assertTrue(state.isConfirmed(NEXT_EPOCH, 1))
            assertNull(state.automaticTicket(true, NEXT_EPOCH, 1, false, false))
        }
    }

    @Test fun sameRevisionRetryStillUsesActualPanelSequenceAndRecoveryFences() = runTest {
        CacheFixture(StandardTestDispatcher(testScheduler)).use { fixture ->
            var state = ProviderExternalRefreshState()
            val ticket = ProviderExternalRefreshTicket(SESSION, 1)
            val recovery = ProviderWriteRecoveryState()
            state = state.readStarted(ticket, SESSION, 1)
            val old = recovery.read(fixture.cache, SESSION, 10L, force = true) { ProviderListSnapshot() }
            val newer = recovery.read(fixture.cache, SESSION, 11L, force = true) { ProviderListSnapshot() }
            assertNull(old.resolve(SESSION, 11L, recovery, editorOpen = false))
            assertNull(newer.resolve(SESSION, 11L, ProviderWriteRecoveryState("newer-save"), editorOpen = false))
            assertTrue(newer.resolve(SESSION, 11L, recovery, editorOpen = false) != null)
            assertFalse(state.isConfirmed(SESSION, 1))
            state = state.readConfirmed(ticket, SESSION, 1)
            assertTrue(state.isConfirmed(SESSION, 1))
            assertFalse(state.needsAttempt(SESSION, 1))
        }
    }

    @Test fun revisionZeroLoadsNormallyButNeverCreatesAnAutomaticReturnLoop() = runTest {
        CacheFixture(StandardTestDispatcher(testScheduler)).use { fixture ->
            var state = ProviderExternalRefreshState()
            assertNull(state.automaticTicket(true, SESSION, 0, false, false))
            assertFalse(state.isConfirmed(SESSION, 0))
            val initialTicket = ProviderExternalRefreshTicket(SESSION, 0)
            state = state.readStarted(initialTicket, SESSION, 0)
            var fetches = 0
            fixture.cache.read(SESSION) { fetches++; ProviderListSnapshot() }
            state = state.readConfirmed(initialTicket, SESSION, 0)
            assertTrue(state.isConfirmed(SESSION, 0))
            assertFalse(state.needsConfirmation(SESSION, 0))
            assertNull(state.automaticTicket(true, SESSION, 0, false, false))
            assertEquals(1, fetches)
        }
    }

    @Test fun externalRecoveryWaitsForUnknownPostWorkerExitAndNeverReplaysTheWrite() = runBlocking {
        ManagementPeer().use { peer ->
            CacheFixture(Dispatchers.Unconfined).use { fixture ->
                val leases = ProviderRequestLeases()
                val credential = ManagerCredential.fromKey("provider-external-refresh-synthetic-key", peer.port)
                val acquire: (ProviderSessionKey) -> ProviderRequestLease = { leases.acquire(credential) { true } }
                val writerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val controller = ProviderOperationController(writerScope, acquire)
                val recovery = AtomicReference(ProviderWriteRecoveryState())
                val transportUnwinding = CountDownLatch(1)
                val releaseTransport = CountDownLatch(1)
                val transportExited = AtomicBoolean()
                val unknown = AtomicBoolean()
                var state = confirmed(SESSION, 0)
                try {
                    fixture.cache.read(SESSION) { withProviderRequest(SESSION, acquireLease = acquire) { it.getProviders() } }
                    val writeJob = controller.launch(SESSION) {
                        try {
                            write(fixture.cache, SESSION, onPending = { recovery.set(ProviderWriteRecoveryState("saved-provider")) }) { client ->
                                val draft = ProviderCreateDraft("saved-provider", "Saved provider", "https://api.example/v1",
                                    "synthetic-provider-draft-key", listOf("after-model"))
                                try { client.create(draft) }
                                finally {
                                    draft.clearSecret()
                                    transportUnwinding.countDown()
                                    check(releaseTransport.await(10, TimeUnit.SECONDS))
                                    transportExited.set(true)
                                }
                            }
                        } catch (_: ManagementExecutionUnknown) { unknown.set(true) }
                    }
                    assertTrue(transportUnwinding.await(5, TimeUnit.SECONDS))
                    assertTrue(recovery.get().required)
                    assertFalse(transportExited.get())
                    fixture.cache.invalidate()
                    val ticket = checkNotNull(state.automaticTicket(true, SESSION, 1, false, false))
                    state = state.readStarted(ticket, SESSION, 1)
                    val recoveryEntered = CompletableDeferred<Unit>()
                    val refreshed = async {
                        recoveryEntered.complete(Unit)
                        recovery.get().read(fixture.cache, SESSION, 4L, force = true) {
                            assertTrue("GET started before the actual POST worker exited", transportExited.get())
                            withProviderRequest(SESSION, acquireLease = acquire) { it.getProviders() }
                        }
                    }
                    recoveryEntered.await()
                    assertFalse(refreshed.isCompleted)
                    assertEquals(1, peer.requests.count { it == "GET $LIST_PATH" })
                    assertEquals(1, peer.requests.count { it == "POST $CREATE_PATH" })
                    assertNull(state.automaticTicket(true, SESSION, 1, false, false))

                    releaseTransport.countDown()
                    withTimeout(5000) { writeJob.join() }
                    val read = withTimeout(5000) { refreshed.await() }
                    val resolution = checkNotNull(read.resolve(SESSION, 4L, recovery.get(), editorOpen = true))
                    assertTrue(unknown.get())
                    assertTrue(transportExited.get())
                    assertTrue(resolution.recoveryChecked)
                    assertTrue(resolution.retireEditor)
                    assertFalse(resolution.recovery.required)
                    assertEquals("After external edit", read.snapshot.providers.single().name)
                    state = state.readConfirmed(ticket, SESSION, 1)
                    assertTrue(state.isConfirmed(SESSION, 1))
                    assertNull(state.automaticTicket(true, SESSION, 1, false, false))
                    assertEquals(2, peer.requests.count { it == "GET $LIST_PATH" })
                    assertEquals(1, peer.requests.count { it == "POST $CREATE_PATH" })
                    assertNull(peer.failure.get())
                } finally {
                    releaseTransport.countDown()
                    writerScope.cancel()
                }
            }
        }
    }

    private fun confirmed(session: ProviderSessionKey, revision: Int): ProviderExternalRefreshState {
        val ticket = ProviderExternalRefreshTicket(session, revision)
        return ProviderExternalRefreshState().readStarted(ticket, session, revision).readConfirmed(ticket, session, revision)
    }

    private class CacheFixture(dispatcher: CoroutineDispatcher) : AutoCloseable {
        private val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val cache = ProviderListCacheViewModel(scope, dispatcher)
        override fun close() { scope.cancel() }
    }

    /** Actual authenticated management transport; a 503 write leaves its result unknown. */
    private class ManagementPeer : AutoCloseable {
        private val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        @Volatile private var active: Socket? = null
        val port = listener.localPort
        val requests = CopyOnWriteArrayList<String>()
        val afterExternalEdit = AtomicBoolean()
        val failure = AtomicReference<Throwable?>()
        private val worker = thread(isDaemon = true, name = "provider-external-return-fixture") {
            try {
                while (!listener.isClosed) listener.accept().use { socket ->
                    active = socket
                    socket.soTimeout = 10_000
                    val input = socket.getInputStream().buffered()
                    val headers = StringBuilder()
                    while (!headers.endsWith("\r\n\r\n")) {
                        val next = input.read()
                        if (next < 0) return@use
                        check(headers.length < 32768)
                        headers.append(next.toChar())
                    }
                    val route = headers.toString().substringBefore("\r\n").split(' ').take(2).joinToString(" ")
                    val length = headers.lineSequence().firstOrNull { it.startsWith("Content-Length:", true) }
                        ?.substringAfter(':')?.trim()?.toInt() ?: 0
                    repeat(length) { check(input.read() >= 0) }
                    check(headers.lineSequence().any { it.startsWith("Cookie:", true) })
                    requests.add(route)
                    val status: String
                    val body: String
                    when (route) {
                        "GET $LIST_PATH" -> {
                            status = "200 OK"
                            val name = if (afterExternalEdit.get()) "After external edit" else "Before external edit"
                            val model = if (afterExternalEdit.get()) "after-model" else "before-model"
                            body = """{"providers":[{"id":"saved-provider","name":"$name","chat":"https://api.example/v1","key":{"set":true,"optional":false},"models":[{"id":"$model","name":"$model","on":true}],"chosen":["$model"],"ready":true}]}"""
                        }
                        "GET /api/mobile/capabilities" -> {
                            status = "200 OK"
                            body = """{"schemaVersion":1,"coreVersion":"external-refresh-test","mobile":true,"loopbackKeys":true,"debugEndpoints":false,"adminLoopbackOnly":true,"nativeAutoUpdate":false,"providerSchemaVersion":1,"providerManagement":true,"providerModelDiscovery":true}"""
                        }
                        "POST $CREATE_PATH" -> {
                            afterExternalEdit.set(true)
                            status = "503 Service Unavailable"
                            body = "{}"
                        }
                        else -> error("Unexpected management route: $route")
                    }
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    socket.getOutputStream().apply {
                        write(("HTTP/1.1 $status\r\nContent-Type: application/json\r\n" +
                            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                        write(bytes)
                        flush()
                    }
                    active = null
                }
            } catch (error: Throwable) {
                if (!listener.isClosed) failure.set(error)
            }
        }

        fun client() = ProviderManagementClient(ManagerCredential.fromKey("provider-external-refresh-synthetic-key", port),
            baseUrl = "http://127.0.0.1:$port")

        override fun close() {
            listener.close()
            active?.close()
            worker.join(5000)
        }
    }

    private companion object {
        val SESSION = ProviderSessionKey(31L, 1L)
        val NEXT_EPOCH = ProviderSessionKey(31L, 2L)
        val NEXT_OWNER = ProviderSessionKey(32L, 1L)
        const val LIST_PATH = "/api/providers"
        const val CREATE_PATH = "/api/mobile/providers/create"
    }
}

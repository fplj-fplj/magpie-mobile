package cc.fplj.magpiemobile

import cc.fplj.magpiemobile.core.ChatRequestControl
import cc.fplj.magpiemobile.core.ManagerCredential
import cc.fplj.magpiemobile.core.ProviderCreateDraft
import cc.fplj.magpiemobile.core.ProviderEditDraft
import cc.fplj.magpiemobile.core.ProviderManagementClient
import cc.fplj.magpiemobile.core.ProviderModelDiscoveryRequest
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderOperationControllerTest {
    @Test fun cancellationBeforeBodyStartsFinishesBusyStateAndReleasesTheLeaseExactlyOnce() = runBlocking {
        assertPrestartCleanup(alreadyDisposed = false)
    }

    @Test fun launchingInAnAlreadyDisposedScopeFinishesBusyStateWithoutStartingHttp() = runBlocking {
        assertPrestartCleanup(alreadyDisposed = true)
    }

    @Test fun invalidatingTheSameOwnerBeforeUiDispatchStillFinishesItsBusyOperation() = runBlocking {
        ManagementPeer().use { peer ->
            val source = LeaseSource(peer)
            val dispatcher = StandardTestDispatcher()
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val controller = ProviderOperationController(scope, source::acquire, dispatcher)
            val finished = AtomicInteger()
            val entered = AtomicInteger()
            try {
                val job = controller.launch(SESSION_A, onFinished = { finished.incrementAndGet() }) {
                    entered.incrementAndGet()
                    request { it.getProviders() }
                }
                // The owner Long is unchanged across suspend/reconnect, but its old lease expires.
                source.changeSession(SESSION_A_NEXT)
                dispatcher.scheduler.runCurrent()
                withTimeout(5000) { job.join() }

                assertTrue(job.isCancelled)
                assertEquals(0, entered.get())
                assertEquals(1, finished.get())
                assertTrue(peer.requests.isEmpty())
                assertReleasedOnce(source)
            } finally {
                scope.cancel()
                dispatcher.scheduler.runCurrent()
            }
        }
    }

    @Test fun replacingOwnerWhileIoIsQueuedCannotSendTheOldWriteToEitherSession() = runBlocking {
        assertQueuedReplacement(SESSION_B)
    }

    @Test fun replacingEpochWhileIoIsQueuedCannotSendTheOldWriteToEitherSession() = runBlocking {
        assertQueuedReplacement(SESSION_A_NEXT)
    }

    private suspend fun assertQueuedReplacement(replacement: ProviderSessionKey) {
        ManagementPeer().use { first ->
            ManagementPeer().use { second ->
                val ui = StandardTestDispatcher()
                val io = StandardTestDispatcher()
                val scope = CoroutineScope(SupervisorJob() + ui)
                val source = LeaseSource(first, second)
                val controller = ProviderOperationController(scope, source::acquire, io)
                val completed = AtomicInteger()
                try {
                    val job = controller.launch(SESSION_A) {
                        request { client ->
                            val draft = createDraft()
                            try { client.create(draft) } finally { draft.clearSecret() }
                        }
                        completed.incrementAndGet()
                    }
                    assertEquals(1, source.leases.size) // Capture happened synchronously.
                    ui.scheduler.runCurrent() // The request is now queued on the IO dispatcher.
                    source.changeSession(replacement)
                    withTimeout(5000) {
                        // The real IO cancellation watcher can enqueue cleanup after either pump.
                        while (!job.isCompleted) {
                            io.scheduler.runCurrent()
                            ui.scheduler.runCurrent()
                            yield()
                        }
                        job.join()
                    }

                    assertTrue(job.isCancelled)
                    assertEquals(0, completed.get())
                    assertTrue(first.requests.isEmpty())
                    assertTrue(second.requests.isEmpty())
                    assertReleasedOnce(source)
                } finally {
                    scope.cancel()
                    io.scheduler.runCurrent()
                    ui.scheduler.runCurrent()
                }
            }
        }
    }

    @Test fun changingOrClearingPinWhileCatalogIoIsQueuedCannotDispatchTheOldRequest() = runBlocking {
        for (nextPin in listOf("", "cd".repeat(32))) {
            assertQueuedCatalogPinChange(nextPin, restoreBeforeIo = false)
        }
    }

    @Test fun restoringPinBeforeCatalogIoStartsCannotReviveTheOldSequence() = runBlocking {
        for (nextPin in listOf("", "cd".repeat(32))) {
            assertQueuedCatalogPinChange(nextPin, restoreBeforeIo = true)
        }
    }

    private suspend fun assertQueuedCatalogPinChange(nextPin: String, restoreBeforeIo: Boolean) {
        ManagementPeer().use { peer ->
            val ui = StandardTestDispatcher()
            val io = StandardTestDispatcher()
            val scope = CoroutineScope(SupervisorJob() + ui)
            val source = LeaseSource(peer)
            val originalPin = "ab".repeat(32)
            val currentPin = AtomicReference(originalPin)
            val sequence = AtomicLong(7L)
            val workerEntries = AtomicInteger()
            val clientCalls = AtomicInteger()
            val base = "https://api.example/v1"
            val revision = "a".repeat(64)

            fun requestIsCurrent(requestedSequence: Long, requestedPin: String): Boolean =
                ProviderCatalogSyncPolicy.requestIsCurrent(
                    requestedSequence = requestedSequence,
                    currentSequence = sequence.get(),
                    requestedCanonicalBaseUrl = base,
                    currentBaseUrl = base,
                    requestedKeyFingerprint = "same-form-key",
                    currentKeyFingerprint = "same-form-key",
                    requestedLocalHttp = false,
                    currentLocalHttp = false,
                    requestedServerPin = requestedPin,
                    currentServerPin = currentPin.get(),
                    requestedProviderId = "saved",
                    currentProviderId = "saved",
                    requestedRevision = revision,
                    currentRevision = revision,
                    editorOpen = true,
                    sessionCurrent = true,
                )

            fun readCatalog(requestedSequence: Long, requestedPin: String) = scope.async {
                // Match both production boundaries: the caller check and the
                // second check inside the real helper's queued IO action.
                check(requestIsCurrent(requestedSequence, requestedPin))
                withProviderRequest(SESSION_A, source::acquire, io) { client ->
                    workerEntries.incrementAndGet()
                    if (!requestIsCurrent(requestedSequence, requestedPin)) {
                        throw CancellationException("Model directory draft changed")
                    }
                    val discovery = ProviderModelDiscoveryRequest(
                        base, "queued-catalog-synthetic-key", "saved", revision,
                        serverPin = requestedPin,
                    )
                    clientCalls.incrementAndGet()
                    try { client.discoverModels(discovery) } finally { discovery.clearSecret() }
                }
            }

            try {
                val oldRead = readCatalog(sequence.get(), originalPin)
                ui.scheduler.runCurrent() // Capture the real lease, leaving its IO body queued.
                assertEquals(1, source.leases.size)
                assertEquals(0, workerEntries.get())
                assertFalse(oldRead.isCompleted)

                // These are the synchronous input-event mutations. Deliberately
                // do not cancel/recompose the effect or change the core session.
                sequence.incrementAndGet(); currentPin.set(nextPin)
                if (restoreBeforeIo) {
                    sequence.incrementAndGet(); currentPin.set(originalPin)
                }
                assertFalse(oldRead.isCancelled)
                withTimeout(5000) {
                    while (!oldRead.isCompleted) {
                        io.scheduler.runCurrent(); ui.scheduler.runCurrent(); yield()
                    }
                    oldRead.join()
                }

                assertTrue(oldRead.isCancelled)
                assertEquals(1, workerEntries.get())
                assertEquals(0, clientCalls.get())
                assertTrue("Stale pin dispatched capability or directory HTTP", peer.requests.isEmpty())
                assertReleasedOnce(source)
                assertCancelled(source.leases.single().control)

                // A fresh request on the same unchanged core session must still
                // reach the actual management peer using the replacement pin.
                val expectedPin = currentPin.get()
                val replacement = readCatalog(sequence.get(), expectedPin)
                ui.scheduler.runCurrent()
                assertEquals(2, source.leases.size)
                val catalog = withTimeout(5000) {
                    while (!replacement.isCompleted) {
                        io.scheduler.runCurrent(); ui.scheduler.runCurrent(); yield()
                    }
                    replacement.await()
                }
                assertEquals(listOf("test-model"), catalog.models.map { it.id })
                assertEquals(base, catalog.baseUrl)
                assertEquals(2, workerEntries.get())
                assertEquals(1, clientCalls.get())
                assertEquals(listOf(CAPABILITIES_PATH, DISCOVERY_PATH), peer.requests.map { it.path })
                assertEquals(expectedPin, peer.requests.single { it.path == DISCOVERY_PATH }.serverPin)
                assertTrue(peer.workerFailure.get()?.toString(), peer.workerFailure.get() == null)
                assertReleasedOnce(source)
                source.leases.last().control.ensureActive()
            } finally {
                scope.cancel()
                io.scheduler.runCurrent()
                ui.scheduler.runCurrent()
            }
        }
    }

    @Test fun disposingTheCallerClosesSilentListHeaders() = runBlocking {
        assertSilentCancellation(LIST_PATH, sendHeaders = false, replaceOwner = false)
    }

    @Test fun disposingTheCallerClosesSilentEditableBody() = runBlocking {
        assertSilentCancellation(EDITABLE_PATH, sendHeaders = true, replaceOwner = false)
    }

    @Test fun replacingOwnerClosesSilentDiscoveryAndDoesNotUseNewCredentials() = runBlocking {
        assertSilentCancellation(DISCOVERY_PATH, sendHeaders = true, replaceOwner = true)
    }

    @Test fun replacingOwnerDuringCapabilityNegotiationPreventsThePendingCreatePost() = runBlocking {
        assertSilentCancellation(CAPABILITIES_PATH, sendHeaders = false, replaceOwner = true)
    }

    @Test fun disposingTheCallerClosesSilentCreateHeadersAndDoesNotReplay() = runBlocking {
        assertSilentCancellation(CREATE_PATH, sendHeaders = false, replaceOwner = false)
    }

    @Test fun replacingOwnerClosesSilentEditBodyAndDoesNotReplay() = runBlocking {
        assertSilentCancellation(EDIT_PATH, sendHeaders = true, replaceOwner = true)
    }

    @Test fun vmReadSurvivesRotationAndKeepsItsLeaseUntilActualFetchCompletes() = runBlocking {
        ManagementPeer(heldPath = LIST_PATH).use { peer ->
            val source = LeaseSource(peer)
            val vmScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val oldUi = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val newUi = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val cache = ProviderListCacheViewModel(vmScope, Dispatchers.IO)
            try {
                val first = oldUi.async {
                    cache.read(SESSION_A) {
                        withProviderRequest(SESSION_A, source::acquire) { it.getProviders() }
                    }
                }
                assertTrue(peer.requestHeld.await(5, TimeUnit.SECONDS))
                first.cancelAndJoin()
                oldUi.cancel()
                assertEquals(0, source.leases.single().closeCount.get())
                source.leases.single().control.ensureActive()

                val secondEntered = CompletableDeferred<Unit>()
                val second = newUi.async {
                    secondEntered.complete(Unit)
                    cache.read(SESSION_A) {
                        withProviderRequest(SESSION_A, source::acquire) { it.getProviders() }
                    }
                }
                secondEntered.await()
                peer.releaseResponse.countDown()
                val snapshot = withTimeout(5000) { second.await() }

                assertFalse(snapshot.hasFileError)
                assertEquals(listOf(LIST_PATH), peer.requests.map { it.path })
                assertReleasedOnce(source)
                source.leases.single().control.ensureActive()
            } finally {
                peer.releaseResponse.countDown()
                oldUi.cancel()
                newUi.cancel()
                vmScope.cancel()
            }
        }
    }

    @Test fun rotatedRecoveryWaitsForActualWriteExitAndPerformsANewGet() = runBlocking {
        ManagementPeer(silentPath = CREATE_PATH, sendHeaders = true).use { peer ->
            val source = LeaseSource(peer)
            val vmScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val oldUi = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val cache = ProviderListCacheViewModel(vmScope, Dispatchers.IO)
            val pending = AtomicBoolean()
            val requestExited = AtomicBoolean()
            val success = AtomicInteger()
            val controller = ProviderOperationController(oldUi, source::acquire)
            try {
                cache.read(SESSION_A) {
                    withProviderRequest(SESSION_A, source::acquire) { it.getProviders() }
                }
                val writeJob = controller.launch(SESSION_A) {
                    write(cache, SESSION_A, onPending = { pending.set(true) }) { client ->
                        val draft = createDraft()
                        try { client.create(draft) } finally {
                            requestExited.set(true)
                            draft.clearSecret()
                        }
                    }
                    pending.set(false)
                    success.incrementAndGet()
                }
                assertTrue(peer.silentArrived.await(5, TimeUnit.SECONDS))
                assertTrue(pending.get())
                assertFalse(requestExited.get())
                val recoveryEntered = CompletableDeferred<Unit>()
                val recovery = vmScope.async {
                    recoveryEntered.complete(Unit)
                    cache.readForRecovery(SESSION_A) {
                        assertTrue("A recovery GET ran before the write transport exited", requestExited.get())
                        withProviderRequest(SESSION_A, source::acquire) { it.getProviders() }
                    }
                }
                recoveryEntered.await()
                // The old healthy snapshot cannot confirm the pending POST.
                assertFalse(recovery.isCompleted)
                assertEquals(1, peer.requests.count { it.path == LIST_PATH })

                oldUi.cancel()
                withTimeout(5000) { writeJob.join() }
                assertTrue(peer.peerClosed.await(5, TimeUnit.SECONDS))
                val refreshed = withTimeout(5000) { recovery.await() }

                assertFalse(refreshed.hasFileError)
                assertTrue(pending.get()) // Cancellation never claims a confirmed save.
                assertEquals(0, success.get())
                assertEquals(1, peer.requests.count { it.path == CREATE_PATH })
                assertEquals(2, peer.requests.count { it.path == LIST_PATH })
                assertReleasedOnce(source)
            } finally {
                oldUi.cancel()
                vmScope.cancel()
                peer.close()
            }
        }
    }

    @Test fun newEpochRecoveryRetiresAnUnknownCreateOnlyAfterItsOldTransportExits() = runBlocking {
        assertNewEpochWriteRecovery(edit = false)
    }

    @Test fun newEpochRecoveryRetiresAnUnknownEditOnlyAfterItsOldTransportExits() = runBlocking {
        assertNewEpochWriteRecovery(edit = true)
    }

    @Test fun cancelFromPendingMarkerFinishesBarrierWithoutSendingAnyHttp() = runBlocking {
        ManagementPeer().use { peer ->
            val source = LeaseSource(peer)
            val vmScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val cache = ProviderListCacheViewModel(vmScope, Dispatchers.IO)
            val controller = ProviderOperationController(uiScope, source::acquire)
            val pending = AtomicBoolean()
            try {
                val job = controller.launch(SESSION_A) {
                    write(cache, SESSION_A, onPending = {
                        pending.set(true)
                        uiScope.cancel()
                    }) { client -> client.getProviders() }
                }
                withTimeout(5000) { job.join() }
                assertTrue(job.isCancelled)
                assertTrue(pending.get())
                assertTrue(peer.requests.isEmpty())

                val snapshot = withTimeout(5000) {
                    cache.readForRecovery(SESSION_A) {
                        withProviderRequest(SESSION_A, source::acquire) { it.getProviders() }
                    }
                }
                assertFalse(snapshot.hasFileError)
                assertEquals(listOf(LIST_PATH), peer.requests.map { it.path })
                assertReleasedOnce(source)
            } finally {
                uiScope.cancel()
                vmScope.cancel()
            }
        }
    }

    @Test fun cacheFetchWithAnOldExpectedOwnerCannotAcquireTheReplacementCredentials() = runBlocking {
        assertQueuedFetchReplacement(SESSION_B)
    }

    @Test fun cacheFetchWithAnOldExpectedEpochCannotAcquireTheReplacementCredentials() = runBlocking {
        assertQueuedFetchReplacement(SESSION_A_NEXT)
    }

    private suspend fun assertQueuedFetchReplacement(replacement: ProviderSessionKey) {
        ManagementPeer().use { first ->
            ManagementPeer().use { second ->
                val source = LeaseSource(first, second)
                val io = StandardTestDispatcher()
                val ui = StandardTestDispatcher()
                val vmScope = CoroutineScope(SupervisorJob() + io)
                val uiScope = CoroutineScope(SupervisorJob() + ui)
                val cache = ProviderListCacheViewModel(vmScope, io)
                val read = uiScope.async {
                    cache.read(SESSION_A) {
                        withProviderRequest(SESSION_A, source::acquire) { it.getProviders() }
                    }
                }
                try {
                    ui.scheduler.runCurrent() // Register the VM fetch, without running its IO body.
                    source.changeSession(replacement)
                    io.scheduler.runCurrent()
                    ui.scheduler.runCurrent()
                    withTimeout(5000) { read.join() }
                    assertTrue("Old expected session read unexpectedly succeeded", read.isCancelled)
                    assertTrue(source.leases.isEmpty())
                    assertTrue(first.requests.isEmpty())
                    assertTrue(second.requests.isEmpty())
                } finally {
                    uiScope.cancel()
                    vmScope.cancel()
                    io.scheduler.runCurrent()
                    ui.scheduler.runCurrent()
                }
            }
        }
    }

    private suspend fun assertPrestartCleanup(alreadyDisposed: Boolean) {
        ManagementPeer().use { peer ->
            val source = LeaseSource(peer)
            val dispatcher = StandardTestDispatcher()
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val controller = ProviderOperationController(scope, source::acquire, dispatcher)
            val busy = AtomicBoolean(true)
            val finished = AtomicInteger()
            val entered = AtomicInteger()
            val releasesAtFinish = AtomicInteger(-1)
            try {
                if (alreadyDisposed) scope.cancel()
                val job = controller.launch(SESSION_A, onFinished = {
                    releasesAtFinish.set(source.leases.single().closeCount.get())
                    busy.set(false)
                    finished.incrementAndGet()
                }) {
                    entered.incrementAndGet()
                    request { it.getProviders() }
                }
                if (!alreadyDisposed) job.cancel()
                dispatcher.scheduler.runCurrent()
                withTimeout(5000) { job.join() }

                assertTrue(job.isCancelled)
                assertEquals(0, entered.get())
                assertFalse(busy.get())
                assertEquals(1, finished.get())
                assertEquals(1, releasesAtFinish.get())
                assertTrue(peer.requests.isEmpty())
                assertReleasedOnce(source)
                assertCancelled(source.leases.single().control)
                job.cancel()
                dispatcher.scheduler.runCurrent()
                assertEquals(1, finished.get())
            } finally {
                scope.cancel()
                dispatcher.scheduler.runCurrent()
            }
        }
    }

    private suspend fun assertNewEpochWriteRecovery(edit: Boolean) {
        val writePath = if (edit) EDIT_PATH else CREATE_PATH
        ManagementPeer(silentPath = writePath, sendHeaders = true).use { oldPeer ->
            ManagementPeer().use { newPeer ->
                val source = LeaseSource(oldPeer, newPeer)
                val recoveryDispatcher = StandardTestDispatcher()
                val vmScope = CoroutineScope(SupervisorJob() + recoveryDispatcher)
                val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val cache = ProviderListCacheViewModel(vmScope, recoveryDispatcher)
                val controller = ProviderOperationController(uiScope, source::acquire)
                val marker = AtomicReference(ProviderWriteRecoveryState())
                val transportUnwinding = CountDownLatch(1)
                val releaseTransportExit = CountDownLatch(1)
                val transportExited = AtomicBoolean()
                val completed = AtomicInteger()
                try {
                    val initial = if (edit) withProviderRequest(SESSION_A, source::acquire) {
                        it.editable("saved")
                    } else null
                    val writeJob = controller.launch(SESSION_A) {
                        write(cache, SESSION_A, onPending = {
                            marker.set(ProviderWriteRecoveryState(if (edit) "saved" else "new-provider"))
                        }) { client ->
                            try {
                                if (initial == null) {
                                    val draft = createDraft()
                                    try { client.create(draft) } finally { draft.clearSecret() }
                                } else {
                                    val draft = ProviderEditDraft(initial, name = "Renamed provider")
                                    try { client.edit(draft) } finally { draft.clearSecret() }
                                }
                            } finally {
                                transportUnwinding.countDown()
                                check(releaseTransportExit.await(5, TimeUnit.SECONDS))
                                transportExited.set(true)
                            }
                        }
                        completed.incrementAndGet()
                    }
                    assertTrue(oldPeer.silentArrived.await(5, TimeUnit.SECONDS))
                    source.changeSession(SESSION_A_NEXT)
                    assertTrue(oldPeer.peerClosed.await(5, TimeUnit.SECONDS))
                    assertTrue(transportUnwinding.await(5, TimeUnit.SECONDS))
                    val recovered = vmScope.async {
                        marker.get().read(cache, SESSION_A_NEXT, sequence = 9L) {
                            assertTrue("New epoch GET preceded old write exit", transportExited.get())
                            withProviderRequest(SESSION_A_NEXT, source::acquire) { it.getProviders() }
                        }
                    }
                    // Drain the recovery observer and any incorrectly dispatched cache fetch.
                    recoveryDispatcher.scheduler.runCurrent()
                    assertFalse(recovered.isCompleted)
                    assertTrue(newPeer.requests.isEmpty())
                    assertTrue(marker.get().required)

                    releaseTransportExit.countDown()
                    withTimeout(5000) { writeJob.join() }
                    val read = withTimeout(5000) {
                        while (!recovered.isCompleted) {
                            recoveryDispatcher.scheduler.runCurrent()
                            yield()
                        }
                        recovered.await()
                    }
                    val resolution = checkNotNull(read.resolve(SESSION_A_NEXT, 9L, marker.get(), editorOpen = true))
                    assertTrue(resolution.recoveryChecked)
                    assertTrue(resolution.retireEditor)
                    assertFalse(resolution.recovery.required)
                    assertTrue(writeJob.isCancelled)
                    assertEquals(0, completed.get())
                    assertEquals(1, oldPeer.requests.count { it.path == writePath })
                    assertEquals(0, oldPeer.requests.count { it.path == LIST_PATH })
                    assertEquals(listOf(LIST_PATH), newPeer.requests.map { it.path })
                    assertReleasedOnce(source)
                } finally {
                    releaseTransportExit.countDown()
                    uiScope.cancel()
                    vmScope.cancel()
                    recoveryDispatcher.scheduler.runCurrent()
                }
            }
        }
    }

    private suspend fun assertSilentCancellation(path: String, sendHeaders: Boolean, replaceOwner: Boolean) {
        ManagementPeer(silentPath = path, sendHeaders = sendHeaders).use { first ->
            ManagementPeer().use { second ->
                val source = LeaseSource(first, second)
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val controller = ProviderOperationController(scope, source::acquire)
                val completed = AtomicInteger()
                val failures = CopyOnWriteArrayList<Exception>()
                val job = controller.launch(SESSION_A) {
                    try {
                        request { client ->
                            when (path) {
                                LIST_PATH -> client.getProviders()
                                EDITABLE_PATH -> client.editable("saved")
                                DISCOVERY_PATH -> {
                                    val discovery = ProviderModelDiscoveryRequest("https://api.example/v1")
                                    try { client.discoverModels(discovery) } finally { discovery.clearSecret() }
                                }
                                EDIT_PATH -> {
                                    val snapshot = client.editable("saved")
                                    val draft = ProviderEditDraft(snapshot, name = "Renamed provider")
                                    try { client.edit(draft) } finally { draft.clearSecret() }
                                }
                                else -> {
                                    val draft = createDraft()
                                    try { client.create(draft) } finally { draft.clearSecret() }
                                }
                            }
                        }
                        completed.incrementAndGet()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) { failures += failure }
                }
                try {
                    assertTrue(first.silentArrived.await(5, TimeUnit.SECONDS))
                    if (replaceOwner) source.changeSession(SESSION_B) else scope.cancel()
                    assertTrue("Cancellation must close the real management socket",
                        first.peerClosed.await(5, TimeUnit.SECONDS))
                    withTimeout(5000) { job.join() }

                    assertTrue(job.isCancelled)
                    assertEquals(0, completed.get())
                    assertTrue(failures.isEmpty())
                    assertEquals(if (path == CREATE_PATH) 1 else 0,
                        first.requests.count { it.path == CREATE_PATH })
                    assertEquals(if (path == EDIT_PATH) 1 else 0,
                        first.requests.count { it.path == EDIT_PATH })
                    assertTrue(first.requests.all {
                        it.cookie == "magpie_web_${first.port}=provider-session-synthetic-$OWNER_A"
                    })
                    assertTrue(second.requests.isEmpty())
                    assertTrue(first.workerFailure.get()?.toString(), first.workerFailure.get() == null)
                    assertReleasedOnce(source)
                } finally {
                    scope.cancel()
                    first.close()
                    withTimeout(5000) { job.join() }
                }
            }
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
        private var session = SESSION_A
        val leases = CopyOnWriteArrayList<TestLease>()

        fun acquire(expectedSession: ProviderSessionKey): ProviderRequestLease = synchronized(lock) {
            if (session != expectedSession) throw CancellationException("Provider session changed")
            TestLease(expectedSession, if (expectedSession == SESSION_A) first else second)
                .also { leases += it }
        }

        fun changeSession(next: ProviderSessionKey) = synchronized(lock) {
            session = next
            leases.filter { it.closeCount.get() == 0 }.forEach { it.control.cancel() }
        }

        inner class TestLease(private val expectedSession: ProviderSessionKey, peer: ManagementPeer) : ProviderRequestLease {
            override val control = ChatRequestControl()
            val closeCount = AtomicInteger()
            override val client = ProviderManagementClient(
                ManagerCredential.fromKey("provider-session-synthetic-${expectedSession.owner}", peer.port),
                "http://127.0.0.1:${peer.port}",
                timeout = Duration.ofSeconds(20),
                requestControl = control,
                ensureActive = ::ensureCurrent,
            )

            override fun ensureCurrent() = synchronized(lock) {
                control.ensureActive()
                if (session != expectedSession || closeCount.get() != 0) {
                    control.cancel()
                    throw CancellationException("Provider session changed")
                }
            }

            override fun close() { closeCount.incrementAndGet() }
        }
    }

    private class ManagementPeer(
        private val silentPath: String? = null,
        private val sendHeaders: Boolean = false,
        private val heldPath: String? = null,
    ) : AutoCloseable {
        private val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        @Volatile private var activeSocket: Socket? = null
        val port = listener.localPort
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        val silentArrived = CountDownLatch(1)
        val peerClosed = CountDownLatch(1)
        val requestHeld = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)
        val workerFailure = AtomicReference<Throwable?>()
        private val worker = thread(isDaemon = true, name = "provider-operation-fixture") {
            try {
                while (!listener.isClosed) listener.accept().use { socket ->
                    activeSocket = socket
                    socket.soTimeout = 15_000
                    val input = socket.getInputStream().buffered()
                    val headers = StringBuilder()
                    while (!headers.endsWith("\r\n\r\n")) {
                        val next = input.read()
                        if (next < 0) return@use
                        check(headers.length < 32 * 1024)
                        headers.append(next.toChar())
                    }
                    val path = headers.toString().substringBefore("\r\n").split(' ')[1]
                    val length = headers.lineSequence()
                        .firstOrNull { it.startsWith("Content-Length:", true) }
                        ?.substringAfter(':')?.trim()?.toInt() ?: 0
                    val bytes = ByteArray(length)
                    var offset = 0
                    while (offset < length) {
                        val count = input.read(bytes, offset, length - offset)
                        check(count > 0)
                        offset += count
                    }
                    val cookie = headers.lineSequence().firstOrNull { it.startsWith("Cookie:", true) }
                        ?.substringAfter(':')?.trim().orEmpty()
                    val serverPin = if (path == DISCOVERY_PATH) {
                        val body = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as JsonObject
                        (body["serverPin"] as? JsonPrimitive)?.content
                    } else null
                    requests += RecordedRequest(path, cookie, serverPin)
                    val output = socket.getOutputStream()
                    if (path == silentPath) {
                        if (sendHeaders) {
                            output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                                "Content-Length: 4096\r\nConnection: close\r\n\r\n")
                                .toByteArray(Charsets.US_ASCII))
                            output.flush()
                        }
                        silentArrived.countDown()
                        try {
                            if (input.read() == -1) peerClosed.countDown()
                        } catch (_: SocketException) { peerClosed.countDown() }
                    } else {
                        if (path == heldPath) {
                            requestHeld.countDown()
                            check(releaseResponse.await(10, TimeUnit.SECONDS))
                        }
                        val response = when (path) {
                            CAPABILITIES_PATH -> CAPABILITIES
                            LIST_PATH -> """{"providers":[]}"""
                            EDITABLE_PATH -> """{"id":"saved","revision":"${"a".repeat(64)}","name":"Saved provider","chat":"https://api.example/v1","models":["test-model"],"noModels":false,"key":{"set":true,"masked":"******","optional":false}}"""
                            DISCOVERY_PATH -> """{"models":[{"id":"test-model","name":"Test model"}],"baseUrl":"https://api.example/v1"}"""
                            else -> throw AssertionError("Unexpected fixture request: $path")
                        }.toByteArray(Charsets.UTF_8)
                        output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                            "Content-Length: ${response.size}\r\nConnection: close\r\n\r\n")
                            .toByteArray(Charsets.US_ASCII))
                        output.write(response)
                        output.flush()
                    }
                    activeSocket = null
                }
            } catch (failure: Throwable) {
                if (!listener.isClosed) workerFailure.set(failure)
            }
        }

        override fun close() {
            releaseResponse.countDown()
            listener.close()
            activeSocket?.close()
            worker.join(5000)
        }
    }

    private data class RecordedRequest(val path: String, val cookie: String, val serverPin: String? = null) {
        override fun toString() = "RecordedRequest(path=$path,credentials=<redacted>)"
    }

    private companion object {
        const val OWNER_A = 11L
        const val OWNER_B = 12L
        val SESSION_A = ProviderSessionKey(OWNER_A, 1L)
        val SESSION_A_NEXT = ProviderSessionKey(OWNER_A, 2L)
        val SESSION_B = ProviderSessionKey(OWNER_B, 3L)
        const val LIST_PATH = "/api/providers"
        const val EDITABLE_PATH = "/api/mobile/providers/editable/saved"
        const val DISCOVERY_PATH = "/api/mobile/providers/models"
        const val CREATE_PATH = "/api/mobile/providers/create"
        const val EDIT_PATH = "/api/mobile/providers/edit"
        const val CAPABILITIES_PATH = "/api/mobile/capabilities"
        const val CAPABILITIES = """{"schemaVersion":1,"coreVersion":"provider-operation-test","mobile":true,"loopbackKeys":true,"debugEndpoints":false,"adminLoopbackOnly":true,"nativeAutoUpdate":false,"providerSchemaVersion":1,"providerManagement":true,"providerModelDiscovery":true}"""

        fun createDraft() = ProviderCreateDraft("new-provider", "New provider", "https://api.example/v1",
            "provider-operation-synthetic-key", listOf("test-model"))
    }
}

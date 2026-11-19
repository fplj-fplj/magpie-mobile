package cc.fplj.magpiemobile

import cc.fplj.magpiemobile.core.ManagerCredential
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.Executor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderSessionEpochsTest {
    @Test fun sameOwnerRecoveryIsObservableWhenFlowConflatesEveryIntermediatePhase() = runTest {
        val harness = Harness()
        val binding = harness.attach(7)
        assertTrue(harness.connect(binding))
        assertNull(harness.key())
        assertNull(harness.status.value.providerSession)
        harness.drain()
        val first = harness.status.value
        val observed = mutableListOf<NativeCoreSession.Snapshot>()
        backgroundScope.launch { harness.status.collect { observed += it } }
        runCurrent()
        assertEquals(listOf(first), observed)

        // The collector gets no dispatch opportunity until the next READY. An owner-only READY
        // snapshot would compare equal to first and leave the provider screen on its old session.
        harness.suspend(binding)
        assertNull(harness.key())
        assertNull(harness.status.value.providerSession)
        assertTrue(harness.connect(binding))
        assertNull(harness.key())
        harness.drain()
        val second = harness.status.value
        runCurrent()

        assertEquals(listOf(first, second), observed)
        assertEquals(listOf(NativeCoreSession.Phase.READY, NativeCoreSession.Phase.READY), observed.map { it.phase })
        val firstKey = first.providerSession!!
        val secondKey = second.providerSession!!
        assertEquals(firstKey.owner, secondKey.owner)
        assertTrue(secondKey.epoch > firstKey.epoch)
        assertNotEquals(first, second)
        assertEquals(second.providerSession, harness.key())
    }

    @Test fun duplicateRunningAndRetryAfterReadyDoNotReplaceTheVerifiedKey() {
        val harness = Harness()
        val binding = harness.attach(7)
        assertTrue(harness.connect(binding))
        repeat(3) { assertFalse(harness.connect(binding)) }
        harness.drain()
        val ready = harness.status.value
        val key = harness.key()
        assertNotNull(key)
        repeat(3) {
            assertFalse(harness.connect(binding))
            assertFalse(harness.connect(binding, retry = true))
        }
        assertSame(ready, harness.status.value)
        assertEquals(key, harness.key())
        assertEquals(1, harness.authenticationCount)
    }

    @Test fun errorAndExplicitRetryRevokeOldKeyAndPublishAGreaterEpoch() {
        val harness = Harness()
        val binding = harness.attach(7)
        harness.connect(binding)
        harness.drain()
        val old = harness.key()!!
        harness.suspend(binding)
        assertTrue(harness.connect(binding, outcome = "error"))
        harness.drain()
        assertEquals(NativeCoreSession.Phase.ERROR, harness.status.value.phase)
        assertNull(harness.key())
        assertNull(harness.status.value.providerSession)
        assertFalse(harness.connect(binding))
        assertTrue(harness.connect(binding, retry = true))
        assertNull(harness.key())
        assertNull(harness.status.value.providerSession)
        harness.drain()
        assertEquals(old.owner, harness.key()!!.owner)
        assertTrue(harness.key()!!.epoch > old.epoch)
    }

    @Test fun currentCancellationRevokesItsAttemptAndAllowsAFreshKey() {
        val harness = Harness()
        val binding = harness.attach(7)
        harness.connect(binding)
        harness.drain()
        val old = harness.key()!!
        harness.suspend(binding)
        harness.connect(binding, outcome = "cancel")
        harness.drain()
        assertEquals(NativeCoreSession.Phase.DISCONNECTED, harness.status.value.phase)
        assertNull(harness.key())
        assertNull(harness.status.value.providerSession)
        assertTrue(harness.connect(binding))
        harness.drain()
        assertTrue(harness.key()!!.epoch > old.epoch)
    }

    @Test fun stopAndSameOwnerReplacementCannotReuseAnEarlierVerifiedIdentity() {
        val harness = Harness()
        val oldBinding = harness.attach(7)
        harness.connect(oldBinding)
        harness.drain()
        val first = harness.key()!!
        harness.disconnect(oldBinding)
        assertNull(harness.key())
        assertNull(harness.status.value.providerSession)
        assertFalse(harness.connect(oldBinding))

        val replacement = harness.attach(7)
        assertNull(harness.key())
        harness.connect(replacement)
        harness.drain()
        val second = harness.key()!!
        assertEquals(first.owner, second.owner)
        assertTrue(second.epoch > first.epoch)
        harness.disconnect(oldBinding)
        assertEquals(second, harness.key())
        val nextOwner = harness.attach(8)
        assertNull(harness.key())
        harness.connect(nextOwner)
        harness.drain()
        assertTrue(harness.key()!!.epoch > second.epoch)
    }

    @Test fun oldKeyCannotCaptureNewCredentialsEvenWhenBindingAndConfigAreReused() {
        val sessions = ProviderSessionEpochs<Any, Any>()
        val binding = Any()
        val config = Any()
        sessions.begin(7, binding)
        val first = sessions.publish(binding, config)
        val captured = sessions.capture(first, binding, config, true)
        val requests = ProviderRequestLeases()
        val credential = ManagerCredential.fromKey("a".repeat(32))
        val lease = requests.acquire(credential) { sessions.isCurrent(captured, binding, config, true) }
        try {
            lease.ensureCurrent()
            // Do not call request invalidation: independently exercise the captured-key guard.
            sessions.begin(7, binding)
            val second = sessions.publish(binding, config)
            assertTrue(second.epoch > first.epoch)
            assertThrows(CancellationException::class.java) { sessions.capture(first, binding, config, true) }
            assertThrows(CancellationException::class.java) { lease.client.getProviders() }
            assertThrows(CancellationException::class.java) { lease.control.ensureActive() }
            val fresh = sessions.capture(second, binding, config, true)
            assertTrue(sessions.isCurrent(fresh, binding, config, true))
            assertFalse(sessions.isCurrent(fresh, Any(), config, true))
            assertFalse(sessions.isCurrent(fresh, binding, Any(), true))
            assertFalse(sessions.isCurrent(fresh, binding, config, false))
            assertThrows(CancellationException::class.java) { sessions.capture(second, Any(), config, true) }
            assertThrows(CancellationException::class.java) { sessions.capture(second, binding, Any(), true) }
            assertThrows(CancellationException::class.java) { sessions.capture(second, binding, config, false) }
            sessions.invalidate()
            assertNull(sessions.current(binding, config, true))
            assertFalse(sessions.isCurrent(fresh, binding, config, true))
        } finally {
            lease.close()
            requests.invalidate()
        }
    }

    @Test fun epochsRemainMonotonicAcrossNewHelperInstancesAndInvalidation() {
        val binding = Any()
        val config = Any()
        val first = ProviderSessionEpochs<Any, Any>()
        first.begin(7, binding)
        val old = first.publish(binding, config)
        first.invalidate()
        first.invalidate()
        val next = ProviderSessionEpochs<Any, Any>()
        next.begin(7, binding)
        val key = next.publish(binding, config)
        assertTrue(key.epoch > old.epoch)
        assertNull(first.current(binding, config, true))
        assertThrows(CancellationException::class.java) { next.capture(old, binding, config, true) }
    }

    /** Uses the production coordinator and epoch gate, with authentication dispatched by hand. */
    private class Harness {
        class Binding(val owner: Long) {
            var running = true
            var config: Any? = null
        }

        private val lock = Any()
        private val tasks = ArrayDeque<Runnable>()
        private val sessions = ProviderSessionEpochs<Binding, Any>()
        private var binding: Binding? = null
        var authenticationCount = 0
            private set
        val status = MutableStateFlow(NativeCoreSession.Snapshot(NativeCoreSession.Phase.DISCONNECTED, "disconnected"))
        private val connections = SessionConnectionCoordinator<Binding, String, Any>(
            lock = lock,
            executor = Executor { tasks.addLast(it) },
            isRunning = { it.running },
            authenticate = { _, outcome, _ ->
                authenticationCount += 1
                when (outcome) {
                    "error" -> throw IOException("synthetic connection failure")
                    "cancel" -> throw CancellationException("synthetic cancellation")
                }
                Any()
            },
            onConnecting = { current ->
                sessions.begin(current.owner, current)
                current.config = null
                status.value = NativeCoreSession.Snapshot(NativeCoreSession.Phase.CONNECTING, "connecting")
            },
            onReady = { current, config ->
                current.config = config
                val key = sessions.publish(current, config)
                status.value = NativeCoreSession.Snapshot(NativeCoreSession.Phase.READY, "ready", providerSession = key)
            },
            onError = { current, _ ->
                sessions.invalidate()
                current.config = null
                status.value = NativeCoreSession.Snapshot(NativeCoreSession.Phase.ERROR, "error")
            },
            onCancelled = { current ->
                sessions.invalidate()
                current.config = null
                status.value = NativeCoreSession.Snapshot(NativeCoreSession.Phase.DISCONNECTED, "cancelled")
            },
        )

        fun attach(owner: Long): Binding = synchronized(lock) {
            sessions.invalidate()
            Binding(owner).also {
                binding = it
                connections.attach(it)
                status.value = NativeCoreSession.Snapshot(NativeCoreSession.Phase.DISCONNECTED, "attached")
            }
        }

        fun connect(current: Binding, outcome: String = "ready", retry: Boolean = false): Boolean = synchronized(lock) {
            current.running = true
            connections.connect(current, outcome, retry)
        }

        fun suspend(current: Binding) = synchronized(lock) {
            if (binding !== current) return
            current.running = false
            connections.suspend(current)
            sessions.invalidate()
            current.config = null
            status.value = NativeCoreSession.Snapshot(NativeCoreSession.Phase.DISCONNECTED, "suspended")
        }

        fun disconnect(current: Binding) = synchronized(lock) {
            if (binding !== current) return
            connections.disconnect(current)
            sessions.invalidate()
            binding = null
            status.value = NativeCoreSession.Snapshot(NativeCoreSession.Phase.DISCONNECTED, "disconnected")
        }

        fun key(): ProviderSessionKey? = synchronized(lock) {
            val current = binding
            sessions.current(current, current?.config, current?.running == true)
        }

        fun drain() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }
}

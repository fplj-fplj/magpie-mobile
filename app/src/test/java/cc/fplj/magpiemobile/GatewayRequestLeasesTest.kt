package cc.fplj.magpiemobile

import cc.fplj.magpiemobile.core.GatewayConfig
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayRequestLeasesTest {
    private val config = GatewayConfig(gatewayKey = "test-session-key")

    @Test fun invalidationStopsEveryRegisteredRequestButAllowsANewSession() {
        val requests = GatewayRequestLeases()
        val first = requests.acquire(config) { true }
        val second = requests.acquire(config) { true }
        assertSame(config, first.config)
        first.ensureCurrent()
        requests.invalidate()
        assertThrows(CancellationException::class.java) { first.control.ensureActive() }
        assertThrows(CancellationException::class.java) { second.ensureCurrent() }
        requests.invalidate()
        val next = requests.acquire(config.copy(gatewayKey = "new-key")) { true }
        next.ensureCurrent()
        next.close()
    }

    @Test fun normalReleaseIsIdempotentAndUnregistersWithoutCancellingSuccessfulControl() {
        val requests = GatewayRequestLeases()
        val completed = requests.acquire(config) { true }
        val running = requests.acquire(config) { true }
        completed.close()
        completed.close()
        completed.control.ensureActive()
        requests.invalidate()
        completed.control.ensureActive()
        assertThrows(CancellationException::class.java) { running.control.ensureActive() }
        assertThrows(CancellationException::class.java) { completed.ensureCurrent() }
    }

    @Test fun bindingIdentityAndCredentialChangesInvalidateCapturedRequest() {
        val requests = GatewayRequestLeases()
        var current = true
        val lease = requests.acquire(config) { current }
        lease.ensureCurrent()
        current = false
        assertThrows(CancellationException::class.java) { lease.ensureCurrent() }
        assertThrows(CancellationException::class.java) { lease.control.ensureActive() }
        lease.close()
    }

    @Test fun sameOwnerEpochReplacementRevokesOldGatewayLeaseEvenWithTheSameBindingAndConfig() {
        val sessions = ProviderSessionEpochs<Any, GatewayConfig>()
        val requests = GatewayRequestLeases()
        val binding = Any()
        sessions.begin(7, binding)
        val firstKey = sessions.publish(binding, config)
        val firstConnection = sessions.capture(firstKey, binding, config, true)
        val first = requests.acquire(firstConnection.config) {
            sessions.isCurrent(firstConnection, binding, config, true)
        }
        try {
            first.ensureCurrent()
            // Skip registry invalidation to independently prove the full captured epoch guard.
            sessions.begin(7, binding)
            val secondKey = sessions.publish(binding, config)
            assertEquals(firstKey.owner, secondKey.owner)
            assertTrue(secondKey.epoch > firstKey.epoch)
            assertThrows(CancellationException::class.java) { sessions.capture(firstKey, binding, config, true) }
            assertThrows(CancellationException::class.java) { first.ensureCurrent() }
            assertThrows(CancellationException::class.java) { first.control.ensureActive() }

            val secondConnection = sessions.capture(secondKey, binding, config, true)
            val second = requests.acquire(secondConnection.config) {
                sessions.isCurrent(secondConnection, binding, config, true)
            }
            try {
                assertSame(config, second.config)
                assertNotSame(first.control, second.control)
                second.ensureCurrent()
                second.control.ensureActive()
            } finally {
                second.close()
            }
        } finally {
            first.close()
            requests.invalidate()
        }
    }

    @Test fun reconnectCapturesFreshConfigurationAndControlWithoutAcceptingTheOldKey() {
        val sessions = ProviderSessionEpochs<Any, GatewayConfig>()
        val requests = GatewayRequestLeases()
        val binding = Any()
        sessions.begin(7, binding)
        val oldKey = sessions.publish(binding, config)
        val oldConnection = sessions.capture(oldKey, binding, config, true)
        val old = requests.acquire(oldConnection.config) {
            sessions.isCurrent(oldConnection, binding, config, true)
        }
        try {
            requests.invalidate()
            sessions.invalidate()
            val freshConfig = config.copy(gatewayKey = "fresh-session-key")
            sessions.begin(7, binding)
            val freshKey = sessions.publish(binding, freshConfig)
            assertThrows(CancellationException::class.java) { sessions.capture(oldKey, binding, freshConfig, true) }
            assertThrows(CancellationException::class.java) { old.control.ensureActive() }

            val freshConnection = sessions.capture(freshKey, binding, freshConfig, true)
            val fresh = requests.acquire(freshConnection.config) {
                sessions.isCurrent(freshConnection, binding, freshConfig, true)
            }
            try {
                assertSame(freshConfig, fresh.config)
                assertEquals("fresh-session-key", fresh.config.gatewayKey)
                assertNotSame(old.control, fresh.control)
                fresh.ensureCurrent()
                old.close()
                old.close()
                fresh.ensureCurrent()
                requests.invalidate()
                assertThrows(CancellationException::class.java) { fresh.control.ensureActive() }
            } finally {
                fresh.close()
            }
        } finally {
            old.close()
            requests.invalidate()
        }
    }

    @Test fun verifiedGatewayConnectionRequiresExactBindingConfigAndRunningState() {
        val sessions = ProviderSessionEpochs<Any, GatewayConfig>()
        val requests = GatewayRequestLeases()
        val binding = Any()
        sessions.begin(7, binding)
        val key = sessions.publish(binding, config)
        assertThrows(CancellationException::class.java) { sessions.capture(key, Any(), config, true) }
        assertThrows(CancellationException::class.java) { sessions.capture(key, binding, config.copy(), true) }
        assertThrows(CancellationException::class.java) { sessions.capture(key, binding, config, false) }

        val captured = sessions.capture(key, binding, config, true)
        var running = true
        val lease = requests.acquire(captured.config) {
            sessions.isCurrent(captured, binding, config, running)
        }
        try {
            lease.ensureCurrent()
            running = false
            assertThrows(CancellationException::class.java) { lease.ensureCurrent() }
            assertThrows(CancellationException::class.java) { lease.control.ensureActive() }
        } finally {
            lease.close()
            requests.invalidate()
        }
    }
}

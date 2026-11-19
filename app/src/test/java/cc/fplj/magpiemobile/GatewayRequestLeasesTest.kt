package cc.fplj.magpiemobile

import cc.fplj.magpiemobile.core.GatewayConfig
import java.util.concurrent.CancellationException
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
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
}

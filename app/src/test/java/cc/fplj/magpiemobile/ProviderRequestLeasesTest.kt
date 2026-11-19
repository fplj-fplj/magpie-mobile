package cc.fplj.magpiemobile

import cc.fplj.magpiemobile.core.ManagerCredential
import java.util.concurrent.CancellationException
import org.junit.Assert.assertThrows
import org.junit.Test

class ProviderRequestLeasesTest {
    private val credential = ManagerCredential.fromKey("a".repeat(32))

    @Test fun invalidateCancelsEveryLeaseAndAllowsFreshSession() {
        val requests = ProviderRequestLeases()
        val first = requests.acquire(credential) { true }
        val second = requests.acquire(credential) { true }
        requests.invalidate()
        assertThrows(CancellationException::class.java) { first.control.ensureActive() }
        assertThrows(CancellationException::class.java) { second.client.getProviders() }
        val next = requests.acquire(credential) { true }
        next.ensureCurrent()
        next.close()
    }

    @Test fun completedLeaseUnregistersWithoutCancellingSuccessfulControl() {
        val requests = ProviderRequestLeases()
        val completed = requests.acquire(credential) { true }
        val running = requests.acquire(credential) { true }
        completed.close()
        completed.close()
        requests.invalidate()
        completed.control.ensureActive()
        assertThrows(CancellationException::class.java) { running.ensureCurrent() }
        assertThrows(CancellationException::class.java) { completed.client.getProviders() }
    }

    @Test fun boundClientChecksItsCapturedOwnerEvenWithoutExplicitLeaseCheck() {
        val requests = ProviderRequestLeases()
        var current = true
        val lease = requests.acquire(credential) { current }
        lease.ensureCurrent()
        current = false
        assertThrows(CancellationException::class.java) { lease.client.getProviders() }
        assertThrows(CancellationException::class.java) { lease.control.ensureActive() }
        lease.close()
    }
}

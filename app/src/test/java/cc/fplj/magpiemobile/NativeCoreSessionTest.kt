package cc.fplj.magpiemobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CancellationException

class NativeCoreSessionTest {
    private val key = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    @Before fun resetRuntime() {
        GatewayRuntime.stoppedWithoutService()
        check(GatewayRuntime.requestStart())
    }

    @After fun cleanupRuntime() { GatewayRuntime.stoppedWithoutService() }

    @Test fun publicConnectionStateDoesNotExposeManagementCredential() {
        val lease = checkNotNull(GatewayRuntime.tryClaimStart())
        val owner = lease.owner
        NativeCoreSession.attach(owner, key)
        try {
            assertFalse(NativeCoreSession.status.value.toString().contains(key))
            assertFalse(NativeCoreSession.status.value.ready)
            assertEquals(null, NativeCoreSession.providerSessionId())
            assertEquals(null, NativeCoreSession.providerSessionKey())
            assertEquals(null, NativeCoreSession.status.value.providerSession)
            var copied = false
            assertFalse(NativeCoreSession.copyGatewayKeyIfCurrent(ProviderSessionKey(owner, 1)) { copied = true })
            assertFalse(copied)
            assertThrows(IllegalStateException::class.java) { NativeCoreSession.providerClient() }
            assertThrows(IllegalStateException::class.java) { NativeCoreSession.gatewayConfig() }
            assertThrows(CancellationException::class.java) { NativeCoreSession.gatewayRequest(owner) }
            assertThrows(CancellationException::class.java) { NativeCoreSession.gatewayRequest(ProviderSessionKey(owner, 1)) }
            assertThrows(CancellationException::class.java) { NativeCoreSession.providerRequest(ProviderSessionKey(owner, 1)) }
            assertThrows(CancellationException::class.java) { NativeCoreSession.pluginRequest(ProviderSessionKey(owner, 1)) }
        } finally {
            NativeCoreSession.disconnect(owner)
            GatewayRuntime.beginCleanup(owner)
            GatewayRuntime.cleanupCompleted(lease)
        }
    }
    @Test fun lateOldServiceDisconnectDoesNotDestroyNewBinding() {
        val old = checkNotNull(GatewayRuntime.tryClaimStart())
        NativeCoreSession.attach(old.owner, key)
        NativeCoreSession.disconnect(old.owner)
        assertTrue(GatewayRuntime.beginCleanup(old.owner))
        assertTrue(GatewayRuntime.cleanupCompleted(old))
        assertTrue(GatewayRuntime.requestStart())
        val current = checkNotNull(GatewayRuntime.tryClaimStart())
        NativeCoreSession.attach(current.owner, key.reversed())
        try {
            val before = NativeCoreSession.status.value
            NativeCoreSession.suspend(old.owner)
            NativeCoreSession.disconnect(old.owner)
            assertFalse(GatewayRuntime.cleanupCompleted(old))
            assertThrows(CancellationException::class.java) { NativeCoreSession.gatewayRequest(old.owner) }
            assertThrows(CancellationException::class.java) { NativeCoreSession.gatewayRequest(ProviderSessionKey(old.owner, 1)) }
            assertThrows(CancellationException::class.java) { NativeCoreSession.providerRequest(ProviderSessionKey(old.owner, 1)) }
            assertThrows(CancellationException::class.java) { NativeCoreSession.pluginRequest(ProviderSessionKey(old.owner, 1)) }
            assertEquals(before, NativeCoreSession.status.value)
            assertEquals(null, NativeCoreSession.providerSessionKey())
            assertEquals(null, NativeCoreSession.status.value.providerSession)
            assertTrue(GatewayRuntime.isCurrent(current.owner))
            NativeCoreSession.disconnect(current.owner)
            assertEquals(NativeCoreSession.Phase.DISCONNECTED, NativeCoreSession.status.value.phase)
            assertThrows(CancellationException::class.java) { NativeCoreSession.gatewayRequest(current.owner) }
            assertThrows(CancellationException::class.java) { NativeCoreSession.gatewayRequest(ProviderSessionKey(current.owner, 1)) }
            assertThrows(CancellationException::class.java) { NativeCoreSession.providerRequest(ProviderSessionKey(current.owner, 1)) }
            assertThrows(CancellationException::class.java) { NativeCoreSession.pluginRequest(ProviderSessionKey(current.owner, 1)) }
            assertEquals(null, NativeCoreSession.providerSessionKey())
            assertEquals(null, NativeCoreSession.status.value.providerSession)
        } finally {
            NativeCoreSession.disconnect(current.owner)
            GatewayRuntime.beginCleanup(current.owner)
            GatewayRuntime.cleanupCompleted(current)
        }
    }
}

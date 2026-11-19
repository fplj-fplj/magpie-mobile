package cc.fplj.magpiemobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.CancellationException

class NativeCoreSessionTest {
    private val key = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    @Test fun publicConnectionStateDoesNotExposeManagementCredential() {
        val owner = GatewayRuntime.attach()
        NativeCoreSession.attach(owner, key)
        try {
            assertFalse(NativeCoreSession.status.value.toString().contains(key))
            assertFalse(NativeCoreSession.status.value.ready)
            assertEquals(null, NativeCoreSession.providerSessionId())
            assertEquals(null, NativeCoreSession.providerSessionKey())
            assertEquals(null, NativeCoreSession.status.value.providerSession)
            assertThrows(IllegalStateException::class.java) { NativeCoreSession.providerClient() }
            assertThrows(IllegalStateException::class.java) { NativeCoreSession.gatewayConfig() }
            assertThrows(CancellationException::class.java) { NativeCoreSession.gatewayRequest(owner) }
            assertThrows(CancellationException::class.java) { NativeCoreSession.gatewayRequest(ProviderSessionKey(owner, 1)) }
            assertThrows(CancellationException::class.java) { NativeCoreSession.providerRequest(ProviderSessionKey(owner, 1)) }
            assertThrows(CancellationException::class.java) { NativeCoreSession.pluginRequest(ProviderSessionKey(owner, 1)) }
        } finally { NativeCoreSession.disconnect(owner) }
    }
    @Test fun lateOldServiceDisconnectDoesNotDestroyNewBinding() {
        val old = GatewayRuntime.attach()
        NativeCoreSession.attach(old, key)
        val current = GatewayRuntime.attach()
        NativeCoreSession.attach(current, key.reversed())
        val before = NativeCoreSession.status.value
        NativeCoreSession.disconnect(old)
        assertThrows(CancellationException::class.java) { NativeCoreSession.gatewayRequest(old) }
        assertThrows(CancellationException::class.java) { NativeCoreSession.gatewayRequest(ProviderSessionKey(old, 1)) }
        assertThrows(CancellationException::class.java) { NativeCoreSession.providerRequest(ProviderSessionKey(old, 1)) }
        assertThrows(CancellationException::class.java) { NativeCoreSession.pluginRequest(ProviderSessionKey(old, 1)) }
        assertEquals(before, NativeCoreSession.status.value)
        assertEquals(null, NativeCoreSession.providerSessionKey())
        assertEquals(null, NativeCoreSession.status.value.providerSession)
        NativeCoreSession.disconnect(current)
        assertEquals(NativeCoreSession.Phase.DISCONNECTED, NativeCoreSession.status.value.phase)
        assertThrows(CancellationException::class.java) { NativeCoreSession.gatewayRequest(current) }
        assertThrows(CancellationException::class.java) { NativeCoreSession.gatewayRequest(ProviderSessionKey(current, 1)) }
        assertThrows(CancellationException::class.java) { NativeCoreSession.providerRequest(ProviderSessionKey(current, 1)) }
        assertThrows(CancellationException::class.java) { NativeCoreSession.pluginRequest(ProviderSessionKey(current, 1)) }
        assertEquals(null, NativeCoreSession.providerSessionKey())
        assertEquals(null, NativeCoreSession.status.value.providerSession)
    }
}

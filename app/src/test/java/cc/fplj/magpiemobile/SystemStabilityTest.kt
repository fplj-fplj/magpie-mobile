package cc.fplj.magpiemobile

import android.app.Application
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SystemStabilityTest {

    @Test
    fun nullCapabilitiesYieldsDisconnectedState() {
        val state = NetworkConnectivityMonitor.evaluateNetworkState(null)
        assertFalse(state.connected)
        assertEquals(NetworkType.NONE, state.type)
        assertFalse(state.validated)
        assertTrue(state.description.contains("无网络连接"))
    }

    @Test
    fun validatedWifiIsReportedAsConnected() {
        val capabilities = NetworkCapabilities.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()
        val state = NetworkConnectivityMonitor.evaluateNetworkState(capabilities)
        assertTrue(state.connected)
        assertTrue(state.validated)
        assertEquals(NetworkType.WIFI, state.type)
    }

    @Test
    fun cellularWaitingForValidationIsDistinctFromDisconnected() {
        val capabilities = NetworkCapabilities.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val state = NetworkConnectivityMonitor.evaluateNetworkState(capabilities)
        assertTrue(state.connected)
        assertFalse(state.validated)
        assertEquals(NetworkType.CELLULAR, state.type)
        assertTrue(state.description.contains("待验证"))
    }

    @Test
    fun batteryOptimizationHelperProvidesStatusAndIntent() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val status = BatteryOptimizationHelper.checkStatus(app)
        assertNotNull(status)
        assertNotNull(status.description)

        // Verifies intent generation logic doesn't crash
        val intent = BatteryOptimizationHelper.createOptimizationSettingsIntent(app)
        // Intent may be null or non-null depending on shadow package manager, but mustn't throw
    }

    @Test
    fun stabilityViewModelInitializesAndRefreshes() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = StabilityViewModel(app)

        val state = vm.state.value
        assertNotNull(state.networkState)
        assertNotNull(state.batteryStatus)

        vm.refresh()
        assertEquals(state.networkState.type, vm.state.value.networkState.type)

        vm.clearMemoryNotice()
        assertEquals("", vm.state.value.memoryTrimNotice)
    }
}

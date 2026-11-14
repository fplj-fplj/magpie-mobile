package cc.fplj.magpiemobile

import android.app.Application
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class BatteryOptimizationTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun register(intent: Intent) {
        val info = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = "com.android.settings"
                name = "BatterySettings"
                exported = true
            }
        }
        Shadows.shadowOf(app.packageManager).addResolveInfoForIntent(intent, info)
    }

    @Test
    fun missingBatteryScreensFallBackToThisAppsDetails() {
        register(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:${app.packageName}")))
        val route = BatteryOptimizationHelper.createOptimizationSettingsRoute(app)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, route.intent?.action)
        assertEquals("package:${app.packageName}", route.intent?.data.toString())
        assertEquals("应用详情", route.label)
        assertTrue(route.note.contains("电池"))
    }

    @Test
    fun rejectedLaunchContinuesToNextResolvableScreen() {
        register(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(Uri.parse("package:${app.packageName}")))
        register(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        val attempts = mutableListOf<String?>()
        val context = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent) {
                attempts += intent.action
                if (intent.action == Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) throw SecurityException("ROM denied launch")
            }
        }
        val route = BatteryOptimizationHelper.openOptimizationSettings(context)
        assertEquals(listOf(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), attempts)
        assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, route.intent?.action)
        assertTrue(route.note.contains("Magpie Mobile"))
    }

    @Test
    fun noResolvableScreenAlwaysProvidesManualInstructions() {
        val route = BatteryOptimizationHelper.openOptimizationSettings(app)
        assertNull(route.intent)
        assertTrue(route.note.contains("设置 → 应用 → Magpie Mobile"))
    }
}

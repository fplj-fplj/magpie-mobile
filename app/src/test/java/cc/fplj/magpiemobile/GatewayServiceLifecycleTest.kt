package cc.fplj.magpiemobile

import android.app.Application
import android.app.Service
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import cc.fplj.magpiemobile.core.GatewayEngineController.Phase
import cc.fplj.magpiemobile.core.GatewayEngineController.Status
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

/** Real Service callbacks, without authorizing a payload launch or opening an Activity UI. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class GatewayServiceLifecycleTest {
    private val application: Application = ApplicationProvider.getApplicationContext()
    private var ownedLease: ServiceLease? = null

    @Before fun reset() {
        GatewayRuntime.stoppedWithoutService()
    }

    @After fun cleanup() {
        ownedLease?.let { lease ->
            GatewayRuntime.requestStop()
            NativeCoreSession.disconnect(lease.owner)
            GatewayRuntime.cleanupCompleted(lease)
        }
        ownedLease = null
        GatewayRuntime.stoppedWithoutService()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun repeatedStopOnlyServiceRecreationCannotClaimOrCompleteThePrecedingLease() {
        val lease = claimOwner()
        GatewayRuntime.requestStop()
        val waiting = GatewayRuntime.status.value
        val connection = NativeCoreSession.status.value

        repeat(3) { index ->
            dispatchOwnerlessService(Intent(application, GatewayService::class.java).setAction(GatewayService.ACTION_STOP),
                flags = 0, startId = index + 1) {
                assertHeld(lease, waiting, connection)
            }
            assertHeld(lease, waiting, connection)
        }

        assertTrue(GatewayRuntime.cleanupCompleted(lease))
        assertFalse(GatewayRuntime.isCurrent(lease.owner))
        assertFalse(GatewayRuntime.stopping.value)
        assertEquals(Phase.STOPPED, GatewayRuntime.status.value.phase)
        assertEquals(FINAL_TAIL, GatewayRuntime.status.value.outputTail)
        assertEquals(23, GatewayRuntime.status.value.exitCode)
        assertTrue(GatewayRuntime.requestStart())
        GatewayRuntime.requestStop() // Cancel the unused authorization; no Service was started.
    }

    @Test fun nullUnknownFreshAndReplayStartCommandsCannotStealAnUnfinishedLease() {
        val lease = claimOwner()
        assertTrue(GatewayRuntime.beginCleanup(lease.owner))
        val waiting = GatewayRuntime.status.value
        val connection = NativeCoreSession.status.value
        // beginCleanup alone is not an explicit user stop. Exercise STOP_SELF/START
        // decisions too, rather than letting mustStop turn every command into STOP.
        assertFalse(GatewayRuntime.mustStop())
        val deliveries = listOf(
            null to 0,
            Intent(application, GatewayService::class.java) to 0,
            Intent(application, GatewayService::class.java).setAction("synthetic.unknown") to 0,
            Intent(application, GatewayService::class.java).setAction(GatewayService.ACTION_START) to 0,
            Intent(application, GatewayService::class.java).setAction(GatewayService.ACTION_START) to Service.START_FLAG_RETRY,
            Intent(application, GatewayService::class.java).setAction(GatewayService.ACTION_START) to Service.START_FLAG_REDELIVERY,
            Intent(application, GatewayService::class.java).setAction(GatewayService.ACTION_START) to
                (Service.START_FLAG_RETRY or Service.START_FLAG_REDELIVERY),
        )
        for ((index, delivery) in deliveries.withIndex()) {
            dispatchOwnerlessService(delivery.first, delivery.second, index + 1) {
                assertHeld(lease, waiting, connection)
            }
            assertHeld(lease, waiting, connection)
        }
    }

    @Test fun rejectedReplayNullAndUnknownServicesDoNotConsumeAPendingStartAuthorization() {
        assertTrue(GatewayRuntime.requestStart())
        val pending = GatewayRuntime.status.value
        val connection = NativeCoreSession.status.value
        val deliveries = listOf(
            null to 0,
            Intent(application, GatewayService::class.java).setAction("synthetic.unknown") to 0,
            Intent(application, GatewayService::class.java).setAction(GatewayService.ACTION_START) to Service.START_FLAG_REDELIVERY,
        )
        for ((index, delivery) in deliveries.withIndex()) {
            dispatchOwnerlessService(delivery.first, delivery.second, index + 1) {
                assertEquals(pending, GatewayRuntime.status.value)
                assertEquals(connection, NativeCoreSession.status.value)
                assertFalse(GatewayRuntime.stopping.value)
                assertFalse(GatewayRuntime.mustStop())
            }
            assertEquals(pending, GatewayRuntime.status.value)
        }
        // The pending authorization is still claimable by the intended fresh START.
        val lease = checkNotNull(GatewayRuntime.tryClaimStart())
        ownedLease = lease
        assertTrue(GatewayRuntime.isCurrent(lease.owner))
    }

    @Test fun ownerlessForegroundFailureCannotOverwriteAnUnfinishedOwner() {
        val lease = claimOwner()
        GatewayRuntime.requestStop()
        val waiting = GatewayRuntime.status.value
        val connection = NativeCoreSession.status.value
        for (action in listOf<String?>(GatewayService.ACTION_STOP, null, "synthetic.unknown", GatewayService.ACTION_START)) {
            val intent = Intent(application, GatewayService::class.java).apply { this.action = action }
            dispatchOwnerlessService(intent, flags = 0, foregroundFailed = true) {
                assertHeld(lease, waiting, connection)
            }
            assertHeld(lease, waiting, connection)
        }
    }

    @Test fun replayedForegroundFailureDoesNotPublishOrConsumeAPendingAuthorization() {
        assertTrue(GatewayRuntime.requestStart())
        val pending = GatewayRuntime.status.value
        val connection = NativeCoreSession.status.value
        val intent = Intent(application, GatewayService::class.java).setAction(GatewayService.ACTION_START)
        dispatchOwnerlessService(intent, flags = Service.START_FLAG_RETRY, foregroundFailed = true) {
            assertEquals(pending, GatewayRuntime.status.value)
            assertEquals(connection, NativeCoreSession.status.value)
            assertFalse(GatewayRuntime.stopping.value)
            assertFalse(GatewayRuntime.mustStop())
        }
        assertEquals(pending, GatewayRuntime.status.value)
        ownedLease = checkNotNull(GatewayRuntime.tryClaimStart())
    }

    @Test fun authorizedFreshStartPublishesItsForegroundFailureAndRetiresItsOwnLease() {
        assertTrue(GatewayRuntime.requestStart())
        val intent = Intent(application, GatewayService::class.java).setAction(GatewayService.ACTION_START)
        val controller = Robolectric.buildService(GatewayService::class.java, intent)
        var destroyed = false
        try {
            controller.create()
            assertEquals(Phase.STARTING, GatewayRuntime.status.value.phase)
            ReflectionHelpers.setField(controller.get(), "foregroundReady", false)
            ReflectionHelpers.setField(controller.get(), "foregroundFailure",
                SecurityException("synthetic foreground refusal token=$SYNTHETIC_ADMIN_KEY"))

            assertEquals(Service.START_NOT_STICKY, controller.get().onStartCommand(intent, 0, 1))
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            val lease = checkNotNull(ReflectionHelpers.getField<ServiceLease?>(controller.get(), "lease"))
            ownedLease = lease
            assertTrue(GatewayRuntime.isCurrent(lease.owner))
            assertNull("failed promotion must never create a payload controller",
                ReflectionHelpers.getField<Any?>(controller.get(), "controller"))
            // Public session IDs require a ready gateway. Read only the binding owner,
            // not its credentials, to prove the authorized Service attached the right run.
            val binding = checkNotNull(ReflectionHelpers.getField<Any?>(NativeCoreSession, "binding"))
            assertEquals(lease.owner, ReflectionHelpers.getField<Long>(binding, "owner"))
            val failed = GatewayRuntime.status.value
            assertEquals(Phase.FAILED, failed.phase)
            assertTrue(failed.message.contains("前台服务启动失败"))
            assertTrue(failed.message.contains("synthetic foreground refusal"))
            assertFalse(failed.toString().contains(SYNTHETIC_ADMIN_KEY))
            assertFalse(failed.toString().contains(SYNTHETIC_ADMIN_KEY.takeLast(4)))

            controller.destroy()
            destroyed = true
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (GatewayRuntime.isCurrent(lease.owner) && System.nanoTime() < deadline) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(5)
            }
            assertFalse("controller-less failure cleanup did not finish", GatewayRuntime.isCurrent(lease.owner))
            assertFalse(GatewayRuntime.stopping.value)
            assertEquals(failed, GatewayRuntime.status.value)
            assertNull(ReflectionHelpers.getField<Any?>(NativeCoreSession, "binding"))
            assertTrue(GatewayRuntime.requestStart())
            GatewayRuntime.requestStop() // Cancel the newly accepted but unused START.
        } finally {
            if (!destroyed) controller.destroy()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test fun coldStopOnlyServiceSettlesWithoutAnOwnerAndDoesNotNeedCleanupProof() {
        val intent = Intent(application, GatewayService::class.java).setAction(GatewayService.ACTION_STOP)
        repeat(3) { index ->
            dispatchOwnerlessService(intent, flags = 0, startId = index + 1) {
                assertEquals(Phase.STOPPED, GatewayRuntime.status.value.phase)
                assertFalse(GatewayRuntime.stopping.value)
                assertTrue(GatewayRuntime.mustStop())
            }
        }
        assertTrue(GatewayRuntime.requestStart())
        ownedLease = checkNotNull(GatewayRuntime.tryClaimStart())
        assertTrue(GatewayRuntime.isCurrent(checkNotNull(ownedLease).owner))
    }

    @Test fun actualMainStopServiceFalseDoesNotCompleteAnOwnedLease() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        val applicationShadow = Shadows.shadowOf(application)
        val intent = Intent(activity, GatewayService::class.java)
        assertTrue(applicationShadow.allStartedServices.isEmpty())
        // No Android Service exists. This verifies the real shadow's false fixture
        // before invoking the production method; the probe does not touch Runtime.
        assertFalse(activity.stopService(intent))
        assertEquals(intent.component, applicationShadow.nextStoppedService?.component)

        val lease = claimOwner()
        val tail = GatewayRuntime.status.value.outputTail
        val exitCode = GatewayRuntime.status.value.exitCode
        activity.stopGateway()
        assertEquals(intent.component, applicationShadow.nextStoppedService?.component)
        assertTrue(applicationShadow.allStartedServices.isEmpty())
        assertTrue(GatewayRuntime.isCurrent(lease.owner))
        assertTrue(GatewayRuntime.stopping.value)
        assertTrue(GatewayRuntime.mustStop())
        assertEquals(tail, GatewayRuntime.status.value.outputTail)
        assertEquals(exitCode, GatewayRuntime.status.value.exitCode)
        assertFalse(GatewayRuntime.requestStart())

        val waiting = GatewayRuntime.status.value
        val connection = NativeCoreSession.status.value
        repeat(2) {
            activity.stopGateway()
            assertEquals(intent.component, applicationShadow.nextStoppedService?.component)
            assertHeld(lease, waiting, connection)
        }
        assertNull(applicationShadow.nextStoppedService)
        assertTrue(GatewayRuntime.cleanupCompleted(lease))
        assertFalse(GatewayRuntime.stopping.value)
        assertFalse(GatewayRuntime.isCurrent(lease.owner))
    }

    @Test fun actualMainStopServiceFalseSettlesWhenThereIsNoLease() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        val applicationShadow = Shadows.shadowOf(application)
        val intent = Intent(activity, GatewayService::class.java)
        assertTrue(applicationShadow.allStartedServices.isEmpty())
        assertFalse(activity.stopService(intent))
        assertEquals(intent.component, applicationShadow.nextStoppedService?.component)
        assertTrue(GatewayRuntime.requestStart()) // Pending command, no Service has claimed it.

        activity.stopGateway()
        assertEquals(intent.component, applicationShadow.nextStoppedService?.component)
        assertEquals(Phase.STOPPED, GatewayRuntime.status.value.phase)
        assertFalse(GatewayRuntime.stopping.value)
        assertTrue(GatewayRuntime.mustStop())
        assertNull(GatewayRuntime.tryClaimStart())
        assertTrue(applicationShadow.allStartedServices.isEmpty())
    }

    private fun claimOwner(): ServiceLease {
        assertTrue(GatewayRuntime.requestStart())
        val lease = checkNotNull(GatewayRuntime.tryClaimStart())
        ownedLease = lease
        NativeCoreSession.attach(lease.owner, SYNTHETIC_ADMIN_KEY)
        assertNotNull(GatewayRuntime.publish(lease.owner, Status(
            Phase.RUNNING, "owned service awaiting its own cleanup", restartCount = 2,
            outputTail = FINAL_TAIL, exitCode = 23,
        )))
        return lease
    }

    private fun assertHeld(lease: ServiceLease, expected: Status, connection: NativeCoreSession.Snapshot) {
        assertTrue(GatewayRuntime.isCurrent(lease.owner))
        assertTrue(GatewayRuntime.stopping.value)
        assertFalse(GatewayRuntime.requestStart())
        assertEquals(expected, GatewayRuntime.status.value)
        assertEquals(connection, NativeCoreSession.status.value)
        assertEquals(FINAL_TAIL, GatewayRuntime.status.value.outputTail)
        assertEquals(23, GatewayRuntime.status.value.exitCode)
    }

    private fun dispatchOwnerlessService(
        intent: Intent?,
        flags: Int,
        startId: Int = 1,
        foregroundFailed: Boolean = false,
        verify: () -> Unit,
    ) {
        val controller = Robolectric.buildService(GatewayService::class.java,
            intent ?: Intent(application, GatewayService::class.java))
        try {
            controller.create()
            verify() // onCreate itself must not claim, attach, clear tail or complete A.
            if (foregroundFailed) {
                // Seed only Android's failed-promotion outcome. Commands, reporter gates and
                // destruction still execute the production GatewayService implementation.
                ReflectionHelpers.setField(controller.get(), "foregroundReady", false)
                ReflectionHelpers.setField(controller.get(), "foregroundFailure", SecurityException("synthetic foreground refusal"))
            }
            assertEquals(Service.START_NOT_STICKY, controller.get().onStartCommand(intent, flags, startId))
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            verify()
        } finally {
            controller.destroy()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
        verify() // A controller-less B must not use onDestroy as cleanup evidence for A.
    }

    private companion object {
        const val FINAL_TAIL = "prior owner final diagnostic marker\n"
        const val SYNTHETIC_ADMIN_KEY = "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef"
    }
}

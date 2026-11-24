package cc.fplj.magpiemobile

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.fplj.magpiemobile.theme.MagpieTheme
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real Android archive/provider checks. No GitHub request, package-installer launch or APK
 * replacement occurs in this suite.
 */
@RunWith(AndroidJUnit4::class)
class AppUpdateInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val updateDirectory by lazy { AppUpdateArchiveVerifier.updatesDirectory(context) }

    @Before fun prepare() {
        GatewayRuntime.stoppedWithoutService()
        assertTrue(updateDirectory.isDirectory || updateDirectory.mkdirs())
    }

    @After fun cleanup() {
        GatewayRuntime.stoppedWithoutService()
        updateDirectory.listFiles()
            .orEmpty()
            .filter { it.name.startsWith("instrumented-") }
            .forEach { it.delete() }
    }

    @Test fun fileProviderServesOnlyThePrivateUpdateDirectory() {
        val expected = "instrumented-provider-${UUID.randomUUID()}".toByteArray()
        val inside = File(updateDirectory, "instrumented-${UUID.randomUUID()}.apk")
        val outside = File(context.cacheDir, "instrumented-outside-${UUID.randomUUID()}.apk")
        inside.writeBytes(expected)
        outside.writeBytes("outside".toByteArray())
        try {
            val authority = context.packageName + ".updates"
            val uri = FileProvider.getUriForFile(context, authority, inside)
            assertEquals("content", uri.scheme)
            assertEquals(authority, uri.authority)
            val served = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            assertNotNull(served)
            assertTrue(expected.contentEquals(served))

            try {
                FileProvider.getUriForFile(context, authority, outside)
                fail("FileProvider exposed a cache file outside updates/")
            } catch (_: IllegalArgumentException) {
                // The provider path is restricted to cache/updates.
            }
        } finally {
            inside.delete()
            outside.delete()
        }
    }

    @Test fun unknownSourcesIntentTargetsOnlyThisPackageAndIsNeverLaunched() {
        val intent = AppUpdateInstaller(context).unknownSourcesSettingsIntent()
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, intent.action)
        assertEquals(Uri.parse("package:" + context.packageName), intent.data)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test fun installedDebugArchiveIsRejectedAndUsesANonReleaseSigner() {
        val source = File(context.applicationInfo.sourceDir)
        assertTrue("installed app archive is missing", source.isFile)
        val candidate = File(updateDirectory, "instrumented-current-debug-${UUID.randomUUID()}.apk")
        source.copyTo(candidate, overwrite = true)
        try {
            val flags = PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_META_DATA
            val info = checkNotNull(context.packageManager.getPackageArchiveInfo(candidate.absolutePath, flags))
            val application = checkNotNull(info.applicationInfo)
            assertTrue("fixture app must be a real debuggable archive",
                application.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
            val candidateSigner = uniqueSignerSha256(info)
            val installed = checkNotNull(context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES,
            ))
            val installedSigner = uniqueSignerSha256(installed)
            assertEquals("debug candidate and installed debug app must share one signer",
                installedSigner, candidateSigner)
            assertNotEquals(AppUpdatePolicy.RELEASE_CERTIFICATE_SHA256, candidateSigner)

            val rejected = expectRejected {
                AppUpdateArchiveVerifier(context).verify(
                    file = candidate,
                    expectedVersionName = checkNotNull(info.versionName),
                    expectedVersionCode = info.longVersionCode,
                    expectedSha256 = sha256(candidate),
                )
            }
            assertTrue(rejected.message.orEmpty().isNotBlank())
        } finally {
            candidate.delete()
        }
    }

    @Test fun verifierPropagatesCancellationFromTheRealApkHashLoop() {
        val source = File(context.applicationInfo.sourceDir)
        assertTrue("installed app archive is missing", source.isFile)
        val candidate = File(updateDirectory, "instrumented-cancel-${UUID.randomUUID()}.apk")
        source.copyTo(candidate, overwrite = true)
        try {
            assertTrue("debug APK must be large enough to enter the hash loop", candidate.length() > 64L * 1024L)
            val cancellation = CancellationException("instrumented hash-loop cancellation")
            var ensureActiveCalls = 0
            var propagated: CancellationException? = null
            try {
                AppUpdateArchiveVerifier(context).verify(
                    file = candidate,
                    expectedVersionName = "instrumented-cancel",
                    expectedVersionCode = BuildConfig.VERSION_CODE.toLong() + 1,
                    expectedSha256 = "0".repeat(64),
                    ensureActive = {
                        ensureActiveCalls += 1
                        // Call 1 enters verify, 2 precedes the hash read and 3 follows its first chunk.
                        if (ensureActiveCalls == 3) throw cancellation
                    },
                )
            } catch (cancelled: CancellationException) {
                propagated = cancelled
            }
            assertSame("verification must rethrow the exact coroutine cancellation", cancellation, propagated)
            assertEquals("cancellation must occur inside the real APK hash loop", 3, ensureActiveCalls)
        } finally {
            candidate.delete()
        }
    }

    @Test fun androidViewModelFactoryCreatesAppUpdateViewModelWithoutStartingWork() {
        val application = context.applicationContext as Application
        val store = ViewModelStore()
        try {
            val factory = AndroidViewModelFactory.getInstance(application)
            val viewModel = ViewModelProvider(store, factory)[AppUpdateViewModel::class.java]
            assertEquals(AppUpdatePhase.IDLE, viewModel.state.value.phase)
            assertEquals(BuildConfig.VERSION_NAME, viewModel.state.value.currentVersionName)
            assertEquals(BuildConfig.VERSION_CODE.toLong(), viewModel.state.value.currentVersionCode)
            assertFalse(viewModel.state.value.installBusy)
        } finally {
            store.clear()
        }
    }

    @Test fun gatewayLeaseAndCleanupBarrierBlockInstallationUntilTerminal() {
        val fake = File(updateDirectory, "instrumented-install-gate-${UUID.randomUUID()}.apk").apply {
            writeBytes("not reached because lifecycle gates run first".toByteArray())
        }
        val update = VerifiedAppUpdate(fake, "instrumented-update", 67, "0".repeat(64))
        val installer = AppUpdateInstaller(context)
        assertTrue(GatewayRuntime.canInstallUpdate())
        assertTrue(GatewayRuntime.requestStart())
        val lease = checkNotNull(GatewayRuntime.tryClaimStart())
        try {
            assertFalse("STARTING with an owner must block installation", GatewayRuntime.canInstallUpdate())
            assertEquals(
                "请先停止网关并等待当前任务结束",
                expectRejected { installer.installIntent(update) { true } }.message,
            )

            assertTrue(GatewayRuntime.beginCleanup(lease.owner))
            assertTrue(GatewayRuntime.stopping.value)
            assertFalse("cleanup in progress must keep installation blocked", GatewayRuntime.canInstallUpdate())
            assertEquals(
                "请先停止网关并等待当前任务结束",
                expectRejected { installer.installIntent(update) { true } }.message,
            )

            assertTrue(GatewayRuntime.cleanupCompleted(lease))
            assertFalse(GatewayRuntime.stopping.value)
            assertTrue(GatewayRuntime.canInstallUpdate())
            assertEquals(
                "请先停止网关并等待当前任务结束",
                expectRejected {
                    installer.installIntent(update) { false }
                }.message,
            )
        } finally {
            if (GatewayRuntime.isCurrent(lease.owner)) {
                GatewayRuntime.beginCleanup(lease.owner)
                GatewayRuntime.cleanupCompleted(lease)
            }
            fake.delete()
        }
    }

    @Test fun updateActionsProgressAndReleaseDetailsFollowTheVisiblePhase() {
        val state = mutableStateOf(AppUpdateUiState(
            currentVersionName = "0.1.66",
            currentVersionCode = 66,
            currentCoreVersion = "0.1.3781-androidhost.3",
            currentCommit = "fedcba9876543210fedcba9876543210fedcba98",
        ))
        val checkCalls = mutableIntStateOf(0)
        val cancelCalls = mutableIntStateOf(0)
        val downloadCalls = mutableIntStateOf(0)
        val installAllowed = mutableStateOf(true)
        compose.setContent {
            MagpieTheme(darkTheme = false) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        AppUpdateContent(
                            state = state.value,
                            installAllowed = installAllowed.value,
                            needsInstallPermission = false,
                            onCheck = { checkCalls.intValue += 1 },
                            onCancel = { cancelCalls.intValue += 1 },
                            onDownload = { downloadCalls.intValue += 1 },
                            onInstall = { fail("install callback must stay hidden outside VERIFIED") },
                            onGateway = { fail("gateway callback must stay hidden outside a blocked VERIFIED state") },
                            onOpenRelease = { fail("release callback must stay hidden outside LEGACY_RELEASE") },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("app-update-card").assertIsDisplayed()
        compose.onNodeWithText("当前 0.1.66 · 66").assertIsDisplayed()
        compose.onNodeWithText("内核 0.1.3781-androidhost.3 · fedcba9876543210fedcba9876543210fedcba98")
            .assertIsDisplayed()
        compose.onNodeWithTag("app-update-check").assertIsEnabled()
        compose.onNodeWithTag("app-update-download").assertDoesNotExist()
        compose.onNodeWithTag("app-update-install").assertDoesNotExist()
        compose.onNodeWithTag("app-update-cancel").assertDoesNotExist()
        compose.onNodeWithTag("app-update-progress").assertDoesNotExist()
        compose.onNodeWithTag("app-update-check").performClick()
        assertEquals(1, checkCalls.intValue)

        compose.runOnUiThread {
            state.value = state.value.copy(
                phase = AppUpdatePhase.CURRENT,
                message = "当前已是 GitHub 最新版本。",
            )
        }
        compose.waitForIdle()
        compose.onNodeWithText("当前已是 GitHub 最新版本。").assertIsDisplayed()
        compose.onNodeWithText("重新检查").assertIsEnabled()
        compose.onNodeWithTag("app-update-download").assertDoesNotExist()
        compose.onNodeWithTag("app-update-install").assertDoesNotExist()

        compose.runOnUiThread {
            state.value = state.value.copy(
                phase = AppUpdatePhase.CHECKING,
                message = "正在读取 GitHub 稳定版 Release…",
            )
        }
        compose.waitForIdle()
        compose.onNodeWithTag("app-update-progress").assertIsDisplayed()
        compose.onNodeWithTag("app-update-cancel").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("app-update-check").assertDoesNotExist()
        compose.onNodeWithTag("app-update-download").assertDoesNotExist()
        assertEquals(1, cancelCalls.intValue)

        compose.runOnUiThread {
            state.value = AppUpdateUiState(
                phase = AppUpdatePhase.AVAILABLE,
                release = releaseSummary(),
                message = "发现可用更新 · 0.1.67",
            )
        }
        compose.waitForIdle()
        compose.onNodeWithTag("app-update-download").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("0.1.67 · 67").assertIsDisplayed()
        compose.onNodeWithText(
            "核心 0.1.3781-androidhost.4 · 上游 2026.11.24 · 01234567",
        ).assertIsDisplayed()
        compose.onNodeWithText("这是可折叠的完整发行说明。").assertDoesNotExist()
        compose.onNodeWithTag("app-update-notes").performScrollTo().performClick()
        compose.onNodeWithText("这是可折叠的完整发行说明。").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("app-update-download").performScrollTo().performClick()
        assertEquals(1, downloadCalls.intValue)
        compose.onNodeWithTag("app-update-progress").assertDoesNotExist()

        compose.runOnUiThread {
            state.value = state.value.copy(
                phase = AppUpdatePhase.DOWNLOADING,
                downloadedBytes = 512,
                totalBytes = 2L * 1024L * 1024L,
                message = "正在下载并校验完整签名 APK…",
            )
        }
        compose.waitForIdle()
        compose.onNodeWithTag("app-update-progress").assertIsDisplayed()
        compose.onNodeWithText("512 B / 2.0 MiB").assertIsDisplayed()
        compose.onNodeWithTag("app-update-cancel").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("app-update-download").assertDoesNotExist()

        compose.runOnUiThread {
            state.value = state.value.copy(
                phase = AppUpdatePhase.INSTALLING,
                installBusy = true,
                message = "正在重新核对安装条件…",
            )
        }
        compose.waitForIdle()
        compose.onNodeWithTag("app-update-progress").assertIsDisplayed()
        compose.onNodeWithText("正在重新核对安装条件…").assertIsDisplayed()
        compose.onNodeWithTag("app-update-install").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("等待 Android 安装确认…").assertIsDisplayed()
        compose.onNodeWithTag("app-update-check").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("app-update-download").assertDoesNotExist()
        compose.onNodeWithTag("app-update-cancel").assertDoesNotExist()
    }

    @Test fun verifiedInstallIsGatedAndPermissionCopyIsExposedWithoutLaunchingSettings() {
        val state = mutableStateOf(AppUpdateUiState(
            phase = AppUpdatePhase.VERIFIED,
            release = releaseSummary(),
            message = "签名、哈希、版本与 ARM64 核心载荷均已核验。",
        ))
        val installAllowed = mutableStateOf(false)
        val needsPermission = mutableStateOf(false)
        val installCalls = mutableIntStateOf(0)
        val gatewayCalls = mutableIntStateOf(0)
        compose.setContent {
            MagpieTheme(darkTheme = false) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        AppUpdateContent(
                            state = state.value,
                            installAllowed = installAllowed.value,
                            needsInstallPermission = needsPermission.value,
                            onCheck = { fail("refresh must not run while controlling the install gate") },
                            onCancel = { fail("cancel must stay hidden outside checking/downloading") },
                            onDownload = { fail("download must stay hidden after verification") },
                            onInstall = { installCalls.intValue += 1 },
                            onGateway = { gatewayCalls.intValue += 1 },
                            onOpenRelease = { fail("release callback must stay hidden outside LEGACY_RELEASE") },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("app-update-install").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("app-update-gateway").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("交给系统安装器").assertIsDisplayed()
        assertEquals(1, gatewayCalls.intValue)
        assertEquals(0, installCalls.intValue)

        compose.runOnUiThread {
            installAllowed.value = true
            needsPermission.value = true
        }
        compose.waitForIdle()
        compose.onNodeWithTag("app-update-install").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("允许安装并继续").assertIsDisplayed()
        compose.onNodeWithTag("app-update-gateway").assertDoesNotExist()
        assertEquals(1, installCalls.intValue)
        compose.onNodeWithTag("app-update-download").assertDoesNotExist()
    }

    @Test fun errorAndLegacyReleaseStatesExposeSafeRecoveryActions() {
        val state = mutableStateOf(AppUpdateUiState(
            phase = AppUpdatePhase.ERROR,
            error = "GitHub 更新检查失败，请稍后重试。",
        ))
        val checkCalls = mutableIntStateOf(0)
        val releaseCalls = mutableIntStateOf(0)
        compose.setContent {
            MagpieTheme(darkTheme = false) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        AppUpdateContent(
                            state = state.value,
                            installAllowed = false,
                            needsInstallPermission = false,
                            onCheck = { checkCalls.intValue += 1 },
                            onCancel = { fail("cancel must stay hidden for an error") },
                            onDownload = { fail("download must stay hidden for an error") },
                            onInstall = { fail("install must stay hidden for an error") },
                            onGateway = { fail("gateway recovery must stay hidden for an error") },
                            onOpenRelease = { releaseCalls.intValue += 1 },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("app-update-error")
            .assertIsDisplayed()
        compose.onNodeWithTag("app-update-check").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("app-update-download").assertDoesNotExist()
        assertEquals(1, checkCalls.intValue)

        compose.runOnUiThread {
            state.value = AppUpdateUiState(
                phase = AppUpdatePhase.LEGACY_RELEASE,
                legacyTag = "v0.1.65-old-protocol",
                legacyReleaseName = "Magpie Mobile 0.1.65",
                legacyNotes = "旧版 Release 仍可人工查看，但不会直接安装。",
                message = "当前 Latest 尚未包含机器可读更新信息，无法安全核对 APK。",
            )
        }
        compose.waitForIdle()
        compose.onNodeWithText("Magpie Mobile 0.1.65 · v0.1.65-old-protocol").assertIsDisplayed()
        compose.onNodeWithTag("app-update-open-release")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        compose.onNodeWithText("旧版 Release 仍可人工查看，但不会直接安装。").assertIsDisplayed()
        compose.onNodeWithTag("app-update-download").assertDoesNotExist()
        compose.onNodeWithTag("app-update-install").assertDoesNotExist()
        assertEquals(1, releaseCalls.intValue)
    }

    private fun releaseSummary() = AppUpdateReleaseSummary(
        versionName = "0.1.67",
        versionCode = 67,
        coreVersion = "0.1.3781-androidhost.4",
        sourceCommit = "0123456789abcdef0123456789abcdef01234567",
        upstreamTag = "2026.11.24",
        tag = "v0.1.67-update-test",
        apkSize = 103_617_244,
        notes = "这是可折叠的完整发行说明。",
    )

    private fun uniqueSignerSha256(info: android.content.pm.PackageInfo): String {
        val signing = checkNotNull(info.signingInfo)
        assertFalse(signing.hasMultipleSigners())
        val signers = signing.apkContentsSigners
        assertEquals(1, signers.size)
        return MessageDigest.getInstance("SHA-256").digest(signers[0].toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun expectRejected(block: () -> Unit): AppUpdateRejected = try {
        block()
        fail("expected an update rejection")
        error("unreachable")
    } catch (rejected: AppUpdateRejected) {
        rejected
    }
}

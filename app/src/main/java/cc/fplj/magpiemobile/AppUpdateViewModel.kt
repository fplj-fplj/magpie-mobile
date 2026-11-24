package cc.fplj.magpiemobile

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cc.fplj.magpiemobile.core.AppRelease
import cc.fplj.magpiemobile.core.AppReleaseClient
import cc.fplj.magpiemobile.core.AppReleaseException
import cc.fplj.magpiemobile.core.AppReleaseResult
import cc.fplj.magpiemobile.core.ReleaseRequestControl
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

enum class AppUpdatePhase { IDLE, CHECKING, CURRENT, AVAILABLE, DOWNLOADING, VERIFIED, INSTALLING, LEGACY_RELEASE, ERROR }

data class AppUpdateReleaseSummary(
    val versionName: String,
    val versionCode: Long,
    val coreVersion: String,
    val sourceCommit: String,
    val upstreamTag: String,
    val tag: String,
    val apkSize: Long,
    val notes: String,
)

private fun AppRelease.summary() = AppUpdateReleaseSummary(
    versionName, versionCode, coreVersion, sourceCommit, upstreamTag, tag, apkSize, notes,
)

data class AppUpdateUiState(
    val phase: AppUpdatePhase = AppUpdatePhase.IDLE,
    val currentVersionName: String = BuildConfig.VERSION_NAME,
    val currentVersionCode: Long = BuildConfig.VERSION_CODE.toLong(),
    val currentCoreVersion: String = BuildConfig.CORE_VERSION,
    val currentCommit: String = BuildConfig.GIT_SHA,
    val release: AppUpdateReleaseSummary? = null,
    val verified: VerifiedAppUpdate? = null,
    val legacyTag: String = "",
    val legacyReleaseName: String = "",
    val legacyReleaseUrl: String = "",
    val legacyNotes: String = "",
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val installBusy: Boolean = false,
    val message: String = "",
    val error: String = "",
)

/** GitHub is only a source of signed whole-APK candidates; native files are never hot-swapped. */
class AppUpdateViewModel internal constructor(
    application: Application,
    private val releaseClient: AppReleaseClient = AppReleaseClient(),
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, AppReleaseClient())

    private val mutable = MutableStateFlow(AppUpdateUiState())
    val state = mutable.asStateFlow()
    private val generation = AtomicLong()
    private var work: Job? = null
    private var control: ReleaseRequestControl? = null
    private var installing = false
    private var handoffPending = false
    private var currentRelease: AppRelease? = null
    // Rendezvous prevents a system settings/installer screen from being queued while More is stopped.
    private val installEvents = Channel<VerifiedAppUpdate>(Channel.RENDEZVOUS)
    val installRequests = installEvents.receiveAsFlow()

    fun check() {
        if (installing || work?.isActive == true || mutable.value.phase == AppUpdatePhase.CHECKING || mutable.value.phase == AppUpdatePhase.DOWNLOADING) return
        startWork { request, revision ->
            currentRelease = null
            mutable.update { it.copy(phase = AppUpdatePhase.CHECKING, release = null, legacyTag = "", error = "", message = "正在读取 GitHub 稳定版 Release…", downloadedBytes = 0, totalBytes = 0) }
            try {
                val result = withContext(Dispatchers.IO) {
                    val owner = currentCoroutineContext()
                    releaseClient.latest(request) { owner.ensureActive() }
                }
                if (!isCurrent(revision)) return@startWork
                when (result) {
                    is AppReleaseResult.Available -> {
                        val release = result.release
                        currentRelease = release
                        if (release.versionCode > mutable.value.currentVersionCode) {
                            mutable.update { it.copy(phase = AppUpdatePhase.AVAILABLE, release = release.summary(),
                                message = "发现可用更新 · ${release.versionName}", error = "") }
                        } else {
                            mutable.update { it.copy(phase = AppUpdatePhase.CURRENT, release = release.summary(),
                                message = if (release.versionCode == it.currentVersionCode) "当前已是 GitHub 最新版本。"
                                else "GitHub Latest 低于当前安装版本，已拒绝降级。", error = "") }
                        }
                    }
                    is AppReleaseResult.ProtocolUpgradeRequired -> mutable.update {
                        it.copy(phase = AppUpdatePhase.LEGACY_RELEASE, legacyTag = result.tag,
                            legacyReleaseName = result.releaseName, legacyReleaseUrl = result.releaseUrl,
                            legacyNotes = result.notes, message = "当前 Latest 尚未包含机器可读更新信息，无法安全核对 APK。", error = "")
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: AppReleaseException) {
                if (isCurrent(revision)) mutable.update { it.copy(phase = AppUpdatePhase.ERROR, error = failure.message ?: "GitHub 更新检查失败。", message = "") }
            } catch (_: Exception) {
                if (isCurrent(revision)) mutable.update { it.copy(phase = AppUpdatePhase.ERROR, error = "GitHub 更新检查失败，请稍后重试。", message = "") }
            }
        }
    }

    fun download() {
        if (installing || work?.isActive == true) return
        val release = currentRelease ?: return
        if (release.versionCode <= mutable.value.currentVersionCode ||
            mutable.value.phase !in setOf(AppUpdatePhase.AVAILABLE, AppUpdatePhase.VERIFIED)) return
        startWork { request, revision ->
            mutable.update { it.copy(phase = AppUpdatePhase.DOWNLOADING, verified = null, error = "", message = "正在下载并校验完整签名 APK…", downloadedBytes = 0, totalBytes = release.apkSize) }
            val directory = AppUpdateArchiveVerifier.updatesDirectory(getApplication())
            val part = File(directory, "${release.versionCode}-$revision.apk.part")
            val final = File(directory, "magpie-mobile-${release.versionName}-$revision.apk")
            val candidate = File(directory, "magpie-mobile-${release.versionName}-$revision.apk")
            try {
                withContext(Dispatchers.IO) {
                    if (!directory.exists() && !directory.mkdirs()) throw IllegalStateException("update cache unavailable")
                    val owner = currentCoroutineContext()
                    releaseClient.download(release, part, request,
                        progress = { loaded, total ->
                            if (isCurrent(revision)) mutable.update { it.copy(downloadedBytes = loaded, totalBytes = total) }
                        },
                        ensureActive = { owner.ensureActive() },
                    )
                    owner.ensureActive()
                    Files.move(part.toPath(), candidate.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    owner.ensureActive()
                    val verified = AppUpdateArchiveVerifier(getApplication()).verify(
                        file = candidate,
                        expectedVersionName = release.versionName,
                        expectedVersionCode = release.versionCode,
                        expectedSha256 = release.apkSha256,
                        ensureActive = { owner.ensureActive() },
                    )
                    owner.ensureActive()
                    Files.move(candidate.toPath(), final.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    verified.copy(file = final)
                }.also { verified ->
                    if (isCurrent(revision)) mutable.update { it.copy(phase = AppUpdatePhase.VERIFIED, verified = verified,
                        downloadedBytes = release.apkSize, totalBytes = release.apkSize,
                        message = "签名、哈希、版本与 ARM64 核心载荷均已核验。", error = "") }
                }
            } catch (cancelled: CancellationException) {
                part.delete()
                candidate.delete()
                final.delete()
                throw cancelled
            } catch (rejected: AppUpdateRejected) {
                part.delete()
                candidate.delete()
                final.delete()
                if (isCurrent(revision)) mutable.update { it.copy(phase = AppUpdatePhase.ERROR, verified = null, error = rejected.message ?: "更新 APK 校验失败。", message = "") }
            } catch (_: Exception) {
                part.delete()
                candidate.delete()
                final.delete()
                if (isCurrent(revision)) mutable.update { it.copy(phase = AppUpdatePhase.ERROR, verified = null, error = "更新下载或 APK 核验失败；当前安装未更改。", message = "") }
            }
        }
    }

    fun cancel() {
        if (mutable.value.phase !in setOf(AppUpdatePhase.CHECKING, AppUpdatePhase.DOWNLOADING)) return
        generation.incrementAndGet()
        control?.cancel()
        work?.cancel()
        work = null
        control = null
        mutable.update { it.copy(phase = if (it.release != null) AppUpdatePhase.AVAILABLE else AppUpdatePhase.IDLE,
            downloadedBytes = 0, totalBytes = 0, message = "已取消；未完成的临时文件已清理。", error = "") }
    }

    fun install(appIdle: () -> Boolean) {
        val verified = mutable.value.verified ?: return
        if (installing || mutable.value.phase != AppUpdatePhase.VERIFIED) return
        if (work?.isActive == true) return
        val context = getApplication<Application>()
        val expected = verified
        installing = true
        mutable.update { it.copy(phase = AppUpdatePhase.INSTALLING, installBusy = true, error = "", message = "正在重新核对安装条件…") }
        viewModelScope.launch {
            try {
                if (!GatewayRuntime.canInstallUpdate() || !appIdle()) throw AppUpdateRejected("请先停止网关并等待当前任务结束")
                val current = withContext(Dispatchers.IO) {
                    val owner = currentCoroutineContext()
                    AppUpdateArchiveVerifier(context).verify(
                        expected.file, expected.versionName, expected.versionCode, expected.sha256,
                        ensureActive = { owner.ensureActive() },
                    )
                }
                currentCoroutineContext().ensureActive()
                if (!GatewayRuntime.canInstallUpdate() || !appIdle()) throw AppUpdateRejected("请先停止网关并等待当前任务结束")
                currentCoroutineContext().ensureActive()
                handoffPending = true
                if (installEvents.trySend(current).isFailure) {
                    handoffPending = false
                    throw AppUpdateRejected("请在前台的“更多”页面重新发起安装")
                }
            } catch (rejected: AppUpdateRejected) {
                mutable.update { it.copy(phase = AppUpdatePhase.VERIFIED, error = rejected.message ?: "无法安装此更新。", message = "") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.update { it.copy(phase = AppUpdatePhase.VERIFIED, error = "无法打开系统安装器，请稍后重试。", message = "") }
            } finally {
                if (!handoffPending) {
                    installing = false
                    mutable.update { it.copy(phase = if (it.phase == AppUpdatePhase.INSTALLING) AppUpdatePhase.VERIFIED else it.phase,
                        installBusy = false) }
                }
            }
        }
    }

    /** Called once after Android returns, or immediately if foreground handoff was rejected. */
    fun installHandoffResult(success: Boolean) {
        handoffPending = false
        installing = false
        mutable.update { it.copy(
            phase = AppUpdatePhase.VERIFIED,
            installBusy = false,
            message = if (success) "已交给 Android；系统将显示最终确认。若刚授予安装权限，请再次点安装。" else "未能打开系统页面；请保持网关和操作空闲后重试。",
            error = if (success) "" else "无法打开 Android 安装页面。",
        ) }
    }

    fun installPermissionResult(granted: Boolean) {
        handoffPending = false
        installing = false
        mutable.update { it.copy(
            phase = AppUpdatePhase.VERIFIED,
            installBusy = false,
            message = if (granted) "已允许 Magpie 安装应用，请再次点安装。" else "尚未允许此应用安装更新，可稍后重试。",
            error = if (granted) "" else "未授予安装权限。",
        ) }
    }

    fun openLegacyRelease(context: Context) {
        val link = mutable.value.legacyReleaseUrl
        if (!link.startsWith("https://github.com/fplj-fplj/magpie-mobile/releases/tag/")) return
        try { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(link))) }
        catch (_: Exception) { mutable.update { it.copy(error = "无法打开 GitHub Release 页面。") } }
    }

    private fun startWork(block: suspend (ReleaseRequestControl, Long) -> Unit) {
        val revision = generation.incrementAndGet()
        val request = ReleaseRequestControl()
        control?.cancel()
        work?.cancel()
        control = request
        work = viewModelScope.launch {
            try { block(request, revision) }
            catch (cancelled: CancellationException) { throw cancelled }
            finally {
                if (isCurrent(revision)) {
                    control = null
                    work = null
                }
            }
        }
    }

    private fun isCurrent(revision: Long): Boolean = generation.get() == revision

    override fun onCleared() {
        generation.incrementAndGet()
        handoffPending = false
        control?.cancel()
        work?.cancel()
        control = null
        work = null
        installEvents.close()
        super.onCleared()
    }
}

package cc.fplj.magpiemobile

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.SigningInfo
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.zip.ZipFile

/** Safe user-facing rejection; never include an upstream response, path or exception text. */
class AppUpdateRejected(reason: String) : IllegalArgumentException(reason)

data class VerifiedAppUpdate internal constructor(
    val file: File,
    val versionName: String,
    val versionCode: Long,
    val sha256: String,
)

/**
 * Verifies a complete release APK before the system installer is allowed to see it.
 *
 * Native engines are upgraded only as part of this signed APK. The verifier checks the
 * release identity, package-manager signature, archive hash, production flags, and the
 * two AArch64 PIE engine payloads. The gateway/chat lifecycle is checked again by the
 * caller immediately before launching installation.
 */
class AppUpdateArchiveVerifier(private val context: Context) {
    private val packageManager = context.packageManager

    fun verify(
        file: File,
        expectedVersionName: String,
        expectedVersionCode: Long,
        expectedSha256: String,
        ensureActive: () -> Unit = {},
    ): VerifiedAppUpdate {
        ensureActive()
        val safeFile = requireSafeDownload(file)
        val expectedName = expectedVersionName.trim()
        AppUpdatePolicy.expectedFailure(expectedName, expectedVersionCode)?.let { reject(it) }
        val expectedHash = AppUpdatePolicy.normalizedSha256(expectedSha256)
            ?: reject("更新摘要格式无效")
        val size = safeFile.length()
        if (size !in 1..AppUpdatePolicy.MAX_APK_BYTES) reject("更新文件大小无效")
        val actualHash = sha256(safeFile, ensureActive)
        ensureActive()
        if (actualHash != expectedHash) reject("更新文件摘要不一致")

        val current = packageInfo(context.packageName, installed = true)
            ?: reject("无法读取当前应用版本")
        val candidate = packageInfo(safeFile.absolutePath, installed = false)
            ?: reject("无法读取更新 APK")
        val candidateApplication = candidate.applicationInfo ?: reject("更新 APK 缺少应用信息")
        val debuggable =
            (candidateApplication.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val testOnly =
            (candidateApplication.flags and ApplicationInfo.FLAG_TEST_ONLY) != 0
        AppUpdatePolicy.identityFailure(
            expectedVersionName = expectedName,
            expectedVersionCode = expectedVersionCode,
            candidatePackageName = candidate.packageName,
            candidateVersionName = candidate.versionName,
            candidateVersionCode = candidate.longVersionCode,
            debuggable = debuggable,
            testOnly = testOnly,
            installedPackageName = context.packageName,
            installedVersionCode = current.longVersionCode,
        )?.let { reject(it) }

        val currentCertificate = uniqueCertificateHash(current.signingInfo)
            ?: reject("当前安装版本签名无效")
        val candidateCertificate = uniqueCertificateHash(candidate.signingInfo)
            ?: reject("更新 APK 签名无效或包含多个签名者")
        AppUpdatePolicy.signingFailure(currentCertificate, candidateCertificate)
            ?.let { reject(it) }
        validateArm64Payloads(safeFile, ensureActive)
        ensureActive()
        return VerifiedAppUpdate(safeFile, expectedName, expectedVersionCode, actualHash)
    }

    private fun packageInfo(path: String, installed: Boolean): PackageInfo? = try {
        if (installed) {
            packageManager.getPackageInfo(path, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            packageManager.getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES)
        }
    } catch (_: Exception) {
        null
    }

    private fun uniqueCertificateHash(info: SigningInfo?): String? {
        if (info == null || info.hasMultipleSigners()) return null
        val currentSigners = info.apkContentsSigners
        val history = info.signingCertificateHistory
        if (currentSigners == null || currentSigners.size != 1 ||
            history == null || history.size != 1) return null
        val currentHash = certificateHash(currentSigners[0].toByteArray())
        val historyHash = certificateHash(history[0].toByteArray())
        return currentHash.takeIf { it == historyHash }
    }

    private fun validateArm64Payloads(file: File, ensureActive: () -> Unit) {
        try {
            ZipFile(file).use { archive ->
                ensureActive()
                val entries = archive.entries()
                val names = mutableListOf<String>()
                while (entries.hasMoreElements()) {
                    ensureActive()
                    names += entries.nextElement().name
                }
                val libraryNames = names
                    .filter { it.startsWith("lib/") && it.count { char -> char == '/' } >= 2 }
                AppUpdatePolicy.arm64PayloadFailure(libraryNames)?.let { reject(it) }
                for (name in REQUIRED_ENGINE_PAYLOADS) {
                    val entry = archive.getEntry(name) ?: reject("更新 APK 缺少核心载荷")
                    archive.getInputStream(entry).use { input ->
                        val header = readExactly(input, 20) ?: reject("核心载荷文件无效")
                        ensureActive()
                        AppUpdatePolicy.elfFailure(header)?.let { reject(it) }
                    }
                }
            }
        } catch (error: AppUpdateRejected) {
            throw error
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            reject("更新 APK 压缩结构无效")
        }
    }

    private fun requireSafeDownload(file: File): File {
        if (!file.isFile) reject("更新文件不存在")
        if (!AppUpdatePolicy.validDownloadName(file.name)) reject("更新文件名无效")
        val directory = try { updatesDirectory(context).canonicalFile } catch (_: Exception) {
            reject("更新目录无效")
        }
        val canonical = try { file.canonicalFile } catch (_: Exception) {
            reject("更新文件路径无效")
        }
        if (canonical.parentFile != directory) reject("更新文件不在应用更新目录")
        return canonical
    }

    companion object {
        private val REQUIRED_ENGINE_PAYLOADS = setOf(
            "lib/arm64-v8a/libmagpie_exec.so",
            "lib/arm64-v8a/libbun_exec.so",
        )

        fun updatesDirectory(context: Context): File = File(context.cacheDir, "updates")

        private fun sha256(file: File, ensureActive: () -> Unit): String = try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    ensureActive()
                    val count = input.read(buffer)
                    ensureActive()
                    if (count < 0) break
                    if (count > 0) digest.update(buffer, 0, count)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            reject("无法读取更新文件摘要")
        }

        private fun certificateHash(raw: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(raw)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }

        private fun readExactly(input: InputStream, size: Int): ByteArray? {
            val bytes = ByteArray(size)
            var offset = 0
            while (offset < size) {
                val count = input.read(bytes, offset, size - offset)
                if (count < 0) return null
                if (count == 0) continue
                offset += count
            }
            return bytes
        }

        private fun reject(reason: String): Nothing = throw AppUpdateRejected(reason)
    }
}

/** Immutable policy shared by the verifier and JVM tests. */
internal object AppUpdatePolicy {
    const val PACKAGE_NAME = "cc.fplj.magpiemobile"
    const val RELEASE_CERTIFICATE_SHA256 =
        "dee1d3f485ee17a6184178eaab318d6333fb8b67e7276146a30a03e1f9ee09d6"
    const val MAX_APK_BYTES = 200L * 1024L * 1024L

    fun normalizedSha256(value: String): String? =
        value.trim().lowercase().takeIf { Regex("[0-9a-f]{64}").matches(it) }

    fun validDownloadName(value: String): Boolean =
        Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,120}\\.apk").matches(value)

    fun expectedFailure(versionName: String, versionCode: Long): String? = when {
        versionName.isEmpty() || versionName.length > 128 ||
            versionName.any { it.isISOControl() } -> "更新版本名称无效"
        versionCode <= 0 -> "更新版本代码无效"
        else -> null
    }

    fun identityFailure(
        expectedVersionName: String,
        expectedVersionCode: Long,
        candidatePackageName: String?,
        candidateVersionName: String?,
        candidateVersionCode: Long?,
        debuggable: Boolean,
        testOnly: Boolean,
        installedPackageName: String,
        installedVersionCode: Long?,
    ): String? = expectedFailure(expectedVersionName, expectedVersionCode) ?: when {
        installedPackageName != PACKAGE_NAME -> "当前应用包名不受更新器支持"
        installedVersionCode == null -> "无法读取当前应用版本"
        candidatePackageName == null -> "无法读取更新 APK 包信息"
        candidatePackageName != PACKAGE_NAME -> "更新 APK 包名不匹配"
        candidateVersionName != expectedVersionName -> "更新 APK 版本名称不匹配"
        candidateVersionCode == null -> "更新 APK 版本代码不匹配"
        candidateVersionCode != expectedVersionCode -> "更新 APK 版本代码不匹配"
        candidateVersionCode <= installedVersionCode -> "更新 APK 不是更高版本"
        debuggable -> "更新 APK 是可调试版本"
        testOnly -> "更新 APK 是测试版本"
        else -> null
    }

    fun signingFailure(currentCertificate: String, candidateCertificate: String): String? = when {
        currentCertificate != RELEASE_CERTIFICATE_SHA256 -> "当前安装版本签名不受信任"
        candidateCertificate != RELEASE_CERTIFICATE_SHA256 -> "更新 APK 签名证书不匹配"
        currentCertificate != candidateCertificate -> "更新 APK 与当前应用签名不同"
        else -> null
    }

    fun arm64PayloadFailure(names: List<String>): String? {
        val libraries = names.filter { it.startsWith("lib/") && it.count { char -> char == '/' } >= 2 }
        val abis = libraries.map { it.split('/')[1] }.toSet()
        if (abis != setOf("arm64-v8a")) return "更新 APK 不是 ARM64 生产包"
        if (!libraries.containsAll(listOf(
                "lib/arm64-v8a/libmagpie_exec.so",
                "lib/arm64-v8a/libbun_exec.so",
            ))) return "更新 APK 缺少核心载荷"
        return null
    }

    /** Returns a fixed reason for anything other than a 64-bit little-endian AArch64 PIE. */
    fun elfFailure(header: ByteArray): String? {
        if (header.size < 20 || header[0] != 0x7f.toByte() ||
            header[1] != 'E'.code.toByte() || header[2] != 'L'.code.toByte() ||
            header[3] != 'F'.code.toByte()) return "核心载荷不是 ELF"
        if (header[4] != 2.toByte()) return "核心载荷不是 64 位 ELF"
        if (header[5] != 1.toByte()) return "核心载荷不是小端 ELF"
        val type = (header[16].toInt() and 0xff) or ((header[17].toInt() and 0xff) shl 8)
        val machine = (header[18].toInt() and 0xff) or ((header[19].toInt() and 0xff) shl 8)
        if (type != 3) return "核心载荷不是 PIE"
        if (machine != 183) return "核心载荷不是 AArch64"
        return null
    }
}

/**
 * Hands a verified private-cache APK to Android's package installer. Android always owns
 * the final confirmation; this class never attempts a silent install.
 */
class AppUpdateInstaller(private val context: Context) {
    private val authority = context.packageName + ".updates"

    fun needsInstallPermission(): Boolean = try {
        !context.packageManager.canRequestPackageInstalls()
    } catch (_: Exception) {
        true
    }

    fun unknownSourcesSettingsIntent(): Intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
        .setData(Uri.parse("package:" + context.packageName))
        .apply {
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /**
     * [appIdle] must also prove that no chat/provider/plugin operation is active. The
     * gateway lease is checked here because Android service deletion alone is not cleanup proof.
     */
    fun installIntent(update: VerifiedAppUpdate, appIdle: () -> Boolean): Intent {
        val appIsIdle = try { appIdle() } catch (_: Exception) { false }
        if (!GatewayRuntime.canInstallUpdate() || !appIsIdle) {
            throw AppUpdateRejected("请先停止网关并等待当前任务结束")
        }
        if (needsInstallPermission()) throw AppUpdateRejected("请先允许本应用安装更新")
        if (!update.file.isFile) throw AppUpdateRejected("更新文件已被清理，请重新下载")
        val uri = try {
            FileProvider.getUriForFile(context, authority, update.file)
        } catch (_: Exception) {
            throw AppUpdateRejected("无法安全提供更新文件")
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .setClipData(ClipData.newRawUri("Magpie Mobile update", uri))
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) == null) {
            throw AppUpdateRejected("系统中没有可用的 APK 安装器")
        }
        return intent
    }

    companion object {
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}

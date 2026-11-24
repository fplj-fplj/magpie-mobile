package cc.fplj.magpiemobile.core

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Strict client for the public, stable Magpie-Mobile GitHub Release.
 *
 * The production constructor is fixed to one repository. Every asset URL in Release metadata must
 * exactly match the URL derived from the validated tag and fixed asset name. Authentication and
 * cookie jars are deliberately absent.
 *
 * This client only fetches and verifies a full APK. It never downloads or replaces a native
 * executable independently; Android PackageInstaller remains responsible for the APK update.
 */
class AppReleaseClient internal constructor(
    private val transport: AppReleaseTransport,
) {
    constructor() : this(OkHttpAppReleaseTransport())

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
        allowTrailingComma = false
        allowSpecialFloatingPointValues = false
    }

    /**
     * Read the fixed repository's stable Latest Release.
     *
     * A successfully downloaded build-info without the new machine-readable fields is returned as
     * [AppReleaseResult.ProtocolUpgradeRequired]. In that case no versionCode is inferred.
     */
    fun latest(
        control: ReleaseRequestControl = ReleaseRequestControl(),
        ensureActive: () -> Unit = {},
    ): AppReleaseResult = guarded(control, ensureActive) {
        val response = requestWithoutRedirect(
            API_LATEST_URL,
            "application/vnd.github+json",
            control,
            ensureActive,
        )
        val body = response.use {
            requireSuccess(it, null)
            readBounded(it, MAX_RELEASE_JSON_BYTES, control, ensureActive)
        }
        val parsed = try {
            parseLatest(body.toString(Charsets.UTF_8))
        } catch (failure: AppReleaseException.Protocol) {
            throw failure
        } catch (_: Exception) {
            throw AppReleaseException.Protocol()
        }

        val buildInfoBytes = downloadBounded(
            parsed.buildInfo.url,
            parsed.buildInfo.size,
            MAX_BUILD_INFO_BYTES,
            control,
            ensureActive,
        )
        val checksumBytes = downloadBounded(
            parsed.checksum.url,
            parsed.checksum.size,
            MAX_CHECKSUM_BYTES,
            control,
            ensureActive,
        )
        if (sha256(buildInfoBytes) != parsed.buildInfo.sha256 ||
            sha256(checksumBytes) != parsed.checksum.sha256
        ) {
            throw AppReleaseException.Protocol()
        }
        val apkDigestFromChecksum = parseChecksum(checksumBytes, parsed.tag, parsed.versionName, parsed.apk.name)
        if (apkDigestFromChecksum != parsed.apk.sha256) throw AppReleaseException.Protocol()

        val machine = parseMachineBuildInfo(
            buildInfoBytes.toString(Charsets.UTF_8),
            parsed.versionName,
            parsed.tagSha,
        ) ?: return@guarded AppReleaseResult.ProtocolUpgradeRequired(
            tag = parsed.tag,
            releaseName = parsed.releaseName,
            releaseUrl = parsed.releaseUrl,
            notes = parsed.notes,
        )

        AppReleaseResult.Available(
            AppRelease(
                versionName = machine.versionName,
                versionCode = machine.versionCode,
                coreVersion = machine.coreVersion,
                sourceCommit = machine.commit,
                upstreamTag = machine.upstreamTag,
                upstreamRevision = machine.upstreamRevision,
                tag = parsed.tag,
                releaseName = parsed.releaseName,
                releaseUrl = parsed.releaseUrl,
                notes = parsed.notes,
                apkSize = parsed.apk.size,
                apkSha256 = parsed.apk.sha256,
                apkAssetName = parsed.apk.name,
                apkDownloadUrl = parsed.apk.url,
            ),
        )
    }

    /**
     * Download one already parsed release into [destination].
     *
     * [destination] must end in `.part`. The file remains present only after the declared size and
     * SHA-256 both match. Failure or cancellation removes the part and never touches a sibling
     * finalized APK. Within this process, downloads targeting the same canonical destination are
     * rejected without touching the active download's file. Progress callbacks run on the caller's
     * worker thread.
     *
     * @throws IllegalStateException when another download already owns the same canonical
     * destination; the existing part is left untouched.
     */
    fun download(
        release: AppRelease,
        destination: File,
        control: ReleaseRequestControl = ReleaseRequestControl(),
        progress: (loaded: Long, total: Long) -> Unit = { _, _ -> },
        ensureActive: () -> Unit = {},
    ): File {
        requireDownloadDestination(destination)
        val claim = claimDownloadDestination(destination)
        return try {
            guarded(
                control = control,
                ensureActive = ensureActive,
                failureCleanup = { deletePart(destination) },
            ) {
                downloadChecked(release, destination, control, progress, ensureActive)
            }
        } finally {
            claim.close()
        }
    }

    private fun downloadChecked(
        release: AppRelease,
        destination: File,
        control: ReleaseRequestControl,
        progress: (loaded: Long, total: Long) -> Unit,
        ensureActive: () -> Unit,
    ): File {
        if (destination.exists() && !destination.delete()) {
            throw AppReleaseException.Unavailable()
        }

        var completed = false
        var stream: AppReleaseHttpResponse? = null
        return try {
            progress(0L, release.apkSize)
            active(control, ensureActive)
            stream = followDownloadRedirects(
                release.apkDownloadUrl,
                release.apkSize,
                control,
                ensureActive,
            )
            val input = BufferedInputStream(stream.body, BUFFER_BYTES)
            val digest = MessageDigest.getInstance("SHA-256")
            var loaded = 0L
            FileOutputStream(destination, false).use { file ->
                val output = BufferedOutputStream(file, BUFFER_BYTES)
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    active(control, ensureActive)
                    val read = input.read(buffer)
                    active(control, ensureActive)
                    if (read < 0) break
                    if (read == 0) continue
                    if (loaded > release.apkSize - read) {
                        throw AppReleaseException.Protocol()
                    }
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    loaded += read
                    progress(loaded, release.apkSize)
                    active(control, ensureActive)
                }
                output.flush()
                file.fd.sync()
            }
            active(control, ensureActive)
            if (loaded != release.apkSize) throw AppReleaseException.Protocol()
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (actual != release.apkSha256) throw AppReleaseException.Protocol()
            completed = true
            destination
        } finally {
            try {
                stream?.close()
            } catch (_: IOException) {
                // The terminal cancellation/protocol result is authoritative.
            }
            if (!completed) {
                deletePart(destination)
            }
        }
    }

    private fun claimDownloadDestination(destination: File): Closeable {
        val canonicalPath = try {
            destination.canonicalPath
        } catch (_: Exception) {
            throw AppReleaseException.Unavailable()
        }
        if (!activeDownloadDestinations.add(canonicalPath)) {
            throw IllegalStateException("Update destination is already downloading")
        }
        return Closeable { activeDownloadDestinations.remove(canonicalPath) }
    }

    private fun requireDownloadDestination(destination: File) {
        require(destination.isAbsolute) {
            "Update destination must be absolute"
        }
        require(destination.name.endsWith(PART_SUFFIX, ignoreCase = false)) {
            "Update destination must end in .part"
        }
        val parent = destination.parentFile
        require(parent != null && parent.isDirectory) {
            "Update destination parent is unavailable"
        }
        require(parent.canWrite()) {
            "Update destination parent is not writable"
        }
        require(!java.nio.file.Files.isSymbolicLink(destination.toPath())) {
            "Update destination must not be a symlink"
        }
    }

    private fun downloadBounded(
        url: String,
        expectedSize: Long,
        limit: Int,
        control: ReleaseRequestControl?,
        ensureActive: () -> Unit,
    ): ByteArray {
        val response = followDownloadRedirects(url, expectedSize, control, ensureActive)
        return response.use {
            if (it.contentLength != null && it.contentLength != expectedSize) {
                throw AppReleaseException.Protocol()
            }
            val bytes = readBounded(it, limit, control, ensureActive)
            if (bytes.size.toLong() != expectedSize) throw AppReleaseException.Protocol()
            bytes
        }
    }

    private fun followDownloadRedirects(
        initialUrl: String,
        expectedSize: Long,
        control: ReleaseRequestControl?,
        ensureActive: () -> Unit,
    ): AppReleaseHttpResponse {
        var url = validateDownloadUrl(initialUrl)
        repeat(MAX_REDIRECTS + 1) { redirect ->
            active(control, ensureActive)
            val response = transport.execute(
                AppReleaseHttpRequest(url, "application/octet-stream"),
                control,
                ensureActive,
            )
            active(control, ensureActive)
            if (response.code in REDIRECT_CODES) {
                response.close()
                if (redirect == MAX_REDIRECTS) throw AppReleaseException.Protocol()
                val location = response.location ?: throw AppReleaseException.Protocol()
                url = try {
                    validateDownloadUrl(URI(url).resolve(location).toString())
                } catch (_: Exception) {
                    throw AppReleaseException.Protocol()
                }
                return@repeat
            }
            try {
                requireSuccess(response, expectedSize)
            } catch (failure: Exception) {
                try { response.close() } catch (_: IOException) { /* Preserve the fixed result. */ }
                throw failure
            }
            return response
        }
        throw AppReleaseException.Protocol()
    }

    private fun requestWithoutRedirect(
        url: String,
        accept: String,
        control: ReleaseRequestControl?,
        ensureActive: () -> Unit,
    ): AppReleaseHttpResponse {
        val response = transport.execute(
            AppReleaseHttpRequest(url, accept),
            control,
            ensureActive,
        )
        if (response.code in REDIRECT_CODES) {
            response.close()
            throw AppReleaseException.Protocol()
        }
        return response
    }

    private fun requireSuccess(response: AppReleaseHttpResponse, expectedSize: Long?) {
        // Anonymous requests only target the fixed public repository. GitHub reports rate limits
        // with 403 as well as 429, and neither response offers the app another safe action.
        if (response.code == 403 || response.code == 429) {
            throw AppReleaseException.RateLimited()
        }
        if (response.code != 200) throw AppReleaseException.Unavailable()
        val length = response.contentLength
        if (length != null) {
            if (length < 0 || length > MAX_APK_BYTES) throw AppReleaseException.Protocol()
            if (expectedSize != null && length != expectedSize) throw AppReleaseException.Protocol()
        }
    }

    private fun readBounded(
        response: AppReleaseHttpResponse,
        limit: Int,
        control: ReleaseRequestControl?,
        ensureActive: () -> Unit,
    ): ByteArray {
        val length = response.contentLength
        if (length != null && (length < 0 || length > limit)) throw AppReleaseException.Protocol()
        val output = ByteArrayOutputStream(minOf(length ?: BUFFER_BYTES.toLong(), BUFFER_BYTES.toLong()).toInt())
        val input = response.body
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            active(control, ensureActive)
            val available = limit - output.size()
            val read = input.read(buffer, 0, minOf(buffer.size, available + 1))
            active(control, ensureActive)
            if (read < 0) break
            if (read == 0) continue
            if (read > available) throw AppReleaseException.Protocol()
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun parseLatest(raw: String): ParsedRelease {
        val root = json.parseToJsonElement(raw) as? JsonObject ?: throw AppReleaseException.Protocol()
        if (root.requiredBoolean("draft") || root.requiredBoolean("prerelease")) {
            throw AppReleaseException.Protocol()
        }
        val tag = root.requiredBoundedString("tag_name", MAX_TAG_CHARS)
        val tagMatch = TAG_PATTERN.matchEntire(tag) ?: throw AppReleaseException.Protocol()
        val versionName = tagMatch.groupValues[1]
        val tagSha = tagMatch.groupValues[2]
        val releaseName = (root["name"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.let(::safeNotes)
            ?.takeIf { it.isNotBlank() }
            ?: tag
        val notes = (root["body"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.let(::safeNotes)
            .orEmpty()
        val assets = root["assets"] as? JsonArray ?: throw AppReleaseException.Protocol()
        if (assets.size != 4) throw AppReleaseException.Protocol()

        val names = setOf(
            "build-info.txt",
            "mobile-core-build-info.txt",
            "magpie-mobile-$versionName.apk",
            "magpie-mobile-$versionName.apk.sha256",
        )
        val parsed = mutableMapOf<String, ParsedAsset>()
        for (element in assets) {
            val asset = element as? JsonObject ?: throw AppReleaseException.Protocol()
            val name = asset.requiredBoundedString("name", MAX_ASSET_NAME_CHARS)
            if (name !in names || name in parsed) throw AppReleaseException.Protocol()
            if (asset.requiredBoundedString("state", 32) != "uploaded") {
                throw AppReleaseException.Protocol()
            }
            val size = asset.requiredPositiveLong("size") ?: throw AppReleaseException.Protocol()
            val digest = asset.requiredBoundedString("digest", "sha256:".length + 64)
            if (!SHA256_ASSET_DIGEST.matches(digest)) throw AppReleaseException.Protocol()
            val max = when {
                name.endsWith(".apk") -> MAX_APK_BYTES
                name.endsWith(".apk.sha256") -> MAX_CHECKSUM_BYTES.toLong()
                name == "build-info.txt" -> MAX_BUILD_INFO_BYTES.toLong()
                else -> MAX_CORE_INFO_BYTES.toLong()
            }
            if (size > max) throw AppReleaseException.Protocol()
            val expectedDownloadUrl = assetDownloadUrl(tag, name)
            val declaredDownloadUrl =
                asset.requiredBoundedString("browser_download_url", MAX_ASSET_URL_CHARS)
            if (declaredDownloadUrl != expectedDownloadUrl) {
                throw AppReleaseException.Protocol()
            }
            parsed[name] = ParsedAsset(
                name = name,
                size = size,
                sha256 = digest.substringAfter(':'),
                url = expectedDownloadUrl,
            )
        }
        if (parsed.keys != names) throw AppReleaseException.Protocol()

        val apkName = "magpie-mobile-$versionName.apk"
        return ParsedRelease(
            tag = tag,
            tagSha = tagSha,
            versionName = versionName,
            releaseName = releaseName,
            releaseUrl = releaseUrl(tag),
            notes = notes,
            apk = parsed.getValue(apkName),
            buildInfo = parsed.getValue("build-info.txt"),
            checksum = parsed.getValue("$apkName.sha256"),
        )
    }

    private fun parseChecksum(
        raw: ByteArray,
        tag: String,
        versionName: String,
        apkName: String,
    ): String {
        val text = try {
            raw.toString(Charsets.UTF_8)
        } catch (_: Exception) {
            throw AppReleaseException.Protocol()
        }
        val match = CHECKSUM_PATTERN.matchEntire(text) ?: throw AppReleaseException.Protocol()
        if (match.groupValues[2] != apkName ||
            !tag.startsWith("v$versionName-")
        ) {
            throw AppReleaseException.Protocol()
        }
        return match.groupValues[1]
    }

    /**
     * Return null only when every new machine field is absent. This is the legacy-protocol bridge
     * for the currently public release; it deliberately does not infer a version code from a tag.
     */
    private fun parseMachineBuildInfo(raw: String, versionName: String, tagSha: String): MachineBuildInfo? {
        val machineKeys = setOf(
            "Package",
            "VersionName",
            "VersionCode",
            "CoreVersion",
            "UpstreamTag",
            "UpstreamRevision",
            "Commit",
        )
        val protocolKeys = machineKeys - "Commit"
        val values = mutableMapOf<String, String>()
        for (line in raw.lineSequence()) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val key = line.substring(0, colon)
            if (key !in machineKeys) continue
            if (key in values) throw AppReleaseException.Protocol()
            values[key] = line.substring(colon + 1).trim()
        }
        if (protocolKeys.none { it in values }) return null
        if (!values.keys.containsAll(machineKeys)) throw AppReleaseException.Protocol()

        if (values.getValue("Package") != PACKAGE_NAME ||
            values.getValue("VersionName") != versionName
        ) {
            throw AppReleaseException.Protocol()
        }
        val versionCode = values.getValue("VersionCode")
            .takeIf { POSITIVE_INTEGER.matches(it) }
            ?.toLongOrNull()
            ?.takeIf { it in 1..Int.MAX_VALUE.toLong() }
            ?: throw AppReleaseException.Protocol()
        val core = values.getValue("CoreVersion")
        val upstreamTag = values.getValue("UpstreamTag")
        val upstreamRevision = values.getValue("UpstreamRevision")
        val commit = values.getValue("Commit")
        if (!VERSION_TOKEN.matches(core) ||
            !UPSTREAM_TAG.matches(upstreamTag) ||
            !HEX_40.matches(upstreamRevision) ||
            !HEX_40.matches(commit) ||
            !commit.startsWith(tagSha)
        ) {
            throw AppReleaseException.Protocol()
        }
        return MachineBuildInfo(
            versionCode = versionCode,
            versionName = versionName,
            coreVersion = core,
            upstreamTag = upstreamTag,
            upstreamRevision = upstreamRevision,
            commit = commit,
        )
    }

    private fun active(
        control: ReleaseRequestControl?,
        ensureActive: () -> Unit,
    ) {
        ensureActive()
        control?.ensureActive()
        ensureActive()
    }

    private fun <T> guarded(
        control: ReleaseRequestControl?,
        ensureActive: () -> Unit,
        failureCleanup: () -> Unit = {},
        operation: () -> T,
    ): T {
        active(control, ensureActive)
        return try {
            operation().also { active(control, ensureActive) }
        } catch (cancelled: CancellationException) {
            failureCleanup()
            active(control, ensureActive)
            throw cancelled
        } catch (failure: AppReleaseException) {
            failureCleanup()
            active(control, ensureActive)
            throw failure
        } catch (_: Exception) {
            failureCleanup()
            active(control, ensureActive)
            throw AppReleaseException.Unavailable()
        }
    }

    private companion object {
        const val REPOSITORY = "fplj-fplj/magpie-mobile"
        const val PACKAGE_NAME = "cc.fplj.magpiemobile"
        const val API_LATEST_URL =
            "https://api.github.com/repos/$REPOSITORY/releases/latest"
        const val RELEASE_WEB_ROOT =
            "https://github.com/$REPOSITORY/releases/tag"
        const val ASSET_DOWNLOAD_ROOT =
            "https://github.com/$REPOSITORY/releases/download"

        const val BUFFER_BYTES = 64 * 1024
        const val MAX_RELEASE_JSON_BYTES = 512 * 1024
        const val MAX_BUILD_INFO_BYTES = 64 * 1024
        const val MAX_CORE_INFO_BYTES = 256 * 1024
        const val MAX_CHECKSUM_BYTES = 4 * 1024
        const val MAX_APK_BYTES = 200L * 1024 * 1024
        const val MAX_REDIRECTS = 5
        const val MAX_TAG_CHARS = 160
        const val MAX_ASSET_NAME_CHARS = 200
        const val MAX_ASSET_URL_CHARS = 512
        const val PART_SUFFIX = ".part"

        val TAG_PATTERN = Regex(
            "^v([0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z][0-9A-Za-z._-]*)?)-([0-9a-f]{8})$",
        )
        val SHA256_ASSET_DIGEST = Regex("^sha256:[0-9a-f]{64}$")
        val CHECKSUM_PATTERN = Regex("^([0-9a-f]{64})  ([^\\r\\n]+)\\n$")
        val HEX_40 = Regex("^[0-9a-f]{40}$")
        val POSITIVE_INTEGER = Regex("^[1-9][0-9]{0,9}$")
        val VERSION_TOKEN = Regex("^[0-9A-Za-z][0-9A-Za-z._+-]{0,127}$")
        val UPSTREAM_TAG = Regex("^[0-9A-Za-z][0-9A-Za-z._+-]{0,127}$")
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        val DOWNLOAD_HOSTS = setOf(
            "github.com",
            "release-assets.githubusercontent.com",
            "objects.githubusercontent.com",
        )
        val activeDownloadDestinations = ConcurrentHashMap.newKeySet<String>()

        fun releaseUrl(tag: String): String = "$RELEASE_WEB_ROOT/$tag"
        fun assetDownloadUrl(tag: String, name: String): String = "$ASSET_DOWNLOAD_ROOT/$tag/$name"

        fun validateDownloadUrl(value: String): String {
            val uri = try {
                URI(value)
            } catch (_: Exception) {
                throw AppReleaseException.Protocol()
            }
            if (uri.scheme != "https" ||
                uri.rawUserInfo != null ||
                uri.host?.lowercase(Locale.ROOT) !in DOWNLOAD_HOSTS ||
                (uri.port != -1 && uri.port != 443) ||
                uri.rawFragment != null
            ) {
                throw AppReleaseException.Protocol()
            }
            return uri.toASCIIString()
        }

        fun safeNotes(value: String): String =
            value
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .filter { it == '\n' || it == '\t' || !it.isISOControl() }
                .trim()
                .take(MAX_NOTES_CHARS)

        const val MAX_NOTES_CHARS = 16 * 1024

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }

        fun deletePart(file: File) {
            try {
                file.delete()
            } catch (_: Exception) {
                // Best effort: the terminal failure or cancellation remains authoritative.
            }
        }
    }

    private data class ParsedAsset(
        val name: String,
        val size: Long,
        val sha256: String,
        val url: String,
    )

    private data class ParsedRelease(
        val tag: String,
        val tagSha: String,
        val versionName: String,
        val releaseName: String,
        val releaseUrl: String,
        val notes: String,
        val apk: ParsedAsset,
        val buildInfo: ParsedAsset,
        val checksum: ParsedAsset,
    )

    private data class MachineBuildInfo(
        val versionCode: Long,
        val versionName: String,
        val coreVersion: String,
        val upstreamTag: String,
        val upstreamRevision: String,
        val commit: String,
    )
}

/** Thread-safe cancellation shared by a metadata request and at most one APK download. */
class ReleaseRequestControl {
    private val cancelled = AtomicBoolean()
    private val actions = ConcurrentHashMap.newKeySet<() -> Unit>()

    fun cancel() {
        if (!cancelled.compareAndSet(false, true)) return
        actions.forEach { action ->
            try { action() } catch (_: Exception) { /* Fixed cancellation is authoritative. */ }
        }
    }

    fun ensureActive() {
        if (cancelled.get()) throw CancellationException("Release request cancelled")
    }

    internal fun register(cancelAction: () -> Unit): Closeable {
        ensureActive()
        val invoked = AtomicBoolean()
        val registered = { if (invoked.compareAndSet(false, true)) cancelAction() }
        actions.add(registered)
        if (cancelled.get()) {
            actions.remove(registered)
            registered()
            ensureActive()
        }
        return Closeable { actions.remove(registered) }
    }
}

sealed class AppReleaseResult {
    data class Available(val release: AppRelease) : AppReleaseResult()

    /**
     * The latest Release still uses the old build-info format. The UI may open [releaseUrl], but
     * must not compare or install that APK through the typed update path.
     */
    data class ProtocolUpgradeRequired(
        val tag: String,
        val releaseName: String,
        val releaseUrl: String,
        val notes: String,
    ) : AppReleaseResult()
}

class AppRelease internal constructor(
    val versionName: String,
    val versionCode: Long,
    val coreVersion: String,
    val sourceCommit: String,
    val upstreamTag: String,
    val upstreamRevision: String,
    val tag: String,
    val releaseName: String,
    val releaseUrl: String,
    val notes: String,
    val apkSize: Long,
    val apkSha256: String,
    internal val apkAssetName: String,
    internal val apkDownloadUrl: String,
)

/** Fixed, user-safe failure categories. Messages never contain a URL, response body or cause. */
sealed class AppReleaseException(message: String) : Exception(message) {
    class Unavailable internal constructor() :
        AppReleaseException("无法连接 GitHub 更新服务，请稍后重试。")

    class Protocol internal constructor() :
        AppReleaseException("GitHub Release 信息不完整或格式不受支持。")

    class RateLimited internal constructor() :
        AppReleaseException("GitHub 更新服务请求过于频繁，请稍后重试。")
}

internal data class AppReleaseHttpRequest(
    val url: String,
    val accept: String,
)

internal abstract class AppReleaseHttpResponse(
    val code: Int,
    val location: String?,
    val contentLength: Long?,
    val rateLimitRemaining: String?,
    val body: InputStream,
) : Closeable {
    abstract override fun close()
}

internal interface AppReleaseTransport {
    fun execute(
        request: AppReleaseHttpRequest,
        control: ReleaseRequestControl?,
        ensureActive: () -> Unit,
    ): AppReleaseHttpResponse
}

private class OkHttpAppReleaseTransport : AppReleaseTransport {
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .cookieJar(CookieJar.NO_COOKIES)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun execute(
        request: AppReleaseHttpRequest,
        control: ReleaseRequestControl?,
        ensureActive: () -> Unit,
    ): AppReleaseHttpResponse {
        ensureActive()
        control?.ensureActive()
        val builder = Request.Builder()
            .url(request.url)
            .header("Accept", request.accept)
            .header("Accept-Encoding", "identity")
            .header("User-Agent", "magpie-mobile-android-updater")
            .get()
        if (request.accept == "application/vnd.github+json") {
            builder.header("X-GitHub-Api-Version", "2022-11-28")
        }
        val call = client.newCall(builder.build())
        val registration = control?.register(call::cancel)
        try {
            ensureActive()
            control?.ensureActive()
            val response = call.execute()
            ensureActive()
            control?.ensureActive()
            val body = response.body?.byteStream() ?: run {
                response.close()
                throw AppReleaseException.Protocol()
            }
            return object : AppReleaseHttpResponse(
                code = response.code,
                location = response.header("Location"),
                contentLength = response.body?.contentLength()?.takeIf { it >= 0 },
                rateLimitRemaining = response.header("X-RateLimit-Remaining"),
                body = body,
            ) {
                override fun close() {
                    call.cancel()
                    try { response.close() } catch (_: IOException) { /* Cleanup is best effort. */ }
                    registration?.close()
                }
            }
        } catch (failure: Throwable) {
            call.cancel()
            registration?.close()
            throw failure
        }
    }
}

private fun JsonObject.requiredBoolean(key: String): Boolean {
    val primitive = this[key] as? JsonPrimitive ?: throw AppReleaseException.Protocol()
    if (primitive.isString) throw AppReleaseException.Protocol()
    return primitive.booleanOrNull ?: throw AppReleaseException.Protocol()
}

private fun JsonObject.requiredBoundedString(key: String, maxLength: Int): String {
    val primitive = this[key] as? JsonPrimitive ?: throw AppReleaseException.Protocol()
    if (!primitive.isString) throw AppReleaseException.Protocol()
    val value = primitive.content
    if (value.isEmpty() || value.length > maxLength || value.any { it.isISOControl() }) {
        throw AppReleaseException.Protocol()
    }
    return value
}

private fun JsonObject.requiredPositiveLong(key: String): Long? {
    val primitive = this[key] as? JsonPrimitive ?: throw AppReleaseException.Protocol()
    if (primitive.isString) throw AppReleaseException.Protocol()
    return primitive.longOrNull?.takeIf { it > 0 }
}

package cc.fplj.magpiemobile.core

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppReleaseClientTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun latestReturnsOnlyMachineFieldsAndDerivesEveryPublicUrl() {
        val fixture = Fixture()
        val transport = FixtureTransport(fixture)
        val result = AppReleaseClient(transport).latest()
        val release = (result as AppReleaseResult.Available).release

        assertEquals(67L, release.versionCode)
        assertEquals("0.1.66-update-center", release.versionName)
        assertEquals("0.1.3781-androidhost.5", release.coreVersion)
        assertEquals(fixture.commit, release.sourceCommit)
        assertEquals("v0.1.4000", release.upstreamTag)
        assertEquals("a".repeat(40), release.upstreamRevision)
        assertEquals(fixture.tag, release.tag)
        assertEquals("https://github.com/fplj-fplj/magpie-mobile/releases/tag/${fixture.tag}", release.releaseUrl)
        assertEquals(fixture.apkData.size.toLong(), release.apkSize)
        assertEquals(fixture.apkDigest, release.apkSha256)

        assertFalse(transport.urls.any { it.contains("evil.example") })
        assertTrue(transport.urls.first().startsWith("https://api.github.com/repos/fplj-fplj/magpie-mobile/releases/latest"))
        assertTrue(transport.urls.drop(1).all {
            it.startsWith("https://github.com/fplj-fplj/magpie-mobile/releases/download/${fixture.tag}/")
        })
    }

    @Test
    fun legacyBuildInfoRequestsNewProtocolWithoutGuessingVersionCode() {
        val fixture = Fixture(
            buildInfo = """
                Commit: ${"d".repeat(8)}${"e".repeat(32)}
                Core: 0.1.3781-androidhost.4
                Validation: old
            """.trimIndent(),
        )
        val result = AppReleaseClient(FixtureTransport(fixture)).latest()
        val legacy = result as AppReleaseResult.ProtocolUpgradeRequired

        assertEquals(fixture.tag, legacy.tag)
        assertEquals(
            "https://github.com/fplj-fplj/magpie-mobile/releases/tag/${fixture.tag}",
            legacy.releaseUrl,
        )
        assertEquals(fixture.notes, legacy.notes)
    }

    @Test
    fun partiallyPresentMachineFieldsAreAProtocolFailure() {
        val fixture = Fixture(
            buildInfo = """
                VersionCode: 67
                Commit: ${"d".repeat(40)}
            """.trimIndent(),
        )
        val failure = assertFailsWith<AppReleaseException.Protocol> {
            AppReleaseClient(FixtureTransport(fixture)).latest()
        }
        assertEquals("GitHub Release 信息不完整或格式不受支持。", failure.message)
    }

    @Test
    fun everyAssetRequiresSha256DigestAndExactFourNames() {
        val fixture = Fixture()
        val transport = FixtureTransport(fixture)
        transport.latestResponse = response(
            fixture.latestJson.replaceFirst("sha256:", "sha512:").toByteArray(),
            contentType = "application/json",
        )
        val failure = assertFailsWith<AppReleaseException.Protocol> {
            AppReleaseClient(transport).latest()
        }
        assertFalse(failure.message.orEmpty().contains(fixture.tag))
    }

    @Test
    fun browserDownloadUrlMustBeTheExactFixedRepositoryAssetUrl() {
        val fixture = Fixture()
        val transport = FixtureTransport(fixture)
        val expected = "https://github.com/fplj-fplj/magpie-mobile/releases/download/" +
            "${fixture.tag}/build-info.txt"
        transport.latestResponse = response(
            fixture.latestJson
                .replaceFirst(expected, "https://evil.example/build-info.txt")
                .toByteArray(),
            contentType = "application/json",
        )

        assertFailsWith<AppReleaseException.Protocol> {
            AppReleaseClient(transport).latest()
        }
        assertEquals(1, transport.urls.size)
    }

    @Test
    fun malformedJsonAndConnectionFailuresUseFixedCategories() {
        val fixture = Fixture()
        val malformedTransport = FixtureTransport(fixture)
        malformedTransport.latestResponse = response("{not-json".toByteArray())
        assertFailsWith<AppReleaseException.Protocol> {
            AppReleaseClient(malformedTransport).latest()
        }

        val failedTransport = FixtureTransport(fixture)
        failedTransport.latestFailure = IOException("private upstream detail")
        val failure = assertFailsWith<AppReleaseException.Unavailable> {
            AppReleaseClient(failedTransport).latest()
        }
        assertEquals("无法连接 GitHub 更新服务，请稍后重试。", failure.message)
        assertFalse(failure.message.orEmpty().contains("private"))
    }

    @Test
    fun declaredApkSizeAboveHardLimitIsRejectedBeforeAssetDownload() {
        val fixture = Fixture(apkDeclaredSize = 200L * 1024 * 1024 + 1)
        val transport = FixtureTransport(fixture)

        assertFailsWith<AppReleaseException.Protocol> {
            AppReleaseClient(transport).latest()
        }
        assertEquals(1, transport.urls.size)
    }

    @Test
    fun apiRedirectIsRejectedWithoutFollowingIt() {
        val fixture = Fixture()
        val transport = FixtureTransport(fixture)
        transport.latestResponse = response(
            ByteArray(0),
            code = 302,
            location = "https://github.com/fplj-fplj/magpie-mobile/releases/latest",
        )
        assertFailsWith<AppReleaseException.Protocol> {
            AppReleaseClient(transport).latest()
        }
        assertEquals(1, transport.urls.size)
    }

    @Test
    fun onlyHttpsGitHubRedirectHostsAreAccepted() {
        val fixture = Fixture()
        val transport = FixtureTransport(fixture)
        val client = AppReleaseClient(transport)
        val release = (client.latest() as AppReleaseResult.Available).release

        transport.apkResponse = { response(
            ByteArray(0),
            code = 302,
            location = "https://release-assets.githubusercontent.com/github-production-release-asset/file",
        ) }
        transport.redirectedApkResponse = { response(fixture.apkData) }
        val downloaded = client.download(release, partFile())
        assertEquals(fixture.apkData, downloaded.readBytes())
        assertTrue(transport.urls.any {
            it.startsWith("https://release-assets.githubusercontent.com/")
        })

        downloaded.delete()
        transport.apkResponse = { response(
            ByteArray(0),
            code = 302,
            location = "https://evil.example/update.apk",
        ) }
        assertFailsWith<AppReleaseException.Protocol> {
            client.download(release, partFile())
        }
        assertFalse(partFile().exists())
    }

    @Test
    fun declaredAndActualSizeBoundsCleanOnlyThePart() {
        val fixture = Fixture()
        val transport = FixtureTransport(fixture)
        val client = AppReleaseClient(transport)
        val release = (client.latest() as AppReleaseResult.Available).release
        val finalized = File(directory, "magpie-update.apk").apply { writeText("keep-valid") }
        val part = partFile()

        transport.apkResponse = {
            response(ByteArray(1), contentLength = release.apkSize + 1)
        }
        assertFailsWith<AppReleaseException.Protocol> {
            client.download(release, part)
        }
        assertFalse(part.exists())
        assertEquals("keep-valid", finalized.readText())
    }

    @Test
    fun countAndHashMismatchesCleanThePart() {
        val fixture = Fixture()
        val transport = FixtureTransport(fixture)
        val client = AppReleaseClient(transport)
        val release = (client.latest() as AppReleaseResult.Available).release
        val finalized = File(directory, "previous.apk").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        transport.apkResponse = { response(fixture.apkData.copyOf(fixture.apkData.size - 1)) }
        assertFailsWith<AppReleaseException.Protocol> {
            client.download(release, partFile())
        }
        assertFalse(partFile().exists())

        val wrongSameSize = fixture.apkData.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        transport.apkResponse = { response(wrongSameSize) }
        assertFailsWith<AppReleaseException.Protocol> {
            client.download(release, partFile())
        }
        assertFalse(partFile().exists())
        assertEquals(listOf<Byte>(1, 2, 3), finalized.readBytes().toList())
    }

    @Test
    fun cancellationUsesCancellationExceptionAndRemovesThePart() {
        val fixture = Fixture()
        val transport = FixtureTransport(fixture)
        val client = AppReleaseClient(transport)
        val release = (client.latest() as AppReleaseResult.Available).release
        val control = ReleaseRequestControl()

        val failure = assertFailsWith<CancellationException> {
            client.download(
                release = release,
                destination = partFile(),
                progress = { loaded, _ ->
                    if (loaded > 0) control.cancel()
                },
                control = control,
            )
        }
        assertTrue(failure !is AppReleaseException)
        assertFalse(partFile().exists())
    }

    @Test
    fun postCompletionCancellationStillRemovesThePart() {
        val fixture = Fixture()
        val transport = FixtureTransport(fixture)
        val client = AppReleaseClient(transport)
        val release = (client.latest() as AppReleaseResult.Available).release

        var probeChecks = 0
        val probe = File(directory, "probe.apk.part")
        assertEquals(fixture.apkData, client.download(
            release = release,
            destination = probe,
            ensureActive = { probeChecks++ },
        ).readBytes())
        assertTrue(probeChecks > 0)
        assertTrue(probe.delete())

        val target = File(directory, "post-completion.apk.part")
        var checks = 0
        assertFailsWith<CancellationException> {
            client.download(
                release = release,
                destination = target,
                ensureActive = {
                    checks++
                    if (checks == probeChecks) {
                        throw CancellationException("cancelled after the completed operation")
                    }
                },
            )
        }
        assertFalse(target.exists())
    }

    @Test
    fun concurrentAliasesOfTheSameDestinationAreRejectedWithoutTouchingItsPart() {
        val fixture = Fixture()
        val transport = FixtureTransport(fixture)
        val client = AppReleaseClient(transport)
        val release = (client.latest() as AppReleaseResult.Available).release
        val downloadStarted = CountDownLatch(1)
        val allowDownload = CountDownLatch(1)
        transport.apkResponse = {
            streamingResponse(
                BlockingInputStream(fixture.apkData, downloadStarted, allowDownload),
                fixture.apkData.size.toLong(),
            )
        }

        val executor = Executors.newFixedThreadPool(2)
        try {
            val primary = File(directory, "canonical-race.apk.part")
            val aliasDirectory = File(directory, "alias").apply {
                assertTrue(mkdir())
            }
            val canonicalAlias = File(aliasDirectory, "../canonical-race.apk.part")
            assertEquals(primary.canonicalPath, canonicalAlias.canonicalPath)
            assertTrue(primary.path != canonicalAlias.path)

            val first = executor.submit<File> {
                client.download(release, primary)
            }
            assertTrue(downloadStarted.await(5, TimeUnit.SECONDS))
            assertTrue(primary.exists())

            val competing = executor.submit<File> {
                client.download(release, canonicalAlias)
            }
            val collision = assertFailsWith<ExecutionException> {
                competing.get(5, TimeUnit.SECONDS)
            }
            assertTrue(collision.cause is IllegalStateException)
            assertTrue(primary.exists())
            assertEquals(0L, primary.length())

            allowDownload.countDown()
            assertEquals(fixture.apkData, first.get(5, TimeUnit.SECONDS).readBytes())
        } finally {
            allowDownload.countDown()
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun invalidDestinationIsACallerErrorInsteadOfANetworkFailure() {
        val fixture = Fixture()
        val client = AppReleaseClient(FixtureTransport(fixture))
        val release = (client.latest() as AppReleaseResult.Available).release

        assertFailsWith<IllegalArgumentException> {
            client.download(release, File("relative.apk.part"))
        }
    }

    @Test
    fun anonymousRateLimitHasAFixedSafeMessage() {
        val fixture = Fixture()
        val transport = FixtureTransport(fixture)
        transport.latestResponse = response(
            "{\"message\":\"secret upstream detail\"}".toByteArray(),
            code = 403,
        )
        val failure = assertFailsWith<AppReleaseException.RateLimited> {
            AppReleaseClient(transport).latest()
        }
        assertEquals("GitHub 更新服务请求过于频繁，请稍后重试。", failure.message)
        assertFalse(failure.message.orEmpty().contains("secret"))
    }

    private fun partFile() = File(directory, "magpie-update.apk.part")

    private data class Fixture(
        val buildInfo: String = """
            Package: cc.fplj.magpiemobile
            VersionName: 0.1.66-update-center
            VersionCode: 67
            CoreVersion: 0.1.3781-androidhost.5
            UpstreamTag: v0.1.4000
            UpstreamRevision: ${"a".repeat(40)}
            Commit: ${"d".repeat(40)}
            Actions run: https://github.com/fplj-fplj/magpie-mobile/actions/runs/1
        """.trimIndent(),
        val apkData: ByteArray = "verified-apk-payload".toByteArray(),
        val apkDeclaredSize: Long? = null,
    ) {
        val tag = "v0.1.66-update-center-${"d".repeat(8)}"
        val versionName = "0.1.66-update-center"
        val commit = "d".repeat(40)
        val apkName = "magpie-mobile-$versionName.apk"
        val notes = "更新摘要与核心 `v0.1.4000` 同步。"
        val apkDigest = sha256(apkData)
        val buildInfoBytes = buildInfo.toByteArray()
        val coreInfo = "go version -m core".toByteArray()
        val checksum = "$apkDigest  $apkName\n".toByteArray()
        val latestJson = buildJsonObject {
            put("draft", false)
            put("prerelease", false)
            put("tag_name", tag)
            put("name", "Magpie-Mobile $versionName")
            put("body", notes)
            put("assets", buildJsonArray {
                add(asset("$apkName.sha256", checksum))
                add(asset("build-info.txt", buildInfoBytes))
                add(asset("mobile-core-build-info.txt", coreInfo))
                add(asset(apkName, apkData))
            })
        }.toString()

        private fun asset(name: String, bytes: ByteArray) = buildJsonObject {
            put("name", name)
            put("size", if (name == apkName) apkDeclaredSize ?: bytes.size.toLong() else bytes.size)
            put("digest", "sha256:${sha256(bytes)}")
            put("state", "uploaded")
            put(
                "browser_download_url",
                "https://github.com/fplj-fplj/magpie-mobile/releases/download/$tag/$name",
            )
        }
    }

    private class FixtureTransport(
        var fixture: Fixture,
    ) : AppReleaseTransport {
        val urls = mutableListOf<String>()
        var latestResponse: AppReleaseHttpResponse? = null
        var buildInfoResponse: AppReleaseHttpResponse? = null
        var checksumResponse: AppReleaseHttpResponse? = null
        var latestFailure: Exception? = null
        var apkResponse: ((AppReleaseHttpRequest) -> AppReleaseHttpResponse)? = null
        var redirectedApkResponse: ((AppReleaseHttpRequest) -> AppReleaseHttpResponse)? = null

        override fun execute(
            request: AppReleaseHttpRequest,
            control: ReleaseRequestControl?,
            ensureActive: () -> Unit,
        ): AppReleaseHttpResponse {
            ensureActive()
            control?.ensureActive()
            urls += request.url
            latestFailure?.let { latestFailure = null; throw it }
            latestResponse?.let { latestResponse = null; return it }
            buildInfoResponse?.let { buildInfoResponse = null; return it }
            checksumResponse?.let { checksumResponse = null; return it }
            if (request.url.startsWith("https://release-assets.githubusercontent.com/")) {
                return redirectedApkResponse?.invoke(request)
                    ?: response(fixture.apkData)
            }
            if (request.url.startsWith("https://github.com/fplj-fplj/magpie-mobile/releases/download/${fixture.tag}/")) {
                return when {
                    request.url.endsWith("/build-info.txt") -> response(fixture.buildInfoBytes)
                    request.url.endsWith("/${fixture.apkName}.sha256") -> response(fixture.checksum)
                    request.url.endsWith("/${fixture.apkName}") -> apkResponse?.invoke(request)
                        ?: response(fixture.apkData)
                    else -> throw AssertionError("Unexpected fixed asset URL: ${request.url}")
                }
            }
            if (request.url == "https://api.github.com/repos/fplj-fplj/magpie-mobile/releases/latest") {
                return response(fixture.latestJson.toByteArray())
            }
            throw AssertionError("Unexpected release URL: ${request.url}")
        }
    }

    private class FixtureResponse(
        code: Int,
        location: String?,
        contentLength: Long?,
        rateLimitRemaining: String?,
        body: InputStream,
    ) : AppReleaseHttpResponse(
        code = code,
        location = location,
        contentLength = contentLength,
        rateLimitRemaining = rateLimitRemaining,
        body = body,
    ) {
        override fun close() {
            try {
                body.close()
            } catch (_: IOException) {
                // Test cleanup only.
            }
        }
    }

    private class BlockingInputStream(
        bytes: ByteArray,
        private val downloadStarted: CountDownLatch,
        private val allowDownload: CountDownLatch,
    ) : InputStream() {
        private val delegate = ByteArrayInputStream(bytes)

        override fun read(): Int {
            awaitDownload()
            return delegate.read()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            awaitDownload()
            return delegate.read(buffer, offset, length)
        }

        override fun close() {
            allowDownload.countDown()
            delegate.close()
        }

        private fun awaitDownload() {
            downloadStarted.countDown()
            if (!allowDownload.await(10, TimeUnit.SECONDS)) {
                throw IOException("test download was not released")
            }
        }
    }

    private companion object {
        fun response(
            body: ByteArray,
            code: Int = 200,
            location: String? = null,
            contentLength: Long? = body.size.toLong(),
            rateRemaining: String? = null,
            contentType: String? = null,
        ): AppReleaseHttpResponse {
            // Content type is intentionally not trusted by the client; keep the argument so tests
            // can demonstrate that GitHub's text/plain and octet-stream assets are both acceptable.
            contentType?.let { }
            return FixtureResponse(
                code,
                location,
                contentLength,
                rateRemaining,
                ByteArrayInputStream(body),
            )
        }

        fun streamingResponse(
            body: InputStream,
            contentLength: Long,
        ): AppReleaseHttpResponse = FixtureResponse(
            code = 200,
            location = null,
            contentLength = contentLength,
            rateLimitRemaining = null,
            body = body,
        )

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

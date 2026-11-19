package cc.fplj.magpiemobile.core

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PostMortemAuditorTest {

    @Test
    fun recordsAndReadsSanitizedReport(@TempDir tempDir: File) {
        val auditor = PostMortemAuditor(tempDir)
        assertNull(auditor.readReport())

        val secrets = listOf("sk-magpie-key-", "sk-", "bearer-", "token-", "password-", "api-", "management-")
            .map(::syntheticSecret)
        val leakyMessage = """
            Process failed with gateway=${secrets[0]} and api=${secrets[1]}
            Authorization: Bearer ${secrets[2]}
            token=${secrets[3]} password=${secrets[4]} api_key=${secrets[5]}
            http://127.0.0.1:8080/editor?k=${secrets[6]}
        """.trimIndent()
        val report = auditor.recordAbnormalExit("BACKOFF", 137, leakyMessage)

        assertEquals("BACKOFF", report.lastKnownPhase)
        assertEquals(137, report.exitCode)
        val storedText = File(tempDir, PostMortemAuditor.AUDIT_FILE_NAME).readText()
        secrets.forEach { secret ->
            assertFullyMasked(secret, report.sanitizedDetail)
            assertFullyMasked(secret, storedText)
        }

        val readBack = auditor.readReport()
        assertNotNull(readBack)
        assertEquals(report.timestamp, readBack.timestamp)
        assertEquals(report.lastKnownPhase, readBack.lastKnownPhase)
        assertEquals(report.exitCode, readBack.exitCode)
        assertEquals(report.sanitizedDetail, readBack.sanitizedDetail)
        secrets.forEach { assertFullyMasked(it, readBack.sanitizedDetail) }

        auditor.clear()
        assertNull(auditor.readReport())
    }

    @Test
    fun preservesObservedIntegerExitCodes(@TempDir tempDir: File) {
        val auditor = PostMortemAuditor(tempDir)
        listOf(23, 137).forEach { code ->
            val report = auditor.recordAbnormalExit("FAILED", code, "Process exit observed")
            assertEquals(code, report.exitCode)
            assertEquals(code, assertNotNull(auditor.readReport()).exitCode)
        }
    }

    @Test
    fun recordsUnknownExitCodesWithoutInventingAProcessResult(@TempDir tempDir: File) {
        val auditor = PostMortemAuditor(tempDir)
        listOf(null, -1).forEach { code ->
            val report = auditor.recordAbnormalExit("FAILED", code, "No process result available")
            assertNull(report.exitCode)
            val readBack = assertNotNull(auditor.readReport())
            assertNull(readBack.exitCode)
            assertEquals(report, readBack)
        }
    }

    @Test
    fun readsLegacyIntegerReportsWithoutRewritingThem(@TempDir tempDir: File) {
        val auditor = PostMortemAuditor(tempDir)
        listOf(23, 137).forEach { code ->
            val (file, original) = writeLegacyReport(tempDir, JsonPrimitive(code))
            val report = assertNotNull(auditor.readReport())
            assertEquals(123L, report.timestamp)
            assertEquals("FAILED", report.lastKnownPhase)
            assertEquals(code, report.exitCode)
            assertEquals("Legacy diagnostic", report.sanitizedDetail)
            assertEquals(original, file.readText())
        }
    }

    @Test
    fun readsLegacySentinelNullAndMissingExitCodesAsUnknown(@TempDir tempDir: File) {
        val auditor = PostMortemAuditor(tempDir)
        listOf<JsonElement?>(JsonPrimitive(-1), JsonNull, null).forEach { code ->
            val (file, original) = writeLegacyReport(tempDir, code)
            val report = assertNotNull(auditor.readReport())
            assertNull(report.exitCode)
            assertEquals("Legacy diagnostic", report.sanitizedDetail)
            assertEquals(original, file.readText())
        }
    }

    @Test
    fun fullyMasksTheCompleteInputBeforeApplyingTheStorageLimit(@TempDir tempDir: File) {
        val auditor = PostMortemAuditor(tempDir)
        val secret = syntheticSecret("bearer-").repeat(128)
        val marker = "safe-after-the-long-credential"
        val detail = "Bearer $secret\n$marker"
        assertTrue(detail.length > 2048)

        val report = auditor.recordAbnormalExit("FAILED", 23, detail)
        assertTrue(report.sanitizedDetail.length <= 2048)
        assertTrue(report.sanitizedDetail.endsWith(marker))
        assertFullyMasked(secret, report.sanitizedDetail)
        assertFullyMasked(secret, File(tempDir, PostMortemAuditor.AUDIT_FILE_NAME).readText())
        assertEquals(report, auditor.readReport())
    }

    @Test
    fun remasksLegacyTextBeforeTruncationWithoutRewritingTheEvidence(@TempDir tempDir: File) {
        val auditor = PostMortemAuditor(tempDir)
        val detailSecret = syntheticSecret("bearer-").repeat(128)
        val phaseSecret = syntheticSecret("sk-")
        val marker = "safe-after-the-legacy-credential"
        val (file, original) = writeLegacyReport(tempDir, JsonPrimitive(137),
            phase = "FAILED $phaseSecret", detail = "Bearer $detailSecret\n$marker")

        val report = assertNotNull(auditor.readReport())
        assertEquals(137, report.exitCode)
        assertTrue(report.sanitizedDetail.length <= 2048)
        assertTrue(report.sanitizedDetail.endsWith(marker))
        assertFullyMasked(detailSecret, report.sanitizedDetail)
        assertFullyMasked(phaseSecret, report.lastKnownPhase)
        assertEquals(original, file.readText())
    }

    @Test
    fun boundsOrdinaryDetailsOnBothWriteAndLegacyRead(@TempDir tempDir: File) {
        val auditor = PostMortemAuditor(tempDir)
        val detail = "ordinary-diagnostic-line\n".repeat(200)
        val report = auditor.recordAbnormalExit("FAILED", null, detail)
        assertEquals(detail.take(2048), report.sanitizedDetail)
        assertEquals(report, auditor.readReport())

        val (file, original) = writeLegacyReport(tempDir, null, detail = detail)
        assertEquals(detail.take(2048), assertNotNull(auditor.readReport()).sanitizedDetail)
        assertEquals(original, file.readText())
    }

    @Test
    fun handlesCorruptFileGracefully(@TempDir tempDir: File) {
        val auditor = PostMortemAuditor(tempDir)
        val file = File(tempDir, PostMortemAuditor.AUDIT_FILE_NAME)
        val corrupt = "invalid json content"
        file.writeText(corrupt)

        assertNull(auditor.readReport())
        assertTrue(file.exists())
        assertEquals(corrupt, file.readText())
        auditor.clear()
        assertFalse(file.exists())
    }

    private fun syntheticSecret(prefix: String): String = prefix + UUID.randomUUID().toString().replace("-", "")

    private fun assertFullyMasked(secret: String, output: String) {
        assertFalse(output.contains(secret), "The complete synthetic credential must be masked")
        assertFalse(output.contains(secret.takeLast(8)), "A synthetic credential suffix must not be retained")
    }

    private fun writeLegacyReport(
        tempDir: File,
        code: JsonElement?,
        phase: String = "FAILED",
        detail: String = "Legacy diagnostic",
    ): Pair<File, String> {
        val original = buildJsonObject {
            put("timestamp", 123L)
            put("lastKnownPhase", phase)
            if (code != null) put("exitCode", code)
            put("sanitizedDetail", detail)
            put("futureField", "ignored for compatibility")
        }.toString()
        val file = File(tempDir, PostMortemAuditor.AUDIT_FILE_NAME)
        file.writeText(original)
        return file to original
    }
}

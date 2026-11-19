package cc.fplj.magpiemobile.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticRedactorTest {
    private val secret = listOf("synthetic", "diagnostic", "credential", "4837").joinToString("-")

    @Test fun fullyMasksKnownCredentialFormsAndShortValues() {
        for (value in listOf("Z", "Q7", secret)) {
            for (line in listOf(
                "Authorization: Bearer $value", "authorization: Basic $value",
                "Proxy-Authorization: Bearer $value", "Bearer $value", "bearer:$value",
                "password=$value&safe=1", "token: $value", "api_key='$value'",
                "\"apiKey\":\"$value\"", "gateway_key=$value", "admin web key: $value",
                "https://localhost/?k=$value&safe=1", "access_token=$value", "refresh-token=$value",
                "standalone sk-$value", "standalone sk-magpie-key-$value",
            )) {
                val safe = SecretRedactor.redactDiagnostic(line)
                assertFalse(safe.contains(value), "credential survived: $safe")
                assertTrue(safe.contains("***"))
                assertEquals(safe, SecretRedactor.redactDiagnostic(safe), "mask must be idempotent")
            }
        }
    }

    @Test fun quotedSpacesEscapesAndIncompleteEofValuesAreMasked() {
        for (line in listOf(
            "{\"Authorization\":\"Bearer $secret\",\"safe\":1}",
            "password=\"$secret with spaces\" safe=1",
            "token='$secret with spaces' safe=1",
            "api_key=\"$secret\\\"suffix\" safe=1",
            "password=\"$secret", "token='$secret", "Bearer $secret", "?k=$secret",
        )) {
            val safe = SecretRedactor.redactDiagnostic(line)
            assertFalse(safe.contains(secret))
            assertFalse(safe.contains("with spaces"))
            assertFalse(safe.contains("suffix"))
            assertEquals(safe, SecretRedactor.redactDiagnostic(safe))
        }
    }

    @Test fun masksEntireValueBeforeCallerTruncatesLongDiagnostics() {
        val longSecret = secret.repeat(600)
        val safe = SecretRedactor.redactDiagnostic("password=$longSecret&safe=marker").takeLast(8192)
        assertFalse(safe.contains(secret))
        assertTrue(safe.endsWith("&safe=marker"))
        assertTrue(safe.length < 100)
    }

    @Test fun preservesUsefulPlainTextAndNonSecretParameters() {
        val clean = "engine exited with code 23\nrequest id=7 /health?ready=true&x=1\n"
        assertEquals(clean, SecretRedactor.redactDiagnostic(clean))
        assertEquals("GET /?k=***&x=1", SecretRedactor.redactDiagnostic("GET /?k=$secret&x=1"))
        assertEquals("Authorization: ***\nsafe=1", SecretRedactor.redactDiagnostic("Authorization: Bearer $secret\nsafe=1"))
    }

    @Test fun partialSkPrefixAndEmptyAssignmentsUseStableMask() {
        for (line in listOf("sk-", "token=", "password=\"", "?k=")) {
            val safe = SecretRedactor.redactDiagnostic(line)
            assertTrue(safe.contains("***"))
            assertEquals(safe, SecretRedactor.redactDiagnostic(safe))
        }
    }

    @Test fun oldDiagnosticSuffixHintsAreFullyMaskedOnRead() {
        assertEquals("key *** and ***", SecretRedactor.redactDiagnostic("key gw:**4837 and sk:**4837"))
    }

    @Test fun longQuotedAndUnterminatedValuesDoNotExhaustTheRegexStack() {
        for (size in listOf(8192, 65536)) {
            val value = (secret + "\\\"").repeat(size / secret.length)
            for (prefix in listOf("token=", "password:", "Authorization:")) {
                for (quote in listOf("\"", "'")) {
                    // Include escapes, both completed lines and final unterminated EOF values.
                    val safe = SecretRedactor.redactDiagnostic(prefix + quote + value + quote + "\nsafe=marker")
                    assertEquals(prefix + "***\nsafe=marker", safe)
                    assertEquals(prefix + "***", SecretRedactor.redactDiagnostic(prefix + quote + value))
                }
            }
        }
    }
}

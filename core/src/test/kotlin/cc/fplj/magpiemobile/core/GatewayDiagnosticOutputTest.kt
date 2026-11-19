package cc.fplj.magpiemobile.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GatewayDiagnosticOutputTest {
    @Test
    fun incompleteSecretsStayPrivateAcrossEveryChunkBoundaryAndPublicationWindow() {
        val credentials = listOf(
            "Authorization: Bearer syntheticBearerSecret7x9Q" to "syntheticBearerSecret7x9Q",
            "http://127.0.0.1:3430/?k=syntheticBrowserSecret8y2R" to "syntheticBrowserSecret8y2R",
            "client key=sk-syntheticGatewaySecret9z3S" to "sk-syntheticGatewaySecret9z3S",
        )
        for ((line, secret) in credentials) {
            for (split in 1 until line.length) {
                val tail = GatewayDiagnosticTail()
                val stdout = tail.openStream()
                val throttle = GatewayOutputThrottle(500)
                throttle.published(0, 0)
                stdout.write(line.substring(0, split))
                assertEquals("", tail.snapshot().text)
                assertFalse(throttle.changed(tail.snapshot().revision, 501))
                stdout.write(line.substring(split))
                assertEquals("", tail.snapshot().text)
                assertFalse(throttle.changed(tail.snapshot().revision, 1001))
                stdout.write("\n")
                val safe = tail.snapshot()
                assertTrue(throttle.changed(safe.revision, 1501))
                assertFalse(safe.text.contains(secret), "Leaked secret at split $split")
                assertFalse(safe.text.contains(secret.takeLast(4)), "Diagnostic masks retain no secret suffix")
                assertTrue(safe.text.endsWith('\n'))
            }
        }
    }

    @Test
    fun stdoutAndStderrHaveIndependentCarriesAndOnlyTheirOwnEofCompletesThem() {
        val tail = GatewayDiagnosticTail()
        val stdout = tail.openStream()
        val stderr = tail.openStream()
        stdout.write("Authorization: Bearer stdoutPrivate7x9Q")
        stderr.write("stderr ready\n")
        assertEquals("stderr ready\n", tail.snapshot().text)
        stderr.endOfInput()
        assertEquals("stderr ready\n", tail.snapshot().text)
        stdout.endOfInput()
        val safe = tail.snapshot().text
        assertTrue(safe.startsWith("stderr ready\n"))
        assertFalse(safe.contains("stdoutPrivate"))
        assertFalse(safe.contains("7x9Q"))
        val finished = tail.snapshot()
        stdout.write("must be ignored\n")
        stdout.endOfInput()
        assertEquals(finished, tail.snapshot())
    }

    @Test
    fun oversizedUnterminatedLineHasBoundedCarryAndItsEntireSuffixIsDiscarded() {
        val tail = GatewayDiagnosticTail(lineLimit = 128)
        val stdout = tail.openStream()
        stdout.write("Authorization: Bearer ")
        val chunk = CharArray(1024) { 's' }
        repeat(2048) {
            stdout.append(chunk, chunk.size)
            assertTrue(stdout.pendingCharacters <= 128)
            assertEquals("", tail.snapshot().text)
        }
        stdout.write("unlabelledPrivateSuffix8x2Q\nhealthy after oversized line\n")
        assertEquals(GatewayDiagnosticTail.OMITTED_LINE + "\nhealthy after oversized line\n", tail.snapshot().text)
        assertEquals(0, stdout.pendingCharacters)
    }

    @Test
    fun eofWithoutANewlineCannotSpliceTogetherDifferentStreamsOrAttempts() {
        val tail = GatewayDiagnosticTail()
        val stdout = tail.openStream()
        val stderr = tail.openStream()
        stdout.write("stdout EOF")
        stdout.endOfInput()
        assertEquals("stdout EOF", tail.snapshot().text)
        stderr.write("stderr EOF")
        stderr.endOfInput()
        val nextAttempt = tail.openStream()
        nextAttempt.write("new attempt line\n")
        assertEquals("stdout EOF\nstderr EOF\nnew attempt line\n", tail.snapshot().text)
    }

    @Test
    fun oversizedEofUsesOnlyTheFixedMarkerAndExactLimitLinesRemainReadable() {
        val tail = GatewayDiagnosticTail(lineLimit = 32)
        val stdout = tail.openStream()
        stdout.write("n".repeat(32) + "\n")
        assertEquals("n".repeat(32) + "\n", tail.snapshot().text)
        stdout.write("private=" + "s".repeat(1000))
        stdout.endOfInput()
        assertEquals("n".repeat(32) + "\n" + GatewayDiagnosticTail.OMITTED_LINE, tail.snapshot().text)
    }

    @Test
    fun credentialsAreFullyRedactedBeforeSharedTailTruncationCanLoseTheirPrefix() {
        val tail = GatewayDiagnosticTail(capacity = 64, lineLimit = 4096)
        val stdout = tail.openStream()
        val secret = "s".repeat(300) + "PrivateSuffix7x9Q"
        stdout.write("context before credential ".repeat(20) + "token=" + secret + "\n")
        val safe = tail.snapshot().text
        assertTrue(safe.length <= 64)
        assertFalse(safe.contains("PrivateSuffix"))
        assertFalse(safe.contains("7x9Q"))
        assertFalse(safe.contains("ssssssss"))
    }

    @Test
    fun sealedOldReaderCannotCompleteOrAppendIntoTheNextAttempt() {
        val tail = GatewayDiagnosticTail()
        val old = tail.openStream()
        old.write("old complete\nBearer half-private")
        old.seal()
        val current = tail.openStream()
        current.write("current attempt\n")
        val before = tail.snapshot()
        old.write("late private suffix\nold late marker\n")
        old.endOfInput()
        assertEquals(before, tail.snapshot())
        assertEquals(0, old.pendingCharacters)
        assertEquals("old complete\ncurrent attempt\n", before.text)
    }

    @Test
    fun bothStreamsCanDrainConcurrentlyIntoOneBoundedRedactedTail() {
        val tail = GatewayDiagnosticTail()
        val stdout = tail.openStream()
        val stderr = tail.openStream()
        val done = CountDownLatch(2)
        val secret = "syntheticPrivate7x9Q"
        val workers = listOf(stdout, stderr).mapIndexed { index, stream ->
            Thread({
                try {
                    repeat(2000) { stream.write("stream $index item $it token=$secret\n") }
                } finally { done.countDown() }
            }, "diagnostic-tail-test-$index").apply { isDaemon = true; start() }
        }
        try {
            assertTrue(done.await(5, TimeUnit.SECONDS))
            stdout.write("stdout final\n")
            stderr.write("stderr final\n")
            val safe = tail.snapshot().text
            assertEquals(8192, safe.length)
            assertTrue(safe.endsWith("stdout final\nstderr final\n"))
            assertFalse(safe.contains(secret))
            assertFalse(safe.contains(secret.takeLast(4)))
            assertTrue(stdout.pendingCharacters <= 8192 && stderr.pendingCharacters <= 8192)
        } finally {
            workers.forEach { it.join(1000) }
            stdout.seal(); stderr.seal()
        }
    }

    @Test
    fun monotonicThrottleCoalescesChangesAndUnchangedRevisionNeverRepublishes() {
        val throttle = GatewayOutputThrottle(500)
        throttle.published(0, -1000)
        assertFalse(throttle.changed(1, -999))
        assertFalse(throttle.changed(2, -501))
        assertTrue(throttle.changed(3, -500))
        assertFalse(throttle.changed(3, 1000))
        assertTrue(throttle.changed(10, 1000))
        assertFalse(throttle.changed(11, 1499))
        assertTrue(throttle.changed(12, 1500))
        // A forced lifecycle publication resets the same clock without a trailing duplicate.
        throttle.published(13, 1501)
        assertFalse(throttle.changed(13, 1900))
        assertFalse(throttle.changed(14, 2000))
        assertTrue(throttle.changed(14, 2001))
        assertFalse(throttle.changed(14, 2500))
    }

    @Test
    fun monotonicThrottleUsesElapsedDifferencesAcrossNanoTimeWraparound() {
        val throttle = GatewayOutputThrottle(500)
        throttle.published(0, Long.MAX_VALUE - 200)
        assertFalse(throttle.changed(1, Long.MIN_VALUE + 298))
        assertTrue(throttle.changed(1, Long.MIN_VALUE + 299))
    }

    private fun GatewayDiagnosticTail.Stream.write(value: String) {
        val chars = value.toCharArray()
        append(chars, chars.size)
    }
}

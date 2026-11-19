package cc.fplj.magpiemobile.core

import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class BoundedSseReaderTest {
    private fun limits(line: Int = 128, total: Long = 1024L) =
        StreamingChatLimits(maxLineBytes = line, maxResponseBytes = total)

    @Test fun crLfAndCrLfPairsPreserveUtf8AcrossEveryByteRead() {
        val text = "你🙂\r\n好\r哈\n尾"
        val input = object : ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int = super.read(bytes, offset, minOf(1, length))
        }
        BoundedSseReader(input, limits()).use { reader ->
            assertEquals("你🙂", reader.readLine())
            assertEquals("好", reader.readLine())
            assertEquals("哈", reader.readLine())
            assertEquals("尾", reader.readLine())
            assertNull(reader.readLine())
            assertNull(reader.readLine())
        }
    }

    @Test fun crTerminatedDoneLineNeverWaitsForFollowingLfOrEof() {
        val bytes = "data: [DONE]\r".toByteArray(Charsets.UTF_8)
        var position = 0
        val input = object : InputStream() {
            override fun read(): Int = error("Bulk reads expected")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                check(position < bytes.size) { "Reader waited beyond the CR terminator" }
                buffer[offset] = bytes[position++]
                return 1
            }
        }
        assertEquals("data: [DONE]", BoundedSseReader(input, limits()).readLine())
        assertEquals(bytes.size, position)
    }

    @Test fun exactUtf8LineBudgetAllowsDelimiterOrEofButRejectsOneMoreByte() {
        val bytes = "你🙂".toByteArray(Charsets.UTF_8)
        assertEquals(7, bytes.size)
        for (suffix in listOf("", "\n", "\r\n")) {
            BoundedSseReader(ByteArrayInputStream(bytes + suffix.toByteArray()), limits(line = bytes.size)).use { reader ->
                assertEquals("你🙂", reader.readLine())
                assertNull(reader.readLine())
            }
        }
        val error = assertFailsWith<StreamingChatException> {
            BoundedSseReader(ByteArrayInputStream(bytes + byteArrayOf(120)), limits(line = bytes.size)).readLine()
        }
        assertEquals(StreamingChatFailure.RESPONSE_LIMIT, error.failure)
        assertEquals("", error.partialText)
        assertNull(error.cause)
    }

    @Test fun unterminatedCommentStopsAtLineLimitWithoutWaitingForEof() {
        var received = 0
        val endless = object : InputStream() {
            override fun read(): Int = ':'.code.also { received++ }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                // A single byte per call proves the reader never asks this source for EOF.
                bytes[offset] = ':'.code.toByte()
                received++
                return 1
            }
        }
        val error = assertFailsWith<StreamingChatException> { BoundedSseReader(endless, limits(line = 8)).readLine() }
        assertEquals(StreamingChatFailure.RESPONSE_LIMIT, error.failure)
        assertEquals(9, received)
    }

    @Test fun responseBudgetCountsIgnoredLinesAndAllCrLfBytes() {
        val bytes = ":\r\nevent: ping\n".toByteArray(Charsets.UTF_8)
        BoundedSseReader(ByteArrayInputStream(bytes), limits(total = bytes.size.toLong())).use { reader ->
            assertEquals(":", reader.readLine())
            assertEquals("event: ping", reader.readLine())
            assertNull(reader.readLine())
        }
        BoundedSseReader(ByteArrayInputStream(bytes), limits(total = bytes.size.toLong() - 1)).use { reader ->
            assertEquals(":", reader.readLine())
            val error = assertFailsWith<StreamingChatException> { reader.readLine() }
            assertEquals(StreamingChatFailure.RESPONSE_LIMIT, error.failure)
        }
    }

    @Test fun responseReadRequestsNeverConsumeMoreThanOneByteBeyondBudget() {
        var received = 0
        val endless = object : InputStream() {
            override fun read(): Int = error("Bulk reads expected")
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                bytes.fill('x'.code.toByte(), offset, offset + length)
                received += length
                return length
            }
        }
        val error = assertFailsWith<StreamingChatException> {
            BoundedSseReader(endless, limits(line = 128, total = 8)).readLine()
        }
        assertEquals(StreamingChatFailure.RESPONSE_LIMIT, error.failure)
        assertEquals(9, received)
    }

    @Test fun maximumLongResponseBudgetDoesNotOverflowReadLength() {
        BoundedSseReader(ByteArrayInputStream("ok\n".toByteArray()), limits(total = Long.MAX_VALUE)).use { reader ->
            assertEquals("ok", reader.readLine())
            assertNull(reader.readLine())
        }
    }

    @Test fun socketTimeoutIsNotRetried() {
        var reads = 0
        val input = object : InputStream() {
            override fun read(): Int = error("Bulk reads expected")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                reads++
                throw SocketTimeoutException("fixture timeout")
            }
        }
        assertFailsWith<SocketTimeoutException> {
            BoundedSseReader(input, limits()).readLine()
        }
        assertEquals(1, reads)
    }

    @Test fun cancellationIsCheckedAfterReceivingAnotherIgnoredByte() {
        var cancelled = false
        val input = object : InputStream() {
            override fun read(): Int = error("Bulk reads expected")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                cancelled = true
                buffer[offset] = ':'.code.toByte()
                return 1
            }
        }
        val error = assertFailsWith<CancellationException> {
            BoundedSseReader(input, limits(), ensureActive = {
                if (cancelled) throw CancellationException("Chat request cancelled")
            }).readLine()
        }
        assertEquals("Chat request cancelled", error.message)
    }
}

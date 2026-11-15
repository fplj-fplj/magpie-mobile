package cc.fplj.magpiemobile.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionStoreTest {

    @TempDir
    lateinit var tmp: File

    @Test
    fun appendAndReadRoundTrip() {
        val store = SessionStore(File(tmp, "chat.jsonl"))
        store.append(SessionRecord(1_700_000_000_000, "user", "chat", "你好"))
        store.append(SessionRecord(1_700_000_000_001, "assistant", "chat",
            "line1\nline2 \"quoted\"", reasoning = "先拆解\n再回答"))
        val all = store.readAll()
        assertEquals(2, all.size)
        assertEquals("你好", all[0].text)
        assertEquals("line1\nline2 \"quoted\"", all[1].text)
        assertEquals("先拆解\n再回答", all[1].reasoning)
    }

    @Test
    fun corruptedLinesSkippedNotFatal() {
        val f = File(tmp, "chat.jsonl")
        f.writeText(
            "{\"ts\":1,\"role\":\"user\",\"engine\":\"chat\",\"text\":\"ok\"}\n" +
            "GARBAGE NOT JSON\n" +
            "{\"broken\":true}\n" +
            "{\"ts\":2,\"role\":\"assistant\",\"engine\":\"chat\",\"text\":\"fine\"}\n"
        )
        val all = SessionStore(f).readAll()
        assertEquals(2, all.size)
        assertEquals("ok", all[0].text)
        assertEquals("fine", all[1].text)
        assertEquals("", all[1].reasoning)
    }

    @Test
    fun oldLineWithoutReasoningRemainsCompatibleAndNullIsNeverLiteralText() {
        val f = File(tmp, "legacy.jsonl")
        f.writeText(
            "{\"ts\":1,\"role\":\"assistant\",\"engine\":\"chat\",\"text\":\"old\"}\n" +
                "{\"ts\":2,\"role\":\"assistant\",\"engine\":\"chat\",\"text\":\"new\"," +
                "\"reasoning\":null}\n",
        )
        val all = SessionStore(f).readAll()
        assertEquals(listOf("", ""), all.map { it.reasoning })
    }

    @Test
    fun lastReturnsTailOnly() {
        val store = SessionStore(File(tmp, "chat.jsonl"))
        repeat(10) { store.append(SessionRecord(it.toLong(), "user", "chat", "m$it")) }
        val tail = store.last(3)
        assertEquals(3, tail.size)
        assertEquals("m7", tail[0].text)
        assertEquals("m9", tail[2].text)
    }

    @Test
    fun missingFileReadsEmpty() {
        assertTrue(SessionStore(File(tmp, "nope.jsonl")).readAll().isEmpty())
    }
}

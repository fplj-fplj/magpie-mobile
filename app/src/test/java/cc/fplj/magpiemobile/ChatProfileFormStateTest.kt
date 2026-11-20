package cc.fplj.magpiemobile

import cc.fplj.magpiemobile.core.ChatProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatProfileFormStateTest {
    private val draft = ChatProfileFormState(
        editing = true,
        id = "writer",
        name = "写作助手",
        prompt = "请完整保留这段提示词，包含换行。\n".repeat(1000),
        temperature = "0.2",
    )

    @Test fun invalidTemperatureKeepsEveryDraftFieldAndDoesNotCallSave() {
        for (value in listOf("abc", "NaN", "Infinity", "-Infinity", "1e309", "3", "-0.1", "")) {
            val input = draft.copy(temperature = value)
            var attempted = false
            val result = input.submit { _, _, _, _ -> attempted = true; true }
            assertFalse(attempted)
            assertEquals(input.copy(error = ChatProfileFormState.TEMPERATURE_ERROR), result)
        }
    }

    @Test fun failedSaveKeepsLongPromptAndAllInputOpen() {
        var receivedPrompt = ""
        val result = draft.submit { id, name, prompt, temperature ->
            assertEquals(draft.id, id)
            assertEquals(draft.name, name)
            assertEquals(0.2, temperature, 0.0)
            receivedPrompt = prompt
            false
        }
        assertEquals(draft, result)
        assertEquals(draft.prompt, receivedPrompt)
        assertTrue(result.editing)
    }

    @Test fun successfulSaveClearsAndClosesOnlyAfterCallbackReturnsTrue() {
        var saved = false
        val result = draft.submit { _, _, prompt, _ ->
            assertEquals(draft.prompt, prompt)
            saved = true
            true
        }
        assertTrue(saved)
        assertEquals(ChatProfileFormState(), result)
        assertFalse(result.editing)
    }

    @Test fun temperatureBoundariesAndSurroundingWhitespaceAreAccepted() {
        for (value in listOf("0", "2", " 0.2 ")) {
            var attempted = false
            val result = draft.copy(temperature = value).submit { _, _, _, temperature ->
                attempted = true
                assertEquals(value.trim().toDouble(), temperature, 0.0)
                true
            }
            assertTrue(attempted)
            assertEquals(ChatProfileFormState(), result)
        }
    }

    @Test fun untouchedBuiltInProfileShowsOnlyModelDefaultsAndHidesInactivePrompt() {
        assertEquals("使用模型默认参数", chatProfileParameterSummary(ChatProfile.DEFAULT.copy()))
        assertNull(chatProfilePromptPreview(ChatProfile.DEFAULT.copy()))
    }

    @Test fun customizedDefaultAndCustomIdsShowTheirActualRequestParameters() {
        val customizedDefault = ChatProfile.DEFAULT.copy(
            systemPrompt = "实际发送的提示词", temperature = 0.0, topP = 0.0, maxTokens = 42,
        )
        assertEquals("温度: 0.0 · Top-P: 0.0 · 最大输出: 42", chatProfileParameterSummary(customizedDefault))
        assertEquals(customizedDefault.systemPrompt, chatProfilePromptPreview(customizedDefault))
        val custom = ChatProfile.DEFAULT.copy(id = "custom")
        assertEquals("温度: 0.7 · Top-P: 1.0", chatProfileParameterSummary(custom))
        assertEquals(custom.systemPrompt, chatProfilePromptPreview(custom))
    }
}

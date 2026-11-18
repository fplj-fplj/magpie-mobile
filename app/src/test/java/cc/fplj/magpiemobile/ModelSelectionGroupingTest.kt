package cc.fplj.magpiemobile

import cc.fplj.magpiemobile.core.ModelEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ModelSelectionGroupingTest {
    @Test
    fun genericOwnersShareGatewayButRealOwnersStaySeparate() {
        val groups = modelPickerGroupsFromCatalog(
            listOf(
                ModelEntry("deepseek-chat", "DeepSeek Chat", null, 128_000),
                ModelEntry("router-model", "Router Model", "magpie", null),
                ModelEntry("gpt-5", "GPT-5", "openai", 400_000),
                ModelEntry("gpt-5-mini", "GPT-5 mini", "OpenAI", null),
            ),
        )

        assertEquals(listOf("Magpie 网关", "openai"), groups.map { it.title })
        assertEquals(
            listOf("deepseek-chat", "router-model"),
            groups.first().entries.map { it.id },
        )
        assertEquals("128K 上下文", groups.first().entries.first().detail)
        assertEquals(2, groups.last().entries.size)
        assertNotEquals(groups.first().id, groups.last().id)
    }

    @Test
    fun providerNamesAreNotGuessedFromModelNamespace() {
        val groups = modelPickerGroupsFromCatalog(
            listOf(ModelEntry("deepseek-ai/DeepSeek-V4", "DeepSeek V4", "", null)),
        )

        assertEquals("Magpie 网关", groups.single().title)
        assertEquals("Magpie 网关", groups.single().entries.single().providerName)
    }
}

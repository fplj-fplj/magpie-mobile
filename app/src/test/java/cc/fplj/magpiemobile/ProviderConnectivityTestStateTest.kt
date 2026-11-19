package cc.fplj.magpiemobile

import cc.fplj.magpiemobile.core.ProviderCapabilityCheck
import cc.fplj.magpiemobile.core.ProviderCapabilityResult
import cc.fplj.magpiemobile.core.ProviderCapabilityStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderConnectivityTestStateTest {

    @Test
    fun onlyMatchingGenerationAndSessionCanPublishResults() {
        assertTrue(providerTestUpdateIsCurrent(4, 4, 91L, 91L))
        assertFalse(providerTestUpdateIsCurrent(4, 5, 91L, 91L))
        assertFalse(providerTestUpdateIsCurrent(4, 4, 91L, 92L))
        assertFalse(providerTestUpdateIsCurrent(4, 4, 91L, null))
        assertFalse(providerTestUpdateIsCurrent(4, 4, null, null))
    }

    @Test
    fun interruptionAfterFirstCheckKeepsItsDetailsAndTerminatesRunningAndUnsentRows() {
        val passed = ProviderCapabilityResult(
            ProviderCapabilityCheck.NON_STREAMING,
            ProviderCapabilityStatus.PASSED,
            safeMessage = "非流式请求已通过",
            preview = "saved answer",
            reasoning = "saved reasoning",
            latencyMs = 24,
        )
        val results = mapOf(
            passed.check to passed,
            ProviderCapabilityCheck.STREAMING to ProviderCapabilityResult(
                ProviderCapabilityCheck.STREAMING, ProviderCapabilityStatus.RUNNING,
            ),
            ProviderCapabilityCheck.TOOL_CALL to ProviderCapabilityResult(
                ProviderCapabilityCheck.TOOL_CALL, ProviderCapabilityStatus.PENDING,
            ),
        )

        val finished = finishProviderCapabilityRun(results, message = "", messageIsError = false)

        assertSame(passed, finished.results[passed.check])
        assertEquals(ProviderCapabilityStatus.FAILED,
            finished.results[ProviderCapabilityCheck.STREAMING]?.status)
        assertEquals("测试已终止", finished.results[ProviderCapabilityCheck.STREAMING]?.safeMessage)
        assertEquals(ProviderCapabilityStatus.UNAVAILABLE,
            finished.results[ProviderCapabilityCheck.TOOL_CALL]?.status)
        assertEquals("本项检查未发送", finished.results[ProviderCapabilityCheck.TOOL_CALL]?.safeMessage)
        assertTrue(finished.message.isNotBlank())
        assertTrue(finished.messageIsError)
        assertFalse(finished.results.values.any {
            it.status == ProviderCapabilityStatus.RUNNING || it.status == ProviderCapabilityStatus.PENDING
        })
        assertEquals(finished,
            finishProviderCapabilityRun(finished.results, finished.message, finished.messageIsError))
    }

    @Test
    fun failureBeforeFirstProgressMarksEveryCheckUnsentAndKeepsExistingError() {
        val finished = finishProviderCapabilityRun(
            results = emptyMap(),
            message = "测试未能安全完成；请检查网关状态后手动重试。",
            messageIsError = true,
        )

        assertEquals(ProviderCapabilityCheck.entries.toSet(), finished.results.keys)
        assertTrue(finished.results.values.all { it.status == ProviderCapabilityStatus.UNAVAILABLE })
        assertEquals("测试未能安全完成；请检查网关状态后手动重试。", finished.message)
        assertTrue(finished.messageIsError)
    }

    @Test
    fun completedChecksKeepTheirExactTerminalResultsAndSuccessfulSummary() {
        val results = ProviderCapabilityCheck.entries.associateWith { check ->
            ProviderCapabilityResult(check, ProviderCapabilityStatus.PASSED, preview = check.name)
        }
        val finished = finishProviderCapabilityRun(results, "三项模型能力检查已通过。", false)

        results.forEach { (check, result) -> assertSame(result, finished.results[check]) }
        assertEquals("三项模型能力检查已通过。", finished.message)
        assertFalse(finished.messageIsError)

        listOf(ProviderCapabilityStatus.FAILED, ProviderCapabilityStatus.UNAVAILABLE).forEach { status ->
            val terminal = ProviderCapabilityResult(
                ProviderCapabilityCheck.STREAMING, status, safeMessage = "existing failure",
            )
            val withFailure = finishProviderCapabilityRun(
                results + (terminal.check to terminal), "existing summary", true,
            )
            assertSame(terminal, withFailure.results[terminal.check])
            assertEquals("existing summary", withFailure.message)
        }
    }
}

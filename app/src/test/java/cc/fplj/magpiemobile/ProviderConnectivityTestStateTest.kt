package cc.fplj.magpiemobile

import org.junit.Assert.assertFalse
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
}

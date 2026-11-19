package cc.fplj.magpiemobile

/** Non-secret identity of one verified provider-management connection in this process. */
data class ProviderSessionKey(val owner: Long, val epoch: Long)

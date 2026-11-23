package cc.fplj.magpiemobile.core

/** Process-only Android-host manager credential. Never use it on the model gateway. */
class ManagerCredential private constructor(
    private val value: String,
    val port: Int,
) {
    /** Android-host management calls send this only to the exact loopback GUI endpoint. */
    fun managerHeaderValue(): String = value

    /** Compatibility display: never exposes the complete key. */
    fun redacted(): String = "manager@127.0.0.1:$port=" + value.take(4) + "…"
    override fun toString(): String = redacted()

    companion object {
        fun fromKey(key: String, port: Int = 3430): ManagerCredential {
            require(port in 1..65535) { "管理端口无效" }
            require(key.length in 16..4096) { "管理 key 长度无效" }
            require(key.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in "-._~" }) {
                "管理 key 含非法字符"
            }
            return ManagerCredential(key, port)
        }
    }
}

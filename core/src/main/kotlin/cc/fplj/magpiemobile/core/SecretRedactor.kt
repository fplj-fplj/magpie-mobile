package cc.fplj.magpiemobile.core

/**
 * 日志/错误信息凭据脱敏（plan.md §4.3“凭据不进日志”的统一出口）。
 *
 * 契约（测试以此为准，不钉掩码形状）：
 * - 网关 key（sk-magpie-key-…）、通用 sk- 密钥、Bearer token、
 *   password=/token=/api_key= 查询值：完整值不再出现于输出
 * - 无害文本原样保留
 * - 掩码保留少量排错线索（前缀或末 4 位）
 */
object SecretRedactor {

    /**
     * Public process diagnostics must never reveal a growing credential suffix.
     * Call on complete lines (or the final EOF line), before truncating the safe tail.
     * Unlike [redact], this uses a fixed, idempotent full mask even for short values.
     * It recognises credential syntax, not arbitrary unlabelled secrets.
     */
    fun redactDiagnostic(input: String): String {
        var out = diagnosticAuthorization.replace(input) { it.groupValues[1] + "***" }
        out = diagnosticBearer.replace(out, "Bearer ***")
        out = diagnosticAssignment.replace(out) { it.groupValues[1] + "***" }
        out = diagnosticLegacyMask.replace(out, "***")
        return diagnosticSk.replace(out, "***")
    }

    // Header values can contain spaces; masking only the word "Bearer"/"Basic"
    // would leave the actual credential behind. Quoted JSON/config values are
    // handled separately from an unquoted header ending at newline/comma.
    private val diagnosticAuthorization = Regex(
        """(?i)((?<![A-Za-z0-9_])["']?(?:authorization|proxy-authorization)["']?\s*[:=]\s*)(?:"(?:\\.|[^"\\\r\n])*+"?|'(?:\\.|[^'\\\r\n])*+'?|[^\r\n,;}]+)""",
    )
    private val diagnosticBearer = Regex("""(?i)\bBearer(?:\s+|:)[^\s"'&,;<>\]}]+""")
    private val diagnosticAssignment = Regex(
        """(?i)((?<![A-Za-z0-9_])["']?(?:password|passwd|token|access[_-]?token|refresh[_-]?token|api[_-]?key|gateway[_ -]?key|admin[_ -]?(?:web[_ -]?)?key|secret|k)["']?\s*[:=]\s*)(?:"(?:\\.|[^"\\\r\n])*+"?|'(?:\\.|[^'\\\r\n])*+'?|[^\s&,;"'<>\]}]*)""",
    )
    private val diagnosticSk = Regex("""(?i)(?<![A-Za-z0-9_])sk-[A-Za-z0-9_.~/-]*""")
    private val diagnosticLegacyMask = Regex("""(?i)\b(?:gw|sk):\*\*[A-Za-z0-9_-]*""")

    fun redact(input: String): String {
        var out = input
        // 1) 网关 key：掩码为 gw+末4位（不含 "sk-m" 等前缀触发词，防二次匹配）
        out = out.replace(Regex("sk-magpie-key-[A-Za-z0-9]+")) { m ->
            "gw:**" + m.value.takeLast(4)
        }
        // 2) Bearer token
        out = out.replace(Regex("Bearer\\s+[A-Za-z0-9-._~]{8,}")) { m ->
            "Bearer:**" + m.value.takeLast(4)
        }
        // 3) 查询串/配置密钥
        out = out.replace(Regex("(?i)(password|token|api_key|k)=([^\\s&]{4,})")) { m ->
            m.groupValues[1] + "=**" + m.groupValues[2].takeLast(4)
        }
        // 4) 通用 sk- 密钥（最后，此时文本已无 sk-magpie 触发词）
        out = out.replace(Regex("sk-[A-Za-z0-9-_]{8,}")) { m ->
            "sk:**" + m.value.takeLast(4)
        }
        return out
    }
}

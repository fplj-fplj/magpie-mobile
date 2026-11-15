package cc.fplj.magpiemobile.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Middleware state as upstream internal/middleware.State exposes it. */
@Serializable
data class PluginMiddlewareDiagnostics(
    val hooks: List<String> = emptyList(),
    val events: List<String> = emptyList(),
    val error: String = "",
    val calls: Long = 0,
    val avgMicros: Double = 0.0,
    val failures: Long = 0,
    val lastError: String = "",
    val strategies: List<String> = emptyList(),
    val orders: Long = 0,
    val usage: Boolean = false,
    val rows: Long = 0,
    val dropped: Long = 0,
)

/** Async gateway-hook diagnostics from plugin.GatewayState. */
@Serializable
data class PluginGatewayDiagnostics(
    val calls: Long = 0,
    val failures: Long = 0,
    val avgMillis: Double = 0.0,
    val lastOutcome: String = "",
    val lastBytes: Long = 0,
)

@Serializable
data class ManagedPlugin(
    val spec: String,
    val name: String = "",
    val packageId: String = "",
    val off: Boolean = false,
    val options: JsonObject = JsonObject(emptyMap()),
    val error: String = "",
    val providerNames: List<String> = emptyList(),
    val toolNames: List<String> = emptyList(),
    val tools: List<PluginTool> = emptyList(),
    val isMiddleware: Boolean = false,
    val middlewareOnly: Boolean = false,
    val isGateway: Boolean = false,
    val gatewayOnly: Boolean = false,
    val middleware: PluginMiddlewareDiagnostics? = null,
    val gateway: PluginGatewayDiagnostics? = null,
    val version: String = "",
    val latest: String = "",
    val optionsExample: JsonObject = JsonObject(emptyMap()),
) {
    /** Same path forms as upstream plugin.IsPath, including paths restored from another OS. */
    val isLocal: Boolean get() = spec.startsWith("file://") || spec.startsWith(".") ||
        spec.startsWith("/") || spec.startsWith("\\") || Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(spec)
    override fun toString(): String = "ManagedPlugin(spec=$spec, off=$off, error=<redacted>)"
}

@Serializable
data class PluginTool(
    val name: String = "",
    val description: String = "",
    val schema: JsonObject? = null,
)

@Serializable
data class PluginSnapshot(
    val entries: List<ManagedPlugin> = emptyList(),
    val bun: Boolean = false,
    val bunVersion: String = "",
) {
    override fun toString(): String =
        "PluginSnapshot(entries=${entries.size}, bun=$bun, bunVersion=$bunVersion)"
}

enum class PluginMarketSource { MARKET, SEARCH }

@Serializable
data class PluginMarketListing(
    val packageId: String = "",
    val name: String = "",
    val kind: String = "",
    val providers: List<String> = emptyList(),
    val community: Boolean = false,
    val replaces: String = "",
    val icon: String = "",
    val version: String = "",
    val description: String = "",
    val publisher: String = "",
    val homepage: String = "",
    val repository: String = "",
    val weekly: Long = 0,
    val summary: String = "",
    val raw: JsonObject = JsonObject(emptyMap()),
)

/** Official market/search rows plus the installed entries whose spec/package/name matched. */
@Serializable
data class PluginMarketSnapshot(
    val query: String = "",
    val source: PluginMarketSource = PluginMarketSource.MARKET,
    val listings: List<PluginMarketListing> = emptyList(),
    val installed: List<ManagedPlugin> = emptyList(),
    val matchedSpecs: List<String> = emptyList(),
)

fun PluginMarketSnapshot.isInstalled(listing: PluginMarketListing): Boolean =
    installed.any { it.matchesListing(listing) }

/** Shared by market reads and the UI; missing names never match each other. */
fun ManagedPlugin.matchesListing(listing: PluginMarketListing): Boolean {
    fun normalized(value: String) = value.trim().lowercase().removePrefix("npm:")
    val bare = listing.packageId.substringAfterLast('/')
    val short = listOf("middleware-", "opencode-", "tools-", "page-", "agent-")
        .firstNotNullOfOrNull { prefix -> bare.takeIf { it.startsWith(prefix) && it.length > prefix.length }?.removePrefix(prefix) }
        .orEmpty().ifBlank { bare }.removeSuffix("-auth")
    val entryNames = listOf(spec, packageId, name).map(::normalized).filter(String::isNotBlank).toSet()
    val listingNames = listOf(listing.packageId, listing.name, bare, short).map(::normalized).filter(String::isNotBlank).toSet()
    return entryNames.intersect(listingNames).isNotEmpty()
}

@Serializable
data class PluginSignInMethod(
    val type: String = "",
    val label: String = "",
    val labels: Map<String, String> = emptyMap(),
    val placeholder: String = "",
    val keyless: Boolean = false,
    val keyAsked: Boolean = false,
)

@Serializable
data class PluginProviderOption(
    val key: String = "",
    val label: String = "",
    val description: String = "",
    val default: Boolean = false,
    val on: Boolean = false,
)

@Serializable
data class PluginProvider(
    val id: String = "",
    val pid: String = "",
    val name: String = "",
    val icon: String = "",
    val spec: String = "",
    val methods: List<PluginSignInMethod> = emptyList(),
    val signedIn: Boolean = false,
    val single: Boolean = false,
    val models: Int = 0,
    val options: List<PluginProviderOption> = emptyList(),
) {
    override fun toString(): String =
        "PluginProvider(id=$id, pid=$pid, spec=$spec, signedIn=$signedIn, models=$models)"
}

@Serializable
data class PluginPromptOption(
    val label: String = "",
    val value: String = "",
    val hint: String = "",
    val labels: Map<String, String> = emptyMap(),
    val hints: Map<String, String> = emptyMap(),
)

@Serializable
data class PluginPrompt(
    val type: String = "",
    val key: String = "",
    val message: String = "",
    val messages: Map<String, String> = emptyMap(),
    val placeholder: String = "",
    val options: List<PluginPromptOption> = emptyList(),
)

@Serializable
data class PluginPromptResult(
    val prompt: PluginPrompt? = null,
    val inputs: Map<String, String> = emptyMap(),
    val error: String = "",
)

@Serializable
data class PluginSignInState(
    val id: String = "",
    val agent: String = "",
    val url: String = "",
    val code: String = "",
    val state: String = "",
    val pasteCallback: Boolean = false,
    val pasteCode: Boolean = false,
    val pasteKey: Boolean = false,
    val keysUrl: String = "",
    val instructions: String = "",
    val opened: Boolean = false,
    val installing: String = "",
    val user: String = "",
    val plan: String = "",
    val using: Boolean = false,
    val again: Boolean = false,
    val error: String = "",
    val callbackBlocked: String = "",
    val callbackPort: Int = 0,
    val proxy: String = "",
    val openError: String = "",
    val linkOnly: Boolean = false,
) {
    override fun toString(): String =
        "PluginSignInState(id=$id, agent=$agent, state=$state, error=<redacted>)"
}

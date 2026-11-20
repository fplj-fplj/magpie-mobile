package cc.fplj.magpiemobile

import cc.fplj.magpiemobile.core.ManagementProtocolException
import cc.fplj.magpiemobile.core.ManagerCredential
import cc.fplj.magpiemobile.core.ProviderManagementClient
import cc.fplj.magpiemobile.core.ProviderSummary
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Real management parsing feeds the same saved-list projection used by ProvidersPanel. */
class ProviderConnectivityModelsTest {
    @Test fun implicitAllUsesExposedModelsEvenWhenChosenIsEmpty() {
        val saved = savedProvider(listOf("exposed-a" to true, "directory-only" to false, "exposed-b" to true))

        assertEquals(emptyList<String>(), saved.chosen)
        assertEquals(listOf("exposed-a", "exposed-b"), providerConnectivityModelIds(saved))
    }

    @Test fun explicitPicksDoNotOverrideExposureFlagsOrCoreModelOrder() {
        val saved = savedProvider(
            models = listOf("listed-second" to true, "unsupported-api" to false, "listed-first" to true),
            chosen = listOf("listed-first", "unsupported-api", "listed-second", "not-in-listing"),
        )

        assertEquals(listOf("listed-second", "listed-first"), providerConnectivityModelIds(saved))
    }

    @Test fun disabledUnreadyOrSelectNoneCannotOfferCandidatesEvenIfModelFlagsAreOn() {
        for ((off, noModels, ready) in listOf(
            Triple(true, false, true), Triple(false, true, true), Triple(false, false, false),
        )) {
            val saved = savedProvider(listOf("still-listed" to true), chosen = listOf("still-listed"),
                off = off, noModels = noModels, ready = ready)

            assertEquals(emptyList<String>(), providerConnectivityModelIds(saved))
        }
    }

    @Test fun missingProviderEmptyListingAndUnexposedPicksNeverFallBackToChosen() {
        assertEquals(emptyList<String>(), providerConnectivityModelIds(null))
        assertEquals(emptyList<String>(), providerConnectivityModelIds(
            savedProvider(emptyList(), chosen = listOf("saved-but-unexposed")),
        ))
        assertEquals(emptyList<String>(), providerConnectivityModelIds(
            savedProvider(listOf("directory-only" to false), chosen = listOf("directory-only")),
        ))
    }

    @Test fun duplicateManagementIdsAppearOnceInCoreOrder() {
        val saved = savedProvider(
            models = listOf("one" to true, "one" to true, "off" to false, "two" to true, "two" to true),
            chosen = listOf("two", "two", "one"),
        )

        assertEquals(listOf("one", "two"), providerConnectivityModelIds(saved))
    }

    @Test fun emptyOrBlankManagementIdsAreRejectedBeforeTheyCanBecomeCandidates() {
        for (invalidId in listOf("", " ", "\t")) {
            assertThrows(ManagementProtocolException::class.java) {
                providerConnectivityModelIds(savedProvider(listOf(invalidId to true)))
            }
            assertThrows(ManagementProtocolException::class.java) {
                providerConnectivityModelIds(savedProvider(listOf("valid" to true), chosen = listOf(invalidId)))
            }
        }
    }

    @Test fun implicitAllTwentyFourBoundaryUsesOnFlagsWithoutPromotingTheTwentyFifthModel() {
        for (size in listOf(23, 24, 25)) {
            // Upstream ExposedOf caps a custom provider's implicit-all exposure at 24.
            // providerInfoWith still returns the whole directory, with later entries off.
            val models = (1..size).map { "model-$it" to (it <= 24) }
            val saved = savedProvider(models)

            assertEquals(size, saved.models.size)
            assertEquals(models.take(24).map { it.first }, providerConnectivityModelIds(saved))
        }
    }

    @Test fun exposureLimitIsAppliedAfterServableFilteringSoEnabledIdsMayFollowDirectoryIndexTwentyFour() {
        // Two unavailable-API entries precede the 24 that the core exposes. A take(24)
        // over the raw directory would lose exposed-23 and exposed-24.
        val models = listOf("unsupported-a" to false, "unsupported-b" to false) +
            (1..24).map { "exposed-$it" to true } + listOf("beyond-limit" to false)
        val saved = savedProvider(models)

        assertEquals(27, saved.models.size)
        assertEquals((1..24).map { "exposed-$it" }, providerConnectivityModelIds(saved))
    }

    @Test fun explicitPicksBeyondTwentyFourAreNotArtificiallyTruncated() {
        val ids = (1..30).map { "picked-$it" }
        val saved = savedProvider(ids.map { it to true }, chosen = ids)

        assertEquals(ids, providerConnectivityModelIds(saved))
    }

    private fun savedProvider(
        models: List<Pair<String, Boolean>>,
        chosen: List<String> = emptyList(),
        noModels: Boolean = false,
        off: Boolean = false,
        ready: Boolean = true,
    ): ProviderSummary {
        val body = buildJsonObject {
            put("providers", buildJsonArray {
                add(buildJsonObject {
                    put("id", "saved-provider")
                    put("name", "Saved provider")
                    put("chat", "https://api.example/v1")
                    put("key", buildJsonObject { put("set", true); put("optional", false) })
                    put("models", buildJsonArray {
                        models.forEach { (id, on) ->
                            add(buildJsonObject { put("id", id); put("name", id); put("on", on) })
                        }
                    })
                    put("chosen", buildJsonArray { chosen.forEach { add(it) } })
                    put("noModels", noModels)
                    put("off", off)
                    put("ready", ready)
                })
            })
        }.toString()
        ListingPeer(body).use { peer ->
            val client = ProviderManagementClient(
                ManagerCredential.fromKey("connectivity-models-synthetic-key", peer.port),
                baseUrl = "http://127.0.0.1:${peer.port}",
            )
            return client.getProviders().providers.single()
        }
    }

    private class ListingPeer(private val body: String) : AutoCloseable {
        private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val failure = AtomicReference<Throwable?>()
        @Volatile private var active: Socket? = null
        val port: Int = listener.localPort
        private val worker = thread(isDaemon = true, name = "provider-connectivity-models-listing") {
            try {
                listener.accept().use { socket ->
                    active = socket
                    socket.soTimeout = 5_000
                    val input = socket.getInputStream().buffered()
                    val headers = StringBuilder()
                    while (!headers.endsWith("\r\n\r\n")) {
                        val next = input.read()
                        check(next >= 0 && headers.length < 32_768)
                        headers.append(next.toChar())
                    }
                    check(headers.startsWith("GET /api/providers HTTP/1.1\r\n"))
                    check(headers.lineSequence().any { it.startsWith("Cookie:", true) })
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    socket.getOutputStream().apply {
                        write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                        write(bytes)
                        flush()
                    }
                    active = null
                }
            } catch (error: Throwable) {
                if (!listener.isClosed) failure.set(error)
            }
        }

        override fun close() {
            listener.close()
            active?.close()
            worker.join(5_000)
            check(!worker.isAlive) { "Listing fixture did not exit" }
            failure.get()?.let { throw AssertionError("Listing fixture failed", it) }
        }
    }
}

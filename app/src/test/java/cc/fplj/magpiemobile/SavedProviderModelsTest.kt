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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real management parsing feeds the shared projection used by provider rows and model tests. */
class SavedProviderModelsTest {
    @Test fun implicitAllUsesExposedModelsEvenWhenChosenIsEmpty() {
        val saved = savedProvider(listOf("exposed-a" to true, "directory-only" to false, "exposed-b" to true))

        assertEquals(emptyList<String>(), saved.chosen)
        assertTrue(saved.ready)
        assertFalse(saved.off)
        assertFalse(saved.noModels)
        assertEquals(3, saved.models.size)
        assertEquals(listOf("exposed-a", "exposed-b"), savedEnabledProviderModelIds(saved))
    }

    @Test fun explicitPicksDoNotOverrideExposureFlagsOrCoreModelOrder() {
        val saved = savedProvider(
            models = listOf("listed-second" to true, "unsupported-api" to false, "listed-first" to true),
            chosen = listOf("listed-first", "unsupported-api", "listed-second", "not-in-listing"),
        )

        assertEquals(4, saved.chosen.size)
        assertEquals(3, saved.models.size)
        assertEquals(listOf("listed-second", "listed-first"), savedEnabledProviderModelIds(saved))
    }

    @Test fun disabledUnreadyOrSelectNoneHaveNoEnabledModelsEvenIfDirectoryFlagsAreOn() {
        for ((off, noModels, ready) in listOf(
            Triple(true, false, true), Triple(false, true, true), Triple(false, false, false),
            Triple(true, true, true), Triple(true, false, false), Triple(false, true, false),
            Triple(true, true, false),
        )) {
            val saved = savedProvider(listOf("still-listed" to true), chosen = listOf("still-listed"),
                off = off, noModels = noModels, ready = ready)

            assertEquals(off, saved.off)
            assertEquals(noModels, saved.noModels)
            assertEquals(ready, saved.ready)
            assertEquals(1, saved.models.size)
            assertTrue(saved.models.single().on)
            assertEquals(listOf("still-listed"), saved.chosen)
            assertEquals(emptyList<String>(), savedEnabledProviderModelIds(saved))
        }
    }

    @Test fun missingProviderEmptyListingAndUnexposedPicksNeverFallBackToChosen() {
        assertEquals(emptyList<String>(), savedEnabledProviderModelIds(null))
        assertEquals(emptyList<String>(), savedEnabledProviderModelIds(
            savedProvider(emptyList(), chosen = listOf("saved-but-unexposed")),
        ))
        assertEquals(emptyList<String>(), savedEnabledProviderModelIds(
            savedProvider(listOf("directory-only" to false), chosen = listOf("directory-only")),
        ))
    }

    @Test fun duplicateManagementIdsAppearOnceInCoreOrder() {
        val saved = savedProvider(
            models = listOf("one" to true, "one" to true, "off" to false, "two" to true, "two" to true),
            chosen = listOf("two", "two", "one"),
        )

        assertEquals(3, saved.models.size)
        assertEquals(listOf("one", "two"), savedEnabledProviderModelIds(saved))
    }

    @Test fun emptyOrBlankManagementIdsAreRejectedBeforeTheyCanBecomeCandidates() {
        for (invalidId in listOf("", " ", "\t")) {
            assertThrows(ManagementProtocolException::class.java) {
                savedEnabledProviderModelIds(savedProvider(listOf(invalidId to true)))
            }
            assertThrows(ManagementProtocolException::class.java) {
                savedEnabledProviderModelIds(savedProvider(listOf("valid" to true), chosen = listOf(invalidId)))
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
            assertEquals(models.take(24).map { it.first }, savedEnabledProviderModelIds(saved))
        }
    }

    @Test fun exposureLimitIsAppliedAfterServableFilteringSoEnabledIdsMayFollowDirectoryIndexTwentyFour() {
        // Two unavailable-API entries precede the 24 that the core exposes. A take(24)
        // over the raw directory would lose exposed-23 and exposed-24.
        val models = listOf("unsupported-a" to false, "unsupported-b" to false) +
            (1..24).map { "exposed-$it" to true } + listOf("beyond-limit" to false)
        val saved = savedProvider(models)

        assertEquals(27, saved.models.size)
        assertEquals((1..24).map { "exposed-$it" }, savedEnabledProviderModelIds(saved))
    }

    @Test fun explicitPicksBeyondTwentyFourAreNotArtificiallyTruncated() {
        val ids = (1..30).map { "picked-$it" }
        val saved = savedProvider(ids.map { it to true }, chosen = ids)

        assertEquals(ids, savedEnabledProviderModelIds(saved))
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
                ManagerCredential.fromKey("saved-provider-models-synthetic-key", peer.port),
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
        private val worker = thread(isDaemon = true, name = "saved-provider-models-listing") {
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
                    check(headers.lineSequence().any { it.startsWith("X-Magpie-Android-Manager:", true) })
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

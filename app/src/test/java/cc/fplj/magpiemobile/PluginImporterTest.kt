package cc.fplj.magpiemobile

import android.net.Uri
import cc.fplj.magpiemobile.core.ManagedPlugin
import cc.fplj.magpiemobile.core.PluginSnapshot
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PluginImporterTest {
    private class Source(private val entries: Map<String, Pair<String, String?>>, private val fail: String? = null) : PluginDocumentSource {
        override fun describe(uri: Uri, tree: Boolean): PluginDocument {
            val item = entries.getValue(uri.toString())
            return PluginDocument(uri, item.first, item.second == null)
        }
        override fun children(document: PluginDocument): List<PluginDocument> = entries.keys.filter {
            it.substringBeforeLast('/') == document.uri.toString()
        }.map { describe(Uri.parse(it), false) }
        override fun open(document: PluginDocument): ByteArrayInputStream {
            if (document.uri.toString() == fail) throw IOException("document provider failed")
            return ByteArrayInputStream(entries.getValue(document.uri.toString()).second!!.toByteArray())
        }
    }

    @Test fun publishesACompleteDirectoryAndRemovesOnlyTheConfirmedImport() {
        val root = kotlin.io.path.createTempDirectory().toFile()
        try {
            val unrelated = File(root, "existing").apply { mkdir() }
            val source = Source(mapOf("content://tree/plugin" to ("plugin" to null),
                "content://tree/plugin/package" to ("package.json" to "{\"main\":\"index.ts\"}"),
                "content://tree/plugin/code" to ("index.ts" to "export default () => ({})")))
            val importer = PluginImporter(root, source)
            val result = File(importer.importPlugin(Uri.parse("content://tree/plugin"), true))
            assertTrue(File(result, "package.json").isFile)
            assertEquals("export default () => ({})", File(result, "index.ts").readText())
            assertFalse(root.listFiles()!!.any { it.name.endsWith(".pending") })
            importer.removeImported(result.absolutePath)
            assertFalse(result.parentFile.exists())
            assertTrue(unrelated.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun aFailedDocumentReadPublishesNothing() {
        val root = kotlin.io.path.createTempDirectory().toFile()
        try {
            val source = Source(mapOf("content://tree/plugin" to ("plugin" to null),
                "content://tree/plugin/package" to ("package.json" to "{}"),
                "content://tree/plugin/code" to ("index.ts" to "code")), "content://tree/plugin/code")
            assertThrows(IOException::class.java) { PluginImporter(root, source).importPlugin(Uri.parse("content://tree/plugin"), true) }
            assertEquals(0, root.listFiles()!!.size)
        } finally { root.deleteRecursively() }
    }

    @Test fun excessiveSizeAndTraversalBothLeaveNoPartialPlugin() {
        val root = kotlin.io.path.createTempDirectory().toFile()
        try {
            val large = Source(mapOf("content://file/code" to ("plugin.ts" to "long content")))
            assertThrows(IllegalArgumentException::class.java) { PluginImporter(root, large, byteLimit = 3).importPlugin(Uri.parse("content://file/code"), false) }
            val traversal = Source(mapOf("content://file/code" to ("../plugin.ts" to "code")))
            assertThrows(IllegalArgumentException::class.java) { PluginImporter(root, traversal).importPlugin(Uri.parse("content://file/code"), false) }
            assertEquals(0, root.listFiles()!!.size)
        } finally { root.deleteRecursively() }
    }

    @Test fun reconciliationProtectsAnUnconfirmedInstallAndReclaimsABrowserRemoval() {
        val root = kotlin.io.path.createTempDirectory().toFile()
        try {
            val importer = PluginImporter(root, Source(mapOf("content://file/code" to ("plugin.ts" to "code"))))
            val spec = importer.importPlugin(Uri.parse("content://file/code"), false)
            importer.reconcile(PluginSnapshot()) // the picker still owns this snapshot
            assertTrue(File(spec).exists())
            importer.markSubmitted(spec)
            val afterRestart = PluginImporter(root, Source(emptyMap()))
            afterRestart.reconcile(PluginSnapshot()) // an absent row cannot disprove a running Add
            assertTrue(File(spec).exists())
            afterRestart.reconcile(PluginSnapshot(entries = listOf(ManagedPlugin(File(spec).toURI().toString()))))
            assertTrue(File(spec).exists())
            afterRestart.reconcile(PluginSnapshot()) // installed earlier, now deleted in the browser
            assertFalse(File(spec).parentFile.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun reconciliationReclaimsInterruptedCopiesButPreservesUnmanagedDirectories() {
        val root = kotlin.io.path.createTempDirectory().toFile()
        try {
            val pending = File(root, "${java.util.UUID.randomUUID()}.pending").apply { mkdir() }
            File(pending, "partial.ts").writeText("partial")
            val unsubmitted = File(root, java.util.UUID.randomUUID().toString()).apply { mkdir() }
            File(unsubmitted, "plugin.ts").writeText("code")
            File(unsubmitted, ".magpie-prepared").writeText("plugin.ts")
            val unmanaged = File(root, "existing").apply { mkdir() }
            PluginImporter(root, Source(emptyMap())).reconcile(PluginSnapshot())
            assertFalse(pending.exists())
            assertFalse(unsubmitted.exists())
            assertTrue(unmanaged.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun aRejectedInstallCanBeReclaimedByALaterReadAfterProcessDeath() {
        val root = kotlin.io.path.createTempDirectory().toFile()
        try {
            val importer = PluginImporter(root, Source(mapOf("content://file/code" to ("plugin.ts" to "code"))))
            val spec = importer.importPlugin(Uri.parse("content://file/code"), false)
            importer.markSubmitted(spec)
            importer.markRejected(spec)
            PluginImporter(root, Source(emptyMap())).reconcile(PluginSnapshot())
            assertFalse(File(spec).exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun removalPreservesOtherPluginEntriesInsideTheSameImportedDirectory() {
        val root = kotlin.io.path.createTempDirectory().toFile()
        try {
            val source = Source(mapOf("content://tree/plugin" to ("plugin" to null),
                "content://tree/plugin/package" to ("package.json" to "{}"),
                "content://tree/plugin/hooks" to ("child.hooks.mjs" to "export default {}")))
            val importer = PluginImporter(root, source)
            val spec = importer.importPlugin(Uri.parse("content://tree/plugin"), true)
            importer.markSubmitted(spec)
            importer.reconcile(PluginSnapshot(entries = listOf(ManagedPlugin(spec))))
            val child = File(spec, "child.hooks.mjs")
            val remaining = PluginSnapshot(entries = listOf(ManagedPlugin(child.toURI().toString())))
            importer.removeIfUnreferenced(spec, remaining)
            importer.reconcile(remaining)
            assertTrue(child.exists())
            importer.removeIfUnreferenced(child.absolutePath, PluginSnapshot())
            assertFalse(File(spec).exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun unreadableCoreListOrANewerDiskReferenceCannotAuthorizeDeletingSources() {
        val root = kotlin.io.path.createTempDirectory().toFile()
        try {
            val list = File(root, "plugins.json")
            val importer = PluginImporter(root, Source(mapOf("content://file/code" to ("plugin.ts" to "code"))), coreList = list)
            val spec = importer.importPlugin(Uri.parse("content://file/code"), false)
            importer.markSubmitted(spec)
            list.writeText("{\"plugins\":[{\"spec\":\"$spec\"}]}")
            importer.reconcile(PluginSnapshot(entries = listOf(ManagedPlugin(spec))))
            importer.removeIfUnreferenced(spec, PluginSnapshot())
            assertTrue(File(spec).exists()) // the disk list can be newer than the API response
            list.writeText("broken json")
            importer.reconcile(PluginSnapshot())
            importer.removeIfUnreferenced(spec, PluginSnapshot())
            assertTrue(File(spec).exists())
            list.writeText("{\"plugins\":[]}")
            importer.reconcile(PluginSnapshot())
            assertFalse(File(spec).exists())
        } finally { root.deleteRecursively() }
    }
}

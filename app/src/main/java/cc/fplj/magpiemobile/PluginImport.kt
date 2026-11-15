package cc.fplj.magpiemobile

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import cc.fplj.magpiemobile.core.PluginSnapshot
import java.io.File
import java.io.InputStream
import java.util.UUID

internal data class PluginDocument(val uri: Uri, val name: String, val directory: Boolean)

internal interface PluginDocumentSource {
    fun describe(uri: Uri, tree: Boolean): PluginDocument
    fun children(document: PluginDocument): List<PluginDocument>
    fun open(document: PluginDocument): InputStream
}

/** A private snapshot is published only after every selected document was copied. */
internal class PluginImporter(
    private val root: File,
    private val source: PluginDocumentSource,
    private val byteLimit: Long = 64L * 1024 * 1024,
    private val fileLimit: Int = 1024,
) {
    constructor(context: Context) : this(File(context.filesDir, "plugin-imports"), AndroidPluginDocuments(context))

    fun importPlugin(uri: Uri, tree: Boolean): String {
        check(root.isDirectory || root.mkdirs()) { "无法创建插件导入目录" }
        val id = UUID.randomUUID().toString()
        val pending = File(root, "$id.pending")
        val published = File(root, id)
        synchronized(activeStages) {
            check(pending.mkdir()) { "无法创建插件暂存目录" }
            activeStages.add(published.absolutePath)
        }
        var bytes = 0L
        var files = 0
        fun copy(document: PluginDocument, parent: File, depth: Int): File {
            require(depth <= 16) { "插件目录层级过深" }
            require(document.name.isNotBlank() && document.name !in setOf(".", "..") &&
                document.name.length <= 255 && document.name.none { it == '/' || it == '\\' || it.isISOControl() }) {
                "插件包含无效文件名"
            }
            val target = File(parent, document.name)
            require(target.parentFile.canonicalFile == parent.canonicalFile && !target.exists()) { "插件文件名重复或路径无效" }
            if (document.directory) {
                check(target.mkdir()) { "无法创建插件目录" }
                source.children(document).forEach { copy(it, target, depth + 1) }
            } else {
                require(++files <= fileLimit) { "插件文件超过 $fileLimit 个，请通过 npm 或 GitHub 安装依赖" }
                source.open(document).use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            bytes += count
                            require(bytes <= byteLimit) { "插件导入超过 64 MB，请通过 npm 或 GitHub 安装依赖" }
                            output.write(buffer, 0, count)
                        }
                    }
                }
            }
            return target
        }
        try {
            val selected = source.describe(uri, tree)
            if (!selected.directory) require(selected.name.substringAfterLast('.', "").lowercase() in
                setOf("js", "mjs", "cjs", "ts", "mts", "tsx")) { "请选择 JS / TS 插件文件，或导入插件目录" }
            val entry = copy(selected, pending, 0)
            if (selected.directory) require(File(entry, "package.json").isFile) { "插件目录需要包含 package.json" }
            File(pending, PREPARED).writeText(entry.name)
            check(pending.renameTo(published)) { "插件导入未完成" }
            return File(published, entry.name).absolutePath
        } catch (e: Exception) {
            pending.deleteRecursively()
            published.deleteRecursively()
            synchronized(activeStages) { activeStages.remove(published.absolutePath) }
            throw e
        }
    }

    /** Persist protection before submitting Add; an absent read cannot disprove a running install. */
    fun markSubmitted(spec: String) {
        val stage = importedStage(spec) ?: return
        File(stage, SUBMITTED).writeText("")
        File(stage, PREPARED).delete()
        synchronized(activeStages) { activeStages.remove(stage.absolutePath) }
    }

    /** The core returned a definite rejection. Defer deletion until a successful absent read. */
    fun markRejected(spec: String) {
        val stage = importedStage(spec) ?: return
        File(stage, REJECTED).writeText("")
        File(stage, SUBMITTED).delete()
    }

    /** Only a confirmed removal, rejected Add, or an unsubmitted snapshot can reach this method. */
    fun removeImported(spec: String) {
        val stage = importedStage(spec) ?: return
        stage.deleteRecursively()
        synchronized(activeStages) { activeStages.remove(stage.absolutePath) }
    }

    fun removeIfUnreferenced(spec: String, snapshot: PluginSnapshot) {
        val stage = importedStage(spec) ?: return
        if (snapshot.entries.none { importedStage(it.spec)?.canonicalFile == stage.canonicalFile }) removeImported(spec)
    }

    /** Run after a successful read or authoritative write result, with no unresolved native write. */
    fun reconcile(snapshot: PluginSnapshot) {
        val referenced = snapshot.entries.mapNotNull { importedStage(it.spec)?.canonicalPath }.toSet()
        for (stage in root.listFiles().orEmpty()) {
            if (!stage.isDirectory || stage.canonicalFile.parentFile != root.canonicalFile) continue
            val id = stage.name.removeSuffix(".pending")
            if (runCatching { UUID.fromString(id) }.isFailure) continue
            val publishedPath = File(root, id).absolutePath
            if (synchronized(activeStages) { publishedPath in activeStages }) continue
            when {
                stage.canonicalPath in referenced -> {
                    val registered = File(stage, REGISTERED)
                    if (!registered.exists()) registered.writeText("")
                    File(stage, PREPARED).delete()
                    File(stage, SUBMITTED).delete()
                    File(stage, REJECTED).delete()
                }
                stage.name.endsWith(".pending") -> stage.deleteRecursively()
                // A submitted but never observed Add remains protected across process death.
                File(stage, SUBMITTED).exists() -> Unit
                File(stage, REGISTERED).exists() || File(stage, PREPARED).exists() || File(stage, REJECTED).exists() -> stage.deleteRecursively()
                // Older snapshots have no journal. Preserve until observed in a core read.
            }
        }
    }

    private fun importedStage(spec: String): File? {
        val path = runCatching { if (spec.startsWith("file:")) File(java.net.URI(spec)) else File(spec) }.getOrNull() ?: return null
        var stage: File? = path
        while (stage != null) {
            if (stage.parentFile?.canonicalFile == root.canonicalFile) {
                return stage.takeIf { it.canonicalFile.parentFile == root.canonicalFile &&
                    runCatching { UUID.fromString(it.name) }.isSuccess }
            }
            stage = stage.parentFile
        }
        return null
    }

    companion object {
        private const val PREPARED = ".magpie-prepared"
        private const val SUBMITTED = ".magpie-submitted"
        private const val REGISTERED = ".magpie-registered"
        private const val REJECTED = ".magpie-rejected"
        private val activeStages = mutableSetOf<String>()
    }
}

private class AndroidPluginDocuments(context: Context) : PluginDocumentSource {
    private val resolver = context.applicationContext.contentResolver

    override fun describe(uri: Uri, tree: Boolean): PluginDocument {
        val documentUri = if (tree) DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri)) else uri
        resolver.query(documentUri, arrayOf(OpenableColumns.DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val name = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                    val mime = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE))
                    return PluginDocument(documentUri, name, mime == DocumentsContract.Document.MIME_TYPE_DIR)
                }
            }
        error("无法读取所选插件")
    }

    override fun children(document: PluginDocument): List<PluginDocument> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(document.uri, DocumentsContract.getDocumentId(document.uri))
        val children = mutableListOf<PluginDocument>()
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID))
                val name = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME))
                val mime = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE))
                children += PluginDocument(DocumentsContract.buildDocumentUriUsingTree(document.uri, id), name,
                    mime == DocumentsContract.Document.MIME_TYPE_DIR)
            }
        } ?: error("无法读取插件目录")
        return children
    }

    override fun open(document: PluginDocument): InputStream = resolver.openInputStream(document.uri) ?: error("无法读取插件文件")
}

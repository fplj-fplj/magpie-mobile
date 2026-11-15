package cc.fplj.magpiemobile

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import java.io.File

/**
 * Virtual read-only DocumentsProvider serving plugin fixture documents for
 * the SAF import path. Declared only in the debug APK's manifest (never
 * release). Uses only the standard DocumentsProvider overrides; the final
 * query() dispatches to queryDocument/queryChildDocuments through the
 * framework's own URI matcher.
 *
 * Documents:
 *   single            → one index.hooks.mjs file (tree=false import);
 *                       exports onRequest appending "saf-file-rewritten"
 *   tree              → a real plugin-factory directory (tree=true import):
 *     package.json      exact dependency "is-number": "7.0.0"
 *     index.mjs         plugin factory exporting a saf_describe tool that
 *                       calls isNumber(42) and reads helper.mjs's label
 *     helper.mjs        export const label = "saf-fixture"
 */
class FixturePluginDocumentsProvider : DocumentsProvider() {

    companion object {
        const val AUTHORITY = "cc.fplj.magpiemobile.fixtures"
        const val ROOT_ID = "fixture-root"

        const val DOC_SINGLE = "single"
        const val DOC_TREE = "tree"
        const val DOC_TREE_PACKAGE = "tree-package.json"
        const val DOC_TREE_INDEX = "tree-index.mjs"
        const val DOC_TREE_HELPER = "tree-helper.mjs"

        private val ROOT_PROJECTION = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_AVAILABLE_BYTES,
        )
        private val DOC_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )

        /** A gateway-hooks plugin: the file name ends in .hooks.mjs and it exports onRequest. */
        private val SINGLE_HOOKS_JS = """export function onRequest(body) {
  const last = body.messages[body.messages.length - 1]
  last.content += "|saf-file-rewritten"
  return body
}
"""
        private val PACKAGE_JSON = """{"name":"saf-fixture-plugin","version":"1.0.0","type":"module","main":"./index.mjs","dependencies":{"is-number":"7.0.0"}}
"""

        /** A real plugin factory: the tool executor depends on both the relative import and the npm dependency. */
        private val TREE_INDEX_JS = """import isNumber from "is-number"
import { label } from "./helper.mjs"
export const SafFixturePlugin = async () => ({
  tool: {
    saf_describe: {
      description: "Describe the SAF fixture",
      args: { type: "object", properties: {}, required: [] },
      execute: async () => label + " isNumber(42)=" + isNumber(42),
    },
  },
})
"""
        private val HELPER_JS = """export const label = "saf-fixture"
"""
    }

    private val files: Map<String, Pair<String, String>> = mapOf(
        DOC_SINGLE to ("index.hooks.mjs" to SINGLE_HOOKS_JS),
        DOC_TREE_PACKAGE to ("package.json" to PACKAGE_JSON),
        DOC_TREE_INDEX to ("index.mjs" to TREE_INDEX_JS),
        DOC_TREE_HELPER to ("helper.mjs" to HELPER_JS),
    )
    private val dirs: Map<String, Pair<String, List<String>>> = mapOf(
        DOC_TREE to ("fixture-plugin" to listOf(DOC_TREE_PACKAGE, DOC_TREE_INDEX, DOC_TREE_HELPER)),
    )

    override fun onCreate() = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cols = projection ?: ROOT_PROJECTION
        val cursor = MatrixCursor(cols)
        cursor.newRow().apply {
            for (col in cols) {
                when (col) {
                    DocumentsContract.Root.COLUMN_ROOT_ID -> add(ROOT_ID)
                    DocumentsContract.Root.COLUMN_DOCUMENT_ID -> add(DOC_TREE)
                    DocumentsContract.Root.COLUMN_FLAGS -> add(DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD)
                    DocumentsContract.Root.COLUMN_TITLE -> add("SAF Fixture Plugins")
                    DocumentsContract.Root.COLUMN_AVAILABLE_BYTES -> add(64L * 1024)
                }
            }
        }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cols = projection ?: DOC_PROJECTION
        val cursor = MatrixCursor(cols)
        includeDocument(cursor, documentId, cols)
        return cursor
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val cols = projection ?: DOC_PROJECTION
        val cursor = MatrixCursor(cols)
        val (name, children) = dirs[parentDocumentId]
            ?: throw IllegalArgumentException("The fixture provider has no directory $parentDocumentId")
        for (childId in children) includeDocument(cursor, childId, cols)
        return cursor
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        check(mode == "r") { "The fixture provider is read-only (mode was $mode)" }
        val (name, content) = files[documentId]
            ?: throw IllegalArgumentException("The fixture provider has no file $documentId")
        val tmp = File(requireNotNull(context).cacheDir, "saf-fixture-$name")
        tmp.parentFile?.mkdirs()
        tmp.writeText(content, Charsets.UTF_8)
        return ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        return dirs[parentDocumentId]?.second?.contains(documentId) == true
    }

    private fun includeDocument(cursor: MatrixCursor, documentId: String, cols: Array<out String>) {
        val isDir = dirs.containsKey(documentId)
        val file = files[documentId]
        check(isDir || file != null) { "Unknown fixture document: $documentId" }
        val row = cursor.newRow()
        for (col in cols) {
            when (col) {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID -> row.add(documentId)
                DocumentsContract.Document.COLUMN_DISPLAY_NAME -> row.add(if (isDir) dirs[documentId]!!.first else file!!.first)
                DocumentsContract.Document.COLUMN_MIME_TYPE -> row.add(
                    if (isDir) DocumentsContract.Document.MIME_TYPE_DIR else "text/javascript"
                )
                DocumentsContract.Document.COLUMN_FLAGS -> row.add(0)
                DocumentsContract.Document.COLUMN_SIZE -> row.add(
                    if (isDir) 0L else file!!.second.toByteArray(Charsets.UTF_8).size.toLong()
                )
                DocumentsContract.Document.COLUMN_LAST_MODIFIED -> row.add(System.currentTimeMillis())
            }
        }
    }
}

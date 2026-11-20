package cc.fplj.magpiemobile.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions

/**
 * Chat Profile and Agent Parameter Configuration (P3-1/P3-2).
 * Controls system prompt injection, sampling temperature, top_p, and model alias metadata.
 */
@Serializable
data class ChatProfile(
    val id: String,
    val name: String,
    val systemPrompt: String = "",
    val temperature: Double = 0.7,
    val topP: Double = 1.0,
    val maxTokens: Int? = null,
    val modelAlias: String = "",
) {
    init {
        require(ID_REGEX.matches(id)) { "Profile ID 格式无效: $id" }
        require(name.isNotBlank() && name.length <= 128) { "Profile 名称必须为 1..128 字符" }
        require(systemPrompt.length <= 32768) { "System Prompt 长度超出上限 (32KB)" }
        require(temperature in 0.0..2.0) { "Temperature 必须在 0.0 .. 2.0 之间" }
        require(topP in 0.0..1.0) { "Top-P 必须在 0.0 .. 1.0 之间" }
        require(maxTokens == null || maxTokens in 1..131072) { "Max Tokens 必须在 1..131072 之间" }
    }

    companion object {
        private val ID_REGEX = Regex("^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$")

        val DEFAULT = ChatProfile(
            id = "default",
            name = "通用助手",
            systemPrompt = "You are a helpful and concise AI assistant.",
            temperature = 0.7,
            topP = 1.0
        )
    }
}

/**
 * An app-private profile store. An absent source is the only implicit default library;
 * unreadable or malformed sources must be repaired before any mutation is permitted.
 */
class ChatProfileStore(private val storageFile: File) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    internal var beforeCommitForTest: (() -> Unit)? = null

    fun list(): List<ChatProfile> = locked { readSnapshot().profiles }

    /** Returns the committed snapshot without a second, potentially failing disk read. */
    fun save(profile: ChatProfile): List<ChatProfile> = locked {
        val snapshot = readSnapshot()
        val updated = checkedProfiles(snapshot.profiles.filter { it.id != profile.id } + profile)
        commit(snapshot, updated)
        updated
    }

    fun delete(id: String): Boolean = deleteAndList(id) != null

    /** Null means no deletion; even no-ops first validate the existing library. */
    fun deleteAndList(id: String): List<ChatProfile>? = locked {
        val snapshot = readSnapshot()
        if (id == ChatProfile.DEFAULT.id) return@locked null
        val updated = snapshot.profiles.filter { it.id != id }
        if (updated.size == snapshot.profiles.size) return@locked null
        commit(snapshot, updated)
        updated
    }

    private data class Location(
        val suppliedParent: Path,
        val parent: Path,
        val file: Path,
        val suppliedAnchor: Path,
        val anchor: Path,
        val anchorIdentity: BasicFileAttributes,
        val missingDirectories: List<Path>,
        val parentIdentity: BasicFileAttributes?,
    )

    private data class Snapshot(
        val location: Location,
        val identity: BasicFileAttributes?,
        val bytes: ByteArray?,
        val profiles: List<ChatProfile>,
    )

    private fun readSnapshot(): Snapshot {
        val location = locate()
        val identity = regularFile(location.file)
        if (identity == null) {
            verifyLocation(location)
            return Snapshot(location, null, null, listOf(ChatProfile.DEFAULT))
        }
        val bytes = readBounded(location.file, identity)
        verifyLocation(location)
        verifySameFile(identity, regularFile(location.file))
        val source = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
        ProfileJsonValidator(source).validate()
        val profiles = checkedProfiles(json.decodeFromString<List<ChatProfile>>(source))
        return Snapshot(location, identity, bytes, profiles)
    }

    private fun checkedProfiles(profiles: List<ChatProfile>): List<ChatProfile> {
        if (profiles.size > MAX_PROFILES || profiles.map { it.id }.toSet().size != profiles.size) {
            throw ChatProfileStoreException()
        }
        val complete = if (profiles.none { it.id == ChatProfile.DEFAULT.id }) {
            listOf(ChatProfile.DEFAULT) + profiles
        } else profiles.toList()
        if (complete.size > MAX_PROFILES) throw ChatProfileStoreException()
        if (complete.sumOf { it.id.length.toLong() + it.name.length + it.systemPrompt.length + it.modelAlias.length } > MAX_STORE_BYTES) {
            throw ChatProfileStoreException()
        }
        return complete
    }

    /** Resolve existing aliases once, without rejecting legitimate Android /data aliases.
     * Only missing directories beneath the nearest real parent may later be created.
     */
    private fun locate(): Location {
        val supplied = storageFile.toPath().toAbsolutePath()
        if (supplied.any { it.toString() == "." || it.toString() == ".." }) throw ChatProfileStoreException()
        val parent = supplied.parent ?: throw ChatProfileStoreException()
        val name = supplied.fileName ?: throw ChatProfileStoreException()
        if (name.toString().isBlank()) throw ChatProfileStoreException()
        var candidate = parent
        val missing = mutableListOf<Path>()
        while (true) {
            try {
                val anchor = candidate.toRealPath()
                val identity = directory(anchor)
                val missingDirectories = missing.asReversed().toList()
                val resolvedParent = missingDirectories.fold(anchor) { path, part -> path.resolve(part) }
                return Location(parent, resolvedParent, resolvedParent.resolve(name), candidate, anchor,
                    identity, missingDirectories, if (missingDirectories.isEmpty()) identity else null)
            } catch (_: NoSuchFileException) {
                // A dangling directory symlink is an anomalous path, not an absent source.
                val entry = try {
                    Files.readAttributes(candidate, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                } catch (_: NoSuchFileException) { null }
                if (entry != null) throw ChatProfileStoreException()
                missing.add(candidate.fileName ?: throw ChatProfileStoreException())
                candidate = candidate.parent ?: throw ChatProfileStoreException()
            }
        }
    }

    private fun prepareParent(location: Location): Location {
        verifyLocation(location)
        var path = location.anchor
        for (part in location.missingDirectories) {
            path = path.resolve(part)
            try {
                Files.createDirectory(path, PosixFilePermissions.asFileAttribute(PRIVATE_DIRECTORY_MODE))
            } catch (_: FileAlreadyExistsException) { /* validate the entry below */ }
            directory(path) // Never follow a newly substituted directory symlink.
        }
        val prepared = location.copy(parentIdentity = directory(location.parent))
        verifyLocation(prepared)
        return prepared
    }

    private fun verifyLocation(location: Location) {
        if (location.suppliedAnchor.toRealPath() != location.anchor ||
            !sameIdentity(location.anchorIdentity, directory(location.anchor))) {
            throw ChatProfileStoreException()
        }
        if (location.parentIdentity != null &&
            (location.suppliedParent.toRealPath() != location.parent ||
                !sameIdentity(location.parentIdentity, directory(location.parent)))) {
            throw ChatProfileStoreException()
        }
    }

    private fun directory(path: Path): BasicFileAttributes {
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (!attributes.isDirectory || attributes.isSymbolicLink) throw ChatProfileStoreException()
        return attributes
    }

    private fun regularFile(path: Path): BasicFileAttributes? {
        val attributes = try {
            Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) { return null }
        if (!attributes.isRegularFile || attributes.isSymbolicLink) throw ChatProfileStoreException()
        return attributes
    }

    private fun readBounded(path: Path, initial: BasicFileAttributes): ByteArray {
        if (initial.size() > MAX_STORE_BYTES) throw ChatProfileStoreException()
        return FileChannel.open(path, READ, NOFOLLOW_LINKS).use { channel ->
            val output = ByteArrayOutputStream()
            val buffer = ByteBuffer.allocate(4096)
            while (true) {
                buffer.clear()
                val count = channel.read(buffer)
                if (count < 0) break
                if (output.size() + count > MAX_STORE_BYTES) throw ChatProfileStoreException()
                output.write(buffer.array(), 0, count)
            }
            output.toByteArray()
        }
    }

    private fun sameIdentity(before: BasicFileAttributes, after: BasicFileAttributes): Boolean =
        // Some Linux providers synthesize creationTime from a mutable timestamp.
        // Android's Unix file key identifies the inode without rejecting our own writes.
        before.fileKey() != null && before.fileKey() == after.fileKey()

    private fun verifySameFile(before: BasicFileAttributes?, after: BasicFileAttributes?) {
        if (before == null && after == null) return
        if (before == null || after == null || !sameIdentity(before, after) ||
            before.size() != after.size() || before.lastModifiedTime() != after.lastModifiedTime()) {
            throw ChatProfileStoreException()
        }
    }

    private fun verifySource(snapshot: Snapshot, location: Location) {
        verifyLocation(location)
        val current = regularFile(location.file)
        verifySameFile(snapshot.identity, current)
        // File metadata alone cannot detect an in-place edit with a preserved timestamp.
        if (current != null) {
            if (!readBounded(location.file, current).contentEquals(snapshot.bytes)) throw ChatProfileStoreException()
            verifySameFile(current, regularFile(location.file))
        }
        verifyLocation(location)
    }

    private fun commit(snapshot: Snapshot, profiles: List<ChatProfile>) {
        val encoded = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(json.encodeToString(profiles)))
        val bytes = ByteArray(encoded.remaining()).also { encoded.get(it) }
        if (bytes.size > MAX_STORE_BYTES) throw ChatProfileStoreException()
        val location = prepareParent(snapshot.location)
        verifySource(snapshot, location)
        val temp = Files.createTempFile(location.parent, "." + location.file.fileName + ".", ".tmp",
            PosixFilePermissions.asFileAttribute(PRIVATE_FILE_MODE))
        var tempIdentity: BasicFileAttributes? = null
        var committed = false
        try {
            tempIdentity = regularFile(temp) ?: throw ChatProfileStoreException()
            FileChannel.open(temp, WRITE, NOFOLLOW_LINKS).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            val writtenTemp = regularFile(temp) ?: throw ChatProfileStoreException()
            if (!sameIdentity(tempIdentity, writtenTemp) ||
                Files.getPosixFilePermissions(temp, NOFOLLOW_LINKS) != PRIVATE_FILE_MODE) {
                throw ChatProfileStoreException()
            }
            beforeCommitForTest?.invoke()
            verifySource(snapshot, location)
            verifySameFile(writtenTemp, regularFile(temp))
            if (Files.getPosixFilePermissions(temp, NOFOLLOW_LINKS) != PRIVATE_FILE_MODE ||
                !readBounded(temp, writtenTemp).contentEquals(bytes)) throw ChatProfileStoreException()
            Files.move(temp, location.file, ATOMIC_MOVE, REPLACE_EXISTING)
            committed = true
        } finally {
            if (!committed && tempIdentity != null) {
                try {
                    verifyLocation(location)
                    val remaining = regularFile(temp)
                    if (remaining != null && sameIdentity(tempIdentity, remaining)) Files.deleteIfExists(temp)
                } catch (_: Exception) { /* Do not delete an unverified entry or hide the original error. */ }
            }
        }
    }

    private fun <T> locked(action: () -> T): T = synchronized(STORAGE_LOCK) {
        try { action() } catch (_: Exception) {
            // IO/decoder errors may quote paths, JSON, IDs or prompts; never chain them.
            throw ChatProfileStoreException()
        }
    }

    /** Bounded structural pass preserves strict duplicate-key detection before decoding.
     * Unknown fields remain forward-compatible, including their nested JSON values.
     */
    private inner class ProfileJsonValidator(private val source: String) {
        private var cursor = 0

        fun validate() {
            expect('[')
            var count = 0
            if (!consume(']')) {
                do {
                    if (++count > MAX_PROFILES) throw ChatProfileStoreException()
                    value(1)
                } while (consume(','))
                expect(']')
            }
            whitespace()
            if (cursor != source.length) throw ChatProfileStoreException()
        }

        private fun value(depth: Int) {
            if (depth > MAX_JSON_DEPTH) throw ChatProfileStoreException()
            whitespace()
            when (source.getOrNull(cursor)) {
                '{' -> {
                    cursor++
                    val names = HashSet<String>()
                    if (!consume('}')) {
                        do {
                            val name = json.decodeFromString<String>(string())
                            if (!names.add(name)) throw ChatProfileStoreException()
                            expect(':')
                            value(depth + 1)
                        } while (consume(','))
                        expect('}')
                    }
                }
                '[' -> {
                    cursor++
                    if (!consume(']')) {
                        do { value(depth + 1) } while (consume(','))
                        expect(']')
                    }
                }
                '"' -> json.decodeFromString<String>(string())
                else -> {
                    val start = cursor
                    while (cursor < source.length && source[cursor] !in ",]} \t\r\n") cursor++
                    val token = source.substring(start, cursor)
                    if (token != "null" && token != "true" && token != "false" && !JSON_NUMBER.matches(token)) {
                        throw ChatProfileStoreException()
                    }
                }
            }
        }

        private fun string(): String {
            whitespace()
            val start = cursor
            if (source.getOrNull(cursor++) != '"') throw ChatProfileStoreException()
            while (cursor < source.length) {
                when (val character = source[cursor++]) {
                    '\\' -> {
                        when (source.getOrNull(cursor++)) {
                            '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                            'u' -> repeat(4) {
                                val hex = source.getOrNull(cursor++) ?: throw ChatProfileStoreException()
                                if (hex !in '0'..'9' && hex !in 'a'..'f' && hex !in 'A'..'F') {
                                    throw ChatProfileStoreException()
                                }
                            }
                            else -> throw ChatProfileStoreException()
                        }
                    }
                    '"' -> return source.substring(start, cursor)
                    else -> if (character < ' ') throw ChatProfileStoreException()
                }
            }
            throw ChatProfileStoreException()
        }

        private fun expect(character: Char) {
            if (!consume(character)) throw ChatProfileStoreException()
        }

        private fun consume(character: Char): Boolean {
            whitespace()
            if (source.getOrNull(cursor) != character) return false
            cursor++
            return true
        }

        private fun whitespace() {
            while (cursor < source.length && source[cursor] in " \t\r\n") cursor++
        }
    }

    companion object {
        internal const val MAX_STORE_BYTES = 4 * 1024 * 1024
        internal const val MAX_PROFILES = 128
        private const val MAX_JSON_DEPTH = 32
        private val JSON_NUMBER = Regex("""-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?""")
        private val PRIVATE_FILE_MODE = PosixFilePermissions.fromString("rw-------")
        private val PRIVATE_DIRECTORY_MODE = PosixFilePermissions.fromString("rwx------")
        // A bounded process-wide lock also covers separate stores and directory aliases.
        // Libraries are small; serializing their short read/modify/commit avoids lock-map leaks.
        private val STORAGE_LOCK = Any()
    }
}

class ChatProfileStoreException : RuntimeException("聊天预设文件读写失败；请修复存储后重试")

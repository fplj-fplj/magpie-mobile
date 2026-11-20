package cc.fplj.magpiemobile.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatProfileStoreTest {
    @TempDir lateinit var directory: Path

    private fun file() = directory.resolve("profiles-private-path.json")
    private fun store() = ChatProfileStore(file().toFile())
    private fun profile(id: String = "custom") = ChatProfile(id, "预设 $id", systemPrompt = PRIVATE_TEXT)
    private fun encode(profiles: List<ChatProfile>) = Json.encodeToString(profiles)
    private fun names(): Set<String> = Files.list(directory).use { paths ->
        paths.map { it.fileName.toString() }.toList().toSet()
    }

    @Test fun missingSourceReturnsOnlyDefaultWithoutCreatingFileOrParent() {
        val nested = directory.resolve("missing/nested/profiles.json")
        val store = ChatProfileStore(nested.toFile())
        assertEquals(listOf(ChatProfile.DEFAULT), store.list())
        assertNull(store.deleteAndList("absent"))
        assertNull(store.deleteAndList(ChatProfile.DEFAULT.id))
        assertFalse(store.delete("absent"))
        assertFalse(Files.exists(directory.resolve("missing")))
        assertTrue(names().isEmpty())
    }

    @Test fun firstSaveCreatesPrivateParentsAndReturnsTheCommittedCompleteLibrary() {
        val nested = directory.resolve("missing/nested/profiles.json")
        val store = ChatProfileStore(nested.toFile())
        val committed = store.save(profile())
        assertEquals(listOf(ChatProfile.DEFAULT, profile()), committed)
        assertEquals(committed, store.list())
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(nested))
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(nested.parent))
        assertEquals(setOf("profiles.json"), Files.list(nested.parent).use { paths ->
            paths.map { it.fileName.toString() }.toList().toSet()
        })
    }

    @Test fun legacyLibraryWithoutDefaultIsCompletedInMemoryBeforeAnyMutation() {
        val raw = encode(listOf(profile()))
        Files.writeString(file(), raw)
        val store = store()
        assertEquals(listOf(ChatProfile.DEFAULT, profile()), store.list())
        assertEquals(raw, Files.readString(file()))
        assertNull(store.deleteAndList("absent"))
        assertEquals(raw, Files.readString(file()))
        val committed = store.save(profile("second"))
        assertEquals(listOf(ChatProfile.DEFAULT, profile(), profile("second")), committed)
        assertEquals(committed, store.list())
    }

    @Test fun emptyLibraryIsValidAndDefaultCompletionDoesNotWriteDuringRead() {
        Files.writeString(file(), "[]")
        assertEquals(listOf(ChatProfile.DEFAULT), store().list())
        assertEquals("[]", Files.readString(file()))
    }

    @Test fun updateAndDeleteReturnCommittedSnapshotsAndPreserveUnrelatedProfiles() {
        val store = store()
        store.save(profile("one"))
        store.save(profile("two"))
        val renamed = profile("one").copy(name = "新名称", temperature = 0.2)
        val updated = store.save(renamed)
        assertEquals(listOf(ChatProfile.DEFAULT, profile("two"), renamed), updated)
        val beforeNoOp = Files.readAllBytes(file())
        assertNull(store.deleteAndList("default"))
        assertNull(store.deleteAndList("absent"))
        assertFalse(store.delete("absent"))
        assertTrue(beforeNoOp.contentEquals(Files.readAllBytes(file())))
        val deleted = store.deleteAndList("one")
        assertEquals(listOf(ChatProfile.DEFAULT, profile("two")), deleted)
        assertEquals(deleted, store.list())
        assertTrue(store.delete("two"))
        assertEquals(listOf(ChatProfile.DEFAULT), store.list())
    }

    @Test fun rewritingLegacyPermissionsUsesNewPrivateInode() {
        val store = store()
        store.save(profile())
        val oldIdentity = Files.getAttribute(file(), "basic:fileKey")
        Files.setPosixFilePermissions(file(), PosixFilePermissions.fromString("rw-r--r--"))
        val committed = store.save(profile().copy(name = "修改名称"))
        assertEquals(committed, store.list())
        assertFalse(oldIdentity == Files.getAttribute(file(), "basic:fileKey"))
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file()))
        assertEquals(setOf(file().fileName.toString()), names())
    }

    @Test fun malformedTruncatedNullWrongShapeAndInvalidValuesFailWithoutOverwritingSource() {
        val invalidSources = listOf(
            "",
            "not JSON $PRIVATE_TEXT",
            "[{\"id\":\"$PRIVATE_TEXT\",",
            "null",
            "{}",
            "[null]",
            "[{\"id\":\"custom\",\"name\":null}]",
            "[{\"id\":\"$PRIVATE_TEXT invalid\",\"name\":\"name\"}]",
            "[{\"id\":\"custom\",\"name\":\" \"}]",
            "[{\"id\":\"custom\",\"name\":\"name\",\"temperature\":2.1}]",
            "[{\"id\":\"custom\",\"name\":\"name\",\"topP\":-0.1}]",
            "[{\"id\":\"custom\",\"name\":\"name\",\"maxTokens\":0}]",
            "[{\"id\":\"custom\",\"name\":\"name\",\"temperature\":1e999}]",
            "[{\"id\":\"custom\",\"name\":\"name\",\"future\":unquoted}]",
            "[{\"id\":\"custom\",\"name\":\"name\",\"future\":01}]",
            "[{\"id\":\"custom\",\"name\":\"name\",\"future\":\"bad\\xescape\"}]",
            "[{\"id\":\"custom\",\"name\":\"name\",\"future\":\"raw\nnewline\"}]",
            "[{\"id\":\"custom\",\"name\":\"name\",\"future\":\"truncated\\u12\"}]",
        )
        for (source in invalidSources) assertRejectedAndPreserved(source.toByteArray(Charsets.UTF_8))
    }

    @Test fun duplicateIdsAndDuplicateDecodedJsonKeysAreRejected() {
        val invalidSources = listOf(
            encode(listOf(profile(), profile().copy(name = "other"))),
            encode(listOf(ChatProfile.DEFAULT, ChatProfile.DEFAULT)),
            "[{\"id\":\"custom\",\"id\":\"other\",\"name\":\"name\"}]",
            "[{\"id\":\"custom\",\"\\u0069d\":\"other\",\"name\":\"name\"}]",
            "[{\"id\":\"custom\",\"name\":\"name\",\"future\":{\"key\":1,\"key\":2}}]",
        )
        for (source in invalidSources) assertRejectedAndPreserved(source.toByteArray(Charsets.UTF_8))
    }

    @Test fun unknownWellFormedFieldsAndEscapedTextRemainCompatible() {
        val source = """[{"id":"custom","name":"name","systemPrompt":"quote: \" comma: , bracket: ] slash: \\ and \u4e2d","future":{"nested":[true,false,null,-1.2e+3],"escaped\"key":"value"}}]"""
        Files.writeString(file(), source)
        val profiles = store().list()
        assertEquals(2, profiles.size)
        assertEquals("quote: \" comma: , bracket: ] slash: \\ and 中", profiles.last().systemPrompt)
        assertEquals(source, Files.readString(file()))
    }

    @Test fun malformedUtf8IsNotSilentlyReplacedOrOverwritten() {
        val bytes = "[{\"id\":\"custom\",\"name\":\"".toByteArray() +
            byteArrayOf(0xc3.toByte(), 0x28) + "\"}]".toByteArray()
        assertRejectedAndPreserved(bytes)
    }

    @Test fun unicodeOnSaveIsNeverSilentlyReplaced() {
        val store = store()
        store.save(profile())
        val previous = Files.readAllBytes(file())
        val invalid = profile("other").copy(systemPrompt = "isolated-surrogate-\ud800")
        try {
            // A serializer may safely escape the surrogate; otherwise strict UTF-8 must fail.
            val committed = store.save(invalid)
            assertEquals(invalid, committed.last())
            assertEquals(invalid, store.list().last())
        } catch (error: ChatProfileStoreException) {
            assertNull(error.cause)
            assertTrue(previous.contentEquals(Files.readAllBytes(file())))
        }
        assertEquals(setOf(file().fileName.toString()), names())
    }

    @Test fun oversizedSourcesAndTooManyProfilesAreRejectedAndPreserved() {
        assertRejectedAndPreserved(ByteArray(ChatProfileStore.MAX_STORE_BYTES + 1) { 32 })
        val tooMany = (1..ChatProfileStore.MAX_PROFILES + 1).map { profile("p$it") }
        assertRejectedAndPreserved(encode(tooMany).toByteArray())
        // Default completion participates in the total bound too.
        val noRoomForDefault = (1..ChatProfileStore.MAX_PROFILES).map { profile("p$it") }
        assertRejectedAndPreserved(encode(noRoomForDefault).toByteArray())
    }

    @Test fun exactByteLimitAndExactCompletedProfileLimitAreAccepted() {
        Files.writeString(file(), "[]" + " ".repeat(ChatProfileStore.MAX_STORE_BYTES - 2))
        assertEquals(listOf(ChatProfile.DEFAULT), store().list())
        val full = listOf(ChatProfile.DEFAULT) + (1 until ChatProfileStore.MAX_PROFILES).map { profile("p$it") }
        Files.writeString(file(), encode(full))
        val store = store()
        assertEquals(full, store.list())
        val previous = Files.readAllBytes(file())
        assertSafeFailure { store.save(profile("one-too-many")) }
        assertTrue(previous.contentEquals(Files.readAllBytes(file())))
        assertEquals(ChatProfileStore.MAX_PROFILES, store.save(profile("p1").copy(name = "updated")).size)
    }

    @Test fun oversizedSaveAndDeepUnknownJsonCannotDamageExistingSource() {
        val large = (1..46).map { profile("p$it").copy(systemPrompt = "汉".repeat(32768)) }
        val store = store()
        Files.writeString(file(), encode(listOf(ChatProfile.DEFAULT) + large.take(42)))
        assertEquals(43, store.list().size)
        val previous = Files.readAllBytes(file())
        assertSafeFailure { store.save(large[42]) }
        assertTrue(previous.contentEquals(Files.readAllBytes(file())))
        val nested = "[".repeat(64) + "null" + "]".repeat(64)
        assertRejectedAndPreserved("""[{"id":"custom","name":"name","future":$nested}]""".toByteArray())
    }

    @Test fun directoryTargetIsAnErrorForReadsAndEveryMutation() {
        Files.createDirectory(file())
        val store = store()
        assertSafeFailure { store.list() }
        assertSafeFailure { store.save(profile()) }
        assertSafeFailure { store.delete("custom") }
        assertSafeFailure { store.deleteAndList("default") }
        assertTrue(Files.isDirectory(file()))
        assertEquals(setOf(file().fileName.toString()), names())
    }

    @Test fun unreadableSourceIsNotAnEmptyLibrary() {
        val bytes = encode(listOf(profile())).toByteArray()
        Files.write(file(), bytes)
        Files.setPosixFilePermissions(file(), PosixFilePermissions.fromString("---------"))
        try {
            assumeFalse(Files.isReadable(file()), "privileged users bypass permission restrictions")
            val store = store()
            assertSafeFailure { store.list() }
            assertSafeFailure { store.save(profile("other")) }
            assertSafeFailure { store.delete("custom") }
        } finally {
            Files.setPosixFilePermissions(file(), PosixFilePermissions.fromString("rw-------"))
        }
        assertTrue(bytes.contentEquals(Files.readAllBytes(file())))
    }

    @Test fun targetSymlinkAndDanglingSymlinkAreNeverMissingOrOverwritten() {
        val victim = directory.resolve("victim.txt")
        Files.writeString(victim, PRIVATE_TEXT)
        Files.createSymbolicLink(file(), victim)
        val store = store()
        assertSafeFailure { store.list() }
        assertSafeFailure { store.save(profile()) }
        assertSafeFailure { store.deleteAndList("default") }
        assertTrue(Files.isSymbolicLink(file()))
        assertEquals(PRIVATE_TEXT, Files.readString(victim))
        Files.delete(file())
        Files.createSymbolicLink(file(), directory.resolve("absent-victim"))
        assertSafeFailure { store.list() }
        assertSafeFailure { store.save(profile()) }
        assertSafeFailure { store.delete("custom") }
        assertTrue(Files.isSymbolicLink(file()))
        assertFalse(Files.exists(directory.resolve("absent-victim")))
    }

    @Test fun existingDirectoryAliasesWorkButDanglingParentsAndFileParentsFail() {
        val real = Files.createDirectory(directory.resolve("real"))
        val alias = directory.resolve("android-data-alias")
        Files.createSymbolicLink(alias, real)
        val store = ChatProfileStore(alias.resolve("profiles.json").toFile())
        val committed = store.save(profile())
        assertEquals(committed, ChatProfileStore(real.resolve("profiles.json").toFile()).list())
        val dangling = directory.resolve("dangling-parent")
        Files.createSymbolicLink(dangling, directory.resolve("absent-directory"))
        val badStore = ChatProfileStore(dangling.resolve("profiles.json").toFile())
        assertSafeFailure { badStore.list() }
        assertSafeFailure { badStore.save(profile()) }
        assertFalse(Files.exists(directory.resolve("absent-directory")))
        val ordinaryFile = directory.resolve("file-parent")
        Files.writeString(ordinaryFile, PRIVATE_TEXT)
        val fileParentStore = ChatProfileStore(ordinaryFile.resolve("profiles.json").toFile())
        assertSafeFailure { fileParentStore.list() }
        assertSafeFailure { fileParentStore.save(profile()) }
        assertEquals(PRIVATE_TEXT, Files.readString(ordinaryFile))
    }

    @Test fun saveAndDeleteFailuresBeforeCommitKeepBytesAndRemoveOnlyTheOwnedTemp() {
        val store = store()
        store.save(profile())
        val unrelated = directory.resolve(".${file().fileName}.user-owned.tmp")
        Files.writeString(unrelated, PRIVATE_TEXT)
        val previous = Files.readAllBytes(file())
        store.beforeCommitForTest = {
            val temps = Files.list(directory).use { paths ->
                paths.filter { it != unrelated && it != file() }.toList()
            }
            assertEquals(1, temps.size)
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(temps.single()))
            throw IOException(PRIVATE_TEXT + file())
        }
        assertSafeFailure { store.save(profile("other")) }
        assertTrue(previous.contentEquals(Files.readAllBytes(file())))
        assertSafeFailure { store.deleteAndList("custom") }
        assertTrue(previous.contentEquals(Files.readAllBytes(file())))
        assertEquals(PRIVATE_TEXT, Files.readString(unrelated))
        assertEquals(setOf(file().fileName.toString(), unrelated.fileName.toString()), names())
    }

    @Test fun failedFirstCommitLeavesNoFakeLibraryOrOwnedTemp() {
        val store = store()
        store.beforeCommitForTest = { throw IOException(PRIVATE_TEXT) }
        assertSafeFailure { store.save(profile()) }
        assertFalse(Files.exists(file()))
        assertTrue(names().isEmpty())
        assertEquals(listOf(ChatProfile.DEFAULT), store.list())
    }

    @Test fun inPlaceSourceChangeWithRestoredTimestampAbortsCommitWithoutOverwritingIt() {
        val store = store()
        store.save(profile())
        val previous = Files.readAllBytes(file())
        val timestamp = Files.getLastModifiedTime(file())
        val changed = String(previous, Charsets.UTF_8).replace("custom", "edited").toByteArray(Charsets.UTF_8)
        assertEquals(previous.size, changed.size)
        store.beforeCommitForTest = {
            Files.write(file(), changed)
            Files.setLastModifiedTime(file(), timestamp)
        }
        assertSafeFailure { store.save(profile("other")) }
        assertTrue(changed.contentEquals(Files.readAllBytes(file())))
        assertEquals(setOf(file().fileName.toString()), names())
    }

    @Test fun targetSwapToSymlinkBeforeCommitCannotOverwriteVictim() {
        val store = store()
        store.save(profile())
        val previous = Files.readAllBytes(file())
        val backup = directory.resolve("saved-original.json")
        val victim = directory.resolve("victim.txt")
        Files.writeString(victim, PRIVATE_TEXT)
        store.beforeCommitForTest = {
            Files.move(file(), backup, REPLACE_EXISTING)
            Files.createSymbolicLink(file(), victim)
        }
        assertSafeFailure { store.save(profile("other")) }
        assertTrue(previous.contentEquals(Files.readAllBytes(backup)))
        assertTrue(Files.isSymbolicLink(file()))
        assertEquals(PRIVATE_TEXT, Files.readString(victim))
        assertEquals(setOf(file().fileName.toString(), "saved-original.json", "victim.txt"), names())
    }

    @Test fun tamperedOwnedTempCannotReplaceOriginalEvenWithPreservedMetadata() {
        val store = store()
        store.save(profile())
        val previous = Files.readAllBytes(file())
        store.beforeCommitForTest = {
            val temp = Files.list(directory).use { paths -> paths.filter { it != file() }.toList().single() }
            val timestamp: FileTime = Files.getLastModifiedTime(temp)
            val bytes = Files.readAllBytes(temp)
            bytes[0] = '{'.code.toByte()
            Files.write(temp, bytes)
            Files.setLastModifiedTime(temp, timestamp)
        }
        assertSafeFailure { store.save(profile("other")) }
        assertTrue(previous.contentEquals(Files.readAllBytes(file())))
        assertEquals(setOf(file().fileName.toString()), names())
    }

    @Test fun separateStoresSerializeReadModifyCommitAcrossDirectoryAliases() {
        val real = Files.createDirectory(directory.resolve("real"))
        val alias = directory.resolve("directory-alias")
        Files.createSymbolicLink(alias, real)
        val first = ChatProfileStore(real.resolve("profiles.json").toFile())
        val second = ChatProfileStore(alias.resolve("profiles.json").toFile())
        first.save(profile("initial"))
        val firstAtCommit = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val firstThread = AtomicReference<Thread>()
        val secondThread = AtomicReference<Thread>()
        val executor = Executors.newFixedThreadPool(2)
        first.beforeCommitForTest = {
            firstThread.set(Thread.currentThread())
            firstAtCommit.countDown()
            assertTrue(releaseFirst.await(5, TimeUnit.SECONDS))
        }
        try {
            val firstWrite = executor.submit(Callable { first.save(profile("first")) })
            assertTrue(firstAtCommit.await(5, TimeUnit.SECONDS))
            val secondWrite = executor.submit(Callable {
                secondThread.set(Thread.currentThread())
                secondStarted.countDown()
                second.save(profile("second"))
            })
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
            val threadInfo = ManagementFactory.getThreadMXBean()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var blockedByFirst = false
            while (System.nanoTime() < deadline && !secondWrite.isDone) {
                val info = threadInfo.getThreadInfo(secondThread.get().id)
                if (info?.threadState == Thread.State.BLOCKED && info.lockOwnerId == firstThread.get().id) {
                    blockedByFirst = true
                    break
                }
                Thread.yield()
            }
            assertTrue(blockedByFirst, "second store must wait for the first read/modify/commit")
            releaseFirst.countDown()
            assertEquals(setOf("default", "initial", "first"), firstWrite.get(5, TimeUnit.SECONDS).map { it.id }.toSet())
            assertEquals(setOf("default", "initial", "first", "second"), secondWrite.get(5, TimeUnit.SECONDS).map { it.id }.toSet())
            assertEquals(setOf("default", "initial", "first", "second"), first.list().map { it.id }.toSet())
        } finally {
            releaseFirst.countDown()
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    private fun assertRejectedAndPreserved(bytes: ByteArray) {
        Files.write(file(), bytes)
        val store = store()
        assertSafeFailure { store.list() }
        assertSafeFailure { store.save(profile("replacement")) }
        assertSafeFailure { store.delete("custom") }
        assertSafeFailure { store.deleteAndList("default") }
        assertSafeFailure { store.deleteAndList("absent") }
        assertTrue(bytes.contentEquals(Files.readAllBytes(file())))
        assertEquals(setOf(file().fileName.toString()), names())
    }

    private fun assertSafeFailure(action: () -> Any?) {
        val error = assertFailsWith<ChatProfileStoreException> { action() }
        assertEquals(ChatProfileStoreException().message, error.message)
        assertNull(error.cause)
        assertFalse(error.stackTraceToString().contains(PRIVATE_TEXT))
        assertFalse(error.stackTraceToString().contains(file().toString()))
    }

    companion object {
        private const val PRIVATE_TEXT = "private-system-prompt-fixture-do-not-log"
    }
}

package cc.fplj.magpiemobile.core

import java.io.File
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BunRuntimeInspectorTest {

    @field:TempDir
    lateinit var tempDir: File

    @Test
    fun detectArchitectureReturnsNoneWhenFileMissing() {
        val layout = EnginePaths.Layout(
            nativeLibraryDir = tempDir,
            homeDir = File(tempDir, "home"),
            cacheDir = File(tempDir, "cache"),
            configDir = File(tempDir, "config"),
        )
        val status = BunRuntimeInspector.inspect(layout)
        assertFalse(status.installed)
        assertFalse(status.executable)
        assertEquals("none", status.architecture)
    }

    @Test
    fun detectArchitectureIdentifiesAArch64ElfHeader() {
        val mockBun = File(tempDir, EnginePaths.BUN_LIB)
        // Construct valid 20-byte ELF header for aarch64
        val elfHeader = ByteArray(20)
        elfHeader[0] = 0x7F.toByte()
        elfHeader[1] = 'E'.code.toByte()
        elfHeader[2] = 'L'.code.toByte()
        elfHeader[3] = 'F'.code.toByte()
        elfHeader[4] = 2 // 64-bit
        elfHeader[5] = 1 // Little endian
        elfHeader[18] = 183.toByte() // 0xB7 = 183 (EM_AARCH64)
        elfHeader[19] = 0.toByte()
        mockBun.writeBytes(elfHeader)
        mockBun.setExecutable(true)

        val layout = EnginePaths.Layout(
            nativeLibraryDir = tempDir,
            homeDir = File(tempDir, "home"),
            cacheDir = File(tempDir, "cache"),
            configDir = File(tempDir, "config"),
        )
        val status = BunRuntimeInspector.inspect(layout)
        assertTrue(status.installed)
        assertEquals("aarch64", status.architecture)
        assertTrue(status.executable)
    }

    @Test
    fun detectArchitectureRejectsX86_64WhenArm64Expected() {
        val mockBun = File(tempDir, EnginePaths.BUN_LIB)
        val elfHeader = ByteArray(20)
        elfHeader[0] = 0x7F.toByte()
        elfHeader[1] = 'E'.code.toByte()
        elfHeader[2] = 'L'.code.toByte()
        elfHeader[3] = 'F'.code.toByte()
        elfHeader[4] = 2
        elfHeader[5] = 1
        elfHeader[18] = 62.toByte() // EM_X86_64
        mockBun.writeBytes(elfHeader)
        mockBun.setExecutable(true)
        val layout = EnginePaths.Layout(
            nativeLibraryDir = tempDir,
            homeDir = File(tempDir, "home"),
            cacheDir = File(tempDir, "cache"),
            configDir = File(tempDir, "config"),
        )
        val status = BunRuntimeInspector.inspect(layout)
        assertTrue(status.installed)
        assertEquals("x86_64", status.architecture)
        assertFalse(status.executable)
        assertEquals("载荷非 AArch64 架构: x86_64", status.errorMessage)
    }

    @Test
    fun detectArchitectureAcceptsX86_64WhenExpected() {
        val mockBun = File(tempDir, EnginePaths.BUN_LIB)
        val elfHeader = ByteArray(20)
        elfHeader[0] = 0x7F.toByte()
        elfHeader[1] = 'E'.code.toByte()
        elfHeader[2] = 'L'.code.toByte()
        elfHeader[3] = 'F'.code.toByte()
        elfHeader[4] = 2
        elfHeader[5] = 1
        elfHeader[18] = 62.toByte() // EM_X86_64
        mockBun.writeBytes(elfHeader)
        mockBun.setExecutable(true)
        val layout = EnginePaths.Layout(
            nativeLibraryDir = tempDir,
            homeDir = File(tempDir, "home"),
            cacheDir = File(tempDir, "cache"),
            configDir = File(tempDir, "config"),
        )
        val status = BunRuntimeInspector.inspect(layout, PayloadVerifier.Architecture.X86_64)
        assertTrue(status.installed)
        assertEquals("x86_64", status.architecture)
        assertTrue(status.executable)
    }

    @Test
    fun detectArchitectureRejectsNonElfOrCorruptedHeader() {
        val mockBun = File(tempDir, "non_elf.bin")
        mockBun.writeText("NOT AN ELF BINARY AT ALL! 1234567890")
        assertEquals("not-elf", BunRuntimeInspector.detectElfArchitecture(mockBun))

        val shortFile = File(tempDir, "short.bin")
        shortFile.writeBytes(byteArrayOf(0x7F, 'E'.code.toByte()))
        assertEquals("truncated", BunRuntimeInspector.detectElfArchitecture(shortFile))
    }
}

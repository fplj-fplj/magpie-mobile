package cc.fplj.magpiemobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppUpdatePolicyTest {
    private fun identity(
        expectedName: String = "0.1.66-update",
        expectedCode: Long = 67,
        packageName: String? = "cc.fplj.magpiemobile",
        versionName: String? = expectedName,
        versionCode: Long? = expectedCode,
        debuggable: Boolean = false,
        testOnly: Boolean = false,
        installedPackage: String = "cc.fplj.magpiemobile",
        installedCode: Long? = 66,
    ) = AppUpdatePolicy.identityFailure(
        expectedName, expectedCode, packageName, versionName, versionCode,
        debuggable, testOnly, installedPackage, installedCode,
    )

    @Test
    fun acceptsOnlyHigherMatchingProductionPackage() {
        assertNull(identity())
        assertEquals("更新 APK 不是更高版本", identity(versionCode = 66, installedCode = 66))
        assertEquals("更新 APK 不是更高版本", identity(versionCode = 65))
        assertEquals("更新 APK 包名不匹配", identity(packageName = "example.invalid"))
        assertEquals("更新 APK 版本名称不匹配", identity(versionName = "other"))
        assertEquals("更新 APK 版本代码不匹配", identity(versionCode = 68))
        assertEquals("更新 APK 是可调试版本", identity(debuggable = true))
        assertEquals("更新 APK 是测试版本", identity(testOnly = true))
        assertEquals("当前应用包名不受更新器支持", identity(installedPackage = "example.invalid"))
        assertEquals("无法读取当前应用版本", identity(installedCode = null))
        assertEquals("无法读取更新 APK 包信息", identity(packageName = null))
    }

    @Test
    fun expectedVersionAndHashAreBoundedAndCanonical() {
        assertEquals("更新版本名称无效", identity(expectedName = ""))
        assertEquals("更新版本名称无效", identity(expectedName = "bad\nname"))
        assertEquals("更新版本代码无效", identity(expectedCode = 0))
        val hash = "A".repeat(64)
        assertEquals("a".repeat(64), AppUpdatePolicy.normalizedSha256(hash))
        assertNull(AppUpdatePolicy.normalizedSha256("A".repeat(63)))
        assertNull(AppUpdatePolicy.normalizedSha256("g".repeat(64)))
        assertEquals(true, AppUpdatePolicy.validDownloadName("magpie-mobile-0.1.66.apk"))
        assertEquals(false, AppUpdatePolicy.validDownloadName("../../other.apk"))
        assertEquals(false, AppUpdatePolicy.validDownloadName("magpie.apk.part"))
    }

    @Test
    fun bothCurrentAndCandidateMustUseTheUniqueFixedCertificate() {
        val fixed = AppUpdatePolicy.RELEASE_CERTIFICATE_SHA256
        assertNull(AppUpdatePolicy.signingFailure(fixed, fixed))
        assertEquals("当前安装版本签名不受信任",
            AppUpdatePolicy.signingFailure("0".repeat(64), fixed))
        assertEquals("更新 APK 签名证书不匹配",
            AppUpdatePolicy.signingFailure(fixed, "0".repeat(64)))
    }

    @Test
    fun elfPolicyRequiresAarch64PieAndRejectsEveryOtherHeaderShape() {
        assertNull(AppUpdatePolicy.elfFailure(elf()))
        assertEquals("核心载荷不是 ELF", AppUpdatePolicy.elfFailure(elf(magic = 0)))
        assertEquals("核心载荷不是 64 位 ELF", AppUpdatePolicy.elfFailure(elf(bits = 1)))
        assertEquals("核心载荷不是小端 ELF", AppUpdatePolicy.elfFailure(elf(endian = 2)))
        assertEquals("核心载荷不是 PIE", AppUpdatePolicy.elfFailure(elf(type = 2)))
        assertEquals("核心载荷不是 AArch64", AppUpdatePolicy.elfFailure(elf(machine = 62)))
    }

    @Test
    fun productionArchiveMustContainOnlyArm64AndBothEnginePayloads() {
        val engines = listOf(
            "lib/arm64-v8a/libmagpie_exec.so",
            "lib/arm64-v8a/libbun_exec.so",
        )
        assertNull(AppUpdatePolicy.arm64PayloadFailure(engines))
        assertEquals("更新 APK 不是 ARM64 生产包",
            AppUpdatePolicy.arm64PayloadFailure(engines + "lib/x86_64/libmagpie_exec.so"))
        assertEquals("更新 APK 缺少核心载荷",
            AppUpdatePolicy.arm64PayloadFailure(engines.take(1)))
    }

    private fun elf(magic: Int = 0x7f, bits: Int = 2, endian: Int = 1,
                    type: Int = 3, machine: Int = 183): ByteArray =
        ByteArray(20).also { bytes ->
            bytes[0] = magic.toByte(); bytes[1] = 'E'.code.toByte()
            bytes[2] = 'L'.code.toByte(); bytes[3] = 'F'.code.toByte()
            bytes[4] = bits.toByte(); bytes[5] = endian.toByte()
            bytes[16] = type.toByte(); bytes[18] = machine.toByte()
        }
}

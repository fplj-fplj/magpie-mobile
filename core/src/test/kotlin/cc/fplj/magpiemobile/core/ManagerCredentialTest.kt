package cc.fplj.magpiemobile.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ManagerCredentialTest {
    private val value = "0123456789abcdef0123456789abcdef"

    @Test fun keepsAnOpaqueManagerValueAndEndpointPort() {
        val credential = ManagerCredential.fromKey(value)
        assertEquals(value, credential.managerHeaderValue())
        assertEquals(3430, credential.port)
        val custom = ManagerCredential.fromKey(value, 45123)
        assertEquals(45123, custom.port)
        assertEquals(value, custom.managerHeaderValue())
    }

    @Test fun invalidPortsAndValuesAreRejectedWithoutEchoingInput() {
        for (port in listOf(-1, 0, 65536)) {
            assertFailsWith<IllegalArgumentException> { ManagerCredential.fromKey(value, port) }
        }
        for (bad in listOf("short", value + "中文", value + ";evil", value + "\r\nheader")) {
            val error = assertFailsWith<IllegalArgumentException> { ManagerCredential.fromKey(bad) }
            assertTrue(!error.toString().contains(value))
        }
    }

    @Test fun managerAuthenticationHasNoBrowserCookieRepresentation() {
        val credential = ManagerCredential.fromKey(value)
        assertTrue(ManagerCredential::class.java.methods.none { it.name.contains("cookie", ignoreCase = true) })
        assertEquals(value, credential.managerHeaderValue())
    }

    @Test fun toStringNeverLeaksFullValue() {
        val credential = ManagerCredential.fromKey(value)
        assertTrue(credential.toString().contains("0123"))
        assertTrue(!credential.toString().contains(value))
        assertTrue(!mapOf("credential" to credential).toString().contains(value))
    }
}

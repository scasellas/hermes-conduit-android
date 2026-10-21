package app.hermesconduit.android

import app.hermesconduit.android.net.ConnectionUrlException
import app.hermesconduit.android.net.ConnectionUrlPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ConnectionUrlPolicyTest {
    @Test
    fun stripsTrailingSlashesAndKeepsPrefix() {
        assertEquals("https://example.com/hermes", ConnectionUrlPolicy.normalizedBaseUrl(" https://example.com/hermes/ "))
    }

    @Test
    fun allowsHttpOnlyForLocalAndTailscaleHosts() {
        listOf("http://localhost:9119", "http://192.168.1.4:9119", "http://10.0.0.2", "http://172.20.1.1", "http://100.100.1.1", "http://box.tail1234.ts.net")
            .forEach { ConnectionUrlPolicy.normalizedBaseUrl(it) }
        listOf("http://example.com", "http://100.64.evil.example", "http://172.32.0.1", "http://100.128.0.1").forEach {
            assertThrows(ConnectionUrlException::class.java) { ConnectionUrlPolicy.normalizedBaseUrl(it) }
        }
    }

    @Test
    fun rejectsUserInfoQueryAndOtherSchemes() {
        listOf("https://user:pw@example.com", "https://example.com?x=1", "https://example.com#a", "ftp://example.com", "example.com").forEach {
            assertThrows(ConnectionUrlException::class.java) { ConnectionUrlPolicy.normalizedBaseUrl(it) }
        }
    }

    @Test
    fun buildsWebSocketUrl() {
        assertEquals(
            "wss://example.com:8443/hermes/api/ws?ticket=a%2Fb%20c",
            ConnectionUrlPolicy.webSocketUrl("https://example.com:8443/hermes", "api/ws", mapOf("ticket" to "a/b c")),
        )
        assertEquals("ws://192.168.1.4:9119/api/ws", ConnectionUrlPolicy.webSocketUrl("http://192.168.1.4:9119", "/api/ws"))
    }
}

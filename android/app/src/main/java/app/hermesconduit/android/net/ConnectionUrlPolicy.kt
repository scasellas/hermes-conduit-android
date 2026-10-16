package app.hermesconduit.android.net

import java.net.URI

class ConnectionUrlException(message: String) : IllegalArgumentException(message)

/**
 * Mirrors the iOS `ConnectionURLPolicy`: HTTPS everywhere, plain HTTP only
 * for loopback, RFC1918 LAN and Tailscale hosts.
 */
object ConnectionUrlPolicy {
    const val INVALID_URL = "Enter a valid dashboard URL."
    const val INSECURE_TRANSPORT =
        "Remote dashboards must use HTTPS; HTTP is allowed only for local networks (localhost, private LAN, and Tailscale)."

    fun normalizedBaseUrl(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) throw ConnectionUrlException(INVALID_URL)
        val uri = try {
            URI(trimmed)
        } catch (_: Exception) {
            throw ConnectionUrlException(INVALID_URL)
        }
        val scheme = uri.scheme?.lowercase() ?: throw ConnectionUrlException(INVALID_URL)
        val host = uri.host?.trim().orEmpty()
        if (host.isEmpty() || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) {
            throw ConnectionUrlException(INVALID_URL)
        }
        if (scheme != "https" && scheme != "http") throw ConnectionUrlException(INVALID_URL)
        if (scheme == "http" && !isInsecureTransportAllowed(host)) {
            throw ConnectionUrlException(INSECURE_TRANSPORT)
        }
        var normalized = trimmed
        while (normalized.length > scheme.length + 3 && normalized.endsWith("/")) {
            normalized = normalized.dropLast(1)
        }
        return normalized
    }

    /** `ws(s)://host[/prefix]/api/ws` style URL for the given route. */
    fun webSocketUrl(baseUrl: String, path: String, query: Map<String, String> = emptyMap()): String {
        val uri = URI(normalizedBaseUrl(baseUrl))
        val wsScheme = if (uri.scheme.lowercase() == "https") "wss" else "ws"
        val joined = listOf(uri.rawPath.orEmpty().trim('/'), path.trim('/'))
            .filter { it.isNotEmpty() }
            .joinToString("/")
        val port = if (uri.port != -1) ":${uri.port}" else ""
        val queryString = if (query.isEmpty()) "" else "?" + query.entries.joinToString("&") {
            "${encode(it.key)}=${encode(it.value)}"
        }
        return "$wsScheme://${formatHost(uri.host)}$port/$joined$queryString"
    }

    private fun formatHost(host: String): String = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    fun isInsecureTransportAllowed(value: String): Boolean {
        val host = value.lowercase().trim('[', ']')
        return isLoopback(host) || isPrivateLan(host) || isTailscale(host)
    }

    private fun isLoopback(host: String) = host == "localhost" || host == "127.0.0.1" || host == "::1"

    private fun isTailscale(host: String): Boolean {
        if (host.endsWith(".ts.net")) return true
        // 100.64.0.0/10; require a well-formed IPv4 so 100.64.evil.example is refused.
        val octets = ipv4Octets(host) ?: return false
        return octets[0] == 100 && octets[1] in 64..127
    }

    private fun isPrivateLan(host: String): Boolean {
        val octets = ipv4Octets(host) ?: return false
        return when (octets[0]) {
            10 -> true
            172 -> octets[1] in 16..31
            192 -> octets[1] == 168
            else -> false
        }
    }

    private fun ipv4Octets(value: String): List<Int>? {
        val parts = value.split(".")
        if (parts.size != 4) return null
        val octets = parts.map { it.toIntOrNull() ?: return null }
        return if (octets.all { it in 0..255 }) octets else null
    }
}

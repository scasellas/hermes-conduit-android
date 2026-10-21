package app.hermesconduit.android.net

import app.hermesconduit.android.model.long
import app.hermesconduit.android.model.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

data class OAuthTokens(
    val accessToken: String,
    val refreshToken: String?,
    /** Epoch seconds. */
    val expiresAt: Long?,
    val provider: String?,
    val userId: String?,
)

/**
 * Hermes-brokered native OAuth (PKCE + loopback redirect), the port of the iOS
 * `NativeOAuth`: the system browser opens `/auth/native/authorize`, Hermes
 * redirects to `http://127.0.0.1:<port>/callback`, and the code is redeemed
 * at `/auth/native/token`.
 */
object NativeOAuth {
    private val random = SecureRandom()

    data class Pkce(val verifier: String, val challenge: String)

    private fun base64Url(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun randomUrlSafe(byteCount: Int = 32): String = ByteArray(byteCount).also(random::nextBytes).let(::base64Url)

    fun challenge(verifier: String): String = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))

    fun pkce(): Pkce = randomUrlSafe().let { Pkce(it, challenge(it)) }

    fun authorizeUrl(baseUrl: String, challenge: String, redirectUri: String, state: String, provider: String?): String {
        fun enc(v: String) = URLEncoder.encode(v, "UTF-8")
        val query = buildList {
            add("code_challenge=${enc(challenge)}")
            add("code_challenge_method=S256")
            add("redirect_uri=${enc(redirectUri)}")
            add("state=${enc(state)}")
            if (!provider.isNullOrEmpty()) add("provider=${enc(provider)}")
        }.joinToString("&")
        return "${ConnectionUrlPolicy.normalizedBaseUrl(baseUrl)}/auth/native/authorize?$query"
    }

    /** Validates the callback request target and returns the authorization code. */
    fun callbackCode(requestTarget: String, expectedState: String): String {
        val uri = runCatching { URI("http://127.0.0.1$requestTarget") }.getOrNull() ?: throw IOException("Malformed sign-in callback.")
        if (uri.path != "/callback") throw IOException("Malformed sign-in callback.")
        val values = mutableMapOf<String, String>()
        uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.forEach { pair ->
            val name = URLDecoder.decode(pair.substringBefore('='), "UTF-8")
            val value = URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
            if (values.put(name, value) != null) throw IOException("Malformed sign-in callback.")
        }
        if (values["state"] != expectedState) throw IOException("The sign-in response didn't match this request.")
        if (!values["error"].isNullOrEmpty()) throw IOException("Sign-in was cancelled or refused.")
        return values["code"]?.takeIf { it.isNotEmpty() } ?: throw IOException("Malformed sign-in callback.")
    }

    /** One-shot loopback listener on 127.0.0.1 for the redirect. */
    class LoopbackListener : AutoCloseable {
        private val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = socket.localPort
        val redirectUri: String get() = "http://127.0.0.1:$port/callback"

        /** Waits (up to [timeoutMs]) for the browser redirect; returns the request target. */
        suspend fun awaitRequestTarget(timeoutMs: Int = 5 * 60_000): String = withContext(Dispatchers.IO) {
            socket.soTimeout = timeoutMs
            while (true) {
                val client = try {
                    socket.accept()
                } catch (_: SocketTimeoutException) {
                    throw IOException("Sign-in timed out.")
                }
                client.use { c ->
                    c.soTimeout = 10_000
                    val line = c.getInputStream().bufferedReader().readLine().orEmpty()
                    val parts = line.split(' ')
                    val target = if (parts.size >= 2 && parts[0] == "GET") parts[1] else ""
                    val ok = target.startsWith("/callback")
                    val body = if (ok) "<html><body style=\"font-family:sans-serif;text-align:center;padding-top:20vh\"><h2>Signed in</h2><p>You can return to Conduit.</p></body></html>"
                    else "Not found"
                    val response = "HTTP/1.1 ${if (ok) "200 OK" else "404 Not Found"}\r\nContent-Type: text/html; charset=utf-8\r\n" +
                        "Content-Length: ${body.toByteArray().size}\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n$body"
                    c.getOutputStream().apply { write(response.toByteArray()); flush() }
                    if (ok) return@withContext target
                }
            }
            @Suppress("UNREACHABLE_CODE")
            ""
        }

        override fun close() = socket.close()
    }

    suspend fun exchange(api: DashboardApi, code: String, verifier: String): OAuthTokens =
        tokens(api.sendJson("POST", "auth/native/token", buildJsonObject {
            put("code", code)
            put("code_verifier", verifier)
        }))

    suspend fun refresh(api: DashboardApi, refreshToken: String, provider: String?): OAuthTokens {
        val previous = api.bearerToken
        api.bearerToken = null
        try {
            return tokens(api.sendJson("POST", "auth/native/refresh", buildJsonObject {
                put("refresh_token", refreshToken)
                if (provider != null) put("provider", provider)
            }))
        } finally {
            if (api.bearerToken == null) api.bearerToken = previous
        }
    }

    private fun tokens(result: kotlinx.serialization.json.JsonObject) = OAuthTokens(
        accessToken = result["access_token"].str?.takeIf { it.isNotEmpty() } ?: throw IOException("The dashboard returned an unreadable token."),
        refreshToken = result["refresh_token"].str,
        expiresAt = result["expires_at"].long,
        provider = result["provider"].str,
        userId = result["user_id"].str,
    )
}

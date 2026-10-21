package app.hermesconduit.android.net

import okhttp3.Request

/** Port of the iOS custom proxy headers: user headers that can never override transport headers. */
object CustomHeaders {
    val forbidden = setOf(
        "host", "cookie", "content-length", "content-type", "transfer-encoding", "connection", "upgrade",
        "keep-alive", "proxy-connection", "te", "trailer", "expect", "origin", "authorization",
        "cf-access-client-id", "cf-access-client-secret",
    )

    private val nameRegex = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")

    fun isValidName(name: String) = nameRegex.matches(name) && name.lowercase() !in forbidden

    fun isValidValue(value: String) = value.none { it == '\r' || it == '\n' || it == '\u0000' }

    fun apply(builder: Request.Builder, headers: Map<String, String>) {
        headers.forEach { (name, value) ->
            if (isValidName(name) && isValidValue(value)) builder.header(name, value)
        }
    }
}

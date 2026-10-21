package app.hermesconduit.android.net

import okhttp3.Request

/** Cloudflare Access service token, sent only over HTTPS/WSS. */
data class CloudflareAccess(val clientId: String, val clientSecret: String) {
    fun apply(builder: Request.Builder) {
        builder.header("CF-Access-Client-Id", clientId)
        builder.header("CF-Access-Client-Secret", clientSecret)
    }

    companion object {
        fun of(clientId: String?, clientSecret: String?): CloudflareAccess? {
            val id = clientId?.trim().orEmpty()
            val secret = clientSecret?.trim().orEmpty()
            return if (id.isEmpty() || secret.isEmpty()) null else CloudflareAccess(id, secret)
        }
    }
}

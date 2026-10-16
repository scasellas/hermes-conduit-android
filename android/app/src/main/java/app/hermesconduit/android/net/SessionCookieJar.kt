package app.hermesconduit.android.net

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * Holds the dashboard's session cookie. Only host-only cookies for the
 * dashboard host are kept, matching the iOS acceptance rules, and the jar is
 * persisted through [onChange] so a restart doesn't require signing in again.
 */
class SessionCookieJar(
    private val onChange: (String) -> Unit = {},
) : CookieJar {
    private val cookies = mutableMapOf<String, Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        var changed = false
        for (cookie in cookies) {
            if (!cookie.domain.equals(url.host, ignoreCase = true)) continue
            if (cookie.secure && !url.isHttps) continue
            if (cookie.name.startsWith("__Host-") && (!cookie.secure || !cookie.hostOnly || cookie.path != "/")) continue
            if (cookie.name.startsWith("__Secure-") && !cookie.secure) continue
            val key = "${cookie.name};${cookie.path}"
            if (cookie.expiresAt < System.currentTimeMillis()) {
                changed = this.cookies.remove(key) != null || changed
            } else {
                this.cookies[key] = cookie
                changed = true
            }
        }
        if (changed) onChange(serialize())
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        cookies.values.removeAll { it.expiresAt < now }
        return cookies.values.filter { it.matches(url) }
    }

    @Synchronized
    fun isEmpty(): Boolean = cookies.isEmpty()

    @Synchronized
    fun clear() {
        cookies.clear()
        onChange("")
    }

    /** One `Set-Cookie` header per line, re-parsed against the dashboard URL. */
    @Synchronized
    fun serialize(): String = cookies.values.joinToString("\n") { it.toString() }

    @Synchronized
    fun restore(url: HttpUrl, serialized: String?) {
        cookies.clear()
        serialized?.lineSequence()?.filter { it.isNotBlank() }?.forEach { line ->
            Cookie.parse(url, line)?.let { cookies["${it.name};${it.path}"] = it }
        }
    }
}

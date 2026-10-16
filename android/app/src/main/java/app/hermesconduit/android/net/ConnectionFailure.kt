package app.hermesconduit.android.net

import app.hermesconduit.android.core.L10n
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

/** Port of the iOS ConnectionFailure classification: a title and an explanation per failure. */
enum class ConnectionFailure(val titleKey: String, val detailKey: String) {
    INVALID_ADDRESS("Check the dashboard address", "That doesn’t look like a dashboard URL Conduit can use. Check it for typos — it should look like https://hermes.example."),
    INSECURE_TRANSPORT("Insecure dashboard address", "Remote dashboards must use HTTPS. Plain HTTP is only accepted for local addresses like localhost, private LAN IPs, and Tailscale."),
    HOST_NOT_FOUND("Dashboard not found", "Conduit could not find that host. Check the dashboard address and try again."),
    UNREACHABLE("Couldn’t reach Hermes", "Conduit could not connect to the dashboard at this address. Make sure the dashboard is running and that this device can reach it."),
    TIMED_OUT("Connection timed out", "The dashboard did not respond in time. Check the address, the network connection, and whether the Hermes dashboard is running."),
    TLS_UNTRUSTED("Secure connection failed", "Conduit reached the server, but Android does not trust its TLS certificate. If you use your own certificate authority, install its root certificate on this device."),
    TLS_BAD_DATE("Certificate date problem", "The dashboard certificate is expired or not yet valid. Check the certificate dates and this device’s date and time."),
    TLS_FAILURE("Secure connection failed", "Conduit could not establish a secure connection to the dashboard. The server’s TLS certificate may be misconfigured."),
    AUTH_REJECTED("Login failed", "Hermes rejected that username or password. Check your dashboard credentials and try again."),
    RATE_LIMITED("Too many login attempts", "Hermes temporarily blocked additional login attempts. Wait about a minute before trying again."),
    LOGIN_REQUIRED("Sign-in required", "Conduit needs you to sign in to the dashboard to reconnect."),
    CLOUDFLARE_REJECTED("Cloudflare rejected the service token", "Cloudflare Access did not accept the configured service token. Verify the Client ID / Secret and that the token is allowed by a Service Auth policy for this Access application, or turn off \"Use Cloudflare Access service token\" to sign in interactively."),
    TICKET_FAILURE("Could not start the session", "Signing in succeeded, but Conduit could not start a Hermes session. The dashboard may be busy, restarting, or it did not accept the new session — try again."),
    DASHBOARD_UNAVAILABLE("Dashboard unavailable", "The dashboard is reachable but reported a server error. It may be restarting or unhealthy — try again in a moment."),
    UNEXPECTED("Unexpected response", "The dashboard responded in a way Conduit didn’t expect. Make sure the address points at a Hermes dashboard."),
    UNKNOWN("Couldn’t connect", "Something went wrong while connecting to the dashboard. Try again.");

    val title: String get() = L10n.tr(titleKey)
    val detail: String get() = L10n.tr(detailKey)

    companion object {
        fun classify(error: Throwable): ConnectionFailure {
            var e: Throwable? = error
            while (e != null) {
                when (e) {
                    is ConnectionUrlException -> return if (e.message == ConnectionUrlPolicy.INSECURE_TRANSPORT) INSECURE_TRANSPORT else INVALID_ADDRESS
                    is UnknownHostException -> return HOST_NOT_FOUND
                    is SocketTimeoutException -> return TIMED_OUT
                    is ConnectException, is NoRouteToHostException -> return UNREACHABLE
                    is CertificateExpiredException, is CertificateNotYetValidException -> return TLS_BAD_DATE
                    is SSLHandshakeException -> return if (e.message?.contains("Trust anchor", true) == true || e.message?.contains("certification path", true) == true) TLS_UNTRUSTED else TLS_FAILURE
                    is SSLException -> return TLS_FAILURE
                    is AuthExpiredException -> return LOGIN_REQUIRED
                    is DashboardHttpException -> return when {
                        e.code == 429 -> RATE_LIMITED
                        e.code in 401..403 -> AUTH_REJECTED
                        e.code >= 500 -> DASHBOARD_UNAVAILABLE
                        else -> UNEXPECTED
                    }
                }
                if (e.message?.contains("Cloudflare Access rejected", true) == true) return CLOUDFLARE_REJECTED
                if (e.message?.contains("doesn't look like a Hermes dashboard", true) == true) return UNEXPECTED
                e = e.cause
            }
            return UNKNOWN
        }
    }
}

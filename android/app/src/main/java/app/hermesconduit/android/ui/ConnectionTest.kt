package app.hermesconduit.android.ui

import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.data.SavedConnection
import app.hermesconduit.android.net.CloudflareAccess
import app.hermesconduit.android.net.ConnectionFailure
import app.hermesconduit.android.net.DashboardApi
import app.hermesconduit.android.net.GatewayClient
import app.hermesconduit.android.net.SessionCookieJar
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient
import okhttp3.Request

/** The iOS ConnectionSetupTest stages: server reachable, Hermes dashboard found, authentication. */
data class ConnectionTestResult(
    val stages: List<Pair<String, String>>, // label to status: passed | failed | waiting | browser | credentials
    val failure: ConnectionFailure? = null,
    val message: String? = null,
)

object ConnectionTest {
    suspend fun run(candidate: SavedConnection, http: OkHttpClient, scope: CoroutineScope, update: (ConnectionTestResult) -> Unit): ConnectionTestResult {
        val labels = listOf(L10n.tr("Dashboard reachable"), L10n.tr("Hermes dashboard found"), L10n.tr("Authentication"))
        val status = MutableList(3) { "waiting" }
        fun snapshot(failure: ConnectionFailure? = null, message: String? = null) = ConnectionTestResult(labels.zip(status), failure, message).also(update)
        val api = try {
            DashboardApi(candidate.baseUrl, SessionCookieJar(), CloudflareAccess.of(candidate.cfClientId, candidate.cfClientSecret), http, candidate.customHeaders)
        } catch (e: Exception) {
            status[0] = "failed"
            return snapshot(ConnectionFailure.classify(e))
        }
        // 1. Anything answers at the address.
        status[0] = "checking"; snapshot()
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                api.http.newCall(Request.Builder().url(api.url("api/status")).get().build()).execute().close()
            }
            status[0] = "passed"
        } catch (e: Exception) {
            status[0] = "failed"
            return snapshot(ConnectionFailure.classify(e))
        }
        // 2. It's a Hermes dashboard.
        status[1] = "checking"; snapshot()
        val providers = try {
            api.authProviders().also { status[1] = "passed" }
        } catch (e: Exception) {
            status[1] = "failed"
            return snapshot(ConnectionFailure.classify(e).let { if (it == ConnectionFailure.UNKNOWN) ConnectionFailure.UNEXPECTED else it })
        }
        // 3. Sign-in and a socket ticket.
        status[2] = "checking"; snapshot()
        if (!providers.supportsPassword) {
            status[2] = "browser"
            return snapshot(message = L10n.tr("This dashboard uses browser-based sign-in. Conduit will open the sign-in page after you return to the login screen."))
        }
        if (candidate.username.isBlank() || candidate.password.isEmpty()) {
            status[2] = "credentials"
            return snapshot(message = L10n.tr("This dashboard uses username and password sign-in. Enter your credentials to finish testing the connection."))
        }
        return try {
            api.passwordLogin(candidate.username, candidate.password)
            val ticket = runCatching { api.webSocketTicket() }.getOrElse { throw IllegalStateException("ticket", it) }
            val client = GatewayClient(api.http, scope, "default", CloudflareAccess.of(candidate.cfClientId, candidate.cfClientSecret), candidate.customHeaders)
            try {
                client.connect(api.baseUrl, ticket)
            } finally {
                client.close()
            }
            status[2] = "passed"
            snapshot(message = L10n.tr("This connection is ready to use."))
        } catch (e: Exception) {
            status[2] = "failed"
            snapshot(if (e is IllegalStateException && e.message == "ticket") ConnectionFailure.TICKET_FAILURE else ConnectionFailure.classify(e))
        }
    }
}

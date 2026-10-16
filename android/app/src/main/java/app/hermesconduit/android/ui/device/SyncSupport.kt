package app.hermesconduit.android.ui.device

import app.hermesconduit.android.model.SyncPause
import app.hermesconduit.android.net.AuthExpiredException
import app.hermesconduit.android.net.DashboardHttpException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** One dashboard request: method, path (under the dashboard), query, body. Throws [DashboardHttpException] / [AuthExpiredException]. */
typealias SyncRequest = suspend (method: String, path: String, query: Map<String, String?>, body: JsonElement?) -> JsonObject

/** Stops a pass when its time is up; the next pass picks up where it left off. */
class DeadlineReached : Exception("The pass ran out of time.")

/** Thrown by a request when the dashboard in use isn't the one sharing goes to. */
class NotReadyException : Exception(app.hermesconduit.android.core.L10n.tr("Connect to your Hermes dashboard first."))

sealed class SyncOutcome {
    data object Synced : SyncOutcome()
    /** Stopped at the deadline or for later; the next pass goes on. */
    data object Unfinished : SyncOutcome()
    data class Paused(val pause: SyncPause) : SyncOutcome()
    /** Nothing to do: sharing is off. */
    data object Off : SyncOutcome()
    data class Failed(val message: String) : SyncOutcome()
}

object SyncSupport {
    const val PLUGIN_BASE = "api/plugins/conduit_push"

    /** One confined thread per engine: state is only touched there. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun serialDispatcher(): CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)

    fun describe(error: Throwable): String = when (error) {
        is DashboardHttpException -> error.message ?: "HTTP ${error.code}"
        is AuthExpiredException -> app.hermesconduit.android.core.L10n.tr("Your session expired. Sign in again.")
        else -> error.message?.takeIf { it.isNotBlank() } ?: error.toString()
    }

    fun httpCode(error: Throwable): Int? = (error as? DashboardHttpException)?.code
    fun httpDetail(error: Throwable): String = (error as? DashboardHttpException)?.detail.orEmpty()

    /** Profile scoping for plugin routes, as iOS `DashboardPath.withProfile`. */
    fun profileQuery(profile: String): Map<String, String?> = mapOf("profile" to profile.takeIf { it.isNotEmpty() && it != "default" })
}

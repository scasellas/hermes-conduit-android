package app.hermesconduit.wearwire

import app.hermesconduit.android.core.live.GeminiLiveAvailability
import app.hermesconduit.android.core.live.GeminiLiveToken
import app.hermesconduit.android.core.live.GeminiLiveTokenProviding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/**
 * A watch call's tokens (port of the iOS `WatchDirectTokens`): the one that
 * came with the session, then a new one from the phone for each connection,
 * as on the phone. When the phone can't be reached, a resumption reuses the
 * token in hand while it lasts (Gemini accepts it for resumptions until it
 * expires); a fresh session, or one whose token is about to expire, asks
 * Hermes for one through the call's grant, once per rejoin.
 */
class WatchDirectTokens(first: GeminiLiveToken, private val now: () -> Long = System::currentTimeMillis) : GeminiLiveTokenProviding {
    enum class Source(val raw: String) { FIRST("first"), PHONE("phone"), REUSED("reused"), RELAY("relay"), FAILED("failed") }

    var fetch: (suspend () -> GeminiLiveToken)? = null
    /** Hermes' token through the call's grant; null, or throwing, when the grant can't mint one. */
    var relayFetch: (suspend () -> GeminiLiveToken)? = null
    var canResume: () -> Boolean = { false }
    var onIssued: ((Source, String?) -> Unit)? = null
    private var first: GeminiLiveToken? = first
    private var latest: GeminiLiveToken = first
    /** One try through the relay per rejoin, or per connection lost. */
    private var relayTried = false
    /** A token is on its way through the relay. */
    var relayInFlight = false
        private set

    /** A rejoin may ask the relay once more. */
    fun allowRelayTry() {
        relayTried = false
    }

    override suspend fun availability(): GeminiLiveAvailability = GeminiLiveAvailability.Available(latest.model)

    override suspend fun freshToken(): GeminiLiveToken {
        first?.let {
            first = null
            onIssued?.invoke(Source.FIRST, null)
            return it
        }
        try {
            val fetch = fetch ?: throw IllegalStateException("ended")
            val token = fetch()
            latest = token
            onIssued?.invoke(Source.PHONE, null)
            return token
        } catch (e: CancellationException) {
            throw e
        } catch (error: Exception) {
            val lasts = latest.expiresAtMs?.let { it - now() > REUSE_MARGIN_MS } ?: true
            if (canResume() && lasts) {
                onIssued?.invoke(Source.REUSED, error.message)
                return latest
            }
            // A stopped session mustn't spend one of Hermes' tokens.
            if (!currentCoroutineContext().isActive) throw CancellationException("stopped")
            val relayFetch = relayFetch
            if (relayFetch == null || relayTried) {
                onIssued?.invoke(Source.FAILED, error.message)
                throw error
            }
            relayTried = true
            relayInFlight = true
            try {
                val token = relayFetch()
                latest = token
                onIssued?.invoke(Source.RELAY, error.message)
                return token
            } catch (e: CancellationException) {
                throw e
            } catch (relayError: Exception) {
                onIssued?.invoke(Source.FAILED, "${error.message} Relay: ${relayError.message}")
                throw relayError
            } finally {
                relayInFlight = false
            }
        }
    }

    companion object {
        /** A reused token needs this long left, so it doesn't expire mid-setup. */
        const val REUSE_MARGIN_MS = 60_000L
    }
}

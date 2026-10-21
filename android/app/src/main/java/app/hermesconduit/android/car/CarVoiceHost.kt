package app.hermesconduit.android.car

import app.hermesconduit.android.core.HermesConnection
import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.model.StreamEvent
import app.hermesconduit.android.model.VoiceSessionTagKind
import app.hermesconduit.android.model.obj
import app.hermesconduit.android.model.str
import app.hermesconduit.android.net.GatewayClient
import app.hermesconduit.android.net.RpcException
import app.hermesconduit.android.ui.voice.VoiceConversationHost
import app.hermesconduit.android.ui.voice.VoiceHub
import app.hermesconduit.android.ui.voice.VoiceSteerOutcome
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The voice host for a conversation the car runs, with no chat screen behind
 * it: spoken turns go straight to Hermes as voice turns (`prompt.submit` with
 * surface "voice" and `voice_turn`), into the chat picked from the car's list
 * or, with none picked, a new chat filed under Voice.
 */
class CarVoiceHost(
    private val hub: VoiceHub,
    chat: CarChatRow?,
    private val onEnd: () -> Unit,
) : VoiceConversationHost {
    private val hermes: HermesConnection = hub.hermes
    private val chatIds: Set<String> = chat?.let { setOfNotNull(it.sessionId, it.runtimeId) + it.alternateIds }.orEmpty()
    @Volatile private var storedId: String? = chat?.sessionId
    @Volatile var runtimeId: String? = null
        private set
    @Volatile private var isTurnRunning = false
    private var refusesVoiceTurn = false

    /** Follows the chat's turns for steering (a turn is running between its start and its end). */
    fun observe(event: StreamEvent) {
        if (!ownsSession(event.sessionId)) return
        when (event) {
            is StreamEvent.MessageStart -> isTurnRunning = true
            is StreamEvent.MessageComplete, is StreamEvent.Error, is StreamEvent.Interrupted -> isTurnRunning = false
            else -> Unit
        }
    }

    override suspend fun submit(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        return try {
            val runtime = runtime(trimmed)
            fun params(marksVoiceTurn: Boolean) = buildJsonObject {
                put("session_id", runtime)
                put("text", trimmed)
                put("surface", SURFACE)
                if (marksVoiceTurn) put("voice_turn", true)
            }
            val timeout = GatewayClient.timeoutFor("prompt.submit")
            try {
                hermes.rpc("prompt.submit", params(!refusesVoiceTurn), timeout)
            } catch (e: RpcException) {
                // A Hermes from before `voice_turn` refuses the key: send the turn without it.
                if (refusesVoiceTurn || e.code != 4000 || "voice_turn" !in e.message.orEmpty()) throw e
                refusesVoiceTurn = true
                hermes.rpc("prompt.submit", params(false), timeout)
            }
            isTurnRunning = true
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    /** The chat's live session: the picked chat resumed, or a new chat created on the first turn. */
    private suspend fun runtime(firstWords: String): String {
        runtimeId?.let { return it }
        val stored = storedId
        val runtime = if (stored != null) {
            val resumed = hermes.rpc("session.resume", buildJsonObject {
                put("session_id", stored)
                put("cols", 96)
                put("source", "desktop")
                put("omit_messages", true)
            }) as? JsonObject
            resumed?.get("session_id").str?.takeIf { it.isNotEmpty() } ?: stored
        } else {
            val created = hermes.rpc("session.create", buildJsonObject {
                put("cols", 96)
                put("source", "desktop")
            }).obj
            val id = created?.get("session_id").str?.takeIf { it.isNotEmpty() } ?: error(L10n.tr("The gateway didn't create a session."))
            val newStored = created?.get("stored_session_id").str ?: id
            storedId = newStored
            runCatching {
                hermes.rpc("session.title", buildJsonObject {
                    put("session_id", id)
                    put("title", firstWords.take(60))
                })
            }
            // A chat started from the car is a voice chat, as one started from the voice screen is.
            hub.tagSessions(listOf(newStored, id), VoiceSessionTagKind.CLASSIC)
            id
        }
        runtimeId = runtime
        return runtime
    }

    override suspend fun interrupt(): Boolean {
        val runtime = runtimeId ?: return true
        return runCatching { hermes.rpc("session.interrupt", buildJsonObject { put("session_id", runtime) }) }.isSuccess
    }

    override suspend fun steer(text: String): VoiceSteerOutcome {
        val runtime = runtimeId ?: return VoiceSteerOutcome.NO_RUNNING_TURN
        if (!isTurnRunning) return VoiceSteerOutcome.NO_RUNNING_TURN
        return try {
            val result = hermes.rpc("session.steer", buildJsonObject { put("session_id", runtime); put("text", text) })
            if ((result as? JsonObject)?.get("status").str == "rejected") VoiceSteerOutcome.FAILED else VoiceSteerOutcome.STEERED
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            VoiceSteerOutcome.FAILED
        }
    }

    override fun ownsSession(sessionId: String): Boolean =
        sessionId.isNotEmpty() && (sessionId == runtimeId || sessionId == storedId || sessionId in chatIds)

    override fun endConversation() {
        hub.closeConversation()
        onEnd()
    }

    companion object {
        const val SURFACE = "voice"
    }
}

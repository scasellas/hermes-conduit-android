package app.hermesconduit.android.watch

import android.content.Context
import app.hermesconduit.android.appSettings
import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.core.LinkState
import app.hermesconduit.android.hermes
import app.hermesconduit.android.model.MessageNormalizer
import app.hermesconduit.android.model.SessionSummary
import app.hermesconduit.android.model.obj
import app.hermesconduit.android.model.str
import app.hermesconduit.android.net.GatewayClient
import app.hermesconduit.android.net.RpcException
import app.hermesconduit.wearwire.PhoneContext
import app.hermesconduit.wearwire.ReplyEvent
import app.hermesconduit.wearwire.WearApproval
import app.hermesconduit.wearwire.WearChat
import app.hermesconduit.wearwire.WearMessage
import app.hermesconduit.wearwire.WearWire
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

/**
 * The phone end of a Wear OS watch (iOS WatchVoiceLink + the bridge half of
 * WatchDirectBroker). The watch hears the user; each turn comes here, runs
 * through Hermes on this phone's connection (`prompt.submit` with surface
 * "voice" and `voice_turn`, so Hermes answers on its voice model when one is
 * set) and the reply streams back for the watch to speak. It also lists
 * recent chats and pending approvals for the watch and answers them.
 */
class WatchBridge private constructor(context: Context) {
    private val app = context.applicationContext
    private val hermes = app.hermes
    private val settings = app.appSettings
    val log = WatchPhoneCallLog.get(app)
    private val messages by lazy { Wearable.getMessageClient(app) }

    /** A watch call's Hermes chat on this phone. */
    private class Call(var runtimeId: String? = null, var storedId: String? = null, var turn: Job? = null)
    private val calls = ConcurrentHashMap<String, Call>()

    /** One ordered outbox per watch node, so a reply's events arrive in order. */
    private val outboxes = ConcurrentHashMap<String, Channel<WearMessage>>()

    fun handle(nodeId: String, path: String, data: ByteArray) {
        val message = WearWire.decode(path, data)
        if (message == null) {
            log.note("watchMessageUnread", mapOf("path" to path))
            return
        }
        hermes.scope.launch {
            publishContext()
            when (message) {
                is WearMessage.Turn -> startTurn(nodeId, message)
                is WearMessage.Stop -> stop(message)
                WearMessage.ChatsRequest -> send(nodeId, chats())
                WearMessage.ApprovalsRequest -> send(nodeId, approvals())
                is WearMessage.ApprovalRespond -> send(nodeId, respond(message))
                else -> Unit
            }
        }
    }

    /** A queued call log line from the watch (DataItem). */
    fun watchLogLine(line: String) = log.watchLine(line)

    // region Context

    fun phoneContext() = PhoneContext(
        signedIn = hermes.isSignedIn,
        connected = hermes.link.value == LinkState.CONNECTED,
        voiceApprovals = settings.getBool(WatchSettings.VOICE_APPROVALS, false),
        profile = hermes.profile.value,
        updatedAtMs = System.currentTimeMillis(),
    )

    /** The watch reads this DataItem whenever it runs next (iOS application context). */
    suspend fun publishContext() {
        runCatching {
            val request = PutDataMapRequest.create(WearWire.CONTEXT).apply { dataMap.putString(WearWire.DATA_KEY, phoneContext().encoded()) }
            Wearable.getDataClient(app).putDataItem(request.asPutDataRequest().setUrgent()).await()
        }
    }

    // endregion

    // region Calls

    private suspend fun ensureConnected(): Boolean {
        if (!hermes.isSignedIn) return false
        if (hermes.gateway == null || hermes.link.value == LinkState.OFFLINE) hermes.reconnectNow()
        return withTimeoutOrNull(12_000) { hermes.link.first { it == LinkState.CONNECTED } } != null &&
            hermes.gateway?.state?.value == GatewayClient.State.CONNECTED
    }

    private fun startTurn(nodeId: String, turn: WearMessage.Turn) {
        val call = calls.getOrPut(turn.callId) { Call() }
        call.turn?.cancel()
        call.turn = hermes.scope.launch { runTurn(nodeId, call, turn) }
    }

    private suspend fun runTurn(nodeId: String, call: Call, turn: WearMessage.Turn) {
        val started = System.currentTimeMillis()
        fun reply(event: ReplyEvent) = send(nodeId, WearMessage.Reply(turn.callId, turn.turnId, event))
        log.note("bridgeTurn", mapOf("call" to turn.callId, "turn" to turn.turnId, "chars" to turn.text.length))
        if (!ensureConnected()) {
            log.note("bridgeTurnFailed", mapOf("call" to turn.callId, "reason" to "notConnected"))
            reply(ReplyEvent.Failed(if (hermes.isSignedIn) L10n.tr("Conduit on your phone can't reach Hermes right now.") else L10n.tr("Sign in to Hermes in Conduit on your phone.")))
            return
        }
        try {
            val runtime = call.runtimeId ?: openChat(turn.sessionId).also { (runtime, stored) ->
                call.runtimeId = runtime
                call.storedId = stored
                reply(ReplyEvent.Started(stored))
            }.first
            val reducer = WatchTurnReducer(runtime) { L10n.tr("Hermes") }
            val done = kotlinx.coroutines.CompletableDeferred<Unit>()
            var pending: ReplyEvent.Text? = null
            var flusher: Job? = null
            var firstText = true
            val collector = hermes.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                hermes.events.collect { event ->
                    val out = reducer.on(event) ?: return@collect
                    when (out) {
                        is ReplyEvent.Text -> {
                            if (firstText) {
                                firstText = false
                                log.note("bridgeFirstText", mapOf("call" to turn.callId, "ms" to System.currentTimeMillis() - started))
                            }
                            // Coalesced: the latest text every 150 ms is enough to speak from.
                            pending = out
                            if (flusher?.isActive != true) flusher = hermes.scope.launch {
                                delay(150)
                                pending?.let { reply(it) }
                                pending = null
                            }
                        }
                        is ReplyEvent.Done, is ReplyEvent.Failed -> {
                            flusher?.cancel()
                            pending = null
                            reply(out)
                            done.complete(Unit)
                        }
                        else -> reply(out)
                    }
                }
            }
            try {
                submit(runtime, turn.text)
                if (withTimeoutOrNull(TURN_TIMEOUT_MS) { done.await() } == null) {
                    reply(ReplyEvent.Failed(L10n.tr("Hermes took too long to answer.")))
                }
            } finally {
                collector.cancel()
                flusher?.cancel()
            }
            log.note("bridgeTurnDone", mapOf("call" to turn.callId, "turn" to turn.turnId, "ms" to System.currentTimeMillis() - started, "replyChars" to reducer.reply.length))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            log.note("bridgeTurnFailed", mapOf("call" to turn.callId, "reason" to (e.javaClass.simpleName)))
            reply(ReplyEvent.Failed(e.message ?: L10n.tr("Couldn't send.")))
        }
    }

    /** The call's chat: a new one, or [sessionId] resumed. Returns (runtime id, stored id). */
    private suspend fun openChat(sessionId: String?): Pair<String, String> {
        if (sessionId != null) {
            val resumed = hermes.rpc("session.resume", buildJsonObject {
                put("session_id", sessionId); put("cols", 96); put("source", "desktop"); put("omit_messages", true)
            }).obj
            val runtime = resumed?.get("session_id").str ?: sessionId
            return runtime to (resumed?.get("stored_session_id").str ?: sessionId)
        }
        val created = hermes.rpc("session.create", buildJsonObject { put("cols", 96); put("source", "desktop") }).obj
        val runtime = created?.get("session_id").str ?: error(L10n.tr("The gateway didn't create a session."))
        return runtime to (created?.get("stored_session_id").str ?: runtime)
    }

    private suspend fun submit(runtime: String, text: String) {
        fun params(voiceTurn: Boolean) = buildJsonObject {
            put("session_id", runtime); put("text", text); put("surface", "voice")
            if (voiceTurn) put("voice_turn", true)
        }
        try {
            hermes.rpc("prompt.submit", params(true), GatewayClient.timeoutFor("prompt.submit"))
        } catch (e: RpcException) {
            // An older gateway refuses the unknown key: send the turn without it.
            if (e.message?.contains("voice_turn") != true) throw e
            hermes.rpc("prompt.submit", params(false), GatewayClient.timeoutFor("prompt.submit"))
        }
    }

    private suspend fun stop(stop: WearMessage.Stop) {
        val call = (if (stop.end) calls.remove(stop.callId) else calls[stop.callId]) ?: return
        val running = call.turn?.isActive == true
        log.note(if (stop.end) "bridgeCallEnd" else "bridgeInterrupt", mapOf("call" to stop.callId, "running" to running))
        val runtime = call.runtimeId ?: return
        if (running) runCatching { hermes.rpc("session.interrupt", buildJsonObject { put("session_id", runtime) }) }
        if (stop.end) call.turn?.cancel()
    }

    // endregion

    // region Chats and approvals

    private suspend fun recentSessions(): List<SessionSummary> =
        hermes.rest { it.sessions(hermes.profile.value) }.sessions
            .filter { !it.archived && it.parentId == null }
            .sortedByDescending { it.lastActiveMs ?: 0 }

    private suspend fun chats(): WearMessage.Chats = try {
        if (!hermes.isSignedIn) WearMessage.Chats(emptyList(), L10n.tr("Sign in to Hermes in Conduit on your phone."))
        else WearMessage.Chats(recentSessions().take(MAX_CHATS).map { WearChat(it.id, it.title, it.preview?.take(160), it.lastActiveMs) })
    } catch (e: Exception) {
        WearMessage.Chats(emptyList(), e.message ?: L10n.tr("Couldn't load chats."))
    }

    /** Every approval waiting on Hermes: the `session.active_list` sweep the notifier uses. */
    private suspend fun approvals(): WearMessage.Approvals {
        if (!ensureConnected()) return WearMessage.Approvals(emptyList(), L10n.tr("Conduit on your phone can't reach Hermes right now."))
        return try {
            val rows = (hermes.rpc("session.active_list", buildJsonObject {}).obj?.get("sessions") as? JsonArray)?.mapNotNull { it.obj }.orEmpty()
            val waiting = rows.filter { it["status"].str == "waiting" }.mapNotNull { it["id"].str ?: it["session_key"].str }
            val titles = if (waiting.isEmpty()) emptyList() else runCatching { recentSessions() }.getOrDefault(emptyList())
            val out = mutableListOf<WearApproval>()
            for (id in waiting) {
                val pending = runCatching { hermes.rpc("approval.pending", buildJsonObject { put("session_id", id) }).obj }.getOrNull()
                val title = titles.firstOrNull { it.matches(id) }?.title ?: L10n.tr("Hermes")
                (pending?.get("approvals") as? JsonArray)?.mapNotNull { it.obj }?.forEach { raw ->
                    MessageNormalizer.approvalRequest(raw, id)?.let { out += WatchTurnReducer.approval(it, title) }
                }
            }
            WearMessage.Approvals(out.distinctBy { it.key })
        } catch (e: Exception) {
            WearMessage.Approvals(emptyList(), e.message ?: L10n.tr("Couldn't load approvals."))
        }
    }

    /** Approve once or Deny from the watch; never for the session or always. */
    private suspend fun respond(answer: WearMessage.ApprovalRespond): WearMessage.ApprovalResult {
        log.note("watchApproval", mapOf("choice" to answer.choice))
        if (!ensureConnected()) return WearMessage.ApprovalResult(answer.sessionId, answer.requestId, false, L10n.tr("Conduit on your phone can't reach Hermes right now."))
        return try {
            hermes.rpc("approval.respond", buildJsonObject {
                put("choice", answer.choice)
                put("session_id", answer.sessionId)
                if (answer.requestId.isNotEmpty()) put("request_id", answer.requestId)
            })
            WearMessage.ApprovalResult(answer.sessionId, answer.requestId, true)
        } catch (e: Exception) {
            WearMessage.ApprovalResult(answer.sessionId, answer.requestId, false, e.message ?: L10n.tr("The approval didn't go through."))
        }
    }

    // endregion

    private fun send(nodeId: String, message: WearMessage) {
        val box = outboxes.getOrPut(nodeId) {
            Channel<WearMessage>(Channel.UNLIMITED).also { channel ->
                hermes.scope.launch {
                    for (next in channel) {
                        val (path, bytes) = WearWire.encode(next)
                        runCatching { messages.sendMessage(nodeId, path, bytes).await() }
                            .onFailure { log.note("watchSendFailed", mapOf("path" to path)) }
                    }
                }
            }
        }
        box.trySend(message)
    }

    companion object {
        const val MAX_CHATS = 12
        const val TURN_TIMEOUT_MS = 180_000L
        @Volatile private var shared: WatchBridge? = null
        fun get(context: Context): WatchBridge = shared ?: synchronized(this) { shared ?: WatchBridge(context).also { shared = it } }
    }
}

/** Phone-side Watch preferences (iOS WatchJobSettings keys). */
object WatchSettings {
    /** Off by default: text in a web page or a job's output could talk the model into approving. Voice only ever approves once. */
    const val VOICE_APPROVALS = "watchDirect.voiceApprovals"
}

package app.hermesconduit.android.notify

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import app.hermesconduit.android.appSettings
import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.hermes
import app.hermesconduit.android.model.ApprovalRequest
import app.hermesconduit.android.model.ContentSegmenter
import app.hermesconduit.android.model.MessageNormalizer
import app.hermesconduit.android.model.SessionSummary
import app.hermesconduit.android.model.StreamEvent
import app.hermesconduit.android.model.obj
import app.hermesconduit.android.model.str
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Turns gateway activity into local notifications — Android's counterpart of
 * the relay pushes (approval needed, input needed, response ready, turn
 * failed, background task finished) and of the Live Activity (an ongoing
 * progress notification while a turn runs).
 *
 * Events cover the chats this device has open on the socket; a periodic
 * `session.active_list` + `approval.pending` sweep covers the others.
 */
class NotificationMonitor(private val context: Context) {
    private val hermes = context.hermes
    private val settings = context.appSettings
    private var jobs: List<Job> = emptyList()

    private data class Turn(val startedMs: Long, var text: StringBuilder = StringBuilder(), var tool: String? = null, var tools: Int = 0)
    private val turns = mutableMapOf<String, Turn>()
    private val lastStatus = mutableMapOf<String, String>()
    private val notifiedApprovals = mutableSetOf<String>()
    private var titles: Map<String, SessionSummary> = emptyMap()

    private fun pref(key: String) = NotificationPrefs.get(settings, key)
    private val enabled get() = settings.getBool(NotificationPrefs.ENABLED, false) && ConduitNotifications.canPost(context)

    fun start() {
        if (jobs.isNotEmpty()) return
        ConduitNotifications.ensureChannels(context)
        // Calls from Hermes ride the same socket (Android has no VoIP push relay).
        app.hermesconduit.android.calls.HermesCalls.get(context).start()
        jobs = listOf(
            hermes.scope.launch { hermes.events.collect(::onEvent) },
            hermes.scope.launch {
                while (isActive) {
                    delay(60_000)
                    if (enabled && !ForegroundState.visible) runCatching { sweep() }
                }
            },
        )
    }

    private fun title(sessionId: String): String =
        titles.values.firstOrNull { it.matches(sessionId) }?.title ?: L10n.tr("Hermes")

    private fun summary(sessionId: String) = titles.values.firstOrNull { it.matches(sessionId) }

    private suspend fun refreshTitles() {
        runCatching { hermes.rest { it.sessions(hermes.profile.value) } }.onSuccess { page -> titles = page.sessions.associateBy { it.id } }
    }

    private fun shouldNotify(sessionId: String) = enabled && !ForegroundState.isShowing(sessionId)

    private fun sourceAllowed(sessionId: String): Boolean {
        val kind = summary(sessionId)?.sourceKind ?: "chat"
        return kind == "chat" || settings.getBool(NotificationPrefs.replySource(kind), true)
    }

    private fun onEvent(event: StreamEvent) {
        val sid = event.sessionId
        when (event) {
            is StreamEvent.MessageStart -> { turns.getOrPut(sid) { Turn(System.currentTimeMillis()) }; progress(sid) }
            is StreamEvent.MessageDelta -> { turns.getOrPut(sid) { Turn(System.currentTimeMillis()) }.text.append(event.text) }
            is StreamEvent.MessageComplete -> event.content?.let { c -> turns[sid]?.let { it.text = StringBuilder(c) } }
            is StreamEvent.ToolStart -> turns.getOrPut(sid) { Turn(System.currentTimeMillis()) }.let { it.tool = event.name; it.tools++; progress(sid) }
            is StreamEvent.Approval -> if (pref(NotificationPrefs.APPROVAL) && shouldNotify(event.request.sessionId)) approval(event.request)
            is StreamEvent.Clarify -> if (pref(NotificationPrefs.INPUT) && shouldNotify(sid)) attention(sid, L10n.tr("Hermes needs your answer"), event.request.questions.firstOrNull()?.question)
            is StreamEvent.InputPromptRequest -> if (pref(NotificationPrefs.INPUT) && shouldNotify(sid)) attention(sid, L10n.tr("Input needed"), L10n.tr("Hermes needs your answer"))
            is StreamEvent.RequestCancelled -> if (event.method == "approval") ConduitNotifications.cancel(context, ConduitNotifications.idFor("approval", event.requestId))
            is StreamEvent.Error -> {
                clearProgress(sid)
                turns.remove(sid)
                if (pref(NotificationPrefs.FAILED) && shouldNotify(sid)) completion(sid, L10n.tr("Turn failed"), event.message)
            }
            is StreamEvent.Interrupted -> { clearProgress(sid); turns.remove(sid) }
            is StreamEvent.Busy -> if (!event.busy) {
                clearProgress(sid)
                val turn = turns.remove(sid)
                if (turn != null && pref(NotificationPrefs.RESPONSE) && shouldNotify(sid) && sourceAllowed(sid)) {
                    val text = ContentSegmenter.removingTags(turn.text.toString()).trim()
                    completion(sid, title(sid), if (pref(NotificationPrefs.PREVIEWS)) text.take(400).ifEmpty { L10n.tr("Response ready") } else L10n.tr("Response ready"))
                }
            }
            is StreamEvent.Subagent -> if (event.type.contains("complete") || event.type.contains("finish")) {
                if (pref(NotificationPrefs.BACKGROUND_TASK) && shouldNotify(sid)) {
                    completion(sid, L10n.tr("Background task finished"), event.payload["goal"].str ?: event.payload["summary"].str)
                }
            }
            else -> Unit
        }
    }

    /** Sessions this socket isn't attached to: their status edges and pending approvals. */
    private suspend fun sweep() {
        val result = hermes.rpc("session.active_list", buildJsonObject {}).obj ?: return
        if (titles.isEmpty()) refreshTitles()
        val rows = (result["sessions"] as? JsonArray)?.mapNotNull { it.obj }.orEmpty()
        val seen = mutableSetOf<String>()
        for (row in rows) {
            val id = row["id"].str ?: row["session_key"].str ?: continue
            val status = row["status"].str ?: continue
            seen += id
            val before = lastStatus.put(id, status)
            if ((before == "working" || before == "waiting") && status == "idle") app.hermesconduit.android.calls.HermesCalls.get(context).sessionIdle(id)
            if (id in turns) continue // the event path handles attached chats
            if (status == "waiting" && pref(NotificationPrefs.APPROVAL) && shouldNotify(id)) {
                val pending = runCatching { hermes.rpc("approval.pending", buildJsonObject { put("session_id", id) }).obj }.getOrNull()
                (pending?.get("approvals") as? JsonArray)?.firstOrNull()?.obj?.let { MessageNormalizer.approvalRequest(it, id) }?.let(::approval)
            }
            if ((before == "working" || before == "waiting") && status == "idle" && pref(NotificationPrefs.RESPONSE) && shouldNotify(id) && sourceAllowed(id)) {
                refreshTitles()
                completion(id, title(id), L10n.tr("Response ready"))
            }
        }
        // Rows that dropped off the active list finished too.
        (lastStatus.keys - seen).toList().forEach { id ->
            val before = lastStatus.remove(id)
            if (before == "working" || before == "waiting") app.hermesconduit.android.calls.HermesCalls.get(context).sessionIdle(id)
            if ((before == "working" || before == "waiting") && pref(NotificationPrefs.RESPONSE) && shouldNotify(id) && sourceAllowed(id)) {
                completion(id, title(id), L10n.tr("Response ready"))
            }
        }
    }

    private fun open(sid: String): PendingIntent =
        ConduitNotifications.openIntent(context, sid, title(sid), hermes.connection.value?.id, hermes.profile.value)

    private fun approval(request: ApprovalRequest) {
        val key = request.requestId.ifEmpty { request.sessionId }
        if (!notifiedApprovals.add(key)) return
        val id = ConduitNotifications.idFor("approval", key)
        val sound = pref(NotificationPrefs.ATTENTION_SOUND)
        val cards = pref(NotificationPrefs.DECISION_CARDS)
        val builder = ConduitNotifications.builder(context, if (sound) ConduitNotifications.CHANNEL_ATTENTION else ConduitNotifications.CHANNEL_ATTENTION_QUIET)
            .setContentTitle(L10n.tr("Approval needed"))
            .setContentText(if (cards) request.command ?: request.description else title(request.sessionId))
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (cards) listOfNotNull(request.description, request.command).joinToString("\n") else title(request.sessionId)))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open(request.sessionId))
            .setAutoCancel(true)
        if (cards) {
            request.choices.filter { it == "once" || it == "deny" || it == "session" }.forEach { choice ->
                val label = when (choice) { "once" -> L10n.tr("Allow once"); "session" -> L10n.tr("Allow for chat"); else -> L10n.tr("Deny") }
                val intent = Intent(context, NotificationActionReceiver::class.java)
                    .setAction(NotificationActionReceiver.ACTION_APPROVAL)
                    .putExtra(NotificationActionReceiver.EXTRA_CHOICE, choice)
                    .putExtra(NotificationActionReceiver.EXTRA_SESSION, request.sessionId)
                    .putExtra(NotificationActionReceiver.EXTRA_REQUEST, request.requestId)
                    .putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION, id)
                val pi = PendingIntent.getBroadcast(context, (id.toString() + choice).hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                builder.addAction(0, label, pi)
            }
        }
        ConduitNotifications.post(context, id, builder.build())
    }

    private fun attention(sid: String, title: String, text: String?) {
        val channel = if (pref(NotificationPrefs.ATTENTION_SOUND)) ConduitNotifications.CHANNEL_ATTENTION else ConduitNotifications.CHANNEL_ATTENTION_QUIET
        ConduitNotifications.post(
            context, ConduitNotifications.idFor("attention", sid),
            ConduitNotifications.builder(context, channel).setContentTitle(title).setContentText(text ?: title(sid))
                .setSubText(title(sid)).setContentIntent(open(sid)).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH).build(),
        )
    }

    private fun completion(sid: String, title: String, text: String?) {
        val channel = if (pref(NotificationPrefs.COMPLETION_SOUND)) ConduitNotifications.CHANNEL_COMPLETION else ConduitNotifications.CHANNEL_COMPLETION_QUIET
        val id = ConduitNotifications.idFor("done", sid)
        // MessagingStyle with Reply and Mark as read: Android Auto and Wear OS
        // read these aloud and take spoken replies (the CarPlay counterpart).
        val hermesPerson = androidx.core.app.Person.Builder().setName("Hermes").setKey("hermes").setBot(true).build()
        val me = androidx.core.app.Person.Builder().setName(L10n.tr("You")).setKey("me").build()
        val style = NotificationCompat.MessagingStyle(me)
            .setConversationTitle(title)
            .addMessage(text ?: L10n.tr("Response ready"), System.currentTimeMillis(), hermesPerson)
        val remoteInput = androidx.core.app.RemoteInput.Builder(NotificationActionReceiver.EXTRA_REPLY).setLabel(L10n.tr("Reply")).build()
        fun broadcast(action: String, code: Int) = PendingIntent.getBroadcast(
            context, code,
            Intent(context, NotificationActionReceiver::class.java).setAction(action)
                .putExtra(NotificationActionReceiver.EXTRA_SESSION, sid)
                .putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val reply = NotificationCompat.Action.Builder(0, L10n.tr("Reply"), broadcast(NotificationActionReceiver.ACTION_REPLY, id))
            .addRemoteInput(remoteInput)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()
        val read = NotificationCompat.Action.Builder(0, L10n.tr("Mark as read"), broadcast(NotificationActionReceiver.ACTION_READ, id + 1))
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()
        ConduitNotifications.post(
            context, id,
            ConduitNotifications.builder(context, channel)
                .setStyle(style)
                .setContentTitle(title)
                .setContentText(text)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setContentIntent(open(sid))
                .addAction(reply)
                .addInvisibleAction(read)
                .setAutoCancel(true)
                .build(),
        )
    }

    /** The Live Activity analog: what the running turn is doing, and for how long. */
    private fun progress(sid: String) {
        if (!enabled || !pref(NotificationPrefs.PROGRESS) || ForegroundState.isShowing(sid)) return
        val turn = turns[sid] ?: return
        val detail = listOfNotNull(turn.tool?.let { L10n.tr("Using %s", it) }, if (turn.tools > 1) L10n.tr("%d tools", turn.tools) else null).joinToString(" · ")
        ConduitNotifications.post(
            context, ConduitNotifications.idFor("progress", sid),
            ConduitNotifications.builder(context, ConduitNotifications.CHANNEL_PROGRESS)
                .setContentTitle(title(sid))
                .setContentText(detail.ifEmpty { L10n.tr("Hermes is working…") })
                .setUsesChronometer(true).setWhen(turn.startedMs).setShowWhen(true)
                .setOngoing(true).setOnlyAlertOnce(true).setProgress(0, 0, true)
                .setContentIntent(open(sid)).build(),
        )
    }

    private fun clearProgress(sid: String) = ConduitNotifications.cancel(context, ConduitNotifications.idFor("progress", sid))
}

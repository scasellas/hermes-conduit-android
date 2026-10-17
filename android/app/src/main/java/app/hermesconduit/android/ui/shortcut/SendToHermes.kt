package app.hermesconduit.android.ui.shortcut

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.app.NotificationCompat
import app.hermesconduit.android.appSettings
import app.hermesconduit.android.core.HermesConnection
import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.core.LinkState
import app.hermesconduit.android.hermes
import app.hermesconduit.android.model.ChatMessage
import app.hermesconduit.android.model.MessageRole
import app.hermesconduit.android.model.arr
import app.hermesconduit.android.model.obj
import app.hermesconduit.android.model.str
import app.hermesconduit.android.net.GatewayClient
import app.hermesconduit.android.notify.ConduitNotifications
import app.hermesconduit.android.ui.voice.LiveSessionStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The automation entry points' shared half (port of `AppState+ShortcutSend`):
 * the intent extras, the run bound to the app-wide connection, and how the
 * outcome goes back to the caller.
 */
object SendToHermes {
    const val ACTION = "app.hermesconduit.android.action.SEND_TO_HERMES"
    const val EXTRA_TEXT = "text"
    const val EXTRA_CHAT_NAME = "chat_name"
    const val EXTRA_PROFILE = "profile"
    const val EXTRA_WAIT_FOR_ANSWER = "wait_for_answer"
    /** Broadcast variant: the action of the reply broadcast, sent only to [EXTRA_REPLY_PACKAGE]. */
    const val EXTRA_REPLY_ACTION = "reply_action"
    const val EXTRA_REPLY_PACKAGE = "reply_package"
    const val EXTRA_REPLY = "reply"
    const val EXTRA_OUTCOME = "outcome"
    const val EXTRA_ERROR = "error"

    /** Off until the user allows it in Settings > Automation: any installed app could otherwise talk to Hermes. */
    const val ENABLED_KEY = "automation.sendToHermes.enabled"

    private const val NOTIFICATION_ID = 0x5E4D

    fun isEnabled(context: Context) = context.appSettings.getBool(ENABLED_KEY, false)

    /** The run an intent asks for. Automation apps send booleans as text too. */
    fun request(intent: Intent): ShortcutSendRequest {
        val extras = intent.extras ?: Bundle.EMPTY
        fun string(key: String) = extras.getCharSequence(key)?.toString() ?: extras.getStringValue(key)
        val wait = when (val value = extras.getValue(EXTRA_WAIT_FOR_ANSWER)) {
            is Boolean -> value
            is String -> value.trim().lowercase() !in setOf("false", "0", "no", "off")
            is Number -> value.toInt() != 0
            else -> true
        }
        return ShortcutSendRequest(
            text = string(EXTRA_TEXT) ?: string(Intent.EXTRA_TEXT).orEmpty(),
            chatName = string(EXTRA_CHAT_NAME),
            profile = string(EXTRA_PROFILE),
            waitsForAnswer = wait,
        )
    }

    @Suppress("DEPRECATION")
    private fun Bundle.getValue(key: String): Any? = get(key)

    private fun Bundle.getStringValue(key: String): String? = getValue(key)?.let { it as? String ?: it.toString() }

    /** What a finished run hands back. */
    sealed interface Result {
        data class Done(val outcome: ShortcutSendOutcome, val chat: ShortcutChat?) : Result
        data class Failed(val message: String) : Result
    }

    /** One run with the iOS time budget. Never throws but for cancellation. */
    suspend fun run(context: Context, request: ShortcutSendRequest): Result {
        if (!isEnabled(context)) {
            return Result.Failed(L10n.tr("Turn on Send to Hermes in Conduit's Automation settings first."))
        }
        val hermes = context.hermes
        val sender = ShortcutSender(backend(hermes), ShortcutChatStore(storage(context)))
        return try {
            val outcome = sender.send(request, System.currentTimeMillis() + ShortcutSender.RUN_TIME_MS)
            Result.Done(outcome, sender.chat)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: ShortcutSendError) {
            Result.Failed(e.message)
        } catch (e: Exception) {
            Result.Failed(e.message ?: L10n.tr("Conduit couldn't reach Hermes. Open Conduit and check the connection."))
        }
    }

    fun storage(context: Context) = object : ShortcutChatStorage {
        private val settings = context.appSettings
        override fun read() = settings.getString(ShortcutChatStore.STORAGE_KEY, "").ifEmpty { null }
        override fun write(value: String) = settings.set(ShortcutChatStore.STORAGE_KEY, value)
    }

    /** Result extras for an activity result or a reply broadcast. */
    fun fill(intent: Intent, result: Result): Intent = when (result) {
        is Result.Done -> intent.putExtra(EXTRA_REPLY, result.outcome.text).putExtra(EXTRA_OUTCOME, result.outcome.name)
        is Result.Failed -> intent.putExtra(EXTRA_ERROR, result.message)
    }

    /** No caller is waiting: the outcome shows as a notification that opens the chat. */
    fun notify(context: Context, result: Result) {
        ConduitNotifications.ensureChannels(context)
        val hermes = context.hermes
        val (text, chat) = when (result) {
            is Result.Done -> result.outcome.text to result.chat
            is Result.Failed -> result.message to null
        }
        val session = chat?.storedSessionId ?: chat?.runtimeSessionId
        val notification = ConduitNotifications.builder(context, ConduitNotifications.CHANNEL_COMPLETION)
            .setContentTitle(chat?.title ?: L10n.tr("Send to Hermes"))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(ConduitNotifications.openIntent(context, session, chat?.title, hermes.connection.value?.id, chat?.profile))
            .build()
        ConduitNotifications.post(context, session?.let { ConduitNotifications.idFor("shortcut", it) } ?: NOTIFICATION_ID, notification)
    }

    // region Backend

    private fun HermesConnection.isActive(profile: String) = profile.trim().equals(this.profile.value.trim(), ignoreCase = true)

    /** The profile param an RPC carries: none for the profile in use. */
    private fun HermesConnection.scope(profile: String?): String? = profile?.takeUnless { isActive(it) }

    /** Each call leaves time for the rest of the run. */
    private suspend fun HermesConnection.call(method: String, params: JsonObject) =
        rpc(method, params, GatewayClient.timeoutFor(method).coerceAtMost(RPC_TIMEOUT_MS))

    private const val RPC_TIMEOUT_MS = 15_000L

    fun backend(hermes: HermesConnection) = ShortcutSendBackend(
        connect = { deadline ->
            if (hermes.isSignedIn) {
                // Leave time to send once connected.
                val wait = (deadline - System.currentTimeMillis() - 8_000).coerceIn(0, 12_000)
                if (hermes.gateway == null) hermes.start()
                withTimeoutOrNull(wait) { hermes.link.first { it == LinkState.CONNECTED } } != null
            } else {
                false
            }
        },
        activeProfile = { hermes.profile.value },
        createChat = { profile, title ->
            val created = hermes.call("session.create", buildJsonObject {
                put("cols", 96)
                put("source", "desktop")
                if (profile.isNotBlank()) put("profile", profile)
            }).obj
            val runtime = created?.get("session_id").str?.takeIf { it.isNotEmpty() } ?: error(L10n.tr("The gateway didn't create a session."))
            val returned = created?.get("profile").str ?: created?.get("profile_id").str
            if (returned != null && !returned.trim().equals(profile.trim(), ignoreCase = true)) error(L10n.tr("The gateway didn't create a session."))
            val other = hermes.scope(profile)
            val stored = created?.get("stored_session_id").str
            if (other != null) {
                hermes.scopeSession(runtime, other)
                hermes.scopeSession(stored, other)
            }
            runCatching {
                hermes.call("session.title", buildJsonObject {
                    put("session_id", runtime)
                    put("title", title)
                    other?.let { put("profile", it) }
                })
            }
            ShortcutChat(runtime, stored, title, profile)
        },
        resumeChat = { chat ->
            val other = hermes.scope(chat.profile)
            val rows = runCatching { liveSessions(hermes, other) }.getOrDefault(emptyList())
            rows.firstOrNull { chat.owns(it.runtimeSessionId) || chat.owns(it.storedSessionId) }?.runtimeSessionId
                ?: resume(hermes, chat, other)
        },
        submit = { _, runtimeId, text ->
            // Automation text is literal: it always goes to Hermes as a message.
            val result = hermes.call("prompt.submit", buildJsonObject {
                put("session_id", runtimeId)
                put("text", text)
            }).obj
            PromptSubmission.of(result?.get("status").str)
        },
        liveSessions = { profile ->
            if (hermes.gateway == null || hermes.link.value != LinkState.CONNECTED) throw ShortcutConnectionLost()
            liveSessions(hermes, hermes.scope(profile))
        },
        latestReply = { chat -> latestReply(hermes, chat) },
    )

    /** `session.resume` of a chat that isn't live: its stored id, so Hermes reopens it. */
    private suspend fun resume(hermes: HermesConnection, chat: ShortcutChat, profile: String?): String {
        val resumed = hermes.call("session.resume", buildJsonObject {
            put("session_id", chat.storedSessionId ?: chat.runtimeSessionId)
            put("cols", 96)
            put("source", "desktop")
            put("omit_messages", true)
            profile?.let { put("profile", it) }
        }).obj
        val runtime = resumed?.get("session_id").str.orEmpty()
        if (profile != null) hermes.scopeSession(runtime, profile)
        return runtime
    }

    private suspend fun liveSessions(hermes: HermesConnection, profile: String?): List<LiveSessionStatus> {
        val result = hermes.call("session.active_list", if (profile != null) buildJsonObject { put("profile", profile) } else buildJsonObject {}).obj
        return result?.get("sessions").arr?.mapNotNull { it.obj }?.mapNotNull { row ->
            val id = row["id"].str?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            LiveSessionStatus(id, row["session_key"].str.orEmpty(), row["status"].str ?: "idle")
        }.orEmpty()
    }

    /** The chat's latest assistant reply from its saved history, newest pages first. */
    private suspend fun latestReply(hermes: HermesConnection, chat: ShortcutChat): String? {
        val profile = chat.profile ?: hermes.profile.value
        for (id in listOfNotNull(chat.storedSessionId, chat.runtimeSessionId).filter { it.isNotEmpty() }.distinct()) {
            var offset = 0
            for (pageIndex in 0 until LAST_REPLY_MAXIMUM_PAGES) {
                val page = try {
                    hermes.rest { it.messages(id, profile, offset, PAGE_SIZE) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    break
                }
                latestAssistantReply(page.messages)?.let { return it }
                if (page.messages.isEmpty() || !page.hasMore) break
                offset += page.messages.size
            }
        }
        return null
    }

    private const val LAST_REPLY_MAXIMUM_PAGES = 4
    private const val PAGE_SIZE = 60

    fun latestAssistantReply(messages: List<ChatMessage>): String? =
        messages.lastOrNull { it.role == MessageRole.ASSISTANT && it.content.isNotBlank() }?.content?.trim()

    // endregion
}

package app.hermesconduit.android.ui.shortcut

import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.ui.voice.LiveSessionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/*
 * "Send to Hermes" for automation apps (port of the iOS Shortcuts action,
 * `SendToHermesIntent`): an automation hands Conduit some text and Hermes
 * gets it as a chat message, without Conduit opening. With Wait for Answer
 * on, the run hands Hermes's reply back to the caller.
 *
 * It goes over the app's own gateway connection. A run has about half a
 * minute, like iOS; a longer answer is left in the chat and the caller is
 * told so.
 */

/** What a run asks for. */
data class ShortcutSendRequest(
    val text: String,
    /** Keep every run in one chat with this name. Null: a new chat each run. */
    val chatName: String? = null,
    /** The profile the chat lives in. Null: the profile in use. */
    val profile: String? = null,
    val waitsForAnswer: Boolean = true,
)

/** A chat a run talks to (iOS `VoiceThreadTarget`). */
@Serializable
data class ShortcutChat(
    val runtimeSessionId: String,
    val storedSessionId: String? = null,
    val title: String,
    /** The profile the chat lives in. */
    val profile: String? = null,
) {
    fun owns(sessionId: String): Boolean =
        sessionId.isNotEmpty() && (sessionId == runtimeSessionId || sessionId == storedSessionId)
}

/** `prompt.submit`'s `status`: a busy chat never refuses, it queues or joins the turn running. */
enum class PromptSubmission {
    ACCEPTED, STEERED, REDIRECTED, QUEUED;

    companion object {
        /** "streaming", a missing status and anything unknown: accepted. */
        fun of(status: String?): PromptSubmission = when (status?.lowercase()) {
            "steered" -> STEERED
            "redirected" -> REDIRECTED
            "queued" -> QUEUED
            else -> ACCEPTED
        }
    }
}

sealed class ShortcutSendOutcome(
    /** The iOS case name, handed to callers as the `outcome` extra. */
    val name: String,
) {
    /** Hermes replied before the run's time was up. */
    data class Answered(val reply: String) : ShortcutSendOutcome("answered")
    /** Sent; Wait for Answer was off. */
    data object Sent : ShortcutSendOutcome("sent")
    /** Hermes was still working when the time was up. */
    data object StillWorking : ShortcutSendOutcome("stillWorking")
    /** Hermes asked a question or wants an approval in the chat. */
    data object NeedsInput : ShortcutSendOutcome("needsInput")
    /** The named chat was busy; Hermes queued the text there. */
    data object Queued : ShortcutSendOutcome("queued")
    /** The named chat was busy; Hermes added the text to the reply running. */
    data object JoinedRunningReply : ShortcutSendOutcome("joinedRunningReply")

    /** What the caller gets back. */
    val text: String
        get() = when (this) {
            is Answered -> reply
            Sent -> L10n.tr("Sent to Hermes.")
            StillWorking -> L10n.tr("Hermes is still working on it. The answer will be in the chat in Conduit.")
            NeedsInput -> L10n.tr("Hermes needs your answer. Open the chat in Conduit.")
            Queued -> L10n.tr("Hermes is busy in that chat, so it will read this when its current reply finishes.")
            JoinedRunningReply -> L10n.tr("Hermes added this to the reply already running in that chat.")
        }
}

data class ShortcutSendError(override val message: String) : Exception(message) {
    companion object {
        val empty get() = ShortcutSendError(L10n.tr("There's no text to send. Give Send to Hermes a message."))
        val outOfTime get() = ShortcutSendError(L10n.tr("Conduit ran out of time before it could send this. Nothing was sent; try again."))
        val notConnected get() = ShortcutSendError(L10n.tr("Conduit couldn't reach Hermes. Open Conduit and check the connection."))
    }
}

/** Thrown by [ShortcutSendBackend.liveSessions] when the connection went. */
class ShortcutConnectionLost : Exception("Not connected to the gateway.")

/** Hermes operations a run needs. Closures, so the app binds them to its connection and tests script them. */
class ShortcutSendBackend(
    /** Waits for the connection; false when Hermes can't be reached by `deadlineMs`. */
    val connect: suspend (deadlineMs: Long) -> Boolean,
    val activeProfile: () -> String,
    /** A new chat titled `title` in `profile`. */
    val createChat: suspend (profile: String, title: String) -> ShortcutChat,
    /** The runtime the chat's next turn runs on, resuming it when it isn't live. */
    val resumeChat: suspend (chat: ShortcutChat) -> String,
    val submit: suspend (chat: ShortcutChat, runtimeId: String, text: String) -> PromptSubmission,
    val liveSessions: suspend (profile: String) -> List<LiveSessionStatus>,
    val latestReply: suspend (chat: ShortcutChat) -> String?,
    val sleep: suspend (ms: Long) -> Unit = { delay(it) },
    val now: () -> Long = System::currentTimeMillis,
)

/** Where [ShortcutChatStore] keeps its list. */
interface ShortcutChatStorage {
    fun read(): String?
    fun write(value: String)
}

/** The chats runs with a Chat Name went to, so the next run with that name joins the same chat. */
class ShortcutChatStore(private val storage: ShortcutChatStorage) {
    @Serializable
    private data class Entry(val key: String, val chat: ShortcutChat, val usedAtMs: Long)

    fun chat(named: String, profile: String): ShortcutChat? {
        val key = key(named, profile)
        return entries().firstOrNull { it.key == key }?.chat
    }

    fun save(chat: ShortcutChat, named: String, profile: String, atMs: Long = System.currentTimeMillis()) {
        val key = key(named, profile)
        val list = (entries().filter { it.key != key } + Entry(key, chat, atMs)).sortedByDescending { it.usedAtMs }
        write(list.take(MAXIMUM_CHATS))
    }

    fun forget(named: String, profile: String) {
        val key = key(named, profile)
        write(entries().filter { it.key != key })
    }

    private fun entries(): List<Entry> =
        storage.read()?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()

    private fun write(list: List<Entry>) = storage.write(json.encodeToString(serializer, list))

    companion object {
        const val STORAGE_KEY = "conduit.shortcutChats.v1"
        const val MAXIMUM_CHATS = 40
        private val json = Json { ignoreUnknownKeys = true }
        private val serializer = ListSerializer(Entry.serializer())

        fun key(name: String, profile: String) = profile.trim().lowercase() + "\n" + name.trim().lowercase()
    }
}

/** One run: find or make the chat, send, and wait for the reply until the deadline when asked to. */
class ShortcutSender(private val backend: ShortcutSendBackend, private val chats: ShortcutChatStore) {
    /** The chat the last run went to, for a notification that opens it. */
    var chat: ShortcutChat? = null
        private set

    suspend fun send(request: ShortcutSendRequest, deadlineMs: Long): ShortcutSendOutcome {
        val text = request.text.trim().take(MAXIMUM_TEXT_LENGTH)
        if (text.isEmpty()) throw ShortcutSendError.empty
        if (!backend.connect(deadlineMs)) throw ShortcutSendError.notConnected
        val profile = cleaned(request.profile) ?: backend.activeProfile()
        val name = cleaned(request.chatName)?.take(80)

        val chat: ShortcutChat
        val runtimeId: String
        var reused = false
        val saved = name?.let { chats.chat(it, profile) }
        val resumed = saved?.let { resume(it) }
        if (saved != null && !resumed.isNullOrEmpty()) {
            chat = saved.copy(runtimeSessionId = resumed)
            runtimeId = resumed
            reused = true
        } else {
            chat = backend.createChat(profile, name ?: title(text))
            runtimeId = chat.runtimeSessionId
        }
        this.chat = chat
        if (name != null) chats.save(chat, name, profile, backend.now())
        // A slow connection used up the run: nothing was sent.
        currentCoroutineContext().ensureActive()
        if (backend.now() >= deadlineMs) throw ShortcutSendError.outOfTime

        // A reused chat already has replies: only a new one answers this run.
        val earlier = if (reused && request.waitsForAnswer) backend.latestReply(chat) else null
        when (backend.submit(chat, runtimeId, text)) {
            PromptSubmission.ACCEPTED -> Unit
            PromptSubmission.QUEUED -> return ShortcutSendOutcome.Queued
            PromptSubmission.STEERED, PromptSubmission.REDIRECTED -> return ShortcutSendOutcome.JoinedRunningReply
        }
        if (!request.waitsForAnswer) return ShortcutSendOutcome.Sent
        return waitForReply(chat, earlier, deadlineMs)
    }

    /** The saved chat's runtime, or null when Hermes no longer has it (a new chat then takes the name). Cancellation still ends the run. */
    private suspend fun resume(chat: ShortcutChat): String? = try {
        backend.resumeChat(chat)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /** Throws only when the run is cancelled. */
    private suspend fun waitForReply(chat: ShortcutChat, earlier: String?, deadlineMs: Long): ShortcutSendOutcome {
        var sawRunning = false
        var missed = 0
        while (backend.now() + POLL_INTERVAL_MS < deadlineMs) {
            backend.sleep(POLL_INTERVAL_MS)
            val rows = try {
                backend.liveSessions(chat.profile ?: backend.activeProfile())
            } catch (e: CancellationException) {
                throw e
            } catch (_: ShortcutConnectionLost) {
                // The connection went: the reply will be in the chat.
                return ShortcutSendOutcome.StillWorking
            } catch (_: Exception) {
                continue
            }
            val row = rows.firstOrNull { chat.owns(it.runtimeSessionId) || chat.owns(it.storedSessionId) }
            if (row != null) {
                missed = 0
                if (row.status == "waiting") return ShortcutSendOutcome.NeedsInput
                if (row.isRunning) {
                    sawRunning = true
                    continue
                }
                // Idle before the turn was ever seen running: either it hasn't
                // started yet (a resumed chat sits idle until it does) or it
                // finished between two polls. Only a reply says which, so keep
                // waiting until one comes.
                if (!sawRunning) {
                    newReply(chat, earlier)?.let { return ShortcutSendOutcome.Answered(it) }
                    continue
                }
            } else {
                // Absence proves nothing until the turn was seen running (a new
                // chat can be slow to list): only a reply ends the wait.
                if (!sawRunning) {
                    newReply(chat, earlier)?.let { return ShortcutSendOutcome.Answered(it) }
                    continue
                }
                // After it ran, absence counts only when it repeats.
                missed += 1
                if (missed < 2) continue
            }
            newReply(chat, earlier)?.let { return ShortcutSendOutcome.Answered(it) }
            // The reply can land a moment after the turn ends.
            if (backend.now() + 1_000 >= deadlineMs) break
            backend.sleep(1_000)
            newReply(chat, earlier)?.let { return ShortcutSendOutcome.Answered(it) }
            return ShortcutSendOutcome.StillWorking
        }
        // Out of time: a reply that landed in the last moments still counts.
        newReply(chat, earlier)?.let { return ShortcutSendOutcome.Answered(it) }
        return ShortcutSendOutcome.StillWorking
    }

    private suspend fun newReply(chat: ShortcutChat, earlier: String?): String? {
        val reply = backend.latestReply(chat)?.trim()
        if (reply.isNullOrEmpty() || reply == earlier?.trim()) return null
        return reply
    }

    companion object {
        const val MAXIMUM_TEXT_LENGTH = 20_000
        const val POLL_INTERVAL_MS = 1_500L
        /** iOS gives a background action about 30 s; the run keeps 25 of them. */
        const val RUN_TIME_MS = 25_000L

        /** A new chat's title: the text's first line, shortened. */
        fun title(text: String): String {
            val line = text.lineSequence().firstOrNull { it.isNotEmpty() } ?: text
            val trimmed = line.trim()
            val short = if (trimmed.length > 60) trimmed.take(59) + "…" else trimmed
            return L10n.tr("Shortcut: %s", short)
        }

        private fun cleaned(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }
    }
}

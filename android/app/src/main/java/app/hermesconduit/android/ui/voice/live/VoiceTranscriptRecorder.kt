package app.hermesconduit.android.ui.voice.live

import android.util.Log
import app.hermesconduit.android.core.live.LiveTranscriptEntry
import app.hermesconduit.android.model.VoiceTranscriptSaveRequest
import app.hermesconduit.android.model.VoiceTranscriptSaveResult
import app.hermesconduit.android.model.VoiceTranscriptTurn
import app.hermesconduit.android.ui.voice.VoiceHistoryClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Collects one live call's settled turns and saves them to its row (the iOS
 * `VoiceTranscriptRecorder`). A turn is settled once its engine stops
 * streaming into it; only the settled prefix of the transcript is taken, so
 * turns reach the host in the order they were spoken. Saves send only the
 * turns the host doesn't have yet, and the plugin skips any it already
 * stored for this call, so a retried save never duplicates a turn.
 */
class VoiceTranscriptRecorder(
    /** "gemini-live", "gpt-live" or "grok-live". */
    val engine: String,
    val profile: String,
    resumingSessionId: String? = null,
    private val save: suspend (VoiceTranscriptSaveRequest) -> VoiceTranscriptSaveResult,
    /** The title a new row gets, asked for when the first save creates it. */
    private val title: suspend (List<VoiceTranscriptTurn>) -> String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    var callId: String = UUID.randomUUID().toString()
        private set
    /** The attached chat's stored id: a new call's turns are kept with it rather than in a row of their own (#556). */
    var chatId: String? = null
    /** The host kept the call with [chatId]. */
    var savedWithChat = false
        private set
    /** The row this call saves into: the resumed row, or the one its first save created. */
    var sessionId: String? = resumingSessionId
        private set
    private val _turns = mutableListOf<VoiceTranscriptTurn>()
    val turns: List<VoiceTranscriptTurn> get() = _turns
    /** Turns the host confirmed (every turn below this index). */
    var written = 0
        private set
    /** Set when the host can't store transcripts at all; saving stops for this call and its turns wait in the outbox. */
    var isDisabled = false
        private set
    /** A new row's title, asked for once and reused if its save is retried. */
    var newRowTitle: String? = null
        private set
    /** Background jobs this call started, tagged with its row once the call ends. */
    val jobSessionIds = mutableListOf<String>()

    private val recordedEntries = mutableSetOf<String>()
    private val flushLock = Mutex()

    val hasUserSpeech: Boolean get() = _turns.any { it.role == "user" }
    val unsavedTurns: List<VoiceTranscriptTurn> get() = _turns.filter { it.index >= written }

    /** Lines the engine still shows from its last call: never this call's. */
    fun ignore(entries: List<LiveTranscriptEntry>) {
        recordedEntries += entries.map { it.id }
    }

    /** Takes the settled prefix of the engine's transcript: every entry up to the first one still streaming. */
    fun capture(entries: List<LiveTranscriptEntry>, unsettled: Set<String>) {
        for (entry in entries) {
            if (entry.id in unsettled) break
            if (!recordedEntries.add(entry.id)) continue
            append(if (entry.fromUser) "user" else "assistant", entry.text)
        }
    }

    /** A line the call itself adds (a started job, the resume marker). */
    fun note(text: String) = append("assistant", text)

    private fun append(role: String, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        _turns += VoiceTranscriptTurn(_turns.size, role, trimmed, now() / 1000.0)
    }

    /** Saves whatever the host doesn't have. Saves run one at a time, in order; a failed one leaves its turns for the next. */
    suspend fun flush() = flushLock.withLock { performFlush() }

    private suspend fun performFlush() {
        if (isDisabled) return
        // A call where the user never spoke isn't worth a row, nor an append to one.
        if (!hasUserSpeech) return
        val pending = unsavedTurns
        if (pending.isEmpty()) return
        val creating = sessionId == null
        val withChat = creating && chatId != null
        val rowTitle: String? = when {
            // Only a plugin that predates chat calls makes a row of it: no model call for a title it almost never needs.
            withChat -> newRowTitle ?: LiveVoiceCalls.fallbackCallTitle()
            creating -> newRowTitle ?: title(_turns.toList()).also { newRowTitle = it }
            else -> null
        }
        val request = VoiceTranscriptSaveRequest(callId, engine, sessionId, rowTitle, pending, if (withChat) chatId else null)
        try {
            val result = save(request)
            result.sessionId?.let { sessionId = it }
            if (result.chatId != null) savedWithChat = true
            written = maxOf(written, minOf(result.written, _turns.size))
        } catch (e: CancellationException) {
            throw e
        } catch (_: VoiceHistoryClient.HistoryException.RowUnavailable) {
            when {
                withChat -> {
                    // The chat is gone: the call is saved on its own, whole.
                    chatId = null
                    savedWithChat = false
                    written = 0
                    callId = UUID.randomUUID().toString()
                    performFlush()
                }
                !creating -> {
                    // The row was deleted or compacted since: this call becomes a new row holding everything it said.
                    sessionId = null
                    written = 0
                    callId = UUID.randomUUID().toString()
                    performFlush()
                }
                else -> Log.w(TAG, "Saving a voice transcript failed: row unavailable")
            }
        } catch (_: VoiceHistoryClient.HistoryException.PluginMissing) {
            isDisabled = true
        } catch (_: VoiceHistoryClient.HistoryException.Unsupported) {
            isDisabled = true
        } catch (e: Exception) {
            Log.w(TAG, "Saving a voice transcript failed: ${e.javaClass.simpleName}")
        }
    }

    /** What is still unsaved, for the outbox to retry after the call. */
    val outboxRequest: VoiceTranscriptSaveRequest?
        get() {
            if (!hasUserSpeech) return null
            val pending = unsavedTurns
            if (pending.isEmpty()) return null
            return VoiceTranscriptSaveRequest(
                callId, engine, sessionId,
                title = if (sessionId == null) newRowTitle else null,
                turns = pending,
                chatId = if (sessionId == null) chatId else null,
            )
        }

    private companion object {
        const val TAG = "VoiceTranscript"
    }
}

package app.hermesconduit.android.car

import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.core.live.LiveVoiceHostIssue
import app.hermesconduit.android.model.LiveVoiceMode
import app.hermesconduit.android.model.SessionSummary
import app.hermesconduit.android.model.VoiceProfilePreferences
import app.hermesconduit.android.ui.voice.VoiceBackgroundJob
import app.hermesconduit.android.ui.voice.VoiceConversationState
import app.hermesconduit.android.ui.voice.VoiceThreadTarget
import app.hermesconduit.android.ui.voice.live.LiveCallPhase
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * The pure half of the Android Auto app (port of the iOS CarPlay voice state
 * mapping, voice buttons, earcons and chat list rows), testable without a
 * car. The car is a state/control surface, never a mirrored chat window: no
 * transcript text, reasoning or failure detail crosses this boundary. The one
 * piece of user text is a chat's title in Ready, shortened, which says where
 * Listen talks. The Error state instead names what to fix ([CarVoiceIssue]).
 */

/** The five states the car's voice screen shows (iOS CarPlayVoiceState). */
enum class CarVoiceState {
    READY,
    LISTENING,
    PROCESSING,
    RESPONDING,
    ERROR;

    /** Driver-safe titles, longest first; keep them short and free of user content. */
    val titleVariants: List<String>
        get() = when (this) {
            READY -> listOf(L10n.tr("Ready"))
            LISTENING -> listOf(L10n.tr("Listening…"))
            PROCESSING -> listOf(L10n.tr("Thinking…"))
            RESPONDING -> listOf(L10n.tr("Responding…"))
            ERROR -> listOf(L10n.tr("Something went wrong. See your phone."), L10n.tr("Something went wrong"))
        }

    /**
     * The titles for this state, with the Error state naming what to fix when
     * the cause is known. A bare "Voice unavailable" read as the car itself not
     * working to a driver who had never turned Voice on.
     */
    fun titleVariants(errorIssue: CarVoiceIssue?): List<String> =
        if (this == ERROR && errorIssue != null) errorIssue.titleVariants else titleVariants

    /**
     * The titles for this state on a screen built for [controls]. Ready also
     * names the chat Listen talks in, so the driver knows where the next words
     * go; the plain title stays as the short variant.
     */
    fun titleVariants(controls: CarVoiceControls): List<String> {
        val chatTitle = controls.chatTitle
        if (this != READY || chatTitle == null) return titleVariants(controls.errorIssue)
        return listOf(L10n.tr("Ready · %s", shortenedChatTitle(chatTitle))) + titleVariants
    }

    /** A conversation is running: the screen offers Mute and End rather than Listen. */
    val isInConversation: Boolean get() = this == LISTENING || this == PROCESSING || this == RESPONDING

    companion object {
        /** Chat titles are any length; Ready keeps them to a glance. */
        const val MAXIMUM_CHAT_TITLE_LENGTH = 28

        fun shortenedChatTitle(title: String): String {
            val count = title.codePointCount(0, title.length)
            if (count <= MAXIMUM_CHAT_TITLE_LENGTH) return title
            val end = title.offsetByCodePoints(0, MAXIMUM_CHAT_TITLE_LENGTH - 1)
            return title.substring(0, end).trim() + "…"
        }

        /**
         * Approximate mapping from the authoritative controller state. Both
         * transcribing and thinking are "the assistant is working"; muted is a
         * playback presentation detail and maps to responding.
         */
        fun map(state: VoiceConversationState): CarVoiceState = when (state) {
            VoiceConversationState.IDLE -> READY
            VoiceConversationState.LISTENING -> LISTENING
            VoiceConversationState.TRANSCRIBING, VoiceConversationState.THINKING -> PROCESSING
            VoiceConversationState.SPEAKING, VoiceConversationState.MUTED -> RESPONDING
            VoiceConversationState.FAILED -> ERROR
        }

        /**
         * The same mapping for a live call (Gemini Live, GPT-Live and Grok
         * Live share one phase type here; iOS maps the Gemini and GPT-Live
         * phases with identical tables, Gemini's also covering reconnecting).
         * Connecting, reconnecting, a pause for another sound and saying
         * goodbye are all "the assistant is working".
         */
        fun map(phase: LiveCallPhase): CarVoiceState = when (phase) {
            LiveCallPhase.Idle -> READY
            LiveCallPhase.Connecting, LiveCallPhase.Reconnecting, LiveCallPhase.Paused, LiveCallPhase.Ending -> PROCESSING
            LiveCallPhase.Listening -> LISTENING
            LiveCallPhase.Speaking -> RESPONDING
            is LiveCallPhase.Failed -> ERROR
        }
    }
}

/** The voice mode the profile uses, which decides the controller the car presents and drives (iOS CarPlayVoiceMode). */
enum class CarVoiceMode {
    CLASSIC,
    GEMINI_LIVE,
    GPT_LIVE,
    GROK_LIVE;

    /** The live mode this is; null for classic. */
    val live: LiveVoiceMode?
        get() = when (this) {
            CLASSIC -> null
            GEMINI_LIVE -> LiveVoiceMode.GEMINI_LIVE
            GPT_LIVE -> LiveVoiceMode.GPT_LIVE
            GROK_LIVE -> LiveVoiceMode.GROK_LIVE
        }

    val title: String
        get() = when (this) {
            CLASSIC -> L10n.tr("Classic voice")
            GEMINI_LIVE -> L10n.tr("Gemini Live")
            GPT_LIVE -> L10n.tr("GPT-Live")
            GROK_LIVE -> L10n.tr("Grok Live")
        }

    companion object {
        /** The profile keeps at most one live mode on; none is classic. */
        fun of(preferences: VoiceProfilePreferences): CarVoiceMode = when {
            preferences.geminiLiveEnabled -> GEMINI_LIVE
            preferences.gptLiveEnabled -> GPT_LIVE
            preferences.grokLiveEnabled -> GROK_LIVE
            else -> CLASSIC
        }

        /** The preferences with [mode] as the only voice mode on. */
        fun applying(mode: CarVoiceMode, preferences: VoiceProfilePreferences): VoiceProfilePreferences = preferences.copy(
            geminiLiveEnabled = mode == GEMINI_LIVE,
            gptLiveEnabled = mode == GPT_LIVE,
            grokLiveEnabled = mode == GROK_LIVE,
        )
    }
}

/**
 * What Listen does in a GPT-Live call (iOS CarPlayGPTLiveListenAction). The
 * call is full duplex, so there is nothing to interrupt: the driver just
 * talks. Listen shows only at Ready and Error, so only START is reached from
 * the car; the other phases are mapped so the table stays total.
 */
enum class CarGPTLiveListenAction {
    /** Nothing running (or it failed): start a call. */
    START,
    /** Already in a call the driver can talk into, or connecting or ending. */
    NOTHING;

    companion object {
        fun forPhase(phase: LiveCallPhase): CarGPTLiveListenAction = when (phase) {
            LiveCallPhase.Idle, is LiveCallPhase.Failed -> START
            LiveCallPhase.Connecting, LiveCallPhase.Reconnecting, LiveCallPhase.Listening, LiveCallPhase.Speaking,
            LiveCallPhase.Paused, LiveCallPhase.Ending -> NOTHING
        }
    }
}

/**
 * What Listen does in a Gemini Live (or Grok Live) conversation, which
 * listens continuously instead of turn by turn (iOS
 * CarPlayGeminiLiveListenAction).
 */
enum class CarGeminiLiveListenAction {
    /** Nothing running (or it failed): start a conversation. */
    START,
    /** The model is talking: stop it so the driver can speak. */
    INTERRUPT,
    /** Already listening or connecting: nothing to do. */
    NOTHING;

    companion object {
        fun forPhase(phase: LiveCallPhase): CarGeminiLiveListenAction = when (phase) {
            LiveCallPhase.Idle, is LiveCallPhase.Failed -> START
            LiveCallPhase.Speaking -> INTERRUPT
            LiveCallPhase.Connecting, LiveCallPhase.Reconnecting, LiveCallPhase.Listening, LiveCallPhase.Paused, LiveCallPhase.Ending -> NOTHING
        }
    }
}

/**
 * Why Voice can't start, named by what to fix (the iOS VoiceSetupIssue cases
 * the classic mode reaches from the car).
 */
enum class CarVoiceIssue {
    /** Conduit isn't connected to Hermes. */
    NOT_CONNECTED,
    /** Classic Voice was never turned on for this profile on this device. */
    VOICE_OFF,
    /** Conduit isn't allowed the microphone. */
    MICROPHONE_DENIED,
    /** Hermes has no ready speech-to-text provider. */
    NO_SPEECH_TO_TEXT,
    /** Hermes has no ready text-to-speech provider. */
    NO_TEXT_TO_SPEECH,
    /** Hermes can't run Gemini Live (no key, no sign-in). */
    GEMINI_LIVE_NOT_SET_UP,
    /** Hermes can't run GPT-Live. */
    GPT_LIVE_NOT_SET_UP,
    /** Hermes can't run Grok Live. */
    GROK_LIVE_NOT_SET_UP,
    /** The Hermes notifier plugin (or its routes for the live mode) is missing. */
    NOTIFIER_PLUGIN_MISSING;

    /** Short titles for the car's screen, longest first. Each names the fix, never failure internals. */
    val titleVariants: List<String>
        get() = when (this) {
            NOT_CONNECTED -> listOf(L10n.tr("Can't reach Hermes. Check your phone."), L10n.tr("Can't reach Hermes"))
            VOICE_OFF -> listOf(L10n.tr("Voice is off: Conduit Settings > Voice"), L10n.tr("Turn on Voice in Conduit"), L10n.tr("Voice is off"))
            MICROPHONE_DENIED -> listOf(L10n.tr("Allow the microphone for Conduit on your phone"), L10n.tr("Microphone not allowed"))
            NO_SPEECH_TO_TEXT -> listOf(L10n.tr("Set up speech to text in Conduit"), L10n.tr("No speech to text"))
            NO_TEXT_TO_SPEECH -> listOf(L10n.tr("Set up assistant voice in Conduit"), L10n.tr("No assistant voice"))
            GEMINI_LIVE_NOT_SET_UP -> notSetUp(LiveVoiceMode.GEMINI_LIVE)
            GPT_LIVE_NOT_SET_UP -> notSetUp(LiveVoiceMode.GPT_LIVE)
            GROK_LIVE_NOT_SET_UP -> notSetUp(LiveVoiceMode.GROK_LIVE)
            NOTIFIER_PLUGIN_MISSING -> listOf(L10n.tr("Update the Hermes notifier plugin"), L10n.tr("Plugin update needed"))
        }

    private fun notSetUp(mode: LiveVoiceMode) =
        listOf(L10n.tr("%s isn't set up on Hermes", mode.displayName), L10n.tr("%s not set up", mode.displayName))

    companion object {
        /**
         * What stops classic Voice, as far as the app can tell: no Hermes
         * connection, then its switch (until it is on, Conduit never asked for
         * the microphone), a denied microphone, then its speech providers. A
         * provider readiness of null is not known yet and names nothing. Null
         * when nothing in the setup explains the failure.
         */
        fun of(
            isConnected: Boolean,
            isVoiceEnabled: Boolean,
            isMicrophoneDenied: Boolean,
            speechToTextReady: Boolean?,
            textToSpeechReady: Boolean?,
        ): CarVoiceIssue? = when {
            !isConnected -> NOT_CONNECTED
            !isVoiceEnabled -> VOICE_OFF
            isMicrophoneDenied -> MICROPHONE_DENIED
            speechToTextReady == false -> NO_SPEECH_TO_TEXT
            textToSpeechReady == false -> NO_TEXT_TO_SPEECH
            else -> null
        }

        /**
         * What stops a live call: no Hermes connection, then the host's
         * check of the mode (kept by the controller while the call is failed
         * on it), then a denied microphone. The host is checked before the
         * microphone is asked for, so a host issue is what stopped the call
         * even with the microphone denied (iOS `setupIssue` for live modes).
         */
        fun ofLive(
            isConnected: Boolean,
            mode: LiveVoiceMode,
            hostIssue: LiveVoiceHostIssue?,
            isMicrophoneDenied: Boolean,
        ): CarVoiceIssue? = when {
            !isConnected -> NOT_CONNECTED
            hostIssue == LiveVoiceHostIssue.PLUGIN_MISSING -> NOTIFIER_PLUGIN_MISSING
            hostIssue == LiveVoiceHostIssue.NOT_SET_UP -> when (mode) {
                LiveVoiceMode.GEMINI_LIVE -> GEMINI_LIVE_NOT_SET_UP
                LiveVoiceMode.GPT_LIVE -> GPT_LIVE_NOT_SET_UP
                LiveVoiceMode.GROK_LIVE -> GROK_LIVE_NOT_SET_UP
            }
            isMicrophoneDenied -> MICROPHONE_DENIED
            else -> null
        }
    }
}

/** What the voice screen's buttons and title depend on besides the state. */
data class CarVoiceControls(
    /** The classic voice mode, as opposed to a live call. */
    val isClassic: Boolean = true,
    /** The microphone is muted: the classic one paused (by the driver, or after a long silence), or a live call's. */
    val isMicrophoneMuted: Boolean = false,
    /** What the Error state names as the thing to fix; null shows the generic Error title. */
    val errorIssue: CarVoiceIssue? = null,
    /** The chat Listen talks in, which the Ready title names; null when Listen starts a new one. */
    val chatTitle: String? = null,
) {
    /** The classic mode's Listen continues the current chat, so a new chat is its own button. A live call always starts fresh. */
    val offersNewChat: Boolean get() = isClassic
}

/** The voice screen's action buttons (iOS CarPlayVoiceButton). The car shows at most two. */
enum class CarVoiceButton {
    LISTEN,
    NEW_CHAT,
    MUTE,
    /** Unmutes a live call's microphone. */
    UNMUTE,
    /** Reopens the classic mode's paused microphone. */
    RESUME,
    END;

    val title: String
        get() = when (this) {
            LISTEN, RESUME -> L10n.tr("Listen")
            NEW_CHAT -> L10n.tr("New Chat")
            MUTE -> L10n.tr("Mute")
            UNMUTE -> L10n.tr("Unmute")
            END -> L10n.tr("End")
        }

    companion object {
        /** The buttons for one state, in display order. */
        fun buttons(state: CarVoiceState, controls: CarVoiceControls): List<CarVoiceButton> = when (state) {
            CarVoiceState.READY, CarVoiceState.ERROR -> if (controls.offersNewChat) listOf(LISTEN, NEW_CHAT) else listOf(LISTEN)
            // The classic microphone also pauses itself after a long silence,
            // so a paused one offers Listen rather than an Unmute the driver
            // never asked for.
            else -> if (!controls.isMicrophoneMuted) listOf(MUTE, END) else listOf(if (controls.isClassic) RESUME else UNMUTE, END)
        }
    }
}

/** The car going away, for the conversations it presented (iOS `releaseCarPlayGeminiLive` and friends). */
object CarSurfaces {
    /**
     * Whether a live call goes on once the car disconnects: only while the
     * phone shows it (its call screen or the minimised bar, with Conduit on
     * screen). Nothing else would present it, so nothing would end it.
     */
    fun keepsLiveCall(isPhoneOnScreen: Boolean, isCallScreenShown: Boolean, isMinimisedBarShown: Boolean): Boolean =
        isPhoneOnScreen && (isCallScreenShown || isMinimisedBarShown)
}

/**
 * Duplicate suppression for the voice screen. The car limits how often a
 * template may change, so an unchanged state (muted ↔ speaking maps to the
 * same car state) is never forwarded.
 */
object CarVoiceStateActivation {
    /** The state to show, or null when it is a duplicate of the last one shown. */
    fun activationTarget(lastActivated: CarVoiceState?, newState: CarVoiceState): CarVoiceState? =
        if (newState == lastActivated) null else newState
}

/**
 * Short status sounds for the car's voice screen (iOS CarPlayEarcon): a soft
 * two-note rise when the driver's turn is sent, and a low note when Voice
 * fails. Synthesized, so the app ships no sound files.
 */
enum class CarEarcon {
    /** The driver's turn went to Hermes (Listening → Thinking). */
    SENT,
    /** Voice failed. */
    FAILED;

    /** (frequency in Hz, duration in seconds) per note. */
    val notes: List<Pair<Double, Double>>
        get() = when (this) {
            SENT -> listOf(660.0 to 0.07, 880.0 to 0.09)
            FAILED -> listOf(330.0 to 0.16)
        }

    /**
     * Mono PCM16 samples of the notes, each with a short fade in and out so it
     * never clicks.
     */
    fun samples(sampleRate: Int = SAMPLE_RATE): ShortArray {
        val out = ArrayList<Short>()
        for ((frequency, duration) in notes) {
            val count = (sampleRate * duration).toInt()
            val fade = max(1, (sampleRate * 0.012).toInt())
            for (index in 0 until count) {
                val envelope = min(1.0, min(index.toDouble() / fade, (count - 1 - index).toDouble() / fade))
                val value = sin(2 * PI * frequency * index / sampleRate) * envelope * 0.6
                out += (value * Short.MAX_VALUE).toInt().toShort()
            }
        }
        return out.toShortArray()
    }

    companion object {
        const val SAMPLE_RATE = 44_100

        /** The sound for a state change, or null when the change has none. */
        fun forTransition(old: CarVoiceState?, new: CarVoiceState): CarEarcon? = when {
            old == CarVoiceState.LISTENING && new == CarVoiceState.PROCESSING -> SENT
            new == CarVoiceState.ERROR && old != null && old != CarVoiceState.ERROR -> FAILED
            else -> null
        }
    }
}

/** What kind of chat a row is, for its icon. */
enum class CarChatKind { CHAT, PINNED, VOICE, VOICE_JOB, CRON }

/** One chat in the car's list. */
data class CarChatRow(
    /** The id `session.resume` and REST take (the stored id when known). */
    val sessionId: String,
    val runtimeId: String?,
    val title: String,
    /** The latest words in the chat, read aloud by the car as its message. */
    val preview: String,
    val lastActiveMs: Long?,
    val unread: Boolean,
    val kind: CarChatKind = CarChatKind.CHAT,
    val alternateIds: Set<String> = emptySet(),
) {
    /** Whether this row is the chat [id] names, by any of its ids. */
    fun matches(id: String?): Boolean = id != null && (id == sessionId || id == runtimeId || id in alternateIds)
}

/** The chat a live call started from the car works in, as one started from that chat on the phone does. */
val CarChatRow.thread: VoiceThreadTarget get() = VoiceThreadTarget(runtimeId ?: sessionId, sessionId, title)

/** What a Voice Job row's icon shows. */
enum class CarJobKind {
    RUNNING, WAITING, DONE, FAILED, CANCELLED;

    companion object {
        fun of(status: VoiceBackgroundJob.Status): CarJobKind = when (status) {
            VoiceBackgroundJob.Status.Starting, VoiceBackgroundJob.Status.Running -> RUNNING
            VoiceBackgroundJob.Status.NeedsInput -> WAITING
            VoiceBackgroundJob.Status.Finished -> DONE
            is VoiceBackgroundJob.Status.Failed -> FAILED
            VoiceBackgroundJob.Status.Cancelled -> CANCELLED
        }
    }
}

/** One Voice Job in the car's list: its title and status, never its reply. */
data class CarJobRow(
    val id: String,
    val title: String,
    val status: String,
    val kind: CarJobKind,
    /** A settled job can be heard again; a running one has nothing to say yet. */
    val canReplay: Boolean,
)

/** A voice mode in the car's Voice list. */
data class CarModeRow(val mode: CarVoiceMode, val title: String, val isSelected: Boolean)

/** An agent in the car's Voice list. */
data class CarOptionRow(val id: String, val title: String, val isSelected: Boolean)

/** The chats the car's list offers: pinned ones first, then recent. */
data class CarChatList(val pinned: List<CarChatRow>, val recent: List<CarChatRow>) {
    val isEmpty: Boolean get() = pinned.isEmpty() && recent.isEmpty()
    val all: List<CarChatRow> get() = pinned + recent

    /** The list with the chat [id] names read (the car's Mark as read), before Hermes confirms it. */
    fun markingRead(id: String, unread: Boolean = false): CarChatList {
        fun List<CarChatRow>.marked() = map { if (it.matches(id)) it.copy(unread = unread) else it }
        return CarChatList(pinned.marked(), recent.marked())
    }
}

/** The list shaping of the iOS CarPlayBrowse. */
object CarBrowse {
    /** Chats in the list; with the New voice chat row the list holds twelve, what a car shows of a list while driving. */
    const val MAXIMUM_CHATS = 11

    /**
     * Pinned chats first, then the most recent others, together at most
     * [limit]. Pinned chats are newest first too.
     */
    fun chatList(sessions: List<SessionSummary>, limit: Int = MAXIMUM_CHATS): CarChatList {
        val pinned = recentChats(sessions.filter { it.pinned }, limit).map { it.copy(kind = CarChatKind.PINNED) }
        val recent = recentChats(sessions.filter { !it.pinned }, limit).take(max(0, limit - pinned.size))
        return CarChatList(pinned, recent)
    }

    /**
     * The most recent unarchived chats, newest first. Rows without an activity
     * time keep their listed order after the dated ones.
     */
    fun recentChats(sessions: List<SessionSummary>, limit: Int = MAXIMUM_CHATS): List<CarChatRow> =
        sessions.withIndex()
            .filter { !it.value.archived }
            .sortedWith { lhs, rhs ->
                val left = lhs.value.lastActiveMs
                val right = rhs.value.lastActiveMs
                when {
                    left != null && right != null && left != right -> right.compareTo(left)
                    left != null && right == null -> -1
                    left == null && right != null -> 1
                    else -> lhs.index.compareTo(rhs.index)
                }
            }
            .take(limit)
            .map { (_, session) ->
                val title = session.title.trim().ifEmpty { L10n.tr("New Chat") }
                CarChatRow(
                    sessionId = session.id,
                    runtimeId = session.runtimeId,
                    title = title,
                    preview = session.preview?.trim()?.takeIf { it.isNotEmpty() } ?: title,
                    lastActiveMs = session.lastActiveMs,
                    unread = session.unread,
                    kind = kind(session.sourceKind),
                    alternateIds = session.alternateIds,
                )
            }

    /** Saved calls and voice jobs look like what they are; every other chat is a speech bubble. */
    fun kind(sourceKind: String): CarChatKind = when (sourceKind) {
        "voice" -> CarChatKind.VOICE
        "voice_job" -> CarChatKind.VOICE_JOB
        "cron" -> CarChatKind.CRON
        else -> CarChatKind.CHAT
    }

    /** Background Voice Jobs, newest first; a live call's chat turns aren't jobs. */
    fun jobRows(jobs: List<VoiceBackgroundJob>): List<CarJobRow> =
        jobs.filter { !it.isThreadTurn }
            .sortedByDescending { it.startedAtMs }
            .map { job -> CarJobRow(job.id, job.title, statusText(job.status), CarJobKind.of(job.status), canReplay = !job.status.isActive) }

    fun statusText(status: VoiceBackgroundJob.Status): String = when (status) {
        VoiceBackgroundJob.Status.Starting, VoiceBackgroundJob.Status.Running -> L10n.tr("Running")
        VoiceBackgroundJob.Status.NeedsInput -> L10n.tr("Waiting for you")
        VoiceBackgroundJob.Status.Finished -> L10n.tr("Done")
        is VoiceBackgroundJob.Status.Failed -> L10n.tr("Failed")
        VoiceBackgroundJob.Status.Cancelled -> L10n.tr("Cancelled")
    }

    fun modeRows(current: CarVoiceMode): List<CarModeRow> = CarVoiceMode.entries.map { CarModeRow(it, it.title, it == current) }

    /**
     * Agents (Hermes profiles) to switch between; empty when there is only
     * one, so the picker shows no choice that does nothing.
     */
    fun agentRows(profiles: List<String>, active: String, displayName: (String) -> String = ::profileDisplayName): List<CarOptionRow> {
        if (profiles.size <= 1) return emptyList()
        return profiles.map { CarOptionRow(it, displayName(it), it == active) }
    }

    /** A profile's name as the phone's voice screen shows it. */
    fun profileDisplayName(profile: String): String =
        if (profile == "default") L10n.tr("Default profile") else profile.replace('_', ' ').replaceFirstChar { it.uppercase() }

    /** What stands in for the chats while there are none to show yet. */
    fun placeholder(chats: CarChatList, isConnected: Boolean, isLoading: Boolean): String? = when {
        !chats.isEmpty -> null
        !isConnected -> L10n.tr("Connecting…")
        isLoading -> L10n.tr("Loading chats…")
        else -> null
    }
}

/**
 * The voice screen's orb colours per state (iOS CarPlayVoiceArtwork's
 * OrbPalette): calm grey-gold at rest, cool blue while it listens, bright gold
 * while Hermes works and talks, and red at Error. ARGB.
 */
data class CarOrbPalette(val light: Int, val deep: Int, val swirl: Int, val glow: Int, val mark: Int) {
    companion object {
        /** The marks on a gold orb: the orb editor's canvas colour. */
        const val INK = 0xFF0B0D12.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()

        private fun rgb(hex: Int) = (0xFF shl 24) or hex

        fun of(state: CarVoiceState): CarOrbPalette = when (state) {
            CarVoiceState.READY -> CarOrbPalette(rgb(0xB59B6A), rgb(0x6E5B3A), rgb(0x5E7598), rgb(0x6E6450), INK)
            CarVoiceState.LISTENING -> CarOrbPalette(rgb(0x8FB4FF), rgb(0x3A64C8), rgb(0xFAD47A), rgb(0x6194FA), WHITE)
            CarVoiceState.PROCESSING, CarVoiceState.RESPONDING -> CarOrbPalette(rgb(0xFAD47A), rgb(0xB07D24), rgb(0x8FB4FF), rgb(0xE0AB47), INK)
            CarVoiceState.ERROR -> CarOrbPalette(rgb(0xFF9A8F), rgb(0xB8362E), rgb(0xFFC9A8), rgb(0xE5534B), WHITE)
        }
    }
}

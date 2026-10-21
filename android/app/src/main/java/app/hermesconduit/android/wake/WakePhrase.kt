package app.hermesconduit.android.wake

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.hermesconduit.android.MainActivity
import app.hermesconduit.android.appSettings
import app.hermesconduit.android.core.Haptics
import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.core.LinkState
import app.hermesconduit.android.hermes
import app.hermesconduit.android.model.CompiledWakePhrase
import app.hermesconduit.android.model.WakeConfiguration
import app.hermesconduit.android.model.WakeLifecycleCoordinator
import app.hermesconduit.android.model.WakeLifecycleSnapshot
import app.hermesconduit.android.model.WakePhraseBinding
import app.hermesconduit.android.model.WakePhraseCompiler
import app.hermesconduit.android.model.WakePhraseMatcher
import app.hermesconduit.android.model.WakeProfileKey
import app.hermesconduit.android.model.WakeProfilePreferences
import app.hermesconduit.android.model.WakeTokenizationTables
import app.hermesconduit.android.notify.ConduitNotifications
import app.hermesconduit.android.ui.AppViewModel
import app.hermesconduit.android.ui.voice.ReadAloudController
import app.hermesconduit.android.ui.voice.VoiceHub
import app.hermesconduit.android.ui.voice.VoiceLaunch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Wake phrase (#174; iOS AppState+WakePhrase). Follows app state and arms
 * the listener only while it may record: Conduit on screen (or background
 * listening on, in a microphone foreground service), signed in and
 * connected, the microphone allowed, no voice or other audio running, and a
 * usable phrase bound on this dashboard. A detected phrase starts voice on
 * its profile ([VoiceLaunch]). The engine is the sherpa-onnx keyword spotter
 * once its model is downloaded and every phrase compiles against it, else
 * Android's speech recognizer (foreground only).
 */
class WakePhrase private constructor(private val context: Context) {
    enum class Engine { KEYWORD_SPOTTER, SPEECH_RECOGNIZER }

    private val hermes = context.hermes
    private val settings = context.appSettings
    val modelStore = WakeModelStore(context)

    private val _configuration = MutableStateFlow(loadConfiguration())
    val configuration: StateFlow<WakeConfiguration> = _configuration

    private val _failure = MutableStateFlow<String?>(null)
    /** Why wake couldn't listen, while it would. */
    val failure: StateFlow<String?> = _failure
    private val _pausedForOtherAudio = MutableStateFlow(false)
    val pausedForOtherAudio: StateFlow<Boolean> = _pausedForOtherAudio
    private val _engine = MutableStateFlow(Engine.SPEECH_RECOGNIZER)
    val engine: StateFlow<Engine> = _engine
    private val _engineNote = MutableStateFlow<String?>(null)
    /** Why the keyword spotter isn't used although its model is installed (a phrase it can't say). */
    val engineNote: StateFlow<String?> = _engineNote
    private val _armed = MutableStateFlow(false)
    val armed: StateFlow<Boolean> = _armed

    private val sherpa = SherpaWakeListener(context, modelStore)
    private val recognizer = SpeechRecognizerWakeListener(context)
    private var listener: WakeListener = recognizer
    private var lifecycle = WakeLifecycleCoordinator(listener)
    private var lastSnapshot: WakeLifecycleSnapshot? = null
    @Volatile private var visible = false
    private var started = false
    private var tables: WakeTokenizationTables? = null
    private var compiledFor: List<WakePhraseBinding>? = null
    private var compiled: Result<List<CompiledWakePhrase>>? = null

    private data class PendingLaunch(val profile: String, val startsFresh: Boolean, val atMs: Long)
    @Volatile private var pending: PendingLaunch? = null

    // region Configuration

    private fun loadConfiguration() = WakeConfiguration(
        values = WakeConfiguration.decodeValues(settings.getString(WakeConfiguration.STORAGE_KEY, "").ifEmpty { null }),
        listensOnCarPlay = settings.getBool(WakeConfiguration.CAR_PLAY_KEY, true),
        listensOverOtherAudio = settings.getBool(WakeConfiguration.OTHER_AUDIO_KEY, false),
        listensInBackground = settings.getBool(WakeConfiguration.BACKGROUND_KEY, false),
    )

    private fun store(configuration: WakeConfiguration) {
        _configuration.value = configuration
        settings.set(WakeConfiguration.STORAGE_KEY, configuration.encodeValues())
        settings.set(WakeConfiguration.OTHER_AUDIO_KEY, configuration.listensOverOtherAudio)
        settings.set(WakeConfiguration.BACKGROUND_KEY, configuration.listensInBackground)
        _failure.value = null
        lastSnapshot = null
        refresh()
    }

    /** The dashboard key wake phrases are stored under. */
    val gatewayID: String? get() = hermes.connection.value?.id

    fun preferences(profile: String): WakeProfilePreferences =
        gatewayID?.let { _configuration.value.preferences(WakeProfileKey.of(it, profile)) } ?: WakeProfilePreferences()

    fun setPreferences(preferences: WakeProfilePreferences, profile: String) {
        val gateway = gatewayID ?: return
        val key = WakeProfileKey.of(gateway, profile)
        val current = _configuration.value
        store(if (preferences.enabledPhrases.isEmpty()) current.removing(key) else current.saving(preferences, key))
    }

    fun setListensOverOtherAudio(enabled: Boolean) = store(_configuration.value.copy(listensOverOtherAudio = enabled))

    fun setListensInBackground(enabled: Boolean) = store(_configuration.value.copy(listensInBackground = enabled))

    fun suggestedPhrase(profile: String): String {
        val name = if (profile == "default") L10n.tr("Hermes") else profile.replace('_', ' ').replaceFirstChar { it.uppercase() }
        return L10n.tr("Hey %s", name)
    }

    /** Bindings that can fire now: this dashboard, a profile that still exists, a usable phrase. */
    val activeBindings: List<WakePhraseBinding>
        get() {
            val gateway = gatewayID ?: return emptyList()
            val known = VoiceHub.peek()?.knownProfiles.orEmpty().toSet()
            return _configuration.value.enabledBindings().filter {
                it.key.gatewayID == gateway && (known.isEmpty() || it.key.profileID in known || it.key.profileID == hermes.profile.value) &&
                    WakePhraseMatcher.isUsable(it.phrase)
            }
        }

    // endregion

    // region Lifecycle

    fun start() {
        if (started) return
        started = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                visible = true
                lastSnapshot = null
                refresh()
            }

            override fun onStop(owner: LifecycleOwner) {
                visible = false
                // The microphone stops with the app unless background listening keeps it, in its service.
                if (!backgroundAllowed) disarmImmediately()
                refresh()
            }
        })
        sherpa.onDetection = ::handleDetection
        recognizer.onDetection = ::handleDetection
        val failed: (String) -> Unit = { message ->
            _failure.value = message
            _armed.value = false
            hermes.scope.launch {
                delay(10_000)
                lastSnapshot = null
                refresh()
            }
        }
        sherpa.onFailure = failed
        recognizer.onFailure = failed
        hermes.scope.launch {
            while (isActive) {
                refresh()
                delay(2_000)
            }
        }
        hermes.scope.launch {
            modelStore.state.collect { state ->
                // The lexicon is a few MB: read off the main thread.
                tables = if (state is WakeModelStore.State.Installed) withContext(Dispatchers.IO) { runCatching { modelStore.tables() }.getOrNull() } else null
                compiledFor = null
                compiled = null
                lastSnapshot = null
                refresh()
            }
        }
    }

    private val microphonePermitted get() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private val backgroundAllowed get() = _configuration.value.listensInBackground && _engine.value == Engine.KEYWORD_SPOTTER

    private val isVoiceIdle: Boolean
        get() {
            val hub = VoiceHub.peek()
            val pendingLaunch = pending?.takeIf { System.currentTimeMillis() - it.atMs < PENDING_LIFE_MS }
            return pendingLaunch == null && hub?.conversationOpen?.value != true && (hub?.readAloud?.state?.value ?: ReadAloudController.State.Idle) == ReadAloudController.State.Idle
        }

    private val otherAudioPlaying get() = runCatching { context.getSystemService(AudioManager::class.java).isMusicActive }.getOrDefault(false)

    val snapshot: WakeLifecycleSnapshot
        get() {
            val bindings = activeBindings
            return WakeLifecycleSnapshot(
                isForegroundActive = visible || (backgroundAllowed && WakeListeningService.running),
                isAuthenticated = hermes.isSignedIn,
                isGatewayConnected = hermes.link.value == LinkState.CONNECTED,
                microphonePermitted = microphonePermitted,
                isVoiceIdle = isVoiceIdle,
                hasWakePhrases = bindings.isNotEmpty(),
                otherAudioAllowsListening = _configuration.value.listensOverOtherAudio || !otherAudioPlaying,
            )
        }

    /** Chooses the engine for these bindings: the keyword spotter when every phrase compiles against the installed model. */
    private fun chooseEngine(bindings: List<WakePhraseBinding>) {
        val result = if (bindings == compiledFor && compiled != null) compiled!! else {
            val tables = tables
            runCatching { if (tables != null) WakePhraseCompiler(tables).compile(bindings) else throw IllegalStateException() }
                .also { compiledFor = bindings; compiled = it }
        }
        val phrases = result.getOrNull()
        val next = if (phrases != null && phrases.isNotEmpty()) Engine.KEYWORD_SPOTTER else Engine.SPEECH_RECOGNIZER
        _engineNote.value = if (modelStore.isInstalled() && bindings.isNotEmpty()) result.exceptionOrNull()?.message?.takeIf { it.isNotBlank() } else null
        phrases?.let { sherpa.prepare(it) }
        recognizer.bindings = bindings
        if (next != _engine.value || lifecycle.isArmed && listener !== (if (next == Engine.KEYWORD_SPOTTER) sherpa else recognizer)) {
            listener.disarm()
            _engine.value = next
            listener = if (next == Engine.KEYWORD_SPOTTER) sherpa else recognizer
            lifecycle = WakeLifecycleCoordinator(listener)
            lastSnapshot = null
        }
    }

    fun refresh() {
        if (!started) return
        val bindings = activeBindings
        chooseEngine(bindings)
        syncService(bindings)
        val snapshot = snapshot
        _pausedForOtherAudio.value = snapshot.isPausedForOtherAudio
        if (snapshot == lastSnapshot && (snapshot.canArm == listener.isArmed)) return
        lastSnapshot = snapshot
        lifecycle.update(snapshot)
        _armed.value = listener.isArmed
        val failure = if (snapshot.canArm) lifecycle.lastFailureReason else null
        if (failure != null || snapshot.canArm) _failure.value = failure
    }

    /** Background listening runs in a microphone foreground service, started while Conduit is on screen. */
    private fun syncService(bindings: List<WakePhraseBinding>) {
        val wanted = backgroundAllowed && bindings.isNotEmpty() && hermes.isSignedIn && microphonePermitted
        if (wanted && !WakeListeningService.running && visible) WakeListeningService.start(context)
        if (!wanted && WakeListeningService.running) WakeListeningService.stop(context)
    }

    fun disarmImmediately() {
        lifecycle.disarmImmediately()
        lastSnapshot = null
        _armed.value = false
    }

    // endregion

    // region Detection

    private fun handleDetection(binding: WakePhraseBinding) {
        _armed.value = false
        lastSnapshot = null
        Haptics.medium()
        pending = PendingLaunch(binding.key.profileID, binding.startsFreshConversation, System.currentTimeMillis())
        hermes.log("Wake phrase detected")
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_WAKE, true)
        if (visible) {
            context.startActivity(open)
        } else {
            // Android doesn't let a background app open its screen: a full-screen notification does it.
            val pi = PendingIntent.getActivity(context, NOTIFICATION_ID, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            ConduitNotifications.ensureChannels(context)
            ConduitNotifications.post(
                context, NOTIFICATION_ID,
                ConduitNotifications.builder(context, ConduitNotifications.CHANNEL_ATTENTION)
                    .setContentTitle(L10n.tr("Voice conversation"))
                    .setContentText(L10n.tr("Tap to talk to Hermes."))
                    .setCategory(NotificationCompat.CATEGORY_CALL)
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setContentIntent(pi)
                    .setFullScreenIntent(pi, true)
                    .setAutoCancel(true)
                    .setTimeoutAfter(PENDING_LIFE_MS)
                    .build(),
            )
        }
    }

    /** MainActivity took the wake launch: the phrase's profile, a fresh chat when it says so, then voice. */
    fun launchPending(viewModel: AppViewModel) {
        val launch = pending?.takeIf { System.currentTimeMillis() - it.atMs < PENDING_LIFE_MS }
        pending = null
        ConduitNotifications.cancel(context, NOTIFICATION_ID)
        launch ?: return
        if (launch.profile != hermes.profile.value) viewModel.selectProfile(launch.profile)
        if (launch.startsFresh) viewModel.newChat()
        viewModel.requestRoute(VoiceLaunch.route(context))
    }

    // endregion

    companion object {
        const val EXTRA_WAKE = "conduit_wake_phrase"
        const val NOTIFICATION_ID = 7320
        const val PENDING_LIFE_MS = 30_000L

        @Volatile private var instance: WakePhrase? = null

        fun get(context: Context): WakePhrase = instance ?: synchronized(this) {
            instance ?: WakePhrase(context.applicationContext).also { instance = it }
        }

        fun peek(): WakePhrase? = instance
    }
}

/** Keeps wake listening with Conduit in the background (the user's choice): a microphone foreground service. */
class WakeListeningService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            WakePhrase.peek()?.setListensInBackground(false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL) == null) {
                manager.createNotificationChannel(NotificationChannel(CHANNEL, L10n.tr("Wake phrase"), NotificationManager.IMPORTANCE_MIN).apply { setSound(null, null) })
            }
        }
        val stop = PendingIntent.getService(this, 1, Intent(this, WakeListeningService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val notification: Notification = ConduitNotifications.builder(this, CHANNEL)
            .setContentTitle(L10n.tr("Listening for your wake phrase"))
            .setContentText(L10n.tr("Listening runs on this phone. Nothing is recorded or kept."))
            .setOngoing(true)
            .setContentIntent(ConduitNotifications.openIntent(this, null, null, null, null))
            .addAction(0, L10n.tr("Stop"), stop)
            .build()
        val started = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(NOTIFICATION_ID, notification)
        }
        running = started.isSuccess
        if (!running) stopSelf()
        WakePhrase.peek()?.refresh()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
        WakePhrase.peek()?.refresh()
    }

    companion object {
        const val CHANNEL = "conduit.wake"
        const val NOTIFICATION_ID = 7321
        const val ACTION_STOP = "conduit.wake.stop"
        @Volatile var running = false
            private set

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, WakeListeningService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeListeningService::class.java))
            running = false
        }
    }
}

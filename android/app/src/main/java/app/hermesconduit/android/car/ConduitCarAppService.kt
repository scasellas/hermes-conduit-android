package app.hermesconduit.android.car

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.messaging.model.CarMessage
import androidx.car.app.messaging.model.ConversationCallback
import androidx.car.app.messaging.model.ConversationItem
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.CarText
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import androidx.core.app.Person
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import app.hermesconduit.android.R
import app.hermesconduit.android.appSettings
import app.hermesconduit.android.core.L10n
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Android Auto's templated messaging app (the iOS CarPlay scene): the voice
 * screen for the classic voice conversation and the chat list. Conduit's
 * MessagingStyle notifications keep reading replies aloud next to it.
 */
class ConduitCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(R.array.car_hosts_allowlist)
                .build()
        }

    override fun onCreateSession(): Session = ConduitCarSession()
}

/**
 * One car connection. Opens on the voice screen and starts Voice at once, or
 * on the chat list when the driver chose that (Settings > Android Auto >
 * Choose a chat first).
 */
class ConduitCarSession : Session() {
    private var coordinator: CarVoiceCoordinator? = null

    override fun onCreateScreen(intent: Intent): Screen {
        val coordinator = CarVoiceCoordinator(carContext).also { coordinator = it }
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                coordinator.release()
            }
        })
        coordinator.onConversationShown = { showVoiceScreen(coordinator) }
        val running = coordinator.shownState.value.isInConversation
        if (CarPreferences.choosesChatFirst(carContext.appSettings) && !running) {
            return CarChatsScreen(carContext, coordinator, isRoot = true)
        }
        // Never over a conversation that is already running.
        if (!running) coordinator.listen()
        return CarVoiceScreen(carContext, coordinator, isRoot = true)
    }

    /** Back to the voice screen, or onto it, when a conversation is listening or talking. */
    private fun showVoiceScreen(coordinator: CarVoiceCoordinator) {
        val screens = carContext.getCarService(androidx.car.app.ScreenManager::class.java)
        if (screens.top is CarVoiceScreen) return
        if (screens.screenStack.any { it is CarVoiceScreen }) {
            screens.popTo(CarVoiceScreen.MARKER)
        } else {
            screens.push(CarVoiceScreen(carContext, coordinator, isRoot = false))
        }
    }
}

/**
 * Rebuilds the screen's template when [content] changes while it is on the
 * car. A screen coming back on screen builds its template afresh, so the
 * value it starts with needs no refresh.
 */
internal fun <T> Screen.refreshWhileShown(content: Flow<T>) {
    lifecycleScope.launch {
        repeatOnLifecycle(Lifecycle.State.STARTED) {
            content.distinctUntilChanged().drop(1).collect { invalidate() }
        }
    }
}

/** Shared car icons. */
internal object CarIcons {
    fun of(context: CarContext, resource: Int): CarIcon =
        CarIcon.Builder(IconCompat.createWithResource(context, resource)).build()

    fun chat(context: CarContext, kind: CarChatKind): CarIcon = of(
        context,
        when (kind) {
            CarChatKind.CHAT -> R.drawable.ic_car_chat
            CarChatKind.PINNED -> R.drawable.ic_car_pin
            CarChatKind.VOICE -> R.drawable.ic_car_voice
            CarChatKind.VOICE_JOB -> R.drawable.ic_car_job
            CarChatKind.CRON -> R.drawable.ic_car_clock
        },
    )

    fun button(button: CarVoiceButton): Int = when (button) {
        CarVoiceButton.LISTEN -> R.drawable.ic_car_mic
        CarVoiceButton.NEW_CHAT -> R.drawable.ic_car_new_chat
        // The microphone's current state, as on the phone's controls.
        CarVoiceButton.MUTE -> R.drawable.ic_car_mic
        CarVoiceButton.RESUME -> R.drawable.ic_car_mic_off
        CarVoiceButton.END -> R.drawable.ic_car_end
    }
}

/**
 * The voice screen (iOS CPVoiceControlTemplate): the state's orb, its title
 * (Ready names the chat Listen talks in; Error names what to fix) and at most
 * two buttons. At Ready and Error the header offers the chat list.
 */
class CarVoiceScreen(
    carContext: CarContext,
    private val coordinator: CarVoiceCoordinator,
    private val isRoot: Boolean,
) : Screen(carContext) {
    init {
        marker = MARKER
        refreshWhileShown(combine(coordinator.shownState, coordinator.controls) { state, controls -> state to controls })
    }

    override fun onGetTemplate(): Template {
        val state = coordinator.shownState.value
        val controls = coordinator.controls.value
        val header = Header.Builder()
            .setTitle(L10n.tr("Voice"))
            .setStartHeaderAction(if (isRoot) Action.APP_ICON else Action.BACK)
        if (!state.isInConversation) {
            header.addEndHeaderAction(
                Action.Builder()
                    .setIcon(CarIcons.of(carContext, R.drawable.ic_car_chat))
                    .setOnClickListener { showChats() }
                    .build(),
            )
        }
        val template = MessageTemplate.Builder(state.titleVariants(controls).first())
            .setHeader(header.build())
            .setIcon(CarIcon.Builder(IconCompat.createWithBitmap(CarVoiceArtwork.image(state))).build())
        for (button in CarVoiceButton.buttons(state, controls)) {
            template.addAction(
                Action.Builder()
                    .setTitle(button.title)
                    .setIcon(CarIcons.of(carContext, CarIcons.button(button)))
                    .setOnClickListener { press(button) }
                    .build(),
            )
        }
        return template.build()
    }

    private fun press(button: CarVoiceButton) = when (button) {
        CarVoiceButton.LISTEN -> coordinator.listen()
        CarVoiceButton.NEW_CHAT -> coordinator.startNewChat()
        CarVoiceButton.MUTE, CarVoiceButton.RESUME -> coordinator.toggleMicrophone()
        CarVoiceButton.END -> coordinator.end()
    }

    /** The list under the voice screen, or the one it came from. */
    private fun showChats() {
        if (isRoot) screenManager.push(CarChatsScreen(carContext, coordinator, isRoot = false)) else finish()
    }

    companion object {
        const val MARKER = "voice"
    }
}

/**
 * The chat list (iOS CarPlay Chats): New voice chat, then pinned chats, then
 * recent ones, as messaging conversations. The car reads a chat's latest words
 * and takes a voice reply to it; its Listen button starts Voice in that chat.
 */
class CarChatsScreen(
    carContext: CarContext,
    private val coordinator: CarVoiceCoordinator,
    private val isRoot: Boolean,
) : Screen(carContext) {
    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                coordinator.loadChats()
            }
        })
        refreshWhileShown(combine(coordinator.chats, coordinator.isLoadingChats) { chats, loading -> chats to loading })
    }

    override fun onGetTemplate(): Template {
        // The New voice chat row takes one of the rows the car allows.
        val limit = runCatching {
            carContext.getCarService(ConstraintManager::class.java).getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
        }.getOrDefault(CarBrowse.MAXIMUM_CHATS + 1)
        val chats = coordinator.chats.value
        val shown = chats.all.take(maxOf(0, minOf(CarBrowse.MAXIMUM_CHATS, limit - 2)))
        val items = ItemList.Builder()
        items.addItem(
            Row.Builder()
                .setTitle(L10n.tr("New voice chat"))
                .setImage(CarIcons.of(carContext, R.drawable.ic_car_new_chat))
                .setOnClickListener {
                    coordinator.startNewChat()
                    showVoice()
                }
                .build(),
        )
        val self = Person.Builder().setName(L10n.tr("You")).setKey(SELF_KEY).build()
        val hermes = Person.Builder().setName("Hermes").setKey(HERMES_KEY).build()
        for (row in shown) items.addItem(conversation(row, self, hermes))
        CarBrowse.placeholder(chats, coordinator.isConnected, coordinator.isLoadingChats.value)?.let { placeholder ->
            items.addItem(Row.Builder().setTitle(placeholder).build())
        }
        val header = Header.Builder()
            .setTitle(L10n.tr("Chats"))
            .setStartHeaderAction(if (isRoot) Action.APP_ICON else Action.BACK)
            .build()
        return ListTemplate.Builder()
            .setHeader(header)
            .setSingleList(items.build())
            .build()
    }

    private fun conversation(row: CarChatRow, self: Person, hermes: Person): ConversationItem {
        val message = CarMessage.Builder()
            .setSender(hermes)
            .setBody(CarText.create(row.preview))
            .setReceivedTimeEpochMillis(row.lastActiveMs ?: System.currentTimeMillis())
            .setRead(!row.unread)
            .build()
        return ConversationItem.Builder()
            .setId(row.sessionId)
            .setTitle(CarText.create(row.title))
            .setIcon(CarIcons.chat(carContext, row.kind))
            .setSelf(self)
            .setMessages(listOf(message))
            .setConversationCallback(object : ConversationCallback {
                override fun onMarkAsRead() = Unit

                override fun onTextReply(replyText: String) {
                    coordinator.reply(row, replyText)
                }
            })
            .addAction(
                Action.Builder()
                    .setTitle(L10n.tr("Listen"))
                    .setIcon(CarIcons.of(carContext, R.drawable.ic_car_mic))
                    .setOnClickListener {
                        coordinator.openChat(row)
                        showVoice()
                    }
                    .build(),
            )
            .build()
    }

    /** The voice screen over the list, or back to the one the list came from. */
    private fun showVoice() {
        if (isRoot) screenManager.push(CarVoiceScreen(carContext, coordinator, isRoot = false)) else finish()
    }

    private companion object {
        const val SELF_KEY = "conduit.self"
        const val HERMES_KEY = "conduit.hermes"
    }
}

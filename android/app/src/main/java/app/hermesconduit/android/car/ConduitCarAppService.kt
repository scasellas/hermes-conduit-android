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
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SectionedItemList
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
 * screen for the profile's voice mode, the chat list, and under More the
 * Voice Jobs, Shortcuts and Voice options. Conduit's MessagingStyle
 * notifications keep reading replies aloud next to it.
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
        coordinator.onChatsWanted = { showChats(coordinator) }
        // A conversation already running (on the phone, say) is shown rather than a list.
        if (CarPreferences.choosesChatFirst(carContext.appSettings) && !coordinator.hasRunningConversation()) {
            return CarChatsScreen(carContext, coordinator, isRoot = true)
        }
        coordinator.establish()
        return CarVoiceScreen(carContext, coordinator, isRoot = true)
    }

    /** Back to the chat list, or onto it, after End with "Choose a chat first" on. */
    private fun showChats(coordinator: CarVoiceCoordinator) {
        val screens = carContext.getCarService(androidx.car.app.ScreenManager::class.java)
        if (screens.top is CarChatsScreen) return
        if (screens.screenStack.any { it is CarChatsScreen }) {
            screens.popTo(CarChatsScreen.MARKER)
        } else {
            screens.push(CarChatsScreen(carContext, coordinator, isRoot = false))
        }
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
        CarVoiceButton.UNMUTE, CarVoiceButton.RESUME -> R.drawable.ic_car_mic_off
        CarVoiceButton.END -> R.drawable.ic_car_end
    }

    fun job(kind: CarJobKind): Int = when (kind) {
        CarJobKind.RUNNING -> R.drawable.ic_car_hourglass
        CarJobKind.WAITING -> R.drawable.ic_car_question
        CarJobKind.DONE -> R.drawable.ic_car_done
        CarJobKind.FAILED -> R.drawable.ic_car_warning
        CarJobKind.CANCELLED -> R.drawable.ic_car_cancelled
    }

    fun mode(mode: CarVoiceMode): Int = when (mode) {
        CarVoiceMode.CLASSIC -> R.drawable.ic_car_mic
        CarVoiceMode.GEMINI_LIVE -> R.drawable.ic_car_sparkles
        CarVoiceMode.GPT_LIVE -> R.drawable.ic_car_voice
        CarVoiceMode.GROK_LIVE -> R.drawable.ic_car_bolt
    }

    /** Grid icons, given out in order so shortcuts are told apart at a glance (iOS `shortcutSymbols`). */
    private val shortcuts = intArrayOf(
        R.drawable.ic_car_sparkles, R.drawable.ic_car_sun, R.drawable.ic_car_calendar, R.drawable.ic_car_mail,
        R.drawable.ic_car_job, R.drawable.ic_car_news, R.drawable.ic_car_home, R.drawable.ic_car_bolt,
    )

    fun shortcut(index: Int): Int = shortcuts[index % shortcuts.size]
}

/** Back to the voice screen under the lists, before Voice starts or after an option is picked. */
internal fun Screen.returnToVoiceScreen() {
    if (screenManager.screenStack.any { it is CarVoiceScreen }) screenManager.popTo(CarVoiceScreen.MARKER)
}

/** A row standing in for an empty list, with nothing to tap. */
private fun placeholderRow(title: String, text: String? = null): Row =
    Row.Builder().setTitle(title).apply { if (text != null) addText(text) }.build()

/** How many rows the car shows of a list while driving. */
private fun listLimit(carContext: CarContext): Int = runCatching {
    carContext.getCarService(ConstraintManager::class.java).getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
}.getOrDefault(CarBrowse.MAXIMUM_CHATS + 1)

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
        // Chats and More only at Ready and Error: while Voice runs, the voice screen stays up.
        if (!state.isInConversation) {
            header.addEndHeaderAction(
                Action.Builder()
                    .setIcon(CarIcons.of(carContext, R.drawable.ic_car_chat))
                    .setOnClickListener { showChats() }
                    .build(),
            )
            header.addEndHeaderAction(
                Action.Builder()
                    .setIcon(CarIcons.of(carContext, R.drawable.ic_car_more))
                    .setOnClickListener { screenManager.push(CarMoreScreen(carContext, coordinator)) }
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
        CarVoiceButton.MUTE, CarVoiceButton.UNMUTE, CarVoiceButton.RESUME -> coordinator.toggleMicrophone()
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
        marker = MARKER
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
                    coordinator.startNewVoiceChat()
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
                override fun onMarkAsRead() {
                    coordinator.markRead(row)
                }

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

    companion object {
        const val MARKER = "chats"
        private const val SELF_KEY = "conduit.self"
        private const val HERMES_KEY = "conduit.hermes"
    }
}

/**
 * More, from the voice screen's header (iOS CarPlay More): Voice Jobs,
 * Shortcuts and the Voice options, each one level further down.
 */
class CarMoreScreen(carContext: CarContext, private val coordinator: CarVoiceCoordinator) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val items = ItemList.Builder()
        fun add(title: String, icon: Int, open: () -> Screen) {
            items.addItem(
                GridItem.Builder()
                    .setTitle(title)
                    .setImage(CarIcons.of(carContext, icon))
                    .setOnClickListener { screenManager.push(open()) }
                    .build(),
            )
        }
        add(L10n.tr("Jobs"), R.drawable.ic_car_job) { CarJobsScreen(carContext, coordinator) }
        add(L10n.tr("Shortcuts"), R.drawable.ic_car_bolt) { CarShortcutsScreen(carContext, coordinator) }
        add(L10n.tr("Voice"), R.drawable.ic_car_voice) { CarVoiceOptionsScreen(carContext, coordinator) }
        return GridTemplate.Builder()
            .setHeader(Header.Builder().setTitle(L10n.tr("More")).setStartHeaderAction(Action.BACK).build())
            .setSingleList(items.build())
            .build()
    }
}

/**
 * Background Voice Jobs, newest first, with their status (iOS CarPlay Voice
 * Jobs). A settled job is heard again in the conversation a tap opens; rows
 * never carry a reply, which is always spoken.
 */
class CarJobsScreen(carContext: CarContext, private val coordinator: CarVoiceCoordinator) : Screen(carContext) {
    init {
        refreshWhileShown(coordinator.jobs)
    }

    override fun onGetTemplate(): Template {
        val rows = coordinator.jobs.value
        val items = ItemList.Builder()
        for (row in rows.take(listLimit(carContext))) {
            val item = Row.Builder()
                .setTitle(row.title)
                .addText(row.status)
                .setImage(CarIcons.of(carContext, CarIcons.job(row.kind)))
            if (row.canReplay) {
                item.setOnClickListener {
                    coordinator.replayJob(row)
                    returnToVoiceScreen()
                }
            }
            items.addItem(item.build())
        }
        if (rows.isEmpty()) items.addItem(placeholderRow(L10n.tr("No Voice Jobs"), L10n.tr("Start one from a shortcut or by asking during a call.")))
        return ListTemplate.Builder()
            .setHeader(Header.Builder().setTitle(L10n.tr("Voice Jobs")).setStartHeaderAction(Action.BACK).build())
            .setSingleList(items.build())
            .build()
    }
}

/**
 * The driver's shortcuts as a grid (iOS CarPlay Shortcuts): one tap starts
 * the prompt as a Voice Job and opens a conversation, which speaks the
 * result when the job is done. Added in Settings > Android Auto on the phone.
 */
class CarShortcutsScreen(carContext: CarContext, private val coordinator: CarVoiceCoordinator) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val header = Header.Builder().setTitle(L10n.tr("Shortcuts")).setStartHeaderAction(Action.BACK).build()
        val shortcuts = coordinator.shortcuts()
        if (shortcuts.isEmpty()) {
            val empty = placeholderRow(L10n.tr("No shortcuts yet"), L10n.tr("Add them on your phone in Settings, Voice."))
            return ListTemplate.Builder().setHeader(header).setSingleList(ItemList.Builder().addItem(empty).build()).build()
        }
        val limit = runCatching {
            carContext.getCarService(ConstraintManager::class.java).getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_GRID)
        }.getOrDefault(CarPreferences.MAXIMUM_SHORTCUTS)
        val items = ItemList.Builder()
        shortcuts.take(minOf(limit, CarPreferences.MAXIMUM_SHORTCUTS)).forEachIndexed { index, shortcut ->
            items.addItem(
                GridItem.Builder()
                    .setTitle(shortcut.title)
                    .setImage(CarIcons.of(carContext, CarIcons.shortcut(index)))
                    .setOnClickListener {
                        coordinator.runShortcut(shortcut)
                        returnToVoiceScreen()
                    }
                    .build(),
            )
        }
        return GridTemplate.Builder().setHeader(header).setSingleList(items.build()).build()
    }
}

/**
 * The voice mode and the agent (iOS CarPlay Voice): picking one ends the
 * running conversation, as switching on the phone does, and goes back to the
 * voice screen. The agent section shows only with more than one profile.
 */
class CarVoiceOptionsScreen(carContext: CarContext, private val coordinator: CarVoiceCoordinator) : Screen(carContext) {
    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                coordinator.loadAgents()
            }
        })
        refreshWhileShown(coordinator.agents)
    }

    override fun onGetTemplate(): Template {
        val modes = ItemList.Builder()
        for (row in coordinator.modeRows()) {
            val icon = if (row.isSelected) R.drawable.ic_car_check else CarIcons.mode(row.mode)
            modes.addItem(optionRow(row.title, icon) { coordinator.selectVoiceMode(row.mode) })
        }
        val template = ListTemplate.Builder()
            .setHeader(Header.Builder().setTitle(L10n.tr("Voice")).setStartHeaderAction(Action.BACK).build())
            .addSectionedList(SectionedItemList.create(modes.build(), L10n.tr("Voice mode")))
        val agents = coordinator.agentRows()
        if (agents.isNotEmpty()) {
            val list = ItemList.Builder()
            for (row in agents.take(listLimit(carContext))) {
                val icon = if (row.isSelected) R.drawable.ic_car_check else R.drawable.ic_car_person
                list.addItem(optionRow(row.title, icon) { coordinator.selectAgent(row.id) })
            }
            template.addSectionedList(SectionedItemList.create(list.build(), L10n.tr("Agent")))
        }
        return template.build()
    }

    /**
     * One choice. The car's lists take one selectable list per screen, so the
     * one in use shows a check mark in place of its icon (iOS: a check beside it).
     */
    private fun optionRow(title: String, icon: Int, select: () -> Unit): Row =
        Row.Builder()
            .setTitle(title)
            .setImage(CarIcons.of(carContext, icon))
            .setOnClickListener {
                select()
                returnToVoiceScreen()
            }
            .build()
}

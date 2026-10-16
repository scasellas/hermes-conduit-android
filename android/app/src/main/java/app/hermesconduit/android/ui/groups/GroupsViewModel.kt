package app.hermesconduit.android.ui.groups

import android.app.Application
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.hermes
import app.hermesconduit.android.model.Attachment
import app.hermesconduit.android.model.BotMentions
import app.hermesconduit.android.model.BotProfile
import app.hermesconduit.android.model.GatewayErrors
import app.hermesconduit.android.model.GroupCapabilities
import app.hermesconduit.android.model.GroupCapabilitiesDecoder
import app.hermesconduit.android.model.GroupDecoders
import app.hermesconduit.android.model.GroupDriverStatus
import app.hermesconduit.android.model.GroupGatewayEvents
import app.hermesconduit.android.model.GroupLogPage
import app.hermesconduit.android.model.GroupMember
import app.hermesconduit.android.model.GroupPendingApproval
import app.hermesconduit.android.model.GroupRoom
import app.hermesconduit.android.model.GroupRoomFiles
import app.hermesconduit.android.model.GroupRoomOutbox
import app.hermesconduit.android.model.GroupRoomPictures
import app.hermesconduit.android.model.GroupRoomReplay
import app.hermesconduit.android.model.GroupRoomTurns
import app.hermesconduit.android.model.GroupWire
import app.hermesconduit.android.model.NotifierPluginCapabilities
import app.hermesconduit.android.model.bool
import app.hermesconduit.android.model.exactInt
import app.hermesconduit.android.model.obj
import app.hermesconduit.android.net.GatewayClient
import app.hermesconduit.android.ui.bots.BotRoster
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64

/** Lifecycle of the Group Chat capability probe (`groups.capabilities`). */
sealed interface GroupChatPhase {
    data object Idle : GroupChatPhase
    data object Loading : GroupChatPhase
    data object Available : GroupChatPhase
    data object GatewayUnsupported : GroupChatPhase
    data class Failed(val message: String) : GroupChatPhase
}

data class GroupsState(
    val phase: GroupChatPhase = GroupChatPhase.Idle,
    val capabilities: GroupCapabilities? = null,
    val rooms: List<GroupRoom> = emptyList(),
    /** Plugin capabilities (`room-files`, `room-images`); null when unknown. */
    val pluginCapabilities: Set<String>? = null,
    val pictures: Map<String, ImageBitmap> = emptyMap(),
    val error: String? = null,
) {
    val canAttachFiles: Boolean get() = pluginCapabilities?.contains(GroupRoomFiles.CAPABILITY) == true
    val canSetPictures: Boolean get() = pluginCapabilities?.contains(GroupRoomPictures.CAPABILITY) == true
    fun supports(method: String) = capabilities?.supports(method) == true
}

sealed interface RoomApprovalAnswer {
    data object Submitting : RoomApprovalAnswer
    data object Sent : RoomApprovalAnswer
    data class Failed(val message: String) : RoomApprovalAnswer
}

/** The open hosted room. A room is NOT a session: it never touches the chat state. */
data class RoomSurface(
    val roomId: String,
    val room: GroupRoom? = null,
    val replay: GroupRoomReplay = GroupRoomReplay(roomId),
    val driver: GroupDriverStatus? = null,
    val approvalAnswers: Map<String, RoomApprovalAnswer> = emptyMap(),
    val sendInFlight: Boolean = false,
    val pending: GroupRoomOutbox.Pending? = null,
    val uploading: Boolean = false,
    val loading: Boolean = true,
    val error: String? = null,
    /** Set once the room was disbanded or vanished: the screen closes. */
    val gone: Boolean = false,
) {
    val members: List<GroupMember> get() = room?.members.orEmpty()
    val visibleEvents get() = replay.events.filter(GroupRoomTurns::isVisible)

    /** The member whose turn the room is running; an idle or blocked driver names nobody. */
    val responder: GroupMember?
        get() {
            driver?.let { if (!it.working || it.blocked) return null }
            return GroupRoomTurns.pendingResponder(replay.events, members)
        }
}

/**
 * Hosted Group Chats (port of iOS AppState §Group Chats): capability probe,
 * room list, the open room's replay + 3 s `groups.log` polling, idempotent
 * sends, stop, approvals, disband, rename, pictures and room files.
 */
class GroupsViewModel(application: Application) : AndroidViewModel(application) {
    private val hermes = application.hermes

    private val _state = MutableStateFlow(GroupsState())
    val state: StateFlow<GroupsState> = _state

    private val _room = MutableStateFlow<RoomSurface?>(null)
    val room: StateFlow<RoomSurface?> = _room

    val bots = BotRoster.bots
    val botAvatars = BotRoster.avatars
    val desktopGroups = BotRoster.desktopGroups

    private var dashboardId: String? = null
    private var epoch = 0
    private var pollJob: Job? = null
    @Volatile private var foreground = true
    private var outbox = GroupRoomOutbox()
    private val parkedOutboxes = mutableMapOf<String, GroupRoomOutbox>()
    private val drafts = mutableMapOf<String, String>()
    private val inFlightSends = mutableMapOf<String, Any>()
    private val pictureData = mutableMapOf<String, ByteArray>()
    private val pictureRevisions = mutableMapOf<String, String>()

    init {
        viewModelScope.launch {
            hermes.rawEvents.collect { params ->
                val open = _room.value ?: return@collect
                if (GroupGatewayEvents.roomId(params) == open.roomId) pollOnce(epoch)
            }
        }
        viewModelScope.launch { hermes.connected.collect { if (_state.value.phase != GroupChatPhase.Idle) refreshSupport() } }
    }

    private val connected: Boolean get() = hermes.gateway?.state?.value == GatewayClient.State.CONNECTED

    private fun message(e: Throwable) = e.message ?: L10n.tr("Something went wrong")

    private fun fence() {
        val id = hermes.connection.value?.id
        if (id != dashboardId) {
            dashboardId = id
            closeRoom()
            _state.value = GroupsState()
            parkedOutboxes.clear()
            drafts.clear()
            pictureData.clear()
            pictureRevisions.clear()
        }
    }

    // region Rooms list

    fun refresh() = viewModelScope.launch {
        launch { BotRoster.refresh(hermes) }
        refreshSupport()
    }

    suspend fun refreshSupport() {
        fence()
        if (!connected) {
            _state.update { it.copy(phase = GroupChatPhase.Failed(L10n.tr("Connect to Hermes to open group chats."))) }
            return
        }
        if (_state.value.phase !is GroupChatPhase.Available) _state.update { it.copy(phase = GroupChatPhase.Loading) }
        try {
            val capabilities = GroupCapabilitiesDecoder.decode(hermes.rpc("groups.capabilities", buildJsonObject {}))
                ?: error(L10n.tr("Hermes sent a reply Conduit didn't understand. Updating Hermes and Conduit usually fixes this."))
            if (!capabilities.foundationSupported) {
                _state.update { it.copy(capabilities = capabilities, phase = GroupChatPhase.GatewayUnsupported, rooms = emptyList()) }
                return
            }
            _state.update { it.copy(capabilities = capabilities, phase = GroupChatPhase.Available) }
            refreshPluginCapabilities()
            refreshRooms()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (GatewayErrors.isMissingMethod(e)) {
                _state.update { it.copy(capabilities = null, phase = GroupChatPhase.GatewayUnsupported, rooms = emptyList()) }
            } else if (_state.value.phase !is GroupChatPhase.Available) {
                _state.update { it.copy(phase = GroupChatPhase.Failed(message(e))) }
            }
        }
    }

    private suspend fun refreshPluginCapabilities() {
        val caps = runCatching { hermes.rest { it.getJson(NotifierPluginCapabilities.PATH) } }.getOrNull()?.let(NotifierPluginCapabilities::decode)
        _state.update { it.copy(pluginCapabilities = caps) }
    }

    suspend fun refreshRooms() {
        if (_state.value.phase !is GroupChatPhase.Available || !connected) return
        try {
            val (rooms, nextOffset) = GroupDecoders.rooms(hermes.rpc("groups.list", buildJsonObject { put("include_disbanded", false) }))
                ?: return
            val live = rooms.filter { !it.isDisbanded }
            _state.update { it.copy(rooms = live) }
            // Only a complete list proves a room is gone.
            if (nextOffset == null) {
                val ids = live.map { it.roomId }.toSet()
                parkedOutboxes.keys.retainAll(ids)
                drafts.keys.retainAll(ids)
            }
            refreshPictures()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Keep the last known room list.
        }
    }

    private suspend fun refreshPictures() {
        if (!_state.value.canSetPictures) return
        val listed = runCatching { hermes.rest { it.getJson(GroupRoomPictures.LIST_PATH) } }.getOrNull() ?: return
        val revisions = GroupRoomPictures.revisions(listed) ?: return
        pictureData.keys.retainAll(revisions.keys)
        pictureRevisions.keys.retainAll(revisions.keys)
        val live = _state.value.rooms.map { it.roomId }.toSet()
        for ((roomId, revision) in revisions) {
            if (pictureRevisions[roomId] == revision || roomId !in live) continue
            val path = GroupRoomPictures.path(roomId) ?: continue
            val answer = runCatching { hermes.rest { it.getJson(path) } }.getOrNull() ?: continue
            val (data, rev) = GroupRoomPictures.picture(answer) ?: continue
            pictureData[roomId] = data
            pictureRevisions[roomId] = rev
        }
        publishPictures()
    }

    private suspend fun publishPictures() {
        val desktop = BotRoster.desktopGroups.value.mapNotNull { g -> g.roomId?.let { id -> g.imageData?.let { id to it } } }.toMap()
        val merged = desktop + pictureData
        val images = withContext(Dispatchers.Default) {
            merged.mapNotNull { (id, bytes) -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()?.let { id to it } }.toMap()
        }
        _state.update { it.copy(pictures = images) }
    }

    fun hasCustomPicture(roomId: String) = pictureData.containsKey(roomId)

    // endregion

    // region Create

    /**
     * Creates a hosted room from 2–6 bots. [roomId] is the client-minted
     * identity and create idempotency key, owned by the create sheet.
     */
    suspend fun createRoom(name: String, bots: List<BotProfile>, roomId: String): GroupRoom? {
        val trimmed = name.trim()
        if (!connected || _state.value.capabilities?.foundationSupported != true) {
            _state.update { it.copy(error = L10n.tr("Connect to Hermes to create a group chat.")) }
            return null
        }
        if (bots.size !in 2..6) {
            _state.update { it.copy(error = L10n.tr("Pick 2 to 6 bots for a group chat.")) }
            return null
        }
        if (trimmed.isEmpty()) return null
        _state.update { it.copy(error = null) }
        val used = mutableSetOf("all", "everyone", "user")
        val members = buildJsonArray {
            for (bot in bots) {
                var handle = BotMentions.mentionTag(bot)
                if (handle.lowercase() in used) handle = BotMentions.handle(bot)
                if (!BotMentions.isValidMentionToken(handle) || handle.lowercase() in used) {
                    _state.update { it.copy(error = L10n.tr("Two of these bots would claim the same @handle. Rename one bot's title, then try again.")) }
                    return null
                }
                used += handle.lowercase()
                add(buildJsonObject {
                    put("member_id", bot.name)
                    put("profile", bot.name)
                    put("handle", handle)
                    put("display_name", bot.displayLabel)
                })
            }
        }
        return try {
            val result = hermes.rpc("groups.create", buildJsonObject { put("room_id", roomId); put("name", trimmed); put("members", members) })
            val room = GroupDecoders.room(result.obj?.get("room")) ?: error(L10n.tr("Hermes sent a reply Conduit didn't understand. Updating Hermes and Conduit usually fixes this."))
            refreshRooms()
            room
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(error = L10n.tr("Could not create the group chat: %s", message(e))) }
            null
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    // endregion

    // region Open room

    private val window: Int get() = minOf(200, _state.value.capabilities?.maxLogLimit ?: 500)

    private suspend fun groupsState(roomId: String): Pair<GroupRoom, GroupDriverStatus?> {
        val result = hermes.rpc("groups.state", buildJsonObject { put("room_id", roomId); put("include_disbanded", true) }).obj
        val room = GroupDecoders.room(result?.get("room")) ?: error(L10n.tr("Hermes sent a reply Conduit didn't understand. Updating Hermes and Conduit usually fixes this."))
        return room to GroupDecoders.driverStatus(result?.get("driver_status"))
    }

    private suspend fun groupsLog(roomId: String, sinceSeq: Int, limit: Int? = null): GroupLogPage {
        val result = hermes.rpc(
            "groups.log",
            buildJsonObject {
                put("room_id", roomId)
                put("since_seq", sinceSeq)
                put("include_disbanded", true)
                if (limit != null) put("limit", limit)
            },
        )
        return GroupDecoders.logPage(result) ?: error(L10n.tr("Hermes sent a reply Conduit didn't understand. Updating Hermes and Conduit usually fixes this."))
    }

    fun openRoom(roomId: String) {
        if (_room.value?.roomId == roomId && !(_room.value?.gone ?: false)) return
        parkActiveSend()
        epoch++
        val current = epoch
        pollJob?.cancel()
        outbox = parkedOutboxes.remove(roomId) ?: GroupRoomOutbox()
        _room.value = RoomSurface(
            roomId = roomId,
            room = _state.value.rooms.firstOrNull { it.roomId == roomId },
            pending = outbox.pending,
            sendInFlight = inFlightSends.containsKey(roomId),
        )
        viewModelScope.launch {
            if (_state.value.phase !is GroupChatPhase.Available) refreshSupport()
            if (current != epoch) return@launch
            try {
                val (room, driver) = groupsState(roomId)
                if (current != epoch) return@launch
                if (room.isDisbanded) return@launch closeGone()
                _room.update { it?.copy(room = room, driver = driver) }
                val start = maxOf(0, (room.latestSeq ?: 0) - window)
                val page = groupsLog(roomId, start, window)
                if (current != epoch) return@launch
                val replay = GroupRoomReplay(roomId).adoptInitialTail(page)
                _room.update { it?.copy(replay = replay, loading = false) }
                settle(replay.events)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (current != epoch) return@launch
                if (GatewayErrors.isRoomNotFound(e)) return@launch closeGone()
                _room.update { it?.copy(loading = false, error = L10n.tr("Could not load this group chat: %s", message(e))) }
            }
            startPolling()
        }
    }

    fun setForeground(value: Boolean) {
        foreground = value
    }

    private fun startPolling() {
        pollJob?.cancel()
        val current = epoch
        pollJob = viewModelScope.launch {
            while (isActive && current == epoch) {
                delay(3_000)
                if (current != epoch) return@launch
                pollOnce(current)
            }
        }
    }

    private suspend fun pollOnce(current: Int) {
        if (!foreground || !connected || current != epoch) return
        val surface = _room.value ?: return
        val roomId = surface.roomId
        try {
            val page = groupsLog(roomId, surface.replay.sinceSeq)
            if (current != epoch) return
            val replay = _room.value?.replay ?: return
            if (page.events.firstOrNull()?.let { it.roomId != roomId } == true) return
            val fresh = page.events.filter { it.roomId == roomId && it.seq > replay.cursor }
            if (replay.hasGap || replay.pageSkipsAhead(page)) {
                resync(current)
                return
            }
            if (fresh.isNotEmpty()) {
                _room.update { it?.copy(replay = it.replay.adopt(page)) }
                settle(fresh)
            }
            val cursor = _room.value?.replay?.cursor ?: return
            if (cursor >= page.latestSeq || fresh.isEmpty()) {
                val (room, driver) = groupsState(roomId)
                if (current != epoch) return
                if (room.isDisbanded) return closeGone()
                _room.update { s -> s?.copy(room = room, driver = driver, approvalAnswers = sweep(s.approvalAnswers, driver)) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (current == epoch && GatewayErrors.isRoomNotFound(e)) closeGone()
        }
    }

    private suspend fun resync(current: Int) {
        val roomId = _room.value?.roomId ?: return
        try {
            val (room, driver) = groupsState(roomId)
            if (current != epoch) return
            if (room.isDisbanded) return closeGone()
            val start = maxOf(0, (room.latestSeq ?: 0) - window)
            val tail = groupsLog(roomId, start, window)
            if (current != epoch) return
            val replay = GroupRoomReplay(roomId).replaceAll(tail)
            _room.update { it?.copy(room = room, driver = driver, replay = replay) }
            settle(replay.events)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The next poll's gap flag retries.
        }
    }

    /** Forget failures whose approval the gateway no longer lists. */
    private fun sweep(answers: Map<String, RoomApprovalAnswer>, driver: GroupDriverStatus?): Map<String, RoomApprovalAnswer> {
        val listed = driver?.pendingApprovals.orEmpty().map { it.id }.toSet()
        return answers.filter { (id, answer) -> answer !is RoomApprovalAnswer.Failed || id in listed }
    }

    private fun settle(events: List<app.hermesconduit.android.model.GroupEvent>) {
        val settled = outbox.settle(events) ?: return
        outbox = settled
        _room.update { it?.copy(pending = null, error = null) }
    }

    private fun parkActiveSend() {
        val open = _room.value ?: return
        if (outbox.pending != null) parkedOutboxes[open.roomId] = outbox else parkedOutboxes.remove(open.roomId)
    }

    private suspend fun closeGone() {
        _room.value?.roomId?.let {
            parkedOutboxes.remove(it)
            drafts.remove(it)
        }
        epoch++
        pollJob?.cancel()
        outbox = GroupRoomOutbox()
        _room.update { it?.copy(gone = true) }
        refreshRooms()
    }

    /** Leaves the room view; a pending send is parked for the next open. */
    fun closeRoom() {
        parkActiveSend()
        epoch++
        pollJob?.cancel()
        pollJob = null
        outbox = GroupRoomOutbox()
        _room.value = null
        viewModelScope.launch { refreshRooms() }
    }

    fun draft(roomId: String): String = drafts[roomId].orEmpty()
    fun saveDraft(roomId: String, text: String) {
        if (text.isEmpty()) drafts.remove(roomId) else drafts[roomId] = text
    }

    // endregion

    // region Send

    /**
     * Sends one user message via `groups.send`. The event id is minted once per
     * logical message and reused on retry. Returns whether the send was
     * registered (the pending row now owns the text).
     */
    suspend fun send(rawText: String): Boolean {
        val text = rawText.trim()
        if (text.isEmpty()) return false
        val surface = _room.value ?: return false
        if (!connected || !_state.value.supports("groups.send") || surface.sendInFlight || surface.room?.isDisbanded == true) return false
        if (!outbox.accepts(text)) {
            _room.update { it?.copy(error = L10n.tr("Your previous message is still pending. Retry it from the room, or wait for it to deliver.")) }
            return false
        }
        val current = epoch
        val roomId = surface.roomId
        val (next, logical) = outbox.beginSend(text)
        outbox = next
        val token = Any()
        inFlightSends[roomId] = token
        _room.update { it?.copy(pending = logical, sendInFlight = true) }
        try {
            val result = hermes.rpc(
                "groups.send",
                buildJsonObject {
                    put("room_id", roomId)
                    put("event_id", logical.eventId)
                    put("payload", buildJsonObject { put("text", logical.text); put("thread_id", GroupWire.MAIN_THREAD_ID) })
                },
            ).obj
            val event = GroupDecoders.event(result?.get("event")) ?: error(L10n.tr("Hermes sent a reply Conduit didn't understand. Updating Hermes and Conduit usually fixes this."))
            val driverStarted = result?.get("driver_started").bool ?: false
            if (current != epoch || _room.value?.roomId != roomId) {
                // Left mid-send: the gateway accepted it, so settle whichever copy holds the key.
                if (event.roomId == roomId) {
                    if (_room.value?.roomId == roomId) {
                        outbox = outbox.accept(logical.eventId)
                        _room.update { it?.copy(pending = outbox.pending) }
                    } else {
                        parkedOutboxes[roomId]?.accept(logical.eventId)?.let { if (it.pending == null) parkedOutboxes.remove(roomId) else parkedOutboxes[roomId] = it }
                    }
                }
                return true
            }
            if (event.roomId != roomId) {
                _room.update { it?.copy(error = L10n.tr("Could not send to this group chat: %s", L10n.tr("Hermes sent a reply Conduit didn't understand. Updating Hermes and Conduit usually fixes this."))) }
                return true
            }
            val cursorBefore = _room.value?.replay?.cursor ?: 0
            outbox = outbox.accept(logical.eventId)
            _room.update { s ->
                s?.copy(
                    replay = s.replay.adopt(GroupLogPage(listOf(event), event.seq, maxOf(event.seq, s.replay.cursor), false, s.replay.authorityGatewayId, s.replay.authorityEpoch)),
                    pending = outbox.pending,
                    error = null,
                    driver = if (driverStarted) GroupDriverStatus(true, true, s.driver?.blocked ?: false, s.driver?.counts.orEmpty(), s.driver?.pendingActions.orEmpty()) else s.driver,
                )
            }
            if (event.seq > cursorBefore + 1) resync(current)
            refreshRooms()
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (current != epoch || outbox.pending?.eventId != logical.eventId) return true
            // Ambiguous outcome: keep the pending row (same event id) for a deduplicated retry.
            _room.update { it?.copy(error = L10n.tr("Could not send to this group chat: %s", message(e))) }
            return true
        } finally {
            if (inFlightSends[roomId] === token) {
                inFlightSends.remove(roomId)
                _room.update { if (it?.roomId == roomId) it.copy(sendInFlight = false) else it }
            }
        }
    }

    /** Retries the pending message with its same event id (the gateway deduplicates). */
    fun retrySend(text: String) {
        viewModelScope.launch { send(text) }
    }

    /** Uploads the files through the host plugin, then sends their references above the text. */
    suspend fun sendWithAttachments(text: String, attachments: List<Attachment>): Boolean {
        if (attachments.isEmpty()) return send(text)
        val roomId = _room.value?.roomId ?: return false
        _room.update { it?.copy(uploading = true) }
        val references = mutableListOf<String>()
        try {
            for (attachment in attachments) {
                val body = buildJsonObject { put("name", attachment.name); put("data_base64", attachment.base64) }
                val response = hermes.rest {
                    it.sendJson("POST", GroupRoomFiles.PATH, body, timeoutMs = GroupRoomFiles.timeoutMs(body.toString().length))
                }
                references += GroupRoomFiles.reference(response)
                    ?: error(L10n.tr("The host's answer couldn't be read. Update the Conduit plugin and try again."))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _room.update { it?.copy(uploading = false, error = L10n.tr("Attachment failed: %s", message(e))) }
            return false
        }
        _room.update { it?.copy(uploading = false) }
        if (_room.value?.roomId != roomId) return false
        return send(Attachment.promptText(text.trim(), references))
    }

    // endregion

    // region Room actions

    fun stop() {
        val surface = _room.value ?: return
        if (!connected || !_state.value.supports("groups.stop")) return
        val current = epoch
        viewModelScope.launch {
            try {
                hermes.rpc("groups.stop", buildJsonObject { put("room_id", surface.roomId) })
                pollOnce(current)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (current == epoch) _room.update { it?.copy(error = L10n.tr("Could not stop this group chat's work: %s", message(e))) }
            }
        }
    }

    fun pendingApprovals(surface: RoomSurface): List<GroupPendingApproval> {
        if (!_state.value.supports("groups.approve")) return emptyList()
        return surface.driver?.pendingApprovals.orEmpty().filter { surface.approvalAnswers[it.id] != RoomApprovalAnswer.Sent }
    }

    fun approve(approval: GroupPendingApproval, choice: String) {
        val surface = _room.value ?: return
        if (!connected || !_state.value.supports("groups.approve") || !approval.allows(choice)) return
        val answer = surface.approvalAnswers[approval.id]
        if (answer == RoomApprovalAnswer.Submitting || answer == RoomApprovalAnswer.Sent) return
        val current = epoch
        _room.update { it?.copy(approvalAnswers = it.approvalAnswers + (approval.id to RoomApprovalAnswer.Submitting)) }
        viewModelScope.launch {
            val outcome: RoomApprovalAnswer = try {
                val result = hermes.rpc(
                    "groups.approve",
                    buildJsonObject {
                        put("room_id", surface.roomId)
                        put("member_id", approval.memberId)
                        put("task_id", approval.taskId)
                        put("execution_generation", approval.executionGeneration)
                        put("choice", choice)
                        put("request_id", approval.requestId)
                    },
                )
                if (resolvedSomething(result)) RoomApprovalAnswer.Sent else RoomApprovalAnswer.Failed(L10n.tr("This approval is no longer active."))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RoomApprovalAnswer.Failed(message(e))
            }
            if (current != epoch) return@launch
            _room.update { it?.copy(approvalAnswers = it.approvalAnswers + (approval.id to outcome)) }
            pollOnce(current)
        }
    }

    /** A local member's receipt carries `resolved`; 0 means the request settled elsewhere. */
    private fun resolvedSomething(result: JsonElement): Boolean {
        val resolved = result.obj?.get("result").obj?.get("resolved") ?: return true
        return (resolved.exactInt() ?: 1) > 0
    }

    fun disband(onDone: () -> Unit) {
        val surface = _room.value ?: return
        if (!connected || !_state.value.supports("groups.disband")) return
        val current = epoch
        viewModelScope.launch {
            try {
                hermes.rpc("groups.disband", buildJsonObject { put("room_id", surface.roomId) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (current == epoch) _room.update { it?.copy(error = L10n.tr("Could not disband this group chat: %s", message(e))) }
                return@launch
            }
            closeGone()
            onDone()
        }
    }

    /** `groups.rename` (offered only when the gateway advertises it). */
    fun rename(name: String) {
        val surface = _room.value ?: return
        val trimmed = name.trim()
        if (trimmed.isEmpty() || !connected || !_state.value.supports("groups.rename")) return
        val current = epoch
        viewModelScope.launch {
            try {
                val result = hermes.rpc("groups.rename", buildJsonObject { put("room_id", surface.roomId); put("name", trimmed) })
                val room = GroupDecoders.room(result.obj?.get("room"))
                if (current != epoch) return@launch
                _room.update { s -> s?.copy(room = room ?: s.room?.copy(name = trimmed)) }
                pollOnce(current)
                refreshRooms()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (current == epoch) _room.update { it?.copy(error = L10n.tr("Could not rename this group chat: %s", message(e))) }
            }
        }
    }

    /** Sets the room's picture from picked image bytes, or removes it with null. */
    fun setPicture(roomId: String, imageBytes: ByteArray?) {
        val path = GroupRoomPictures.path(roomId)
        if (path == null || !connected) {
            _room.update { it?.copy(error = L10n.tr("Connect to Hermes to change the group picture.")) }
            return
        }
        viewModelScope.launch {
            try {
                if (imageBytes != null) {
                    val jpeg = withContext(Dispatchers.Default) {
                        BotRoster.squareImage(imageBytes, GroupRoomPictures.UPLOAD_EDGE, android.graphics.Bitmap.CompressFormat.JPEG, 82, android.graphics.Color.BLACK)
                    }
                    if (jpeg == null) {
                        _room.update { it?.copy(error = L10n.tr("That picture could not be used.")) }
                        return@launch
                    }
                    val body = buildJsonObject { put("data_base64", Base64.getEncoder().encodeToString(jpeg)) }
                    val answer = hermes.rest { it.sendJson("PUT", path, body, timeoutMs = GroupRoomPictures.UPLOAD_TIMEOUT_MS) }
                    val revision = GroupRoomPictures.storedRevision(answer)
                    if (revision == null) {
                        _room.update { it?.copy(error = L10n.tr("The host's answer couldn't be read. Update the Conduit plugin and try again.")) }
                        return@launch
                    }
                    pictureData[roomId] = jpeg
                    pictureRevisions[roomId] = revision
                } else {
                    hermes.rest { it.sendJson("DELETE", path) }
                    pictureData.remove(roomId)
                    pictureRevisions.remove(roomId)
                }
                publishPictures()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _room.update { it?.copy(error = L10n.tr("Could not change the group picture: %s", message(e))) }
            }
        }
    }

    fun dismissRoomError() = _room.update { it?.copy(error = null) }

    fun loadAvatar(bot: BotProfile) = viewModelScope.launch { BotRoster.loadAvatar(hermes, bot) }

    // endregion
}

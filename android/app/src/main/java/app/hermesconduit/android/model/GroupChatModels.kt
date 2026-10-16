package app.hermesconduit.android.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/**
 * Wire models for the hosted Group Chat JSON-RPC contract (port of iOS
 * `GroupChatModels.swift`, protocol version 2). Decoding is defensive:
 * unknown event kinds keep their raw string, malformed identity fields stay
 * raw, and a decode failure never fabricates room state.
 */
object GroupWire {
    const val PROTOCOL_VERSION = 2
    val requiredMethods = setOf("groups.list", "groups.create", "groups.state", "groups.send", "groups.log", "groups.disband")
    val optionalMethods = setOf("groups.stop", "groups.rename", "groups.approve", "groups.retry")
    /** The room composer's one stable thread. */
    const val MAIN_THREAD_ID = "main"
}

data class GroupCapabilities(
    val protocolVersion: Int,
    val driverReady: Boolean,
    val authorityGatewayId: String,
    val features: List<String>,
    val methods: Set<String>,
    val maxLogLimit: Int,
) {
    fun supports(method: String) = method in methods
    val foundationSupported: Boolean get() = methods.containsAll(GroupWire.requiredMethods)
}

object GroupCapabilitiesDecoder {
    fun decode(result: JsonElement?): GroupCapabilities? {
        val o = result.obj ?: return null
        val methods = o["methods"].arr?.mapNotNull { it.str }?.toSet().orEmpty()
        if (methods.isEmpty()) return null
        return GroupCapabilities(
            protocolVersion = o["protocol_version"].exactInt() ?: 0,
            driverReady = o["driver"].bool ?: false,
            authorityGatewayId = o["authority_gateway_id"].str.orEmpty(),
            features = o["features"].arr?.mapNotNull { it.str }.orEmpty(),
            methods = methods,
            maxLogLimit = o["max_log_limit"].exactInt() ?: 500,
        )
    }
}

data class GroupMember(
    val memberId: String? = null,
    val profile: String? = null,
    val handle: String? = null,
    val displayName: String? = null,
    val target: JsonObject? = null,
    val extra: JsonObject = JsonObject(emptyMap()),
    val previousNames: List<String> = emptyList(),
) {
    /** Member id, else profile, else handle. Display names are never identity. */
    val identityKey: String
        get() = listOf(memberId, profile, handle).firstNotNullOfOrNull { it?.trim()?.takeIf(String::isNotEmpty) }.orEmpty()
}

data class GroupActor(
    val kind: String,
    val id: String,
    val profile: String?,
    val displayName: String?,
    val connectionId: String?,
) {
    fun displayLabel(members: List<GroupMember>): String {
        val key = id.trim()
        if (kind == "member" && key.isNotEmpty()) {
            members.firstOrNull { it.memberId.equals(key, ignoreCase = true) || it.profile.equals(key, ignoreCase = true) }?.let { m ->
                val name = m.displayName ?: m.handle ?: m.profile ?: m.memberId
                if (!name.isNullOrEmpty()) return name
            }
        }
        if (!profile.isNullOrEmpty() && kind == "member") return profile
        return displayName.orEmpty()
    }
}

data class GroupEvent(
    val roomId: String,
    val seq: Int,
    val eventId: String,
    val kind: String,
    val actor: GroupActor,
    val authorityEpoch: Int?,
    val payload: JsonObject,
    val createdAt: Double,
) {
    val id: String get() = eventId.ifEmpty { "seq-$seq" }
    val isUserMessage: Boolean get() = kind == "message.user"
    val isMemberMessage: Boolean get() = kind == "message.member"
    val isChatMessage: Boolean get() = isUserMessage || isMemberMessage
    val messageText: String? get() = if (isChatMessage) payload["text"].str else null
    val threadId: String? get() = payload["thread_id"].str
}

data class GroupRoom(
    val roomId: String,
    val name: String,
    val members: List<GroupMember>,
    val authorityGatewayId: String = "",
    val authorityEpoch: Int = 1,
    val revision: Int = 0,
    val createdAt: Double = 0.0,
    val updatedAt: Double = 0.0,
    val disbandedAt: Double? = null,
    val latestSeq: Int? = null,
) {
    val isDisbanded: Boolean get() = disbandedAt != null
}

data class GroupPendingApproval(
    val memberId: String,
    val taskId: String,
    val executionGeneration: Int,
    val requestId: String,
    val command: String,
    val description: String,
    /** Rooms answer `once` or `deny` only. */
    val choices: List<String>,
) {
    val id: String get() = "$memberId|$taskId|$executionGeneration|$requestId"
    fun allows(choice: String) = choice in choices

    companion object {
        fun of(action: JsonObject): GroupPendingApproval? {
            if (action["kind"].str != "approval") return null
            val memberId = action["member_id"].str?.takeIf { it.isNotEmpty() } ?: return null
            val taskId = action["task_id"].str?.takeIf { it.isNotEmpty() } ?: return null
            val generation = action["execution_generation"].exactInt() ?: return null
            val approval = action["approval"].obj ?: JsonObject(emptyMap())
            val requestId = (action["request_id"].str ?: approval["request_id"].str)?.takeIf { it.isNotEmpty() } ?: return null
            val offered = approval["choices"].arr?.mapNotNull { it.str }?.filter { it == "once" || it == "deny" }.orEmpty()
            return GroupPendingApproval(
                memberId, taskId, generation, requestId,
                command = approval["command"].str.orEmpty(),
                description = approval["description"].str.orEmpty(),
                choices = offered.ifEmpty { listOf("once", "deny") },
            )
        }
    }
}

data class GroupDriverStatus(
    val running: Boolean,
    val working: Boolean,
    val blocked: Boolean,
    val counts: Map<String, Int>,
    val pendingActions: List<JsonObject>,
) {
    val pendingApprovals: List<GroupPendingApproval> get() = pendingActions.mapNotNull(GroupPendingApproval::of)
}

data class GroupLogPage(
    val events: List<GroupEvent>,
    val cursor: Int,
    val latestSeq: Int,
    val hasMore: Boolean,
    val authorityGatewayId: String,
    val authorityEpoch: Int,
)

data class GroupSendResult(val event: GroupEvent, val accepted: Boolean, val driverStarted: Boolean)

object GroupDecoders {
    private val knownMemberKeys = setOf("member_id", "profile", "handle", "display_name", "target", "previous_names")

    fun member(value: JsonElement?): GroupMember? {
        val o = value.obj ?: return null
        return GroupMember(
            memberId = o["member_id"].str,
            profile = o["profile"].str,
            handle = o["handle"].str,
            displayName = o["display_name"].str,
            target = o["target"].obj,
            extra = JsonObject(o.filterKeys { it !in knownMemberKeys }),
            previousNames = o["previous_names"].arr?.mapNotNull { it.str?.takeIf(String::isNotEmpty) }.orEmpty(),
        )
    }

    fun actor(value: JsonElement?): GroupActor {
        val o = value.obj ?: JsonObject(emptyMap())
        return GroupActor(o["kind"].str ?: "unknown", o["id"].str.orEmpty(), o["profile"].str, o["display_name"].str, o["connection_id"].str)
    }

    fun event(value: JsonElement?): GroupEvent? {
        val o = value.obj ?: return null
        val roomId = o["room_id"].str ?: return null
        val seq = o["seq"].exactInt()?.takeIf { it >= 1 } ?: return null
        return GroupEvent(
            roomId = roomId,
            seq = seq,
            eventId = o["event_id"].str.orEmpty(),
            kind = o["kind"].str ?: "unknown",
            actor = actor(o["actor"]),
            authorityEpoch = o["authority_epoch"].exactInt(),
            payload = o["payload"].obj ?: JsonObject(emptyMap()),
            createdAt = o["created_at"].double ?: 0.0,
        )
    }

    fun room(value: JsonElement?): GroupRoom? {
        val o = value.obj ?: return null
        val roomId = o["room_id"].str?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return GroupRoom(
            roomId = roomId,
            name = o["name"].str ?: roomId,
            members = o["members"].arr?.mapNotNull(::member).orEmpty(),
            authorityGatewayId = o["authority_gateway_id"].str.orEmpty(),
            authorityEpoch = o["authority_epoch"].exactInt() ?: 1,
            revision = o["revision"].exactInt() ?: 0,
            createdAt = o["created_at"].double ?: 0.0,
            updatedAt = o["updated_at"].double ?: 0.0,
            disbandedAt = o["disbanded_at"].double,
            latestSeq = o["latest_seq"].exactInt(),
        )
    }

    fun driverStatus(value: JsonElement?): GroupDriverStatus? {
        val o = value.obj ?: return null
        val counts = o["counts"].obj?.mapNotNull { (k, v) -> v.exactInt()?.let { k to it } }?.toMap().orEmpty()
        return GroupDriverStatus(
            running = o["running"].bool ?: false,
            working = o["working"].bool ?: false,
            blocked = o["blocked"].bool ?: false,
            counts = counts,
            pendingActions = o["pending_actions"].arr?.mapNotNull { it.obj }.orEmpty(),
        )
    }

    fun logPage(result: JsonElement?): GroupLogPage? {
        val o = result.obj ?: return null
        val latest = o["latest_seq"].exactInt() ?: return null
        val events = o["events"].arr?.mapNotNull(::event).orEmpty()
        val authority = o["authority"].obj ?: JsonObject(emptyMap())
        return GroupLogPage(
            events = events,
            cursor = o["cursor"].exactInt() ?: events.lastOrNull()?.seq ?: 0,
            latestSeq = latest,
            hasMore = o["has_more"].bool ?: false,
            authorityGatewayId = authority["gateway_id"].str.orEmpty(),
            authorityEpoch = authority["epoch"].exactInt() ?: 0,
        )
    }

    fun rooms(result: JsonElement?): Pair<List<GroupRoom>, Int?>? {
        val o = result.obj ?: return null
        val rows = o["rooms"].arr ?: return null
        return rows.mapNotNull(::room) to o["next_offset"].exactInt()
    }
}

/**
 * The room-log replay engine. The cursor advances only over accepted events;
 * replays are dropped by `seq`; a gap raises [hasGap] so the caller resyncs.
 */
data class GroupRoomReplay(
    val roomId: String,
    val events: List<GroupEvent> = emptyList(),
    val cursor: Int = 0,
    val hasGap: Boolean = false,
    val authorityGatewayId: String = "",
    val authorityEpoch: Int = 0,
) {
    val sinceSeq: Int get() = cursor
    val lastChatMessage: GroupEvent? get() = events.lastOrNull { it.isChatMessage }

    /** Adopts the initial bounded tail: its truncation point is never a gap. */
    fun adoptInitialTail(page: GroupLogPage): GroupRoomReplay {
        val incoming = page.events.filter { it.roomId == roomId }.sortedBy { it.seq }
        return copy(
            events = incoming,
            cursor = incoming.lastOrNull()?.seq ?: 0,
            hasGap = false,
            authorityGatewayId = page.authorityGatewayId.ifEmpty { authorityGatewayId },
            authorityEpoch = if (page.authorityGatewayId.isEmpty()) authorityEpoch else page.authorityEpoch,
        )
    }

    fun pageSkipsAhead(page: GroupLogPage): Boolean {
        val seqs = page.events.filter { it.roomId == roomId && it.seq > cursor }.map { it.seq }.toSortedSet()
        var expected = cursor + 1
        for (seq in seqs) {
            if (seq != expected) return true
            expected++
        }
        return false
    }

    fun adopt(page: GroupLogPage): GroupRoomReplay {
        val incoming = page.events.filter { it.roomId == roomId && it.seq > cursor }.sortedBy { it.seq }
        var cur = cursor
        var gap = hasGap
        val list = events.toMutableList()
        for (event in incoming) {
            if (event.seq <= cur) continue
            if (event.seq > cur + 1) gap = true
            list += event
            cur = event.seq
        }
        return copy(
            events = list,
            cursor = cur,
            hasGap = gap,
            authorityGatewayId = page.authorityGatewayId.ifEmpty { authorityGatewayId },
            authorityEpoch = if (page.authorityGatewayId.isEmpty()) authorityEpoch else page.authorityEpoch,
        )
    }

    fun replaceAll(page: GroupLogPage) = adoptInitialTail(page)
}

/** The room composer's retry-key keeper (`groups.send` is idempotent by client event id). */
data class GroupRoomOutbox(val pending: Pending? = null) {
    data class Pending(val text: String, val eventId: String)

    fun accepts(text: String) = pending == null || pending.text == text

    /** Begin (or re-begin) a send; a retry of the pending text keeps its id. */
    fun beginSend(text: String, mint: () -> String = ::mintEventId): Pair<GroupRoomOutbox, Pending> {
        pending?.let { return this to it }
        val created = Pending(text, mint())
        return GroupRoomOutbox(created) to created
    }

    fun accept(eventId: String) = if (pending?.eventId == eventId) GroupRoomOutbox() else this

    /** Settles when [events] carry the pending send's server-side twin. */
    fun settle(events: List<GroupEvent>): GroupRoomOutbox? {
        val p = pending ?: return null
        val serverId = serverEventId(p.eventId)
        return if (events.any { it.eventId == serverId }) GroupRoomOutbox() else null
    }

    companion object {
        fun mintEventId() = "conduit-${UUID.randomUUID().toString().lowercase()}"

        /** Upstream `user_event_id`: `user:` + SHA-256 hex of the trimmed client id. */
        fun serverEventId(clientEventId: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(clientEventId.trim().toByteArray())
            return "user:" + digest.joinToString("") { "%02x".format(it) }
        }
    }
}

object GroupCreateAttempt {
    data class Inputs(val name: String, val members: Set<String>)

    fun mintRoomId() = "conduit-${UUID.randomUUID().toString().lowercase()}"

    /** Same inputs reuse the id (create idempotency); edited inputs get a fresh one. */
    fun roomId(inputs: Inputs, current: String, attempted: Inputs?): String =
        if (attempted == null || attempted == inputs) current else mintRoomId()
}

/** Presentation-only @mention styling for room transcripts. */
object GroupRoomMentions {
    val mentionScan = Regex("(?<!\\S)@([a-z0-9][a-z0-9._:-]*)", RegexOption.IGNORE_CASE)

    enum class Kind { AGENT, BROADCAST, HUMAN }

    fun classify(token: String, members: List<GroupMember>): Kind? {
        val handle = token.lowercase()
        if (handle == "user") return Kind.HUMAN
        if (handle == "all" || handle == "everyone") return Kind.BROADCAST
        val collapsed = strip(handle)
        for (member in members) {
            val candidates = listOfNotNull(member.handle, member.memberId, member.profile, member.displayName) + member.previousNames
            for (candidate in candidates) {
                val lowered = candidate.lowercase()
                if (lowered == handle || lowered == collapsed) return Kind.AGENT
                if (handle in BotMentions.mentionNameForms(candidate)) return Kind.AGENT
                if (lowered.split(' ').firstOrNull { it.isNotEmpty() } == handle) return Kind.AGENT
            }
            val stripped = candidates.firstNotNullOfOrNull { strip(it).takeIf(String::isNotEmpty) }.orEmpty()
            if (stripped.isNotEmpty() && stripped == collapsed) return Kind.AGENT
        }
        return null
    }

    private fun strip(value: String) = value.replace(".", "").replace("-", "").replace("_", "").lowercase()
}

/** Turn-level presentation (port of the gateway's Discussion policy). */
object GroupRoomTurns {
    const val MAX_ROUNDS = 3
    const val MAX_MESSAGES = 10
    val terminalKinds = setOf("turn.settled", "turn.failed", "turn.cancelled")
    private val routingMention = Regex("@([A-Za-z0-9][A-Za-z0-9._:-]*)", RegexOption.IGNORE_CASE)

    fun isVisible(event: GroupEvent): Boolean = when (event.kind) {
        "turn.settled" -> event.payload["passed"].bool == true
        "turn.failed", "turn.cancelled" -> true
        else -> !event.kind.startsWith("turn.")
    }

    enum class RoomActivityOutcome { SETTLED, BOUNDED, OTHER }

    fun roomActivityOutcome(event: GroupEvent) = when (event.payload["status"].str) {
        "settled" -> RoomActivityOutcome.SETTLED
        "bounded" -> RoomActivityOutcome.BOUNDED
        else -> RoomActivityOutcome.OTHER
    }

    fun member(event: GroupEvent, members: List<GroupMember>): GroupMember? =
        event.payload["member_id"].str?.let { memberWithId(it, members) }

    /** Never empty: a nameless member reads as "A member". */
    fun displayName(member: GroupMember, fallback: String = "A member"): String =
        listOf(member.displayName, member.handle, member.profile, member.memberId)
            .firstNotNullOfOrNull { it?.trim()?.takeIf(String::isNotEmpty) } ?: fallback

    fun pendingResponder(events: List<GroupEvent>, members: List<GroupMember>): GroupMember? {
        if (members.isEmpty()) return null
        val discussion = pendingDiscussion(events) ?: return null
        val threadId = discussion.threadId.orEmpty()
        val committed = events.mapNotNull { if (it.kind == "turn.settled") it.payload["message_event_id"].str else null }.toSet()
        val threadMessages = events.filter { e ->
            e.threadId.orEmpty() == threadId && (e.isUserMessage || (e.isMemberMessage && e.eventId in committed))
        }
        val discussionMessages = threadMessages.filter { it.seq >= discussion.seq }
        val memberMessages = threadMessages.filter { it.isMemberMessage && it.payload["discussion_event_id"].str == discussion.eventId }
        if (memberMessages.size >= MAX_MESSAGES) return null

        val terminals = mutableSetOf<String>()
        for (e in events) {
            if (e.kind !in terminalKinds || e.payload["discussion_event_id"].str != discussion.eventId) continue
            val round = e.payload["round_index"].exactInt() ?: continue
            val memberId = e.payload["member_id"].str ?: continue
            terminals += "$round|${memberId.lowercase()}"
        }
        for (round in 0 until MAX_ROUNDS) {
            val responders = if (round == 0) resolveMentions(listOf(discussion.payload["text"].str.orEmpty()), members, defaultAll = true)
            else unaddressedMentions(discussionMessages, members)
            for (m in rotate(responders, round)) if ("$round|${routingId(m)}" !in terminals) return m
            val spoke = memberMessages.any { it.payload["round_index"].exactInt() == round }
            if (!spoke) return null
        }
        return null
    }

    private fun pendingDiscussion(events: List<GroupEvent>): GroupEvent? {
        val stoppedThrough = events.filter { it.kind == "room.stop_requested" }.maxOfOrNull { it.seq } ?: 0
        val completed = events.mapNotNull { e ->
            val status = e.payload["status"].str
            if (e.kind == "room.activity" && (status == "settled" || status == "bounded")) e.payload["discussion_event_id"].str else null
        }.toSet()
        val latestByThread = mutableMapOf<String, GroupEvent>()
        for (e in events) if (e.isUserMessage) {
            val thread = e.threadId.orEmpty()
            val existing = latestByThread[thread]
            if (existing != null && existing.seq > e.seq) continue
            latestByThread[thread] = e
        }
        return latestByThread.values.sortedBy { it.seq }.firstOrNull { it.seq > stoppedThrough && it.eventId !in completed }
    }

    fun resolveMentions(texts: List<String>, members: List<GroupMember>, defaultAll: Boolean): List<GroupMember> {
        val handles = members.mapNotNull { it.handle?.lowercase() }.toSet()
        val mentioned = mutableSetOf<String>()
        var everyone = false
        for (text in texts) for (match in routingMention.findAll(text)) {
            val h = match.groupValues[1].lowercase()
            if (h == "all" || h == "everyone") everyone = true
            else if (h in handles) mentioned += h
        }
        if (everyone || (defaultAll && mentioned.isEmpty())) return members
        return members.filter { it.handle?.lowercase().orEmpty() in mentioned }
    }

    private fun unaddressedMentions(messages: List<GroupEvent>, members: List<GroupMember>): List<GroupMember> {
        val citedAt = mutableMapOf<String, Int>()
        val lastPostAt = mutableMapOf<String, Int>()
        for (e in messages) if (e.isMemberMessage) {
            val speaker = e.payload["member_id"].str?.lowercase().orEmpty()
            lastPostAt[speaker] = e.seq
            for (m in resolveMentions(listOf(e.payload["text"].str.orEmpty()), members, defaultAll = false)) {
                if (routingId(m) != speaker) citedAt[routingId(m)] = e.seq
            }
        }
        return members.filter { m ->
            val cited = citedAt[routingId(m)] ?: return@filter false
            (lastPostAt[routingId(m)] ?: 0) <= cited
        }
    }

    private fun rotate(members: List<GroupMember>, round: Int): List<GroupMember> {
        if (members.isEmpty()) return members
        val shift = round % members.size
        return members.drop(shift) + members.take(shift)
    }

    private fun routingId(member: GroupMember) = (member.memberId ?: member.identityKey).lowercase()

    fun memberWithId(id: String, members: List<GroupMember>): GroupMember? {
        val key = id.trim().lowercase()
        return members.firstOrNull { routingId(it) == key } ?: members.firstOrNull { it.profile.equals(key, ignoreCase = true) }
    }
}

/** A message's leading `@file:` lines, shown as file chips above its text. */
object GroupMessageParts {
    private val fileLine = Regex("^@file:(?:`([^`\\r\\n]+)`|\"([^\"\\r\\n]+)\"|'([^'\\r\\n]+)'|(\\S+))$")

    /** (text without the leading file lines, file paths). */
    fun split(source: String): Pair<String, List<String>> {
        val lines = source.split("\n").toMutableList()
        val paths = mutableListOf<String>()
        while (lines.isNotEmpty()) {
            val line = lines.first().trimEnd()
            val match = fileLine.find(line) ?: break
            val path = match.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: break
            paths += path
            lines.removeAt(0)
        }
        if (paths.isEmpty()) return source to emptyList()
        return lines.joinToString("\n").trim() to paths
    }

    fun fileName(path: String) = path.substringAfterLast('/').ifEmpty { path }
}

/** Files sent into a room go through the host plugin (`room-files`). */
object GroupRoomFiles {
    const val CAPABILITY = "room-files"
    const val PATH = "api/plugins/conduit_push/room-files"
    const val MINIMUM_TIMEOUT_MS = 180_000L

    fun timeoutMs(bodyBytes: Int): Long = maxOf(MINIMUM_TIMEOUT_MS, bodyBytes / 64L)

    /** The host's `ref_text`, else one built from the stored path; null when malformed. */
    fun reference(response: JsonObject): String? {
        if (response["ok"].bool != true) return null
        response["ref_text"].str?.trim()?.let { if (it.startsWith("@file:") && it.length > 6) return it }
        val path = response["path"].str?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return "@file:" + Attachment.formatReferenceValue(path)
    }
}

/** Room pictures kept by the host plugin (`room-images`). */
object GroupRoomPictures {
    const val CAPABILITY = "room-images"
    const val LIST_PATH = "api/plugins/conduit_push/room-images"
    const val UPLOAD_EDGE = 256
    const val UPLOAD_TIMEOUT_MS = 60_000L

    private fun allowed(c: Char) = c.isLetterOrDigit() && c.code < 128 || c in "._:-"

    fun path(roomId: String): String? {
        if (roomId.isEmpty() || !roomId.all(::allowed)) return null
        return "$LIST_PATH/$roomId"
    }

    fun revisions(response: JsonObject): Map<String, String>? {
        if (response["ok"].bool != true) return null
        val raw = response["revisions"].obj ?: return null
        return raw.mapNotNull { (k, v) -> v.str?.takeIf(String::isNotEmpty)?.let { k to it } }.toMap()
    }

    fun picture(response: JsonObject): Pair<ByteArray, String>? {
        if (response["ok"].bool != true) return null
        val rev = response["rev"].str?.takeIf { it.isNotEmpty() } ?: return null
        val data = response["data_base64"].str?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() } ?: return null
        if (data.isEmpty()) return null
        return data to rev
    }

    fun storedRevision(response: JsonObject): String? =
        if (response["ok"].bool == true) response["rev"].str?.takeIf { it.isNotEmpty() } else null

    fun imageData(dataUrl: String): ByteArray? {
        if (!dataUrl.startsWith("data:image/")) return null
        val comma = dataUrl.indexOf(',')
        if (comma < 0 || !dataUrl.substring(0, comma).endsWith(";base64")) return null
        return runCatching { Base64.getMimeDecoder().decode(dataUrl.substring(comma + 1)) }.getOrNull()?.takeIf { it.isNotEmpty() }
    }
}

/** The notifier plugin's `GET capabilities` answer: the set it reported, or null when unknown. */
object NotifierPluginCapabilities {
    const val PATH = "api/plugins/conduit_push/capabilities"

    fun decode(response: JsonObject): Set<String>? {
        if (response["ok"].bool != true) return null
        return (response["capabilities"] as? JsonArray)?.mapNotNull { it.str }?.toSet()
    }
}

/**
 * A Group Chat created in Hermes Desktop: a read-only projection Desktop
 * mirrors under the `default` profile's `ui_meta['hermes-bots-groups']`.
 */
data class DesktopGroupChat(
    val key: String,
    val name: String,
    val members: List<Member>,
    val messages: List<Message>,
    val omitted: Int,
    val roomId: String? = null,
    val imageData: ByteArray? = null,
) {
    data class Member(val name: String, val handle: String?)
    data class Message(val id: String, val isMember: Boolean, val speaker: String, val text: String, val timestamp: Double?, val truncated: Boolean)

    val groupMembers: List<GroupMember> get() = members.map { GroupMember(handle = it.handle, displayName = it.name) }
    val lastActivity: Double get() = messages.lastOrNull()?.timestamp ?: 0.0

    override fun equals(other: Any?) = other is DesktopGroupChat && other.key == key && other.name == name &&
        other.members == members && other.messages == messages && other.omitted == omitted && other.roomId == roomId
    override fun hashCode() = key.hashCode()
}

object DesktopGroupChatDecoder {
    const val META_KEY = "hermes-bots-groups"
    const val PROFILE_NAME = "default"

    fun decode(profileRows: JsonArray): List<DesktopGroupChat> {
        val row = profileRows.firstOrNull { it.obj?.get("name").str == PROFILE_NAME }
        return decodeSnapshot(row.obj?.get("ui_meta").obj?.get(META_KEY))
    }

    fun decodeSnapshot(snapshot: JsonElement?): List<DesktopGroupChat> {
        val o = snapshot.obj ?: return emptyList()
        val version = o["version"].double ?: 0.0
        val rawRooms = o["rooms"].obj ?: JsonObject(emptyMap())
        val rawDeleted = o["deleted"].obj ?: JsonObject(emptyMap())
        val deleted = mutableMapOf<String, Double>()
        for ((key, value) in rawDeleted) {
            val revision = maxOf(0.0, value.double ?: 0.0)
            if (version >= 3) deleted[key] = revision
            else deleted["name:$key"] = if (version >= 2) revision else 0.0
        }
        val rooms = mutableListOf<DesktopGroupChat>()
        for ((rawKey, value) in rawRooms) {
            val room = value.obj ?: continue
            val log = room["log"].arr ?: continue
            val key = if (version >= 3) rawKey else "name:$rawKey"
            val roomId = room["roomId"].str?.trim().orEmpty()
            val idKey = if (roomId.isEmpty()) key.takeIf { it.startsWith("id:") } else "id:$roomId"
            if (idKey != null && deleted.containsKey(idKey)) continue
            if (key.startsWith("name:")) {
                val tombstone = deleted[key]
                if (tombstone != null && tombstone >= maxOf(0.0, room["revision"].double ?: 0.0)) continue
            }
            val fallbackName = if (key.startsWith("name:")) key.drop(5) else key
            val rawName = room["name"].str?.trim().orEmpty()
            val members = room["members"].arr?.mapNotNull { m ->
                val f = m.obj ?: return@mapNotNull null
                val name = f["name"].str?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                DesktopGroupChat.Member(name, f["handle"].str)
            }.orEmpty()
            val messages = log.mapIndexedNotNull { index, entry ->
                val f = entry.obj ?: return@mapIndexedNotNull null
                val from = f["from"].obj ?: JsonObject(emptyMap())
                val at = f["at"].double ?: 0.0
                DesktopGroupChat.Message(
                    id = f["id"].str ?: "entry-$index",
                    isMember = from["kind"].str == "member",
                    speaker = from["name"].str.orEmpty(),
                    text = f["text"].str.orEmpty(),
                    timestamp = if (at > 0) at / 1000 else null,
                    truncated = f["truncated"].bool ?: false,
                )
            }
            rooms += DesktopGroupChat(
                key = key,
                name = rawName.ifEmpty { fallbackName },
                members = members,
                messages = messages,
                omitted = maxOf(0, room["omitted"].exactInt() ?: 0),
                roomId = roomId.ifEmpty { null },
                imageData = room["image"].str?.let(GroupRoomPictures::imageData),
            )
        }
        return rooms.sortedWith { l, r ->
            if (l.lastActivity != r.lastActivity) (if (l.lastActivity > r.lastActivity) -1 else 1) else l.key.compareTo(r.key)
        }
    }
}

/**
 * Gateway `event` notifications that concern a hosted room. Room logs are
 * polled (`groups.log`) like on iOS; a gateway that also pushes room events
 * only makes the open room poll right away.
 */
object GroupGatewayEvents {
    private val roomKinds = setOf(
        "message.user", "message.member", "room.activity", "room.members_changed", "room.renamed",
        "room.disbanded", "room.stop_requested", "member.unavailable", "turn.settled", "turn.failed", "turn.cancelled",
    )

    /** The room id an event's params name, when the event is about a room. */
    fun roomId(params: JsonObject): String? {
        val type = params["type"].str.orEmpty()
        val payload = params["payload"].obj
        val roomId = (params["room_id"].str ?: payload?.get("room_id").str ?: payload?.get("event").obj?.get("room_id").str)
            ?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val kind = payload?.get("kind").str ?: payload?.get("event").obj?.get("kind").str
        return roomId.takeIf { type.startsWith("groups.") || type.startsWith("room.") || type in roomKinds || kind in roomKinds }
    }
}

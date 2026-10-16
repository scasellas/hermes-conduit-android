package app.hermesconduit.android

import app.hermesconduit.android.model.BotProfile
import app.hermesconduit.android.model.BotRosterDecoder
import app.hermesconduit.android.model.ConduitJson
import app.hermesconduit.android.model.DesktopGroupChatDecoder
import app.hermesconduit.android.model.GroupCapabilitiesDecoder
import app.hermesconduit.android.model.GroupCreateAttempt
import app.hermesconduit.android.model.GroupDecoders
import app.hermesconduit.android.model.GroupEvent
import app.hermesconduit.android.model.GroupGatewayEvents
import app.hermesconduit.android.model.GroupLogPage
import app.hermesconduit.android.model.GroupMember
import app.hermesconduit.android.model.GroupMessageParts
import app.hermesconduit.android.model.GroupPendingApproval
import app.hermesconduit.android.model.GroupRoomFiles
import app.hermesconduit.android.model.GroupRoomMentions
import app.hermesconduit.android.model.GroupRoomOutbox
import app.hermesconduit.android.model.GroupRoomPictures
import app.hermesconduit.android.model.GroupRoomReplay
import app.hermesconduit.android.model.GroupRoomTurns
import app.hermesconduit.android.model.MentionAutocomplete
import app.hermesconduit.android.model.str
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of ConduitTests/GroupChatModelsTests.swift and GroupRoomFilesTests.swift (model half). */
class GroupChatModelsTest {
    private fun json(text: String): JsonElement = ConduitJson.parseToJsonElement(text)

    private fun eventJson(
        seq: Int,
        kind: String = "message.user",
        roomId: String = "room-1",
        actor: String = """{"kind":"user","id":"human"}""",
        payload: String = "{}",
        eventId: String? = null,
    ) = """{"room_id":"$roomId","seq":$seq,"event_id":"${eventId ?: "e$seq"}","kind":"$kind","actor":$actor,"payload":$payload,"created_at":${1000 + seq}.0}"""

    private fun page(events: List<String>, latest: Int? = null): GroupLogPage {
        val lastSeq = Regex("\"seq\":(\\d+)").findAll(events.lastOrNull().orEmpty()).lastOrNull()?.groupValues?.get(1)?.toInt() ?: 0
        return GroupDecoders.logPage(json("""{"events":[${events.joinToString(",")}],"cursor":$lastSeq,"latest_seq":${latest ?: lastSeq},"has_more":false,"authority":{"gateway_id":"gw-a","epoch":1}}"""))!!
    }

    private fun event(text: String): GroupEvent = GroupDecoders.event(json(text))!!

    // region capabilities and decode

    @Test
    fun capabilitiesDecodeAndNegotiation() {
        val caps = GroupCapabilitiesDecoder.decode(json("""
            {"protocol_version":2,"driver":true,"authority_gateway_id":"gw-a","features":["monotonic_log"],
             "methods":["groups.capabilities","groups.list","groups.create","groups.state","groups.send","groups.log","groups.disband","groups.stop"],
             "max_log_limit":500}
        """))!!
        assertEquals(2, caps.protocolVersion)
        assertTrue(caps.driverReady)
        assertEquals("gw-a", caps.authorityGatewayId)
        assertEquals(500, caps.maxLogLimit)
        assertTrue(caps.foundationSupported)
        assertTrue(caps.supports("groups.stop"))
        assertFalse(GroupCapabilitiesDecoder.decode(json("""{"methods":["groups.capabilities","groups.list","groups.state"]}"""))!!.foundationSupported)
        assertNull(GroupCapabilitiesDecoder.decode(null))
        assertNull(GroupCapabilitiesDecoder.decode(json("""{"nope":1}""")))
        assertNull(GroupCapabilitiesDecoder.decode(json("""{"methods":[]}""")))
        assertNull(GroupCapabilitiesDecoder.decode(json("""{"methods":"not-a-list"}""")))
    }

    private val roomJson = """
        {"room_id":"room-1","name":"Planning","members":[
          {"member_id":"researcher","profile":"researcher","handle":"research-buddy","display_name":"Research Buddy"},
          {"member_id":"writer","profile":"writer","handle":"writer","display_name":"Writer","custom_future_field":17}],
         "authority_gateway_id":"gw-a","authority_epoch":1,"revision":4,"created_at":10.0,"updated_at":20.0,"latest_seq":9}
    """

    @Test
    fun roomAndMemberDecode() {
        val room = GroupDecoders.room(json(roomJson))!!
        assertEquals("room-1", room.roomId)
        assertEquals("Planning", room.name)
        assertEquals(2, room.members.size)
        assertEquals(4, room.revision)
        assertEquals(9, room.latestSeq)
        assertFalse(room.isDisbanded)
        assertEquals("researcher", room.members[0].identityKey)
        assertEquals("17", room.members[1].extra["custom_future_field"].str)
        assertEquals("", GroupDecoders.member(json("""{"display_name":"Someone"}"""))!!.identityKey)
        assertTrue(GroupDecoders.room(json("""{"room_id":"r","disbanded_at":30.0}"""))!!.isDisbanded)
        assertNull(GroupDecoders.room(json("""{"name":"no identity"}""")))
        assertNull(GroupDecoders.room(json("""{"room_id":"   "}""")))
    }

    @Test
    fun eventDecodeKeepsUnknownKindsAndRejectsBadNumbers() {
        val e = event(eventJson(3, payload = """{"text":"hello @all","thread_id":"main"}"""))
        assertEquals(3, e.seq)
        assertTrue(e.isUserMessage)
        assertEquals("hello @all", e.messageText)
        assertEquals("main", e.threadId)
        val future = event(eventJson(4, kind = "room.somefuturekind", actor = """{"kind":"gateway","id":"driver"}"""))
        assertEquals("room.somefuturekind", future.kind)
        assertFalse(future.isChatMessage)
        assertNull(GroupDecoders.event(json("""{"seq":1}""")))
        assertNull(GroupDecoders.event(json("""{"room_id":"r"}""")))
        assertNull(GroupDecoders.event(json(eventJson(0))))
        assertNull(GroupDecoders.event(json("""{"room_id":"room-1","seq":1e20,"kind":"message.user"}""")))
        assertNull(GroupDecoders.event(json("""{"room_id":"room-1","seq":2.7,"kind":"message.user"}""")))
        val room = GroupDecoders.room(json("""{"room_id":"room-1","revision":1e30,"latest_seq":3.5,"authority_epoch":-1e25}"""))!!
        assertEquals(0, room.revision)
        assertNull(room.latestSeq)
        assertEquals(1, room.authorityEpoch)
        assertNull(GroupDecoders.logPage(json("""{"latest_seq":1e20,"events":[]}""")))
        assertEquals(mapOf("queued" to 2), GroupDecoders.driverStatus(json("""{"counts":{"queued":2,"huge":1e40}}"""))!!.counts)
    }

    @Test
    fun actorDisplayLabelRoutesByIdentity() {
        val members = GroupDecoders.room(json(roomJson))!!.members
        assertEquals("Research Buddy", GroupDecoders.actor(json("""{"kind":"member","id":"researcher","profile":"researcher","display_name":"WRONG NAME"}""")).displayLabel(members))
        assertEquals("ghost", GroupDecoders.actor(json("""{"kind":"member","id":"ghost","profile":"ghost"}""")).displayLabel(members))
        assertEquals("", GroupDecoders.actor(json("""{"kind":"system","id":"room"}""")).displayLabel(members))
    }

    @Test
    fun pendingApprovals() {
        val status = GroupDecoders.driverStatus(json("""
            {"running":true,"working":true,"blocked":false,"pending_actions":[
              {"kind":"retry","task_id":"t0"},
              {"kind":"approval","task_id":"t1","execution_generation":2,"member_id":"researcher","request_id":"req-1",
               "approval":{"command":"rm -rf build","description":"recursive delete","choices":["once","session","deny"],"request_id":"req-1"}},
              {"kind":"approval","task_id":"t2","execution_generation":1,"member_id":"writer"}]}
        """))!!
        val approval = status.pendingApprovals.single()
        assertEquals("researcher", approval.memberId)
        assertEquals(2, approval.executionGeneration)
        assertEquals("rm -rf build", approval.command)
        assertEquals(listOf("once", "deny"), approval.choices)
        assertFalse(approval.allows("session"))
        val bare = GroupPendingApproval.of(json("""{"kind":"approval","task_id":"t1","execution_generation":1,"member_id":"r","request_id":"q"}""") as JsonObject)!!
        assertEquals(listOf("once", "deny"), bare.choices)
        val fallback = GroupPendingApproval.of(json("""{"kind":"approval","task_id":"t1","execution_generation":1,"member_id":"r","approval":{"request_id":"req-9"}}""") as JsonObject)!!
        assertEquals("req-9", fallback.requestId)
    }

    // endregion

    // region replay and outbox

    @Test
    fun replayAppendsSortsAndDropsDuplicates() {
        var replay = GroupRoomReplay("room-1").adopt(page(listOf(eventJson(1, payload = """{"text":"a"}"""), eventJson(2, kind = "message.member", payload = """{"text":"b"}"""))))
        assertEquals(2, replay.cursor)
        assertEquals("gw-a", replay.authorityGatewayId)
        assertEquals("b", replay.lastChatMessage?.messageText)
        replay = replay.adopt(page(listOf(eventJson(1), eventJson(2), eventJson(3))))
        assertEquals(listOf(1, 2, 3), replay.events.map { it.seq })
        val sorted = GroupRoomReplay("room-1").adopt(page(listOf(eventJson(3), eventJson(2))))
        assertEquals(listOf(2, 3), sorted.events.map { it.seq })
        val foreign = GroupRoomReplay("room-1").adopt(page(listOf(eventJson(1, roomId = "room-OTHER"))))
        assertTrue(foreign.events.isEmpty())
        assertEquals(0, foreign.cursor)
    }

    @Test
    fun replayGapsAndResync() {
        var replay = GroupRoomReplay("room-1").adopt(page(listOf(eventJson(1))))
        assertFalse(replay.pageSkipsAhead(page(listOf(eventJson(1), eventJson(3), eventJson(2)))))
        assertFalse(replay.pageSkipsAhead(page(emptyList())))
        assertTrue(replay.pageSkipsAhead(page(listOf(eventJson(3)))))
        assertTrue(replay.pageSkipsAhead(page(listOf(eventJson(2), eventJson(4)))))
        assertFalse(replay.pageSkipsAhead(page(listOf(eventJson(9, roomId = "room-2")))))
        replay = replay.adopt(page(listOf(eventJson(5))))
        assertTrue(replay.hasGap)
        assertEquals(listOf(1, 5), replay.events.map { it.seq })
        replay = replay.replaceAll(page(listOf(eventJson(1), eventJson(2))))
        assertFalse(replay.hasGap)
        assertEquals(2, replay.cursor)
        replay = replay.replaceAll(page(listOf(eventJson(40), eventJson(41))))
        assertFalse(replay.hasGap)
        assertEquals(listOf(40, 41), replay.events.map { it.seq })
        val tail = GroupRoomReplay("room-1").adoptInitialTail(page((301..500).map { eventJson(it) }, latest = 500))
        assertFalse(tail.hasGap)
        assertEquals(200, tail.events.size)
        assertEquals(500, tail.sinceSeq)
        val accepted = page(listOf(eventJson(2)))
        val once = GroupRoomReplay("room-1").adopt(page(listOf(eventJson(1)))).adopt(accepted).adopt(accepted)
        assertEquals(listOf(1, 2), once.events.map { it.seq })
    }

    @Test
    fun outboxMintsOnceAndSettlesFromServerId() {
        var mints = 0
        val (outbox, first) = GroupRoomOutbox().beginSend("hello") { mints++; "conduit-abc" }
        assertEquals("conduit-abc", first.eventId)
        val (_, retry) = outbox.beginSend("hello") { mints++; "conduit-never" }
        assertEquals("conduit-abc", retry.eventId)
        assertEquals(1, mints)
        assertFalse(outbox.accepts("different"))
        assertNotNull(outbox.accept("conduit-other").pending)
        assertNull(outbox.accept("conduit-abc").pending)
        assertEquals("user:af2b48a15035625368f5e79d8610b31e83a85c6d853a55440f3b636d26698de6", GroupRoomOutbox.serverEventId("conduit-abc"))
        assertNull(outbox.settle(page(listOf(eventJson(1, eventId = "user:someone-else"))).events))
        val landed = page(listOf(eventJson(2, eventId = GroupRoomOutbox.serverEventId("conduit-abc"))))
        assertNull(outbox.settle(landed.events)!!.pending)
    }

    @Test
    fun createRetryKeepsRoomIdOnlyForUnchangedInputs() {
        val first = GroupCreateAttempt.Inputs("Planning", setOf("a", "b"))
        assertEquals("room-x", GroupCreateAttempt.roomId(first, "room-x", null))
        assertEquals("room-x", GroupCreateAttempt.roomId(first, "room-x", first))
        val renamed = GroupCreateAttempt.roomId(GroupCreateAttempt.Inputs("Planning 2", setOf("a", "b")), "room-x", first)
        assertNotEquals("room-x", renamed)
        assertTrue(renamed.startsWith("conduit-"))
        assertNotEquals("room-x", GroupCreateAttempt.roomId(GroupCreateAttempt.Inputs("Planning", setOf("a", "c")), "room-x", first))
    }

    // endregion

    // region mentions and turns

    private fun members() = listOf(
        GroupMember(memberId = "furina", profile = "furina", handle = "furina", displayName = "Furina"),
        GroupMember(memberId = "zhongli", profile = "zhongli", handle = "zhongli", displayName = "Zhongli"),
    )

    private fun user(seq: Int, text: String) = event(eventJson(seq, payload = """{"text":"$text","thread_id":"main"}""", eventId = "user:d1"))

    private fun memberMessage(seq: Int, member: String, round: Int, text: String) = event(eventJson(
        seq, kind = "message.member", actor = """{"kind":"member","id":"$member","profile":"$member"}""",
        payload = """{"text":"$text","thread_id":"main","discussion_event_id":"user:d1","member_id":"$member","round_index":$round}""",
        eventId = "dmessage:$seq",
    ))

    private fun settled(seq: Int, member: String, round: Int, messageSeq: Int?) = event(eventJson(
        seq, kind = "turn.settled", actor = """{"kind":"gateway","id":"gw-a"}""",
        payload = """{"thread_id":"main","discussion_event_id":"user:d1","member_id":"$member","round_index":$round,"passed":${messageSeq == null},"message_event_id":${messageSeq?.let { "\"dmessage:$it\"" } ?: "null"}}""",
    ))

    @Test
    fun roomMentionClassification() {
        val researcher = GroupMember(memberId = "researcher", profile = "researcher", handle = "research-buddy", displayName = "Research Buddy")
        val ms = listOf(researcher)
        assertEquals(GroupRoomMentions.Kind.AGENT, GroupRoomMentions.classify("research-buddy", ms))
        assertEquals(GroupRoomMentions.Kind.AGENT, GroupRoomMentions.classify("RESEARCH-BUDDY", ms))
        assertEquals(GroupRoomMentions.Kind.BROADCAST, GroupRoomMentions.classify("all", ms))
        assertEquals(GroupRoomMentions.Kind.BROADCAST, GroupRoomMentions.classify("everyone", ms))
        assertEquals(GroupRoomMentions.Kind.HUMAN, GroupRoomMentions.classify("user", ms))
        assertNull(GroupRoomMentions.classify("nobody", ms))
        assertNull(GroupRoomMentions.classify("example.com", ms))
        val renamed = GroupMember(memberId = "researcher", profile = "researcher", handle = "researcher", displayName = "Research Buddy", previousNames = listOf("scout"))
        assertEquals(GroupRoomMentions.Kind.AGENT, GroupRoomMentions.classify("scout", listOf(renamed)))
        assertEquals(GroupRoomMentions.Kind.AGENT, GroupRoomMentions.classify("research", listOf(renamed)))
        val guide = GroupMember(memberId = "guide", profile = "guide", handle = "guide", displayName = "User Guide")
        assertEquals(GroupRoomMentions.Kind.HUMAN, GroupRoomMentions.classify("user", listOf(guide)))
        assertEquals(GroupRoomMentions.Kind.AGENT, GroupRoomMentions.classify("user-guide", listOf(guide)))
        fun tokens(text: String) = GroupRoomMentions.mentionScan.findAll(text).map { it.groupValues[1] }.toList()
        assertEquals(listOf("all", "user", "scout"), tokens("@all hi @user\n@scout"))
        assertEquals(emptyList<String>(), tokens("a@all x@user name@example.com"))
    }

    @Test
    fun pendingResponderFollowsDiscussionRounds() {
        val ms = members()
        val log = mutableListOf(user(1, "Hello everyone"))
        fun responder() = GroupRoomTurns.pendingResponder(log, ms)?.memberId
        assertEquals("furina", responder())
        log += listOf(memberMessage(2, "furina", 0, "@zhongli, attend!"), settled(3, "furina", 0, 2))
        assertEquals("zhongli", responder())
        log += listOf(memberMessage(4, "zhongli", 0, "@furina, verified."), settled(5, "zhongli", 0, 4))
        assertEquals("furina", responder())
        log += settled(6, "furina", 1, null)
        assertNull(responder())
    }

    @Test
    fun pendingResponderHonorsMentionsCompletionAndStop() {
        val ms = members()
        val log = listOf(user(1, "@zhongli what do you think?"))
        assertEquals("zhongli", GroupRoomTurns.pendingResponder(log, ms)?.memberId)
        val done = log + event(eventJson(2, kind = "room.activity", actor = """{"kind":"gateway","id":"gw-a"}""", payload = """{"status":"settled","thread_id":"main","discussion_event_id":"user:d1"}"""))
        assertNull(GroupRoomTurns.pendingResponder(done, ms))
        val stopped = log + event(eventJson(2, kind = "room.stop_requested", actor = """{"kind":"gateway","id":"gw-a"}""", payload = """{"cancel_id":"c1"}"""))
        assertNull(GroupRoomTurns.pendingResponder(stopped, ms))
    }

    @Test
    fun responderRotatesAndStopsAtTheMessageCap() {
        val ms = members() + GroupMember(memberId = "nahida", profile = "nahida", handle = "nahida", displayName = "Nahida")
        val log = mutableListOf(user(1, "@all hello"))
        assertEquals("furina", GroupRoomTurns.pendingResponder(log, ms)?.memberId)
        log += listOf(memberMessage(2, "furina", 0, "hi"), settled(3, "furina", 0, 2))
        assertEquals("zhongli", GroupRoomTurns.pendingResponder(log, ms)?.memberId)
        val capped = mutableListOf(user(1, "@all hello"))
        var seq = 2
        repeat(GroupRoomTurns.MAX_MESSAGES) { i ->
            val m = listOf("furina", "zhongli", "nahida")[i % 3]
            capped += listOf(memberMessage(seq, m, 0, "@all again"), settled(seq + 1, m, 0, seq))
            seq += 2
        }
        assertNull(GroupRoomTurns.pendingResponder(capped, ms))
    }

    @Test
    fun turnVisibilityCaseAndDeferral() {
        val ms = members()
        assertFalse(GroupRoomTurns.isVisible(settled(3, "furina", 0, 2)))
        val passed = settled(6, "furina", 1, null)
        assertTrue(GroupRoomTurns.isVisible(passed))
        assertEquals("Furina", GroupRoomTurns.member(passed, ms)?.displayName)
        assertTrue(GroupRoomTurns.isVisible(user(1, "hi")))
        assertFalse(GroupRoomTurns.isVisible(event(eventJson(3, kind = "turn.heartbeat"))))
        assertTrue(GroupRoomTurns.isVisible(event(eventJson(4, kind = "turn.failed"))))
        val folded = event(eventJson(4, kind = "turn.settled", payload = """{"thread_id":"main","discussion_event_id":"user:d1","member_id":"Zhongli","round_index":0,"passed":true,"message_event_id":null}"""))
        assertEquals("zhongli", GroupRoomTurns.member(folded, ms)?.memberId)
        assertNull(GroupRoomTurns.pendingResponder(listOf(user(1, "@zhongli hi"), folded), ms))
        val deferred = event(eventJson(2, kind = "turn.deferred", payload = """{"thread_id":"main","discussion_event_id":"user:d1","member_id":"furina","round_index":0}"""))
        assertEquals("furina", GroupRoomTurns.pendingResponder(listOf(user(1, "hi"), deferred), ms)?.memberId)
        assertFalse(GroupRoomTurns.isVisible(deferred))
        assertEquals(GroupRoomTurns.RoomActivityOutcome.SETTLED, GroupRoomTurns.roomActivityOutcome(event(eventJson(9, kind = "room.activity", payload = """{"status":"settled"}"""))))
        assertEquals(GroupRoomTurns.RoomActivityOutcome.BOUNDED, GroupRoomTurns.roomActivityOutcome(event(eventJson(9, kind = "room.activity", payload = """{"status":"bounded"}"""))))
        assertEquals(GroupRoomTurns.RoomActivityOutcome.OTHER, GroupRoomTurns.roomActivityOutcome(event(eventJson(9, kind = "room.activity", payload = """{"status":"resumed"}"""))))
        assertEquals(listOf("zhongli"), GroupRoomTurns.resolveMentions(listOf("a@zhongli"), ms, defaultAll = false).map { it.memberId })
        assertFalse(GroupRoomTurns.displayName(GroupMember()).isEmpty())
    }

    @Test
    fun mentionAutocomplete() {
        assertEquals("", MentionAutocomplete.activeQuery("@"))
        assertEquals("zho", MentionAutocomplete.activeQuery("hey @Zho"))
        assertNull(MentionAutocomplete.activeQuery("hey @zhongli "))
        assertNull(MentionAutocomplete.activeQuery("mail me@example"))
        assertNull(MentionAutocomplete.activeQuery("no tag"))
        assertNull(MentionAutocomplete.activeQuery("hey @钟离"))
        assertNull(MentionAutocomplete.activeQuery("hey @zé"))
        assertNull(MentionAutocomplete.activeQuery("hey @.foo"))
        assertNull(MentionAutocomplete.activeQuery("@-x"))
        assertEquals("zhong.li", MentionAutocomplete.activeQuery("@zhong.li"))
        val candidates = MentionAutocomplete.roomCandidates(members())
        assertEquals(listOf("furina", "zhongli", "all"), candidates.map { it.tag })
        val matches = MentionAutocomplete.filter(candidates, "zh")
        assertEquals(listOf("zhongli"), matches.map { it.tag })
        assertEquals("@furina and @zhongli ", MentionAutocomplete.completing("@furina and @zh", matches[0]))
        assertEquals(3, MentionAutocomplete.filter(candidates, "").size)
        val shadow = GroupMember(memberId = "all", profile = "all", handle = "all", displayName = "All")
        assertEquals(listOf("furina", "zhongli", "all"), MentionAutocomplete.roomCandidates(members() + shadow).map { it.tag })
        val human = GroupMember(memberId = "user", profile = "user", handle = "user", displayName = "User")
        assertFalse("user" in MentionAutocomplete.roomCandidates(listOf(human)).map { it.tag })
    }

    @Test
    fun botCandidates() {
        val roster = listOf(BotProfile("furina", botTitle = "Furina"), BotProfile("zhongli", botTitle = "Zhongli"), BotProfile("ghost", isHiddenByMeta = true))
        val candidates = MentionAutocomplete.botCandidates(roster, "Furina")
        assertEquals(listOf("zhongli"), candidates.map { it.tag })
        assertEquals("Zhongli", candidates.first().title)
        assertEquals(2, MentionAutocomplete.botCandidates(roster, null).size)
        val twins = listOf(BotProfile("scout", botTitle = "Research Buddy"), BotProfile("finder", botTitle = "Research Buddy", isHiddenByMeta = true))
        assertEquals(listOf("scout"), MentionAutocomplete.botCandidates(twins, null).map { it.tag })
    }

    // endregion

    // region desktop projection, files, pictures, events

    private fun desktopRoom(name: String, roomId: String? = null, revision: Int = 1, messages: String = "[]") =
        """{"name":"$name","log":$messages,"revision":$revision,"members":[{"name":"Research Buddy","handle":"research"},{"name":"Writer"}]${roomId?.let { ",\"roomId\":\"$it\"" } ?: ""}}"""

    @Test
    fun desktopProjection() {
        val groups = DesktopGroupChatDecoder.decodeSnapshot(json("""
            {"version":3,"rooms":{
              "id:r-old":${desktopRoom("Old", "r-old", messages = """[{"id":"m1","from":{"kind":"user","name":"You"},"text":"hi","at":1000000.0}]""")},
              "id:r-new":${desktopRoom("New", "r-new", messages = """[{"id":"m2","from":{"kind":"user","name":"You"},"text":"hey","at":2000000.0},{"from":{"kind":"member","name":"Research Buddy"},"text":"on it","at":3000000.0,"truncated":true}]""")},
              "id:broken":{"name":"No log"}}}
        """))
        assertEquals(listOf("New", "Old"), groups.map { it.name })
        val newest = groups[0]
        assertEquals("id:r-new", newest.key)
        assertEquals("research", newest.members.first().handle)
        assertTrue(newest.messages[1].isMember)
        assertTrue(newest.messages[1].truncated)
        assertEquals(3000.0, newest.messages[1].timestamp!!, 0.0)
        assertEquals("entry-1", newest.messages[1].id)

        val tombstoned = DesktopGroupChatDecoder.decodeSnapshot(json("""
            {"version":3,"rooms":{"id:gone":${desktopRoom("Gone", "gone", 9)},"name:Legacy":${desktopRoom("Legacy", revision = 5)},"name:Recreated":${desktopRoom("Recreated", revision = 7)}},
             "deleted":{"id:gone":1,"name:Legacy":5,"name:Recreated":6}}
        """))
        assertEquals(listOf("Recreated"), tombstoned.map { it.name })
        val legacy = DesktopGroupChatDecoder.decodeSnapshot(json("""{"version":2,"rooms":{"Weekly":${desktopRoom("Weekly", revision = 3)}},"deleted":{"Retired":1}}"""))
        assertEquals(listOf("name:Weekly"), legacy.map { it.key })
        assertTrue(DesktopGroupChatDecoder.decodeSnapshot(null).isEmpty())

        val snapshot = BotRosterDecoder.decode(json("""
            {"profiles":[{"name":"default","ui_meta":{"hermes-bots-groups":{"version":3,"rooms":{"id:r1":${desktopRoom("From Desktop", "r1")}}}}},
              {"name":"writer","ui_meta":{"hermes-bots-groups":{"version":3,"rooms":{"id:x":${desktopRoom("Wrong profile", "x")}}}}}]}
        """))!!
        assertEquals(listOf("From Desktop"), snapshot.desktopGroups.map { it.name })
        assertEquals(2, snapshot.bots.size)
    }

    @Test
    fun roomFilesAndMessageParts() {
        assertEquals("@file:/srv/a.txt", GroupRoomFiles.reference(json("""{"ok":true,"ref_text":"@file:/srv/a.txt","path":"/x"}""") as JsonObject))
        assertEquals("@file:`/srv/my file.txt`", GroupRoomFiles.reference(json("""{"ok":true,"path":"/srv/my file.txt"}""") as JsonObject))
        assertNull(GroupRoomFiles.reference(json("""{"ok":true}""") as JsonObject))
        assertNull(GroupRoomFiles.reference(json("""{"ok":false,"path":"/x"}""") as JsonObject))
        val (text, files) = GroupMessageParts.split("@file:/srv/a.txt\n@file:`/srv/my file.pdf`\n\nPlease read these")
        assertEquals("Please read these", text)
        assertEquals(listOf("/srv/a.txt", "/srv/my file.pdf"), files)
        assertEquals("my file.pdf", GroupMessageParts.fileName(files[1]))
        assertEquals("plain" to emptyList<String>(), GroupMessageParts.split("plain"))
        assertEquals(180_000L, GroupRoomFiles.timeoutMs(1000))
        assertEquals(200_000L, GroupRoomFiles.timeoutMs(12_800_000))
    }

    @Test
    fun roomPictures() {
        assertEquals("api/plugins/conduit_push/room-images/conduit-abc:1.x", GroupRoomPictures.path("conduit-abc:1.x"))
        assertNull(GroupRoomPictures.path(""))
        assertNull(GroupRoomPictures.path("a/b"))
        assertNull(GroupRoomPictures.path("a b"))
        assertEquals(mapOf("r1" to "v1"), GroupRoomPictures.revisions(json("""{"ok":true,"revisions":{"r1":"v1","r2":""}}""") as JsonObject))
        val (data, rev) = GroupRoomPictures.picture(json("""{"ok":true,"rev":"v2","data_base64":"AQID"}""") as JsonObject)!!
        assertEquals(3, data.size)
        assertEquals("v2", rev)
        assertNull(GroupRoomPictures.picture(json("""{"ok":true,"rev":"v2","data_base64":""}""") as JsonObject))
        assertEquals("v3", GroupRoomPictures.storedRevision(json("""{"ok":true,"rev":"v3"}""") as JsonObject))
        assertNotNull(GroupRoomPictures.imageData("data:image/png;base64,AQID"))
        assertNull(GroupRoomPictures.imageData("data:text/plain;base64,AQID"))
    }

    @Test
    fun gatewayRoomEvents() {
        assertEquals("room-1", GroupGatewayEvents.roomId(json("""{"type":"room.activity","payload":{"room_id":"room-1"}}""") as JsonObject))
        assertEquals("room-2", GroupGatewayEvents.roomId(json("""{"type":"groups.event","payload":{"event":{"room_id":"room-2","kind":"message.member"}}}""") as JsonObject))
        assertNull(GroupGatewayEvents.roomId(json("""{"type":"message.delta","session_id":"s","payload":{"text":"x"}}""") as JsonObject))
    }

    // endregion
}

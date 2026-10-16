package app.hermesconduit.android

import app.hermesconduit.android.model.BotAvatarColor
import app.hermesconduit.android.model.BotAvatarImage
import app.hermesconduit.android.model.BotCanonicalSession
import app.hermesconduit.android.model.BotChatHygiene
import app.hermesconduit.android.model.BotChatLookupDecoder
import app.hermesconduit.android.model.BotChatLookupRow
import app.hermesconduit.android.model.BotChatResolution
import app.hermesconduit.android.model.BotChatResolver
import app.hermesconduit.android.model.BotConfigureSections
import app.hermesconduit.android.model.BotMetaPatch
import app.hermesconduit.android.model.BotMetaWriteOutcome
import app.hermesconduit.android.model.BotProfile
import app.hermesconduit.android.model.BotProfileSlug
import app.hermesconduit.android.model.BotRosterDecoder
import app.hermesconduit.android.model.BotSoul
import app.hermesconduit.android.model.ConduitJson
import app.hermesconduit.android.model.GatewayErrors
import app.hermesconduit.android.model.SessionSummary
import app.hermesconduit.android.model.str
import app.hermesconduit.android.net.RpcException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ports of ConduitTests/BotModeTests.swift (model half) and BotManagementTests.swift. */
class BotModelsTest {
    private fun json(text: String): JsonElement = ConduitJson.parseToJsonElement(text)

    private fun bot(
        name: String,
        pinned: Boolean = false,
        canonicalLastActive: Double? = null,
        lastActive: Double? = null,
        canonicalId: String? = null,
        resolvedId: String? = null,
    ) = BotProfile(
        name = name,
        isPinned = pinned,
        canonicalSession = if (canonicalLastActive != null || canonicalId != null) {
            BotCanonicalSession(canonicalId ?: "c-$name", resolvedId, canonicalLastActive, null)
        } else null,
        lastActive = lastActive,
    )

    private fun session(id: String, title: String, profile: String? = null, alternate: Set<String> = emptySet()) =
        SessionSummary(id, title, null, null, profile, false, false, false, null, null, null, alternateIds = alternate)

    // region profiles.list

    @Test
    fun rosterDecoderExtractsBotFields() {
        val snapshot = BotRosterDecoder.decode(json("""
            {"profiles":[{"name":"atlas","display_name":"Atlas","description":"Research agent","model":"hermes-4","provider":"nous",
              "is_default":false,"has_avatar":true,
              "ui_meta":{"hermes-bots":{"title":"Scout","pinned":true,"hidden":false,"color":"teal"}},
              "canonical_session":{"id":"stored-1","resolved_id":"runtime-1","last_active":1700000000,"preview":"Working on it"},
              "last_session":{"last_active":1700000500,"preview":"Newest human chat"}}],
             "bot_mode_protocol":true}
        """))!!
        assertTrue(snapshot.supportsBotProtocol)
        val bot = snapshot.bots.single()
        assertEquals("atlas", bot.name)
        assertEquals("Scout", bot.displayLabel)
        assertEquals("Research agent", bot.profileDescription)
        assertEquals("hermes-4", bot.model)
        assertTrue(bot.hasAvatar)
        assertTrue(bot.isPinned)
        assertEquals("stored-1", bot.canonicalSession?.id)
        assertEquals("runtime-1", bot.canonicalSession?.resolvedId)
        assertEquals("Working on it", bot.canonicalSession?.preview)
        assertEquals(1_700_000_500.0, bot.lastActive!!, 0.0)
    }

    @Test
    fun rosterDecodeFallsBackToDisplayNameAndRejectsOtherEnvelopes() {
        val snapshot = BotRosterDecoder.decode(json("""{"profiles":[{"name":"default","display_name":"Default Profile"}]}"""))!!
        assertFalse(snapshot.supportsBotProtocol)
        assertEquals("Default Profile", snapshot.bots.single().displayLabel)
        assertNull(snapshot.bots.single().canonicalSession)
        assertNull(BotRosterDecoder.decode(json("""{"error":"nope"}""")))
    }

    @Test
    fun rosterDecoderKeepsRawMetaRevisionAndDefaultFlag() {
        val bots = BotRosterDecoder.decode(json("""
            {"profiles":[{"name":"default","is_default":true,"ui_meta_revisions":{}},
              {"name":"atlas","ui_meta_revisions":{"hermes-bots":4},"ui_meta":{"hermes-bots":{"title":"Scout","sectionId":"s1"}}},
              {"name":"legacy"}]}
        """))!!.bots
        assertTrue(bots[0].isDefault)
        assertFalse(bots[0].isDeletable)
        assertEquals(0, bots[0].botMetaRevision)
        assertEquals(4, bots[1].botMetaRevision)
        assertEquals("s1", bots[1].botMeta["sectionId"].str)
        assertTrue(bots[1].isDeletable)
        assertNull(bots[2].botMetaRevision)
    }

    @Test
    fun displayOrderPinsFirstThenActivityThenName() {
        val ordered = BotProfile.displayOrder(
            listOf(bot("bbb", lastActive = 5.0), bot("aaa", lastActive = 5.0), bot("older", canonicalLastActive = 100.0), bot("fresh", canonicalLastActive = 500.0), bot("pinned", pinned = true, canonicalLastActive = 10.0)),
        )
        assertEquals(listOf("pinned", "fresh", "older", "aaa", "bbb"), ordered.map { it.name })
    }

    // endregion

    // region canonical chat

    @Test
    fun lookupRowTitlePrecedenceAndResumeTarget() {
        assertTrue(BotChatLookupRow("s1", title = "Bot Chat").isCanonicalTitle())
        assertTrue(BotChatLookupRow("s1", title = "Drifted", rootTitle = "Bot Chat").isCanonicalTitle())
        assertFalse(BotChatLookupRow("s1", title = "Project planning").isCanonicalTitle())
        assertFalse(BotChatLookupRow("s1", rootTitle = "Project planning").isCanonicalTitle())
        assertEquals("runtime-1", BotChatLookupRow("stored-1", resolvedId = "runtime-1").resumeTargetId)
        assertEquals("stored-1", BotChatLookupRow("stored-1").resumeTargetId)
        assertEquals("stored-1", BotChatLookupRow("stored-1", resolvedId = "  ").resumeTargetId)
    }

    @Test
    fun resolverFailsClosed() {
        assertEquals(
            BotChatResolution.OpenExisting("stored-1", "runtime-1"),
            BotChatResolver.resolve(listOf(BotChatLookupRow("stored-1", "runtime-1", "Bot Chat")), "stored-1"),
        )
        assertEquals(BotChatResolution.Create, BotChatResolver.resolve(emptyList(), null))
        assertEquals(BotChatResolution.UnconfirmedAbsence, BotChatResolver.resolve(emptyList(), "stored-1"))
        assertEquals(BotChatResolution.UnconfirmedAbsence, BotChatResolver.resolve(listOf(BotChatLookupRow("stored-9", title = "Bot Chatty")), "stored-1"))
        // The roster's registry breaks ties among legacy forks; a pin that misses falls back to listing order.
        val forks = listOf(BotChatLookupRow("stray-1", title = "Bot Chat"), BotChatLookupRow("stored-1", "runtime-1", "Bot Chat"))
        assertEquals(BotChatResolution.OpenExisting("stored-1", "runtime-1"), BotChatResolver.resolve(forks, "stored-1"))
        val twins = listOf(BotChatLookupRow("a-1", title = "Bot Chat"), BotChatLookupRow("b-1", title = "Bot Chat"))
        assertEquals(BotChatResolution.OpenExisting("a-1", "a-1"), BotChatResolver.resolve(twins, "not-present"))
    }

    @Test
    fun lookupDecoderRejectsAnyMalformedRow() {
        assertNull(BotChatLookupDecoder.decode(json("""{"sessions":[{"id":"stored-1","title":"Bot Chat"},{"title":"Bot Chat"}]}""")))
        assertNull(BotChatLookupDecoder.decode(json("""{"sessions":["not-a-row"]}""")))
        assertEquals(1, BotChatLookupDecoder.decode(json("""{"sessions":[{"id":"s","root_title":"Bot Chat"}]}"""))?.size)
    }

    @Test
    fun errorClassifiers() {
        assertTrue(GatewayErrors.isTitleCollision(RpcException(4022, "Title 'Bot Chat' is already in use by session x")))
        assertTrue(GatewayErrors.isTitleCollision(RpcException(5000, "Title 'Bot Chat' is already in use by session x")))
        assertFalse(GatewayErrors.isTitleCollision(RpcException(5000, "database is locked")))
        assertFalse(GatewayErrors.isTitleCollision(RpcException(-32601, "unknown method")))
        assertTrue(GatewayErrors.isMissingMethod(RpcException(-32601, "unknown method")))
        assertTrue(GatewayErrors.isMissingMethod(RpcException(0, "method not found")))
        assertFalse(GatewayErrors.isMissingMethod(RpcException(4001, "session not found")))
        assertTrue(GatewayErrors.isRoomNotFound(RpcException(4112, "x")))
        assertTrue(GatewayErrors.isRoomNotFound(RpcException(1, "Hosted room not found")))
    }

    @Test
    fun hygieneHidesCanonicalRowsOnly() {
        val atlas = bot("atlas", canonicalId = "stored-1", resolvedId = "runtime-1")
        assertTrue(BotChatHygiene.isCanonicalBotChatRow(session("stored-1", "Bot Chat"), listOf(atlas)))
        assertTrue(BotChatHygiene.isCanonicalBotChatRow(session("runtime-1", "Bot Chat", alternate = setOf("stored-1")), listOf(atlas)))
        assertTrue(BotChatHygiene.isCanonicalBotChatRow(session("stray-1", "Bot Chat", profile = "Atlas"), listOf(atlas)))
        assertFalse(BotChatHygiene.isCanonicalBotChatRow(session("user-1", "Bot Chat", profile = "default"), listOf(atlas)))
        assertFalse(BotChatHygiene.isCanonicalBotChatRow(session("bot-work-1", "Project planning", profile = "atlas"), listOf(atlas)))
        assertFalse(BotChatHygiene.isCanonicalBotChatRow(session("user-2", "Groceries"), listOf(atlas)))
    }

    // endregion

    // region management

    @Test
    fun slugMatchesDesktop() {
        assertEquals("ada-lovelace", BotProfileSlug.slug("Ada Lovelace"))
        assertEquals("hi-there__", BotProfileSlug.slug("  --Hi!! there__ "))
        assertEquals("resume", BotProfileSlug.slug("Résumé"))
        assertEquals("u5c0f-u52a9-u624b", BotProfileSlug.slug("小助手"))
        assertEquals("", BotProfileSlug.slug("!!!"))
        val long = BotProfileSlug.slug("abcdefghi ".repeat(10))
        assertTrue(long.length <= BotProfileSlug.MAX_LENGTH)
        assertFalse(long.endsWith("-"))
        assertTrue(long.endsWith("abcdefghi"))
        assertTrue(BotProfileSlug.isValid(long))
    }

    @Test
    fun slugValidityAndTitle() {
        assertTrue(BotProfileSlug.isValid("atlas"))
        assertTrue(BotProfileSlug.isValid("9lives_bot-2"))
        assertFalse(BotProfileSlug.isValid(""))
        assertFalse(BotProfileSlug.isValid("_atlas"))
        assertFalse(BotProfileSlug.isValid("-atlas"))
        assertFalse(BotProfileSlug.isValid("Atlas"))
        assertFalse(BotProfileSlug.isValid("a".repeat(65)))
        assertEquals("", BotProfileSlug.title("atlas"))
        assertEquals("Ada Lovelace", BotProfileSlug.title(" Ada Lovelace "))
    }

    @Test
    fun colorsParseDesktopSwatchesAndHex() {
        val red = BotAvatarColor.rgb("hsl(0 68% 58%)")!!
        assertEquals(0.8656, red.red, 0.001)
        assertEquals(0.2944, red.green, 0.001)
        assertEquals(0.2944, red.blue, 0.001)
        assertEquals(red, BotAvatarColor.rgb("hsl(0deg, 68%, 58%)"))
        val violet = BotAvatarColor.rgb("#8b5cf6")!!
        assertEquals(139.0 / 255, violet.red, 0.0001)
        assertEquals(246.0 / 255, violet.blue, 0.0001)
        assertNull(BotAvatarColor.rgb("teal"))
        assertEquals(12, BotAvatarColor.swatches.size)
        assertEquals("hsl(0 68% 58%)", BotAvatarColor.swatches.first())
    }

    @Test
    fun fallbackHueMatchesDesktop() {
        val atlas = BotAvatarColor.fallbackRgb("atlas")
        assertEquals(BotAvatarColor.hslToRgb(323.0, 0.68, 0.58), atlas)
        assertEquals(0.8656, atlas.red, 0.001)
        assertEquals(0.6466, atlas.blue, 0.001)
        assertEquals(BotAvatarColor.rgb("#8b5cf6"), BotAvatarColor.fallbackRgb("default"))
    }

    @Test
    fun metaPatchKeepsOtherClientsFieldsAndDropsDataUrls() {
        val existing = json("""{"title":"Scout","shape":"blob:abc:round","sectionId":"s1","groups":["ops"],"image":"data:image/png;base64,AAAA","chat":"legacy"}""") as JsonObject
        val merged = BotMetaPatch.merged(existing, mapOf("color" to JsonPrimitive("hsl(30 68% 58%)"), "pinned" to JsonPrimitive(true), "title" to null))
        assertNull(merged["title"])
        assertEquals("hsl(30 68% 58%)", merged["color"].str)
        assertEquals("true", merged["pinned"].str)
        assertEquals("blob:abc:round", merged["shape"].str)
        assertEquals("s1", merged["sectionId"].str)
        assertEquals("ops", merged["groups"]!!.jsonArray[0].jsonPrimitive.content)
        assertNull(merged["image"])
        assertNull(merged["chat"])
        assertNull(BotMetaPatch.merged(existing, mapOf("shape" to JsonNull))["shape"])
    }

    @Test
    fun metaWriteOutcomeReadsAppliedContract() {
        assertEquals(BotMetaWriteOutcome.PERSISTED, BotMetaWriteOutcome.of(json("""{"applied":{"ui_meta":true}}""")))
        assertEquals(BotMetaWriteOutcome.CONFLICT, BotMetaWriteOutcome.of(json("""{"applied":{"ui_meta":false,"ui_meta_conflicts":{"hermes-bots":{"expected":1,"actual":2}}}}""")))
        assertEquals(BotMetaWriteOutcome.FAILED, BotMetaWriteOutcome.of(json("""{"applied":{"ui_meta":false}}""")))
        assertEquals(BotMetaWriteOutcome.UNSUPPORTED, BotMetaWriteOutcome.of(json("""{"ok":true}""")))
        assertEquals(listOf("soul"), BotConfigureSections.failed(json("""{"applied":{"soul":false,"description":true}}"""), listOf("description", "soul")))
    }

    @Test
    fun composedSoul() {
        val identity = BotSoul.compose("ada-lovelace", "Ada Lovelace", "Math tutor", "", serverInjectsProtocol = true, roster = emptyList())
        assertTrue(identity.startsWith("# Ada Lovelace"))
        assertTrue("**Mission:** Math tutor" in identity)
        assertTrue("(profile `ada-lovelace`)" in identity)
        assertFalse(BotSoul.PROTOCOL_HEADING in identity)

        val teammate = BotProfile("atlas", profileDescription = "Research")
        val hidden = BotProfile("ops-internal", isHiddenByMeta = true)
        val custom = BotSoul.compose("nova", "", "", "You are Nova.", serverInjectsProtocol = false, roster = listOf(teammate, hidden))
        assertFalse("ops-internal" in custom)
        assertTrue(custom.startsWith("You are Nova."))
        assertTrue(BotSoul.PROTOCOL_HEADING in custom)
        assertTrue("- `atlas` — Research" in custom)
        assertTrue("Message from 🤖 nova (@nova)" in custom)
        val already = "Me.\n\n" + BotSoul.PROTOCOL_HEADING + "\n..."
        assertEquals(already, BotSoul.compose("nova", "", "", already, serverInjectsProtocol = false, roster = emptyList()))
    }

    @Test
    fun getAssetResultDecodesTheDataUrl() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val found = json("""{"found":true,"mime":"image/png","data":"${BotAvatarImage.dataUrl(png)}"}""")
        assertArrayEquals(png, BotAvatarImage.imageData(found))
        assertNull(BotAvatarImage.imageData(json("""{"found":false}""")))
        assertNotNull(BotAvatarImage.imageData(json("""{"found":true,"data":"iVBORw=="}""")))
    }

    // endregion
}

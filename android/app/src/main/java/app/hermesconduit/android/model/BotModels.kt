package app.hermesconduit.android.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.text.Normalizer
import java.util.Base64

/**
 * Hermes Bot Mode (port of iOS `BotModeModels.swift` / `BotManagement.swift`).
 *
 * A bot is an ordinary Hermes profile; its canonical DM is the profile's ONE
 * session titled exactly "Bot Chat". Identity is the NAME, consulted on every
 * open — never a stored client-side session pointer.
 */
object BotMode {
    /** Wire constant, never localized. */
    const val CANONICAL_CHAT_TITLE = "Bot Chat"
    const val LOOKUP_SESSION_LIMIT = 200
    /** The `ui_meta` namespace Desktop's bots plugin owns. */
    const val UI_META_KEY = "hermes-bots"
    const val AVATAR_ASSET = "avatar"
    const val AVATAR_EDGE = 256

    fun isDefaultProfile(name: String) = name.trim().lowercase() == "default"
}

/** Lifecycle of the Bot Mode capability probe (`profiles.list`). */
sealed interface BotModePhase {
    data object Idle : BotModePhase
    data object Loading : BotModePhase
    data object Available : BotModePhase
    data object GatewayUnsupported : BotModePhase
    data class Failed(val message: String) : BotModePhase
}

data class BotCanonicalSession(
    val id: String,
    val resolvedId: String?,
    val lastActive: Double?,
    val preview: String?,
)

data class BotProfile(
    val name: String,
    val botTitle: String? = null,
    val displayName: String = "",
    val profileDescription: String = "",
    val model: String? = null,
    val provider: String? = null,
    val hasAvatar: Boolean = false,
    val isPinned: Boolean = false,
    val isHiddenByMeta: Boolean = false,
    val appearanceColor: String? = null,
    val canonicalSession: BotCanonicalSession? = null,
    val lastActive: Double? = null,
    val lastPreview: String? = null,
    val previousNames: List<String> = emptyList(),
    val isDefault: Boolean = false,
    /** The raw `ui_meta['hermes-bots']` object, written back whole. */
    val botMeta: JsonObject = JsonObject(emptyMap()),
    /** `ui_meta_revisions['hermes-bots']`; null on gateways without CAS. */
    val botMetaRevision: Int? = null,
) {
    val isDeletable: Boolean get() = !isDefault && !BotMode.isDefaultProfile(name)

    val displayLabel: String
        get() {
            val title = botTitle?.trim().orEmpty()
            if (title.isNotEmpty()) return title
            val display = displayName.trim()
            return display.ifEmpty { name }
        }

    val canonicalSessionId: String? get() = canonicalSession?.id?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        /** Pinned first, then most recent activity, then name. */
        fun displayOrder(bots: List<BotProfile>): List<BotProfile> = bots.sortedWith { l, r ->
            if (l.isPinned != r.isPinned) return@sortedWith if (l.isPinned) -1 else 1
            val la = l.canonicalSession?.lastActive ?: l.lastActive ?: 0.0
            val ra = r.canonicalSession?.lastActive ?: r.lastActive ?: 0.0
            if (la != ra) return@sortedWith if (la > ra) -1 else 1
            l.name.compareTo(r.name)
        }
    }
}

data class BotRosterSnapshot(
    val bots: List<BotProfile>,
    val supportsBotProtocol: Boolean,
    val desktopGroups: List<DesktopGroupChat> = emptyList(),
)

/** Exact-integer reader: refuses fractional, out-of-range and string numbers. */
fun JsonElement?.exactInt(): Int? {
    val p = this as? JsonPrimitive ?: return null
    if (p is JsonNull || p.isString) return null
    p.content.toLongOrNull()?.let { return if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else null }
    val d = p.content.toDoubleOrNull() ?: return null
    if (!d.isFinite() || d != Math.floor(d) || d < Int.MIN_VALUE || d > Int.MAX_VALUE) return null
    return d.toInt()
}

object BotRosterDecoder {
    /** Null when the payload is not a profiles envelope (a decode failure, never "no bots"). */
    fun decode(result: JsonElement?): BotRosterSnapshot? {
        val rows = result.obj?.get("profiles").arr ?: return null
        return BotRosterSnapshot(
            bots = rows.mapNotNull { it.obj?.let(::decodeRow) },
            supportsBotProtocol = result.obj?.get("bot_mode_protocol").bool ?: false,
            desktopGroups = DesktopGroupChatDecoder.decode(rows),
        )
    }

    private fun decodeRow(o: JsonObject): BotProfile? {
        val name = o["name"].str?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val meta = o["ui_meta"].obj?.get(BotMode.UI_META_KEY).obj
        val revisions = o["ui_meta_revisions"].obj
        return BotProfile(
            name = name,
            botTitle = meta?.get("title").str,
            displayName = o["display_name"].str.orEmpty(),
            profileDescription = o["description"].str.orEmpty(),
            model = o["model"].str,
            provider = o["provider"].str,
            hasAvatar = o["has_avatar"].bool ?: false,
            isPinned = meta?.get("pinned").bool ?: false,
            isHiddenByMeta = meta?.get("hidden").bool ?: false,
            appearanceColor = meta?.get("color").str,
            canonicalSession = decodeCanonical(o["canonical_session"]),
            lastActive = o["last_session"].obj?.get("last_active").double,
            lastPreview = o["last_session"].obj?.get("preview").str,
            previousNames = o["previous_names"].arr?.mapNotNull { it.str?.takeIf(String::isNotEmpty) }.orEmpty(),
            isDefault = o["is_default"].bool ?: false,
            botMeta = meta ?: JsonObject(emptyMap()),
            // Present-but-missing key is revision 0.
            botMetaRevision = revisions?.let { it[BotMode.UI_META_KEY].exactInt() ?: 0 },
        )
    }

    private fun decodeCanonical(value: JsonElement?): BotCanonicalSession? {
        val o = value.obj ?: return null
        val id = o["id"].str?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return BotCanonicalSession(id, o["resolved_id"].str?.trim(), o["last_active"].double, o["preview"].str)
    }
}

data class BotChatLookupRow(
    val id: String,
    val resolvedId: String? = null,
    val title: String? = null,
    val rootTitle: String? = null,
) {
    /** The root title wins when present. */
    fun isCanonicalTitle(): Boolean {
        val root = rootTitle?.trim().orEmpty()
        if (root.isNotEmpty()) return root == BotMode.CANONICAL_CHAT_TITLE
        return title?.trim().orEmpty() == BotMode.CANONICAL_CHAT_TITLE
    }

    val resumeTargetId: String get() = resolvedId?.trim()?.takeIf { it.isNotEmpty() } ?: id
}

object BotChatLookupDecoder {
    /** FAIL-CLOSED: one malformed row makes the whole answer null ("lookup failed"). */
    fun decode(result: JsonElement?): List<BotChatLookupRow>? {
        val rows = result.obj?.get("sessions").arr ?: return null
        val out = ArrayList<BotChatLookupRow>(rows.size)
        for (row in rows) {
            val o = row.obj ?: return null
            val id = o["id"].str?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            out += BotChatLookupRow(id, o["resolved_id"].str, o["title"].str, o["root_title"].str)
        }
        return out
    }
}

sealed interface BotChatResolution {
    data class OpenExisting(val registryId: String, val resumeId: String) : BotChatResolution
    data object Create : BotChatResolution
    /** The lookup was empty while the roster still confirms a canonical chat. */
    data object UnconfirmedAbsence : BotChatResolution
}

object BotChatResolver {
    fun resolve(rows: List<BotChatLookupRow>, rosterCanonicalId: String?): BotChatResolution {
        val canonical = rows.filter { it.isCanonicalTitle() }
        val confirmed = rosterCanonicalId?.trim()?.takeIf { it.isNotEmpty() }
        if (canonical.isNotEmpty()) {
            val pinned = confirmed?.let { c -> canonical.firstOrNull { it.id == c || it.resolvedId == c } }
            val match = pinned ?: canonical.first()
            return BotChatResolution.OpenExisting(match.id, match.resumeTargetId)
        }
        return if (confirmed != null) BotChatResolution.UnconfirmedAbsence else BotChatResolution.Create
    }
}

/** RPC error classification shared by Bot Mode and Group Chats. */
object GatewayErrors {
    private fun parts(error: Throwable): Pair<Int?, String> {
        val rpc = error as? app.hermesconduit.android.net.RpcException
        return (rpc?.code) to (error.message ?: error.toString())
    }

    fun isMissingMethod(error: Throwable): Boolean {
        val (code, message) = parts(error)
        if (code == -32601) return true
        val lowered = message.lowercase()
        return "method not found" in lowered || "-32601" in lowered || "unknown method" in lowered || "no such method" in lowered
    }

    fun isTitleCollision(error: Throwable): Boolean {
        val (code, message) = parts(error)
        return code == 4022 || "already in use" in message.lowercase()
    }

    fun isRoomNotFound(error: Throwable): Boolean {
        val (code, message) = parts(error)
        return code == 4112 || code == 4114 || "hosted room not found" in message.lowercase()
    }
}

/** Sessions-list hygiene: canonical Bot Chats never surface as ordinary sessions. */
object BotChatHygiene {
    fun isCanonicalBotChatRow(row: SessionSummary, roster: List<BotProfile>): Boolean {
        val ids = row.alternateIds + row.id + listOfNotNull(row.runtimeId)
        for (bot in roster) {
            bot.canonicalSession?.let { c ->
                if (c.id.trim() in ids) return true
                c.resolvedId?.trim()?.takeIf { it.isNotEmpty() }?.let { if (it in ids) return true }
            }
            val owner = row.profile?.trim().orEmpty()
            if (row.title.trim() == BotMode.CANONICAL_CHAT_TITLE && owner.isNotEmpty() && owner.equals(bot.name.trim(), ignoreCase = true)) {
                return true
            }
        }
        return false
    }
}

/** Upstream `slugifyProfileName`: the ASCII profile id derived from a typed name. */
object BotProfileSlug {
    const val MAX_LENGTH = 64

    fun slug(raw: String): String {
        val source = Normalizer.normalize(raw.trim(), Normalizer.Form.NFC)
        val folded = StringBuilder()
        var i = 0
        while (i < source.length) {
            val cp = source.codePointAt(i)
            i += Character.charCount(cp)
            if (!Character.isLetterOrDigit(cp) && !isNumber(cp)) {
                folded.appendCodePoint(cp)
                continue
            }
            val base = withoutMarks(String(Character.toChars(cp)))
            if (base.isNotEmpty() && base.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }) folded.append(base)
            else folded.append("-u").append(Integer.toHexString(cp)).append('-')
        }
        val slug = StringBuilder()
        var pendingDash = false
        for (c in folded.toString().lowercase()) {
            if (isSlugChar(c)) {
                if (pendingDash && slug.isNotEmpty()) slug.append('-')
                pendingDash = false
                slug.append(c)
            } else {
                pendingDash = true
            }
        }
        var result = collapseDashes(slug.toString()).trim('-')
        if (result.length <= MAX_LENGTH) return result
        val cut = result.substring(0, MAX_LENGTH)
        if (result[MAX_LENGTH] == '-') return cut.trim('-')
        val boundary = cut.lastIndexOf('-')
        result = if (boundary > 0) cut.substring(0, boundary).trim('-') else cut
        return result
    }

    /** `^[a-z0-9][a-z0-9_-]{0,63}$`. */
    fun isValid(slug: String): Boolean {
        if (slug.isEmpty() || slug.length > MAX_LENGTH) return false
        val first = slug[0]
        if (!(first in 'a'..'z' || first in '0'..'9')) return false
        return slug.all(::isSlugChar)
    }

    /** The display title a name implies: empty when it already reads like the id. */
    fun title(raw: String): String {
        val name = raw.trim()
        return if (name == slug(name)) "" else name
    }

    private fun isNumber(cp: Int) = when (Character.getType(cp).toByte()) {
        Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true
        else -> false
    }

    private fun withoutMarks(s: String): String {
        val decomposed = Normalizer.normalize(s, Normalizer.Form.NFKD)
        val out = StringBuilder()
        var i = 0
        while (i < decomposed.length) {
            val cp = decomposed.codePointAt(i)
            i += Character.charCount(cp)
            when (Character.getType(cp).toByte()) {
                Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> Unit
                else -> out.appendCodePoint(cp)
            }
        }
        return out.toString()
    }

    private fun isSlugChar(c: Char) = c in 'a'..'z' || c in '0'..'9' || c == '_' || c == '-'

    private fun collapseDashes(value: String): String {
        val out = StringBuilder()
        for (c in value) if (!(c == '-' && out.lastOrNull() == '-')) out.append(c)
        return out.toString()
    }
}

/** `ui_meta['hermes-bots'].color` as Desktop writes it (CSS hsl/hex). */
object BotAvatarColor {
    data class RGB(val red: Double, val green: Double, val blue: Double)

    /** Desktop's `PROFILE_SWATCHES`. */
    val swatches: List<String> = (0 until 12).map { "hsl(${it * 30} 68% 58%)" }
    const val PRIMARY_HEX = "#8b5cf6"

    fun rgb(css: String): RGB? {
        val value = css.trim().lowercase()
        if (value.startsWith("#")) return hexRgb(value.drop(1))
        val open = value.indexOf('(')
        val close = value.lastIndexOf(')')
        if (!value.startsWith("hsl") || open < 0 || close <= open) return null
        val parts = value.substring(open + 1, close)
            .split(' ', ',', '/')
            .map { it.trim('%', 'd', 'e', 'g') }
            .filter { it.isNotEmpty() }
        if (parts.size < 3) return null
        val h = parts[0].toDoubleOrNull() ?: return null
        val s = parts[1].toDoubleOrNull() ?: return null
        val l = parts[2].toDoubleOrNull() ?: return null
        return hslToRgb(h, s / 100, l / 100)
    }

    /** Desktop's `profileColor(name)`: `hsl(hash % 360 68% 58%)`. */
    fun fallbackRgb(name: String): RGB {
        val key = name.trim()
        if (key.isEmpty() || key == "default") return rgb(PRIMARY_HEX) ?: RGB(0.55, 0.36, 0.96)
        var hash = 0
        for (unit in key) hash = hash * 31 + unit.code
        val positive = hash.toLong() and 0xffffffffL
        return hslToRgb((positive % 360).toDouble(), 0.68, 0.58)
    }

    fun hslToRgb(hue: Double, saturation: Double, lightness: Double): RGB {
        val h = ((hue % 360) + 360) % 360 / 360
        val s = saturation.coerceIn(0.0, 1.0)
        val l = lightness.coerceIn(0.0, 1.0)
        if (s <= 0) return RGB(l, l, l)
        val q = if (l < 0.5) l * (1 + s) else l + s - l * s
        val p = 2 * l - q
        fun channel(t0: Double): Double {
            var t = t0
            if (t < 0) t += 1
            if (t > 1) t -= 1
            if (t < 1.0 / 6) return p + (q - p) * 6 * t
            if (t < 0.5) return q
            if (t < 2.0 / 3) return p + (q - p) * (2.0 / 3 - t) * 6
            return p
        }
        return RGB(channel(h + 1.0 / 3), channel(h), channel(h - 1.0 / 3))
    }

    /** Named colors earlier Conduit builds wrote. */
    fun named(name: String): RGB? = when (name.trim().lowercase()) {
        "blue" -> RGB(0.0, 0.478, 1.0)
        "brown" -> RGB(0.635, 0.518, 0.369)
        "cyan" -> RGB(0.196, 0.678, 0.902)
        "green" -> RGB(0.204, 0.78, 0.349)
        "indigo" -> RGB(0.345, 0.337, 0.839)
        "mint" -> RGB(0.0, 0.78, 0.745)
        "orange" -> RGB(1.0, 0.584, 0.0)
        "pink" -> RGB(1.0, 0.176, 0.333)
        "purple" -> RGB(0.686, 0.322, 0.871)
        "red" -> RGB(1.0, 0.231, 0.188)
        "teal" -> RGB(0.188, 0.69, 0.78)
        "yellow" -> RGB(1.0, 0.8, 0.0)
        else -> null
    }

    /** What a bot's monogram is painted with. */
    fun resolve(css: String?, name: String): RGB = css?.let { named(it) ?: rgb(it) } ?: fallbackRgb(name)

    private fun hexRgb(hex: String): RGB? {
        val digits = if (hex.length == 3) hex.map { "$it$it" }.joinToString("") else hex
        if (digits.length != 6) return null
        val value = digits.toLongOrNull(16) ?: return null
        return RGB(((value shr 16) and 0xff) / 255.0, ((value shr 8) and 0xff) / 255.0, (value and 0xff) / 255.0)
    }
}

object BotMetaPatch {
    val strippedKeys = setOf("image", "pet", "chat")

    /** The full `hermes-bots` value to write; a null patch value deletes that field. */
    fun merged(existing: JsonObject, patch: Map<String, JsonElement?>): JsonObject {
        val result = existing.toMutableMap()
        for ((key, value) in patch) {
            if (value == null || value is JsonNull) result.remove(key) else result[key] = value
        }
        strippedKeys.forEach(result::remove)
        return JsonObject(result)
    }
}

enum class BotMetaWriteOutcome {
    PERSISTED, CONFLICT, FAILED, UNSUPPORTED;

    companion object {
        fun of(result: JsonElement?): BotMetaWriteOutcome {
            val applied = result.obj?.get("applied").obj ?: return UNSUPPORTED
            return when {
                applied["ui_meta"].bool == true -> PERSISTED
                applied["ui_meta_conflicts"].obj?.isNotEmpty() == true -> CONFLICT
                else -> FAILED
            }
        }
    }
}

object BotConfigureSections {
    fun failed(result: JsonElement?, among: List<String>): List<String> {
        val applied = result.obj?.get("applied").obj ?: JsonObject(emptyMap())
        return among.filter { applied[it].bool == false }
    }
}

/** Upstream `composeSoul`. */
object BotSoul {
    const val PROTOCOL_HEADING = "## Messaging other agents"

    fun compose(name: String, title: String, description: String, customSoul: String, serverInjectsProtocol: Boolean, roster: List<BotProfile>): String {
        val custom = customSoul.trim()
        if (custom.isNotEmpty()) {
            if (serverInjectsProtocol || PROTOCOL_HEADING in custom) return custom
            return custom + "\n\n" + messagingProtocol(name, roster)
        }
        val display = title.trim().ifEmpty { name }
        val mission = description.trim()
        val lines = mutableListOf("# $display", "")
        if (mission.isNotEmpty()) lines += listOf("**Mission:** $mission", "")
        lines += listOf(
            "You are $display, a persistent named agent (profile `$name`) on this machine.",
            "You keep your own memory, skills, and conversation history across sessions.",
        )
        val identity = lines.joinToString("\n")
        return if (serverInjectsProtocol) identity else identity + "\n\n" + messagingProtocol(name, roster)
    }

    fun messagingProtocol(name: String, roster: List<BotProfile>): String {
        val handle = name
        val teammates = roster.filter { it.name != name && !it.isHiddenByMeta }
        val lines = mutableListOf(
            PROTOCOL_HEADING,
            "",
            "You work alongside other named agents. Every agent (including you) has",
            "ONE canonical conversation titled \"Bot Chat\" — created with the agent,",
            "so it always exists. Agent-to-agent messages are delivered straight",
            "into it, like a DM. To message a teammate, run:",
            "",
            "```",
            "hermes -p <agent-name> chat --in ~ -c \"Bot Chat\" --create-if-missing -Q -q \"Message from 🤖 $handle (@$handle): your message\"",
            "",
            "Run the send with background=true and notify_on_complete=true on the",
            "terminal tool, then finish your turn — the reply arrives later as a",
            "background process notification. Never block waiting for it.",
            "```",
            "",
            "(`--in ~ -c \"Bot Chat\" --create-if-missing` resumes their canonical",
            "conversation in the home workspace, creating it if the target has no",
            "\"Bot Chat\" yet. `-Q` keeps output clean. Always open with the",
            "\"Message from 🤖 $handle (@$handle):\" prefix so they know",
            "who is talking (the @handle lets the app show your avatar to them).",
            "Their reply prints to stdout — relay the relevant part back to the",
            "user, and say which agent it came from.)",
            "",
            "If a message in YOUR chat starts with \"Message from 🤖 <name>\", it is",
            "a teammate messaging you, not the user. Answer it directly — your reply",
            "reaches them via their own delivery — and use the same command if you",
            "need to start a conversation yourself.",
            "",
            "When the user writes @<agent-name> or says \"ask <name> to ...\" /",
            "\"tell <name> ...\", that is a handoff: message that agent, wait for the",
            "reply, and report back.",
            "",
            "The roster grows over time — run `hermes profile list` for the LIVE",
            "teammate list before a handoff. Teammates when you were created:",
        )
        if (teammates.isEmpty()) lines += "- (none yet)"
        else lines += teammates.map { bot ->
            val detail = bot.profileDescription.trim()
            if (detail.isEmpty()) "- `${bot.name}`" else "- `${bot.name}` — $detail"
        }
        return lines.joinToString("\n")
    }
}

object BotAvatarImage {
    fun dataUrl(png: ByteArray): String = "data:image/png;base64," + Base64.getEncoder().encodeToString(png)

    /** The bytes of a `profiles.get_asset` answer; null for an absent or unreadable asset. */
    fun imageData(result: JsonElement?): ByteArray? {
        val o = result.obj ?: return null
        if (o["found"].bool != true) return null
        val url = o["data"].str ?: return null
        val payload = if (url.startsWith("data:")) {
            val comma = url.indexOf(',')
            if (comma < 0 || !url.substring(0, comma).endsWith(";base64")) return null
            url.substring(comma + 1)
        } else url
        return runCatching { Base64.getMimeDecoder().decode(payload) }.getOrNull()
    }
}

/** What `profiles.describe` returns that the editor needs. */
data class BotProfileDetails(val description: String, val soul: String)

data class BotEditorDraft(
    val name: String = "",
    val description: String = "",
    val soul: String = "",
    /** A swatch CSS string, or null for the name's own hue. */
    val color: String? = null,
    /** A freshly chosen picture (normalized PNG). */
    val newAvatarPng: ByteArray? = null,
    val removesAvatar: Boolean = false,
)

sealed interface BotSaveResult {
    data class Saved(val warning: String?) : BotSaveResult
    data class Failed(val message: String) : BotSaveResult
}

/** Builds `ui_meta` JSON values without pulling in a builder at every call site. */
internal fun jsonOf(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to jsonOf(v) })
    is List<*> -> JsonArray(value.map(::jsonOf))
    else -> JsonPrimitive(value.toString())
}

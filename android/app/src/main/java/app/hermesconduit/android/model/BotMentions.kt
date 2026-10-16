package app.hermesconduit.android.model

/**
 * Bot @mentions (port of iOS `BotMentions.swift`, upstream Desktop's
 * composer middleware). The user's text is sent UNCHANGED except for a
 * trailing identification note telling the ACTIVE bot which teammate each
 * `@tag` refers to. Unknown tokens, ambiguous handles and e-mail addresses
 * stay ordinary text.
 */
object BotMentions {
    const val MAX_ANNOTATED_BOTS = 16
    val reservedTokens = setOf("all", "everyone", "user", "default", "hermes")

    /** `(^|\s)@token(@connection)?` — the leading boundary is the e-mail guard. */
    private val mentionScan = Regex("(^|\\s)@([a-z0-9][a-z0-9_-]*)(?:@([a-z0-9][a-z0-9_-]*))?", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
    private val fencedCode = Regex("```[\\s\\S]*?```")
    private val inlineCode = Regex("`[^`\\n]*`")

    fun textMightContainMention(text: String) = mentionScan.containsMatchIn(text)

    private sealed interface Owner {
        data class Bot(val bot: BotProfile) : Owner
        data object Ambiguous : Owner
    }

    fun resolve(text: String, roster: List<BotProfile>, activeProfileName: String?): List<BotProfile> {
        val prose = proseStrippingCode(text)
        val owners = mutableMapOf<String, Owner>()
        val active = activeProfileName?.trim()
        for (bot in roster) if (!isActive(bot, active)) for (form in resolvableForms(bot)) claim(form, bot, owners)
        val liveForms = roster.flatMap { resolvableForms(it) }.toSet()
        for (bot in roster) if (!isActive(bot, active)) {
            for (previous in bot.previousNames) for (form in mentionNameForms(previous)) {
                if (form !in liveForms) claim(form, bot, owners)
            }
        }
        val mentioned = mutableListOf<BotProfile>()
        val seen = mutableSetOf<String>()
        for (match in mentionScan.findAll(prose)) {
            if (match.groups[3] != null) continue
            val token = match.groups[2]?.value?.lowercase() ?: continue
            val owner = owners[token] as? Owner.Bot ?: continue
            if (seen.add(owner.bot.name)) {
                mentioned += owner.bot
                if (mentioned.size >= MAX_ANNOTATED_BOTS) break
            }
        }
        return mentioned
    }

    /** The primary profile answers to `@hermes`. */
    fun handle(bot: BotProfile): String {
        val name = bot.name.trim()
        return if (name.lowercase() == "default") "hermes" else name
    }

    fun mentionTag(bot: BotProfile): String {
        for (friendly in friendlyNames(bot)) mentionNameForms(friendly).firstOrNull()?.let { return it }
        return handle(bot)
    }

    fun mentionNameForms(value: String?): List<String> {
        val name = value.orEmpty().trim().lowercase()
        if (name.isEmpty()) return emptyList()
        val slug = StringBuilder()
        var inRun = false
        for (c in name) {
            if (isTokenChar(c)) {
                slug.append(c)
                inRun = false
            } else if (!inRun) {
                slug.append('-')
                inRun = true
            }
        }
        val slugForm = slug.toString().trim('-')
        val collapsed = name.filter(::isTokenChar)
        val forms = mutableListOf<String>()
        for (form in listOf(slugForm, collapsed)) {
            if (isValidMentionToken(form) && form !in reservedTokens && form !in forms) forms += form
        }
        return forms
    }

    /** `^[a-z0-9][a-z0-9_-]*$`, case-insensitive. */
    fun isValidMentionToken(value: String): Boolean {
        val lowered = value.lowercase()
        val first = lowered.firstOrNull() ?: return false
        if (first == '_' || first == '-' || !isTokenChar(first)) return false
        return lowered.drop(1).all(::isTokenChar)
    }

    fun friendlyNames(bot: BotProfile): List<String?> = listOf(bot.botTitle, bot.displayName)

    fun annotated(text: String, mentions: List<BotProfile>): String {
        if (mentions.isEmpty()) return text
        val lines = mentions.map { bot ->
            val title = bot.botTitle?.trim().orEmpty()
            "@${handle(bot)} = agent profile \"${bot.name}\"" + if (title.isEmpty()) "" else " (\"$title\")"
        }
        val note = "\n\n[@mentions resolved from the Bot Mode roster — the user is referring to: " +
            lines.joinToString("; ") +
            ". If they want one of these agents contacted, compose your own message and send it with your message_agent tool (agents on other connected machines are reachable too — the Desktop relays it); never forward the user’s text verbatim. If this session has no message_agent tool, agent messaging is unavailable here — say so.]"
        return text + note
    }

    /** Null means "send the text untouched". Quoted lines are not scanned. */
    fun middlewareAnnotation(text: String, roster: List<BotProfile>, activeProfileName: String?): String? {
        val ownWords = ChatQuote.removingQuotedLines(text)
        if (roster.isEmpty() || !textMightContainMention(ownWords)) return null
        val mentions = resolve(ownWords, roster, activeProfileName)
        if (mentions.isEmpty()) return null
        return annotated(text, mentions)
    }

    /** `/new` and `/reset` inside a canonical Bot Chat reroute to `/compact`. */
    fun foreverChatRerouteText(text: String, isCanonicalBotChat: Boolean): String? {
        if (!isCanonicalBotChat) return null
        val trimmed = text.trim()
        return if (trimmed == "/new" || trimmed == "/reset") "/compact" else null
    }

    fun resolvableForms(bot: BotProfile): Set<String> {
        val forms = mutableSetOf(handle(bot).lowercase(), bot.name.trim().lowercase())
        for (friendly in friendlyNames(bot)) forms += mentionNameForms(friendly)
        return forms
    }

    private fun claim(form: String, bot: BotProfile, owners: MutableMap<String, Owner>) {
        when (val existing = owners[form]) {
            null -> owners[form] = Owner.Bot(bot)
            is Owner.Bot -> if (existing.bot != bot) owners[form] = Owner.Ambiguous
            Owner.Ambiguous -> Unit
        }
    }

    private fun isActive(bot: BotProfile, active: String?): Boolean {
        if (active.isNullOrEmpty()) return false
        return bot.name.trim().equals(active, ignoreCase = true)
    }

    private fun isTokenChar(c: Char) = c in 'a'..'z' || c in '0'..'9' || c == '_' || c == '-'

    fun proseStrippingCode(text: String): String = inlineCode.replace(fencedCode.replace(text, " "), " ")
}

object ChatQuote {
    /** The text without its `>` quoted lines (someone else's words). */
    fun removingQuotedLines(text: String): String =
        text.replace("\r\n", "\n").split('\n', '\r')
            .filterNot { it.trimStart(' ', '\t').startsWith(">") }
            .joinToString("\n")
}

/** The trailing `@query` in a composer draft and what it could complete to. */
object MentionAutocomplete {
    data class Candidate(val tag: String, val title: String)

    private fun isAsciiAlnum(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'
    private fun isTagChar(c: Char) = isAsciiAlnum(c) || c in "._:-"

    fun activeQuery(text: String): String? {
        val at = text.lastIndexOf('@')
        if (at < 0) return null
        if (at > 0 && !text[at - 1].isWhitespace()) return null
        val query = text.substring(at + 1)
        query.firstOrNull()?.let { if (!isAsciiAlnum(it)) return null }
        if (!query.all(::isTagChar)) return null
        return query.lowercase()
    }

    fun filter(candidates: List<Candidate>, query: String): List<Candidate> {
        if (query.isEmpty()) return candidates
        return candidates.filter { c ->
            c.tag.lowercase().startsWith(query) || c.title.lowercase().split(Regex("\\s+")).any { it.startsWith(query) }
        }
    }

    fun completing(text: String, candidate: Candidate): String {
        if (activeQuery(text) == null) return text
        val at = text.lastIndexOf('@')
        return text.substring(0, at) + "@" + candidate.tag + " "
    }

    /** Room members, then `@all`. */
    fun roomCandidates(members: List<GroupMember>, everyoneTitle: String = "Everyone"): List<Candidate> {
        val seen = mutableSetOf("all", "everyone", "user")
        val result = mutableListOf<Candidate>()
        for (member in members) {
            val handle = member.handle?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            if (!seen.add(handle.lowercase())) continue
            result += Candidate(handle, GroupRoomTurns.displayName(member))
        }
        result += Candidate("all", everyoneTitle)
        return result
    }

    /** Roster bots other than the listener, tagged the way the middleware resolves them. */
    fun botCandidates(roster: List<BotProfile>, activeProfileName: String?): List<Candidate> {
        val active = activeProfileName?.trim().orEmpty()
        val others = roster.filter { active.isEmpty() || !it.name.trim().equals(active, ignoreCase = true) }
        val claims = mutableMapOf<String, Int>()
        for (bot in others) for (form in BotMentions.resolvableForms(bot)) claims[form] = (claims[form] ?: 0) + 1
        val seen = mutableSetOf<String>()
        return others.mapNotNull { bot ->
            if (bot.isHiddenByMeta) return@mapNotNull null
            val tag = listOf(BotMentions.mentionTag(bot), BotMentions.handle(bot)).firstOrNull { (claims[it.lowercase()] ?: 0) == 1 }
                ?: return@mapNotNull null
            if (!seen.add(tag.lowercase())) return@mapNotNull null
            Candidate(tag, bot.displayLabel)
        }
    }
}

package app.hermesconduit.wearwire

/**
 * GPT-Live's delegations in a watch call (port of the iOS
 * `WatchBridgeDelegation`). GPT-Live has no tools of its own on the
 * subscription: it hands real work to the app, and on the phone each
 * delegation runs as a Hermes background job. The watch does the same
 * through the call's grant. The texts mirror the phone's. Written for the
 * model, not shown, so not localized.
 */
object WatchBridgeDelegation {
    /** GPTLiveConversationController.delegationContextMarker. */
    const val CONTEXT_MARKER = "\n\n[Recent voice conversation, for context only. Do just the request above: earlier requests marked \"handled separately\" were passed on before (to a job, the chat, or an answer, or turned down), so skip them unless the request above asks for them.]\n"
    /** The host takes up to 3,000 bytes of instructions for a watch job; JSON escaping needs some room. */
    const val MAX_REQUEST_BYTES = 2_600
    /** The lines of conversation a request carries for context, at most. */
    const val CONTEXT_LINES = 8

    /** The call's grant ran out, for a relay call that failed: one that may have gone out counts only when the relay or Hermes turned it away. */
    fun grantRanOut(reason: String, grantGone: Boolean, sent: Boolean): Boolean =
        if (sent) refused(reason) else grantGone || reason == "grantExpiring"

    fun refused(reason: String): Boolean =
        reason in setOf("relay 401", "relay 404", "relay 410", "host 403", "host 410") || reason.endsWith(" grant_exhausted")

    private val quickMarker = Regex("^(quickly|quick)\\s*[:,，：]\\s*|^(快速|快)\\s*[:,，：]\\s*", RegexOption.IGNORE_CASE)

    /** "Quick: …" and "quickly, …": routing, not part of the task. */
    fun removingQuickMarker(request: String): String {
        val trimmed = request.trim()
        val marker = quickMarker.find(trimmed) ?: return trimmed
        val rest = trimmed.substring(marker.range.last + 1)
        return rest.ifEmpty { trimmed }
    }

    /** Each running job by its number, as the phone tells GPT-Live. */
    fun statusContext(jobs: List<Triple<Int, String, String>>): String {
        if (jobs.isEmpty()) return "[Background jobs: none running.]"
        return "[Background jobs on Hermes: " + jobs.joinToString("; ") { (number, title, status) -> "Job $number ($title): ${statusName(status)}" } + ".]"
    }

    fun statusName(status: String) = if (status == "needs_approval") "needs_approval_on_the_watch" else status

    data class Line(val role: WatchVoiceWire.DirectTurn.Role, val text: String, val handled: Boolean)

    /**
     * The work a delegation asks for: its own text when it carries any,
     * otherwise the user's words since the last delegation, with the recent
     * conversation for context, within the host's limit. Empty when there's
     * nothing to ask for.
     */
    fun request(itemText: String, lines: List<Line>): String {
        val own = itemText.trim()
        val userWords = lines.filter { it.role == WatchVoiceWire.DirectTurn.Role.USER && !it.handled }.joinToString(" ") { it.text }
        val request = clipped(own.ifEmpty { userWords }, MAX_REQUEST_BYTES)
        if (request.isEmpty()) return ""
        var budget = MAX_REQUEST_BYTES - request.utf8Size - CONTEXT_MARKER.utf8Size
        var context = ""
        for (line in lines.takeLast(CONTEXT_LINES).asReversed()) {
            val speaker = if (line.role == WatchVoiceWire.DirectTurn.Role.USER) (if (line.handled) "User (handled separately): " else "User: ") else "Assistant: "
            val text = speaker + line.text + "\n"
            if (text.utf8Size > budget) break
            budget -= text.utf8Size
            context = text + context
        }
        return if (context.isEmpty()) request else request + CONTEXT_MARKER + context
    }

    /** [text] cut to [bytes] UTF-8 bytes, never inside a character. */
    fun clipped(text: String, bytes: Int): String {
        if (text.utf8Size <= bytes) return text
        return WatchVoiceWire.DirectTranscript.clippedPrefix(text, bytes)
    }

    /** The quiet note once Hermes took the job; with the job's number in this call when it can take corrections. */
    fun working(title: String, number: Int? = null): String {
        if (number == null) return "Hermes is working on this as a background job (\"$title\"). Its result will follow on this delegation; don't guess it."
        return "Hermes is working on this as background job $number (\"$title\"). Its result will follow on this delegation; don't guess it. If the user corrects or changes it while it runs, delegate their words starting with \"Job $number:\"."
    }

    private val jobMarkerPattern = Regex("^\\s*job\\s*#?\\s*[0-9]{1,4}\\s*[:,.\\-–—](?![0-9])", RegexOption.IGNORE_CASE)

    /** "Job 2: make it Alex" → (2, "make it Alex"), only at the very start, never a time or a decimal. */
    fun jobMarker(request: String): Pair<Int, String>? {
        val match = jobMarkerPattern.find(request) ?: return null
        val number = match.value.filter { it in '0'..'9' }.toIntOrNull() ?: return null
        return number to request.substring(match.range.last + 1).trim()
    }

    /** The user's own words since the last delegation, else the delegation's. */
    fun followUpWords(userWords: String, delegated: String): String {
        val spoken = userWords.trim()
        if (spoken.isNotEmpty()) return spoken
        return delegated.split(CONTEXT_MARKER).first().trim()
    }

    /** Taken, quiet, since the model already acknowledged it; not taken, said aloud. */
    fun followUpReply(outcome: WatchJobAnswer.FollowUp): Pair<String, Boolean> = when (outcome) {
        is WatchJobAnswer.FollowUp.Interrupted -> "Conduit put the user's words into ${WatchJobAnswer.quoted(outcome.title)} at once; Hermes keeps its work so far and changes course now. Its result still follows on the earlier delegation; don't guess it." to false
        is WatchJobAnswer.FollowUp.Queued -> "Conduit passed the user's words to ${WatchJobAnswer.quoted(outcome.title)}; Hermes takes them right after the step it is finishing. Its result still follows on the earlier delegation; don't guess it." to false
        is WatchJobAnswer.FollowUp.Finished -> relay("${WatchJobAnswer.quoted(outcome.title)} had already finished, so Hermes didn't get this. Ask the user what they want instead.") to true
        is WatchJobAnswer.FollowUp.Failed -> relay("Hermes didn't get that (${outcome.message}).") to true
        WatchJobAnswer.FollowUp.UnknownJob -> relay("Hermes didn't get that (that job isn't one this call started).") to true
    }

    val followUpsUnavailable = relay("Hermes didn't get that (${WatchJobAnswer.FOLLOW_UPS_NEED_NEWER_PLUGIN}).")

    fun relay(notice: String) = WatchJobAnswer.updatePrompt(notice)

    /** Leads a delegation's result when the user kept talking after asking for it: what they said since comes first. */
    const val RESULT_AFTER_USER_NOTE = "[The user kept talking after asking for this, so this result waited until they finished. If anything they said since hasn't been answered or passed on to Hermes yet, deal with that first (delegate it if Hermes is needed). Then say that Hermes has come back on the earlier request and give what follows, as it asks.]\n\n"

    val noRequest = relay("Hermes didn't get a request to work on. Ask the user what they want done.")

    /** The call's grant is spent or about to end: a watch call's access to Hermes lasts up to half an hour. */
    const val GRANT_RAN_OUT = "this call's access to Hermes has run out. The user can end the call and start a new one."
    /** [GRANT_RAN_OUT] without its closing period, to sit inside a sentence. */
    val grantRanOutClause: String get() = GRANT_RAN_OUT.trimEnd('.')
    /** Too little of the grant is left to follow a new job to its result. */
    const val GRANT_NEARLY_OUT = "this call has used nearly all its access to Hermes, so it can't follow a new job to its result. The user can end the call and start a new one."

    fun notStarted(reason: String) = relay("The job didn't start: $reason")

    private val String.utf8Size: Int get() = toByteArray(Charsets.UTF_8).size
}

/**
 * Which of the user's Hermes profiles a watch job runs on: "for Fam, check
 * the router" runs on Fam (port of the iOS `VoiceJobProfiles`' relay route).
 * The watch resolves the name from the list the phone sends it.
 */
object WatchJobProfiles {
    sealed class Target {
        data object Active : Target()
        data class Other(val profile: String) : Target()
        data object Unknown : Target()
    }

    sealed class RelayRoute {
        /** Through the relay: [profile] null is the call's own. */
        data class Relay(val instructions: String, val profile: String?) : RelayRoute()
        /** One of the user's profiles the host doesn't run this call's jobs on: the phone starts it. */
        data class ViaPhone(val label: String) : RelayRoute()
        /** Not one of the names the phone sent: the phone, which knows them all, answers it. */
        data class Unknown(val name: String) : RelayRoute()
    }

    fun relayRoute(instructions: String, spokenProfile: String?, names: List<WatchVoiceWire.JobProfileName>, hosted: Set<String>): RelayRoute {
        var task = instructions
        var profile: String? = null
        var label: String? = null
        val resolve = { name: String -> target(name, names) }
        val spoken = spokenProfile?.trim()
        if (!spoken.isNullOrEmpty()) {
            when (val target = resolve(spoken)) {
                Target.Active -> Unit
                is Target.Other -> { profile = target.profile; label = spoken }
                Target.Unknown -> return RelayRoute.Unknown(spoken)
            }
            // The model may also leave "for Fam, …" in the task itself.
            val lead = leadingTarget(task, resolve)
            val expected: Target = profile?.let { Target.Other(it) } ?: Target.Active
            if (lead != null && lead.first == expected) task = lead.third
        } else {
            leadingTarget(task, resolve)?.let { (target, name, remainder) ->
                if (target is Target.Other) { profile = target.profile; label = name }
                task = remainder
            }
        }
        val other = profile ?: return RelayRoute.Relay(task, null)
        return if (other.lowercase() in hosted) RelayRoute.Relay(task, other) else RelayRoute.ViaPhone(label ?: other)
    }

    fun viaPhoneReply(name: String) = "Jobs on $name can't start from this Watch call. Tell the user to start them from the phone."

    fun unknownProfileReply(name: String) = "I don't know a profile or bot called $name, so I didn't start the job."

    /** "for Fam, check the router" → (Fam, "check the router"): only for/on/with plus a known name (up to three words). */
    fun leadingTarget(instructions: String, resolve: (String) -> Target): Triple<Target, String, String>? {
        val words = instructions.split(' ', '\n').filter { it.isNotEmpty() }
        if (words.size < 3 || words[0].lowercase() !in setOf("for", "on", "with")) return null
        for (length in minOf(3, words.size - 2) downTo 1) {
            val name = words.subList(1, length + 1).joinToString(" ").trimSeparators()
            if (name.isEmpty()) continue
            val target = resolve(name)
            if (target == Target.Unknown) continue
            val remainder = words.subList(length + 1, words.size).joinToString(" ").trimSeparators()
            if (remainder.isEmpty()) return null
            return Triple(target, name, remainder)
        }
        return null
    }

    /** [name] resolved against the names the phone listed, in its order. */
    fun target(name: String, names: List<WatchVoiceWire.JobProfileName>): Target {
        val wanted = name.trim()
        if (wanted.isEmpty()) return Target.Unknown
        val match = names.firstOrNull { entry -> entry.names.any { it.trim().equals(wanted, ignoreCase = true) } } ?: return Target.Unknown
        return match.profile?.let { Target.Other(it) } ?: Target.Active
    }

    private fun String.trimSeparators() = trim { it.isWhitespace() || isPunctuation(it) }

    private fun isPunctuation(c: Char): Boolean = when (Character.getType(c).toByte()) {
        Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION, Character.END_PUNCTUATION,
        Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION -> true
        else -> false
    }
}

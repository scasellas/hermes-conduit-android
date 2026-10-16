package app.hermesconduit.android.ui.bots

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.hermes
import app.hermesconduit.android.model.BotAvatarImage
import app.hermesconduit.android.model.BotChatLookupDecoder
import app.hermesconduit.android.model.BotChatLookupRow
import app.hermesconduit.android.model.BotChatResolution
import app.hermesconduit.android.model.BotChatResolver
import app.hermesconduit.android.model.BotConfigureSections
import app.hermesconduit.android.model.BotEditorDraft
import app.hermesconduit.android.model.BotMetaPatch
import app.hermesconduit.android.model.BotMetaWriteOutcome
import app.hermesconduit.android.model.BotMode
import app.hermesconduit.android.model.BotModePhase
import app.hermesconduit.android.model.BotProfile
import app.hermesconduit.android.model.BotProfileDetails
import app.hermesconduit.android.model.BotProfileSlug
import app.hermesconduit.android.model.BotSaveResult
import app.hermesconduit.android.model.BotSoul
import app.hermesconduit.android.model.GatewayErrors
import app.hermesconduit.android.model.jsonOf
import app.hermesconduit.android.model.obj
import app.hermesconduit.android.model.str
import app.hermesconduit.android.ui.AppViewModel
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Bot Mode management (port of iOS AppState §Bot Mode + BotManagement): the
 * roster, create/edit/delete/pin, avatars, and resolving a bot's canonical
 * "Bot Chat" before it opens in the ordinary chat surface.
 */
class BotsViewModel(application: Application) : AndroidViewModel(application) {
    private val hermes = application.hermes

    val bots = BotRoster.bots
    val phase = BotRoster.phase
    val avatars = BotRoster.avatars
    val desktopGroups = BotRoster.desktopGroups

    private val _notice = MutableStateFlow<String?>(null)
    /** The last management or open outcome that needs explaining. */
    val notice: StateFlow<String?> = _notice

    private val _opening = MutableStateFlow<String?>(null)
    /** The bot whose chat is being resolved. */
    val opening: StateFlow<String?> = _opening

    private val openFlights = mutableMapOf<String, Deferred<Boolean>>()

    fun refresh() = viewModelScope.launch { BotRoster.refresh(hermes) }
    fun reload() = viewModelScope.launch { BotRoster.reload(hermes) }
    fun loadAvatar(bot: BotProfile) = viewModelScope.launch { BotRoster.loadAvatar(hermes, bot) }
    fun dismissNotice() { _notice.value = null }
    fun setNotice(message: String?) { _notice.value = message }

    private val connected: Boolean get() = hermes.gateway?.state?.value == app.hermesconduit.android.net.GatewayClient.State.CONNECTED

    private fun message(e: Throwable) = e.message ?: L10n.tr("Something went wrong")

    // region Bot Chat

    /** Resolves (or, on confirmed absence, creates) the bot's Bot Chat and opens it. */
    fun openBotChat(bot: BotProfile, app: AppViewModel, onOpened: () -> Unit) {
        val key = bot.name.trim().lowercase()
        viewModelScope.launch {
            val flight = openFlights[key]?.takeIf { it.isActive } ?: viewModelScope.async { resolveAndOpen(bot, app) }.also { openFlights[key] = it }
            _opening.value = bot.name
            val opened = try { flight.await() } finally {
                if (openFlights[key] === flight) openFlights.remove(key)
                _opening.value = null
            }
            if (opened) onOpened()
        }
    }

    private suspend fun lookup(profile: String): List<BotChatLookupRow> {
        val result = hermes.rpc(
            "session.list",
            buildJsonObject {
                put("profile", profile)
                put("title", BotMode.CANONICAL_CHAT_TITLE)
                put("limit", BotMode.LOOKUP_SESSION_LIMIT)
                put("include_hidden", true)
            },
        )
        return BotChatLookupDecoder.decode(result) ?: error(L10n.tr("Hermes sent a reply Conduit didn't understand. Updating Hermes and Conduit usually fixes this."))
    }

    private suspend fun resolveAndOpen(bot: BotProfile, app: AppViewModel): Boolean {
        if (!connected) {
            _notice.value = L10n.tr("Connect to Hermes to chat with bots.")
            return false
        }
        val rows = try {
            lookup(bot.name)
        } catch (e: Exception) {
            return fail(bot, e, L10n.tr("Could not check %s's Bot Chat — not starting a new chat.", bot.displayLabel))
        }
        return when (val resolution = BotChatResolver.resolve(rows, bot.canonicalSessionId)) {
            BotChatResolution.UnconfirmedAbsence -> refuse(bot)
            is BotChatResolution.OpenExisting -> open(bot, resolution.registryId, resolution.resumeId, app)
            BotChatResolution.Create -> createAndOpen(bot, app)
        }
    }

    private fun open(bot: BotProfile, registryId: String, resumeId: String, app: AppViewModel): Boolean {
        app.openBotChat(resumeId, registryId, bot.name, bot.displayLabel)
        return true
    }

    /**
     * Creates the ONE hidden "Bot Chat" (confirmed absence), titles it eagerly
     * so a second tap can never mint a twin, and adopts the winner on a title
     * collision instead of forking.
     */
    private suspend fun createAndOpen(bot: BotProfile, app: AppViewModel): Boolean {
        val created = try {
            hermes.rpc(
                "session.create",
                buildJsonObject {
                    put("cols", 96)
                    put("source", "desktop")
                    put("profile", bot.name)
                    put("title", BotMode.CANONICAL_CHAT_TITLE)
                    put("hidden", true)
                    put("follow_profile_config", true)
                },
            ).obj
        } catch (e: Exception) {
            if (GatewayErrors.isTitleCollision(e)) return adoptWinner(bot, app)
            return fail(bot, e, L10n.tr("Could not create %s's Bot Chat.", bot.displayLabel))
        }
        val sessionId = created?.get("session_id").str?.trim()?.takeIf { it.isNotEmpty() }
            ?: return fail(bot, IllegalStateException(), L10n.tr("Could not create %s's Bot Chat.", bot.displayLabel))
        val stored = listOf("stored_session_id", "storedSessionId", "session_key")
            .firstNotNullOfOrNull { created?.get(it).str?.trim()?.takeIf(String::isNotEmpty) }
        try {
            hermes.rpc(
                "session.title",
                buildJsonObject {
                    put("session_id", sessionId)
                    put("title", BotMode.CANONICAL_CHAT_TITLE)
                    put("profile", bot.name)
                },
            )
        } catch (e: Exception) {
            if (GatewayErrors.isTitleCollision(e)) return adoptWinner(bot, app)
            return fail(bot, e, L10n.tr("Could not title %s's Bot Chat — not starting a new chat.", bot.displayLabel))
        }
        return open(bot, stored ?: sessionId, sessionId, app)
    }

    private suspend fun adoptWinner(bot: BotProfile, app: AppViewModel): Boolean {
        val rows = try { lookup(bot.name) } catch (_: Exception) { return refuse(bot) }
        val resolution = BotChatResolver.resolve(rows, bot.canonicalSessionId)
        return if (resolution is BotChatResolution.OpenExisting) open(bot, resolution.registryId, resolution.resumeId, app) else refuse(bot)
    }

    private fun refuse(bot: BotProfile): Boolean {
        _notice.value = L10n.tr("Could not confirm %s's Bot Chat — not starting a new chat.", bot.displayLabel)
        return false
    }

    private fun fail(bot: BotProfile, error: Throwable, message: String): Boolean {
        _notice.value = if (GatewayErrors.isMissingMethod(error)) L10n.tr("Update Hermes to use Bot Mode.") else message
        return false
    }

    // endregion

    // region Management

    data class SlugCheck(val slug: String, val isValid: Boolean, val isTaken: Boolean)

    fun newBotSlug(name: String, profiles: List<String>): SlugCheck {
        val slug = BotProfileSlug.slug(name)
        val taken = bots.value.any { it.name.equals(slug, ignoreCase = true) } || profiles.any { it.equals(slug, ignoreCase = true) }
        return SlugCheck(slug, BotProfileSlug.isValid(slug), taken)
    }

    val canManage: Boolean get() = phase.value == BotModePhase.Available && connected

    /** Never the gateway's primary profile, never the dashboard's own profile. */
    fun canDelete(bot: BotProfile) = bot.isDeletable && !bot.name.equals(hermes.profile.value, ignoreCase = true)

    suspend fun loadDetails(bot: BotProfile): BotProfileDetails? = runCatching {
        val o = hermes.rpc("profiles.describe", buildJsonObject { put("name", bot.name) }).obj ?: return null
        BotProfileDetails(o["description"].str.orEmpty(), o["soul"].str.orEmpty())
    }.getOrNull()

    private suspend fun configure(
        name: String,
        botMeta: JsonObject? = null,
        expectedRevision: Int? = null,
        description: String? = null,
        soul: String? = null,
    ): JsonElement = hermes.rpc(
        "profiles.configure",
        buildJsonObject {
            put("name", name)
            if (botMeta != null) {
                put("ui_meta", buildJsonObject { put(BotMode.UI_META_KEY, botMeta) })
                if (expectedRevision != null) put("ui_meta_expected_revisions", buildJsonObject { put(BotMode.UI_META_KEY, expectedRevision) })
            }
            if (description != null) put("description", description)
            if (soul != null) put("soul", soul)
        },
    )

    private suspend fun storeAvatar(name: String, png: ByteArray?): Boolean = try {
        hermes.rpc(
            "profiles.set_asset",
            buildJsonObject {
                put("name", name)
                put("asset", BotMode.AVATAR_ASSET)
                if (png != null) put("data", BotAvatarImage.dataUrl(png)) else put("clear", true)
            },
        )
        BotRoster.setAvatar(name, png)
        true
    } catch (_: Exception) {
        false
    }

    /** One retry of a ui_meta write that lost its compare-and-swap. */
    private suspend fun retryMetaWrite(name: String, patch: Map<String, JsonElement?>): BotMetaWriteOutcome {
        BotRoster.refresh(hermes)
        val fresh = BotRoster.bot(name) ?: return BotMetaWriteOutcome.FAILED
        return try {
            BotMetaWriteOutcome.of(configure(name, BotMetaPatch.merged(fresh.botMeta, patch), fresh.botMetaRevision))
        } catch (_: Exception) {
            BotMetaWriteOutcome.FAILED
        }
    }

    suspend fun createBot(draft: BotEditorDraft, profiles: List<String>): BotSaveResult {
        if (!connected) return BotSaveResult.Failed(L10n.tr("Connect to Hermes to manage bots."))
        val identity = newBotSlug(draft.name, profiles)
        if (!identity.isValid) return BotSaveResult.Failed(L10n.tr("Give the bot a name with at least one letter or number."))
        if (identity.isTaken) return BotSaveResult.Failed(L10n.tr("A profile named %s already exists.", identity.slug))
        val slug = identity.slug
        val title = BotProfileSlug.title(draft.name)
        val description = draft.description.trim()
        val soul = BotSoul.compose(slug, title, description, draft.soul, BotRoster.supportsProtocol, bots.value)
        try {
            hermes.rpc(
                "profiles.create",
                buildJsonObject { put("name", slug); put("description", description); put("soul", soul) },
                60_000,
            )
        } catch (e: Exception) {
            return BotSaveResult.Failed(L10n.tr("Could not create the bot: %s", message(e)))
        }
        // The picture first, so ui_meta only claims a photo the store holds.
        var photoSaved = true
        draft.newAvatarPng?.let { photoSaved = storeAvatar(slug, it) }
        val meta = mutableMapOf<String, Any?>("created" to System.currentTimeMillis())
        if (title.isNotEmpty()) meta["title"] = title
        draft.color?.let { meta["color"] = it; meta["custom"] = true }
        if (draft.newAvatarPng != null && photoSaved) { meta["imageKind"] = "photo"; meta["custom"] = true }
        val lookSaved = try {
            BotMetaWriteOutcome.of(configure(slug, jsonOf(meta) as JsonObject)) != BotMetaWriteOutcome.FAILED
        } catch (_: Exception) {
            false
        }
        BotRoster.refresh(hermes)
        if (!lookSaved || !photoSaved) {
            return BotSaveResult.Saved(L10n.tr("The bot was created, but its picture or look was not saved. Edit the bot to try again."))
        }
        return BotSaveResult.Saved(null)
    }

    suspend fun updateBot(bot: BotProfile, draft: BotEditorDraft, loaded: BotProfileDetails?): BotSaveResult {
        if (!connected) return BotSaveResult.Failed(L10n.tr("Connect to Hermes to manage bots."))
        val problems = mutableListOf<String>()
        var uploaded = false
        draft.newAvatarPng?.let {
            uploaded = storeAvatar(bot.name, it)
            if (!uploaded) problems += L10n.tr("The picture was not saved.")
        }
        val removing = draft.newAvatarPng == null && draft.removesAvatar
        val patch = mutableMapOf<String, JsonElement?>()
        val title = draft.name.trim()
        if (title != bot.botTitle.orEmpty().trim()) patch["title"] = if (title.isEmpty()) null else JsonPrimitive(title)
        if (draft.color != bot.appearanceColor) {
            patch["color"] = draft.color?.let(::JsonPrimitive)
            patch["custom"] = JsonPrimitive(true)
        }
        val changesNameOrColor = patch.isNotEmpty()
        if (uploaded) {
            patch["imageKind"] = JsonPrimitive("photo")
            patch["custom"] = JsonPrimitive(true)
        } else if (removing) {
            patch["imageKind"] = JsonPrimitive("shape")
        }
        val originalDescription = loaded?.description ?: bot.profileDescription
        val description = draft.description.trim()
        val descriptionChanged = description != originalDescription.trim()
        // SOUL is only editable once `profiles.describe` answered.
        val soulChanged = loaded?.let { draft.soul != it.soul } ?: false

        fun metaNotSaved() = buildList {
            if (changesNameOrColor) add(L10n.tr("The name or color was not saved."))
            if (uploaded) add(L10n.tr("The picture was saved, but its look was not."))
            if (removing) add(L10n.tr("The picture was not removed."))
        }

        var metaPersisted = patch.isEmpty()
        if (patch.isNotEmpty() || descriptionChanged || soulChanged) {
            try {
                val result = configure(
                    bot.name,
                    botMeta = if (patch.isEmpty()) null else BotMetaPatch.merged(bot.botMeta, patch),
                    expectedRevision = if (patch.isEmpty()) null else bot.botMetaRevision,
                    description = if (descriptionChanged) description else null,
                    soul = if (soulChanged) draft.soul else null,
                )
                val sections = buildList { if (descriptionChanged) add("description"); if (soulChanged) add("soul") }
                if (BotConfigureSections.failed(result, sections).isNotEmpty()) problems += L10n.tr("The description or personality was not saved.")
                if (patch.isNotEmpty()) {
                    var outcome = BotMetaWriteOutcome.of(result)
                    if (outcome == BotMetaWriteOutcome.CONFLICT) outcome = retryMetaWrite(bot.name, patch)
                    metaPersisted = outcome == BotMetaWriteOutcome.PERSISTED || outcome == BotMetaWriteOutcome.UNSUPPORTED
                    if (!metaPersisted) problems += metaNotSaved()
                }
            } catch (e: Exception) {
                if (!uploaded) return BotSaveResult.Failed(L10n.tr("Could not save the bot: %s", message(e)))
                problems += metaNotSaved()
                if (descriptionChanged || soulChanged) problems += L10n.tr("The description or personality was not saved.")
                BotRoster.refresh(hermes)
                return BotSaveResult.Saved(problems.joinToString(" "))
            }
        }
        if (removing && metaPersisted && !storeAvatar(bot.name, null)) problems += L10n.tr("The picture was not removed.")
        BotRoster.refresh(hermes)
        return BotSaveResult.Saved(problems.takeIf { it.isNotEmpty() }?.joinToString(" "))
    }

    fun setPinned(bot: BotProfile, pinned: Boolean) {
        viewModelScope.launch {
            if (!connected) { _notice.value = L10n.tr("Connect to Hermes to manage bots."); return@launch }
            val patch = mapOf<String, JsonElement?>("pinned" to JsonPrimitive(pinned))
            val failed = try {
                var outcome = BotMetaWriteOutcome.of(configure(bot.name, BotMetaPatch.merged(bot.botMeta, patch), bot.botMetaRevision))
                if (outcome == BotMetaWriteOutcome.CONFLICT) outcome = retryMetaWrite(bot.name, patch)
                outcome == BotMetaWriteOutcome.FAILED || outcome == BotMetaWriteOutcome.CONFLICT
            } catch (_: Exception) {
                true
            }
            BotRoster.refresh(hermes)
            _notice.value = if (failed) L10n.tr("Could not update %s.", bot.displayLabel) else null
        }
    }

    /** `DELETE /api/profiles/{name}`, leaving its Bot Chat first if it is open. */
    fun deleteBot(bot: BotProfile, app: AppViewModel) {
        viewModelScope.launch {
            if (!canDelete(bot)) { _notice.value = L10n.tr("This profile can't be deleted from here."); return@launch }
            if (!connected) { _notice.value = L10n.tr("Connect to Hermes to manage bots."); return@launch }
            try {
                hermes.rest { it.sendJson("DELETE", "api/profiles/${Uri.encode(bot.name, "-_.~")}", timeoutMs = 30_000) }
            } catch (e: Exception) {
                _notice.value = L10n.tr("Could not delete %s: %s", bot.displayLabel, message(e))
                return@launch
            }
            app.leaveBotChat(bot.name)
            BotRoster.setAvatar(bot.name, null)
            BotRoster.refresh(hermes)
            app.refreshProfilesAsync()
            _notice.value = null
        }
    }

    // endregion
}

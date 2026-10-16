package app.hermesconduit.android.ui

import app.hermesconduit.android.core.HermesConnection
import app.hermesconduit.android.model.BotMentions
import app.hermesconduit.android.ui.bots.BotRoster

/** The chat send path's Bot Mode hooks: @mention annotation and the forever-chat guard. */
internal object BotMentionsHook {
    /** The text `prompt.submit` carries: [text] plus the identification note when a teammate is @mentioned. */
    suspend fun annotate(hermes: HermesConnection, text: String, activeProfile: String): String {
        if (!BotMentions.textMightContainMention(text)) return text
        if (BotRoster.bots.value.isEmpty()) runCatching { BotRoster.refresh(hermes) }
        return BotMentions.middlewareAnnotation(text, BotRoster.bots.value, activeProfile) ?: text
    }

    /** `/compact` for `/new` or `/reset` typed inside a Bot Chat. */
    fun reroute(text: String, chat: ChatState): String? =
        BotMentions.foreverChatRerouteText(text, isCanonicalBotChat = chat.botProfile != null)
}

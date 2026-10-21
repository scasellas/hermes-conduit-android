package app.hermesconduit.android.notify

import app.hermesconduit.android.data.AppSettings

/** The iOS "Notify me when" preferences, device-local. */
object NotificationPrefs {
    const val ENABLED = "notify.enabled"
    const val APPROVAL = "notify.approvalNeeded"
    const val INPUT = "notify.inputNeeded"
    const val RESPONSE = "notify.responseReady"
    const val FAILED = "notify.turnFailed"
    const val BACKGROUND_TASK = "notify.backgroundTaskFinished"
    const val ATTENTION_SOUND = "notify.attentionSound"
    const val COMPLETION_SOUND = "notify.completionSound"
    const val PREVIEWS = "notify.showPreviews"
    const val DECISION_CARDS = "notify.decisionCards"
    const val PROGRESS = "notify.progress"
    /** Replies from these sources notify (chat always does): "source.<kind>" toggles. */
    fun replySource(kind: String) = "notify.replySource.$kind"

    val defaults = mapOf(
        APPROVAL to true, INPUT to true, RESPONSE to true, FAILED to true, BACKGROUND_TASK to true,
        ATTENTION_SOUND to true, COMPLETION_SOUND to false, PREVIEWS to true, DECISION_CARDS to true, PROGRESS to true,
    )

    fun get(settings: AppSettings, key: String) = settings.getBool(key, defaults[key] ?: true)
}

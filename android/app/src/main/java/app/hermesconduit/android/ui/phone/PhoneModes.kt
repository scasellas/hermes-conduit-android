package app.hermesconduit.android.ui.phone

import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.service.notification.Condition
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import app.hermesconduit.android.MainActivity
import app.hermesconduit.android.appSettings
import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.model.HermesFocusFilter
import app.hermesconduit.android.model.HermesFocusRules
import app.hermesconduit.android.model.PhoneFocusSnapshot
import app.hermesconduit.android.model.PhoneModeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant

/**
 * Do Not Disturb & Modes for Hermes, Android's stand-in for iOS Focus
 * filters (HermesFocusFilter). Android tells apps whether Do Not Disturb
 * is on, not which mode turned it on, and lists only an app's own modes; so
 * a filter is for any Do Not Disturb, or (Android 15) for Conduit's own
 * mode, which the user can turn on or schedule in the system's Modes.
 */
object PhoneModes {
    /** The condition Conduit's mode carries, to find it again among the app's rules. */
    private val CONDITION: Uri = "condition://app.hermesconduit.android/conduit-mode".toUri()
    private const val MODE_NAME = "Conduit"

    private val _filters = MutableStateFlow<List<HermesFocusFilter>>(emptyList())
    @Volatile private var loaded = false

    /** The filters the user set up, in order. */
    fun filters(context: Context): StateFlow<List<HermesFocusFilter>> {
        if (!loaded) synchronized(this) {
            if (!loaded) {
                _filters.value = HermesFocusFilter.decodeAll(context.appSettings.getString(HermesFocusFilter.STORAGE_KEY, "").ifEmpty { null })
                loaded = true
            }
        }
        return _filters
    }

    fun saveFilters(context: Context, filters: List<HermesFocusFilter>) {
        val kept = filters.take(HermesFocusFilter.MAXIMUM)
        context.appSettings.set(HermesFocusFilter.STORAGE_KEY, if (kept.isEmpty()) "" else HermesFocusFilter.encodeAll(kept))
        _filters.value = kept
        loaded = true
    }

    fun state(context: Context): PhoneModeState {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return PhoneModeState(false)
        val filter = runCatching { manager.currentInterruptionFilter }.getOrDefault(NotificationManager.INTERRUPTION_FILTER_UNKNOWN)
        val dnd = filter != NotificationManager.INTERRUPTION_FILTER_ALL && filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        val modes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM && conduitModeActive(manager)) setOf(HermesFocusFilter.CONDUIT_MODE) else emptySet()
        return PhoneModeState(dnd, modes)
    }

    fun snapshot(context: Context, at: Instant = Instant.now()): PhoneFocusSnapshot =
        HermesFocusRules.snapshot(filters(context).value, state(context), at)

    /** Whether a filter that's on holds Hermes' calls. */
    fun holdsCalls(context: Context): Boolean = runCatching { HermesFocusRules.holdsCalls(filters(context).value, state(context)) }.getOrDefault(false)

    // region Conduit's own mode (Android 15)

    val supportsConduitMode: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM

    fun canAddModes(context: Context): Boolean = context.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true

    /** The system page where the user lets Conduit add a mode. */
    fun policyAccessIntent(): Intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun conduitRuleID(manager: NotificationManager): String? =
        runCatching { manager.automaticZenRules.entries.firstOrNull { it.value.conditionId == CONDITION }?.key }.getOrNull()

    fun hasConduitMode(context: Context): Boolean =
        supportsConduitMode && context.getSystemService(NotificationManager::class.java)?.let(::conduitRuleID) != null

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun conduitModeActive(manager: NotificationManager): Boolean {
        val id = conduitRuleID(manager) ?: return false
        return runCatching { manager.getAutomaticZenRuleState(id) == Condition.STATE_TRUE }.getOrDefault(false)
    }

    /** Adds "Conduit" to the system's Modes; false without the access it needs. */
    fun addConduitMode(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        if (!manager.isNotificationPolicyAccessGranted) return false
        if (conduitRuleID(manager) != null) return true
        val rule = AutomaticZenRule.Builder(MODE_NAME, CONDITION)
            .setType(AutomaticZenRule.TYPE_OTHER)
            .setConfigurationActivity(ComponentName(context, MainActivity::class.java))
            .setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            .setManualInvocationAllowed(true)
            .setTriggerDescription(L10n.tr("Hermes hears the name you gave it while this mode is on."))
            .build()
        return runCatching { manager.addAutomaticZenRule(rule) }.isSuccess
    }

    fun removeConduitMode(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        conduitRuleID(manager)?.let { id -> runCatching { manager.removeAutomaticZenRule(id) } }
    }

    // endregion
}

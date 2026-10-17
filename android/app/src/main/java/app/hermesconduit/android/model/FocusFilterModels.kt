package app.hermesconduit.android.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.time.Instant

/**
 * One "Tell Hermes" filter (iOS HermesFocusFilter, the Focus filter added
 * in Settings > Focus). Android has no Focus filters, so the user sets
 * them up in Conduit: while [mode] is on (Do Not Disturb, or Conduit's own
 * mode on Android 15), Hermes hears [label], such as Work, and with
 * [holdCalls] holds its calls.
 */
@Serializable
data class HermesFocusFilter(
    val id: String,
    /** [ANY] for any Do Not Disturb, or a mode's id ([CONDUIT_MODE]). */
    val mode: String = ANY,
    val label: String = "",
    val holdCalls: Boolean = false,
) {
    companion object {
        const val ANY = "any"
        /** Conduit's own mode in the system's Modes (Android 15). */
        const val CONDUIT_MODE = "conduit"
        const val STORAGE_KEY = "conduit.phoneSync.focusFilters.v1"
        const val MAXIMUM = 10
        /** iOS keeps the name to 40 characters. */
        const val LABEL_LENGTH = 40

        fun decodeAll(text: String?): List<HermesFocusFilter> =
            text?.let { runCatching { ConduitJson.decodeFromString(ListSerializer(serializer()), it) }.getOrNull() }.orEmpty()

        fun encodeAll(list: List<HermesFocusFilter>): String = ConduitJson.encodeToString(ListSerializer(serializer()), list.take(MAXIMUM))
    }
}

/** Do Not Disturb as the phone reports it, and which known modes are on. */
data class PhoneModeState(val doNotDisturb: Boolean, val activeModes: Set<String> = emptySet())

/** What the filters say right now (iOS HermesFocusFilter.perform, run as a Focus turns on or off). */
object HermesFocusRules {
    fun cleanLabel(text: String): String = text.trim().take(HermesFocusFilter.LABEL_LENGTH)

    /** The filter that applies: one for a mode that's on comes before "Any Do Not Disturb". */
    fun applying(filters: List<HermesFocusFilter>, state: PhoneModeState): HermesFocusFilter? =
        filters.firstOrNull { it.mode != HermesFocusFilter.ANY && it.mode in state.activeModes }
            ?: filters.firstOrNull { it.mode == HermesFocusFilter.ANY && (state.doNotDisturb || state.activeModes.isNotEmpty()) }

    /**
     * The snapshot an upload carries. With filters set up it's exactly what
     * iOS's filter writes: active when the applying filter names something
     * or holds calls, and the defaults (nothing named, nothing held) when
     * none applies. Without any, Do Not Disturb alone stands in for Focus,
     * as before filters existed.
     */
    fun snapshot(filters: List<HermesFocusFilter>, state: PhoneModeState, at: Instant): PhoneFocusSnapshot {
        if (filters.isEmpty()) {
            val active = state.doNotDisturb || state.activeModes.isNotEmpty()
            return PhoneFocusSnapshot(active, if (active) "Do Not Disturb" else null, false, at)
        }
        val filter = applying(filters, state) ?: return PhoneFocusSnapshot(false, null, false, at)
        val name = cleanLabel(filter.label)
        return PhoneFocusSnapshot(name.isNotEmpty() || filter.holdCalls, name.ifEmpty { null }, filter.holdCalls, at)
    }

    /** Whether calls from Hermes wait right now. */
    fun holdsCalls(filters: List<HermesFocusFilter>, state: PhoneModeState): Boolean = applying(filters, state)?.holdCalls == true
}

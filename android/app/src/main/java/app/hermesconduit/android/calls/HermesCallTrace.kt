package app.hermesconduit.android.calls

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The last call from Hermes, step by step (#449, iOS HermesCallTrace). An
 * answered call that opens no voice leaves no error to show, so the trace
 * says where it stopped. English only, never hosts, session ids, titles or
 * server error text. Each step also goes to the connection log.
 */
object HermesCallTrace {
    data class Timeline(val trigger: String, val startedAtMs: Long, val events: List<Pair<Long, String>> = emptyList(), val stepsAfterWindow: Boolean = false, val dropped: Int = 0) {
        val report: String
            get() = buildList {
                add("Conduit connection timeline: $trigger")
                events.forEach { (offset, label) -> add("+%.1fs %s".format(java.util.Locale.ROOT, offset / 1000.0, label)) }
                if (dropped > 0) add("($dropped more steps not recorded)")
                if (stepsAfterWindow) add("(stopped recording after ${RECORDING_WINDOW_MS / 1000}s)")
            }.joinToString("\n")
    }

    const val RECORDING_WINDOW_MS = 120_000L
    const val MAXIMUM_EVENTS = 60

    private val _timeline = MutableStateFlow<Timeline?>(null)
    val timeline: StateFlow<Timeline?> = _timeline

    /** Where each recorded step is also logged (the connection log). */
    @Volatile var sink: ((String) -> Unit)? = null
    var clock: () -> Long = System::currentTimeMillis

    /** A call arrived: a new trace. */
    fun begin(label: String) {
        _timeline.value = Timeline("call from Hermes", clock())
        note(label)
    }

    fun note(label: String, sinceMs: Long? = null, error: Throwable? = null) {
        val current = _timeline.value ?: return
        val now = clock()
        if (now - current.startedAtMs > RECORDING_WINDOW_MS) {
            _timeline.value = current.copy(stepsAfterWindow = true)
            return
        }
        if (current.events.size >= MAXIMUM_EVENTS) {
            _timeline.value = current.copy(dropped = current.dropped + 1)
            return
        }
        var text = label
        if (error != null) {
            text += " failed" + (sinceMs?.let { " after %.1fs".format(java.util.Locale.ROOT, (now - it) / 1000.0) } ?: "") + ": " + error.javaClass.simpleName
        } else if (sinceMs != null) {
            text += " (%.1fs)".format(java.util.Locale.ROOT, (now - sinceMs) / 1000.0)
        }
        _timeline.value = current.copy(events = current.events + ((now - current.startedAtMs).coerceAtLeast(0) to text))
        sink?.invoke("Call trace: $text")
    }
}

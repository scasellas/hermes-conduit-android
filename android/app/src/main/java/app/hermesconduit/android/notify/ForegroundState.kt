package app.hermesconduit.android.notify

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/** Whether Conduit is on screen, and which chat is open, so notifications don't repeat what the user sees. */
object ForegroundState {
    @Volatile var visible: Boolean = false
        private set
    /** Every id the open chat is known by (stored and runtime). */
    @Volatile var openSessionIds: Set<String> = emptySet()

    fun install() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { visible = true }
            override fun onStop(owner: LifecycleOwner) { visible = false }
        })
    }

    fun isShowing(sessionId: String) = visible && sessionId in openSessionIds
}

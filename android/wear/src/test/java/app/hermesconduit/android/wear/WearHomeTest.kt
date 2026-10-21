package app.hermesconduit.android.wear

import android.os.Looper
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * The watch app's home screen on the real framework, with no phone in reach
 * (no Data Layer on the JVM). A tall watch, so the whole list composes.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w227dp-h900dp-watch")
class WearHomeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app: WearApp get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        // The ready orb breathes forever: frames advance by hand so Compose can idle.
        compose.mainClock.autoAdvance = false
        // Opening the app would otherwise start a call at once.
        app.prefs.setCallOnOpen(false)
    }

    private fun waitForText(text: String) {
        val deadline = System.currentTimeMillis() + 10_000
        while (compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isEmpty()) {
            if (System.currentTimeMillis() > deadline) fail("Timed out waiting for \"$text\"")
            compose.mainClock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            Thread.sleep(5)
        }
    }

    @Test
    fun homeOffersTalkAndTheScreensWithoutAPhone() {
        ActivityScenario.launch(MainActivity::class.java).use {
            waitForText("Talk to Hermes")
            waitForText("Phone not connected right now")
            listOf("Recent chats", "Approvals", "Call log", "Settings").forEach(::waitForText)
            assertFalse("no call started on open", app.call.state.value.isActive)
        }
    }
}

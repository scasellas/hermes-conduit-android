package app.hermesconduit.android.integration

import android.app.Activity
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.hermesconduit.android.integration.Integration.app
import app.hermesconduit.android.integration.Integration.pump
import app.hermesconduit.android.model.str
import app.hermesconduit.android.ui.shortcut.SendToHermes
import app.hermesconduit.android.ui.shortcut.SendToHermesActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Send to Hermes for automation apps (Tasker, MacroDroid): started for a
 * result, the activity runs the turn on the app's connection and hands back
 * `reply` and `outcome`; with the opt-in off it refuses with `error`.
 */
@RunWith(AndroidJUnit4::class)
class SendToHermesTest {
    private val fake = FakeHermes()

    @Before fun setUp() {
        fake.start()
        Integration.signIn(fake)
    }

    @After fun tearDown() {
        Integration.tearDown()
        fake.close()
    }

    private fun request(text: String) = Intent(SendToHermes.ACTION)
        .setClass(app, SendToHermesActivity::class.java)
        .putExtra(SendToHermes.EXTRA_TEXT, text)
        // Automation apps send booleans as text.
        .putExtra(SendToHermes.EXTRA_WAIT_FOR_ANSWER, "true")

    /** Starts the activity for a result, the way an automation app does, and waits for it to finish. */
    private fun runForResult(intent: Intent): android.app.Instrumentation.ActivityResult {
        val scenario = ActivityScenario.launchActivityForResult<SendToHermesActivity>(intent)
        var activity: Activity? = null
        // A refusal can finish it before the first look.
        runCatching { scenario.onActivity { activity = it } }
        pump(timeoutMs = 30_000, message = "Send to Hermes finished") { activity == null || activity!!.isFinishing || activity!!.isDestroyed }
        return scenario.result.also { scenario.close() }
    }

    @Test
    fun allowedRunReturnsTheReply() {
        app.settings.set(SendToHermes.ENABLED_KEY, true)
        // The new chat's saved history carries Hermes' answer once the turn ends.
        fake.histories["st-new"] = """[{"id":1,"role":"user","content":"Weather?"},{"id":2,"role":"assistant","content":"Sunny, 21°C."}]"""

        val result = runForResult(request("Weather?"))

        assertEquals(Activity.RESULT_OK, result.resultCode)
        assertEquals("Sunny, 21°C.", result.resultData.getStringExtra(SendToHermes.EXTRA_REPLY))
        assertEquals("answered", result.resultData.getStringExtra(SendToHermes.EXTRA_OUTCOME))
        val submit = fake.calls("prompt.submit").single()
        assertEquals("rt-new", submit.params["session_id"].str)
        assertEquals("Weather?", submit.params["text"].str)
        // The new chat got a title from the text.
        assertEquals("Shortcut: Weather?", fake.calls("session.title").single().params["title"].str)
    }

    @Test
    fun turnedOffRefusesWithTheError() {
        assertFalse(SendToHermes.isEnabled(app))

        val result = runForResult(request("Weather?"))

        assertEquals(Activity.RESULT_CANCELED, result.resultCode)
        assertEquals(
            "Turn on Send to Hermes in Conduit's Automation settings first.",
            result.resultData.getStringExtra(SendToHermes.EXTRA_ERROR),
        )
        assertNull(result.resultData.getStringExtra(SendToHermes.EXTRA_REPLY))
        assertEquals(emptyList<FakeHermes.Rpc>(), fake.calls("prompt.submit"))
    }
}

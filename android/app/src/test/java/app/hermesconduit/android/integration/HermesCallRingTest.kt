package app.hermesconduit.android.integration

import android.app.Notification
import android.content.Intent
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.hermesconduit.android.calls.HermesCallActivity
import app.hermesconduit.android.calls.HermesCallNotifications
import app.hermesconduit.android.calls.HermesCalls
import app.hermesconduit.android.integration.Integration.app
import app.hermesconduit.android.integration.Integration.awaitGateway
import app.hermesconduit.android.integration.Integration.exists
import app.hermesconduit.android.integration.Integration.pump
import app.hermesconduit.android.model.HermesCallOutcome
import app.hermesconduit.android.model.HermesCallOutcomeOutbox
import androidx.core.app.NotificationCompat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * A call Hermes places on the socket (`call.requested`) rings as a
 * full-screen CallStyle notification on the calls channel; declining it
 * leaves a missed call and queues the outcome for the host.
 */
@RunWith(AndroidJUnit4::class)
class HermesCallRingTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val fake = FakeHermes()

    @Before fun setUp() {
        fake.start()
        Notifications.allow()
        Integration.signIn(fake)
        app.connection.start()
        awaitGateway(fake)
    }

    @After fun tearDown() {
        Integration.tearDown()
        fake.close()
    }

    private fun placeCall(): Notification {
        fake.event(
            "call.requested", "rt-job",
            """{"call":{"id":"call-00001","kind":"done","title":"Nightly build","session_ids":["rt-job"],"reason":"All tests passed"},"profile":"default"}""",
        )
        return Notifications.awaitPosted(HermesCallNotifications.RINGING_ID, "ringing call")
    }

    private fun queuedOutcomes() =
        HermesCallOutcomeOutbox.parse(app.settings.getString(HermesCallOutcomeOutbox.STORAGE_KEY, "").ifEmpty { null }).entries

    @Test
    fun callRingsFullScreenAndDeclineQueuesOutcome() {
        val ringing = placeCall()
        assertEquals(HermesCallNotifications.CHANNEL_CALLS, ringing.channelId)
        assertEquals(NotificationCompat.CATEGORY_CALL, ringing.category)
        assertEquals("android.app.Notification\$CallStyle", ringing.extras.getString(Notification.EXTRA_TEMPLATE))
        assertEquals("Hermes · Nightly build", ringing.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertTrue("rings until answered", ringing.flags and Notification.FLAG_INSISTENT != 0)
        val channel = Notifications.manager.getNotificationChannel(HermesCallNotifications.CHANNEL_CALLS)
        assertNotNull(channel)
        val fullScreen = shadowOf(ringing.fullScreenIntent).savedIntent
        assertEquals(HermesCallActivity::class.java.name, fullScreen.component?.className)

        // CallStyle puts Decline first and Answer last.
        val decline = ringing.actions.first()
        Notifications.fire(decline.actionIntent)

        pump(message = "ringing stopped") { Notifications.posted(HermesCallNotifications.RINGING_ID) == null }
        assertEquals(null, HermesCalls.get(app).ringingSession.value)
        val outcome = queuedOutcomes().single()
        assertEquals(HermesCallOutcome.DECLINED, outcome.outcome)
        assertEquals("call-00001", outcome.callID)
        assertEquals(listOf("rt-job"), outcome.sessionIDs)
        // A declined call leaves a missed-call notice with Talk.
        val missed = Notifications.shadow.allNotifications.single { it.category == NotificationCompat.CATEGORY_MISSED_CALL }
        assertEquals("Talk", missed.actions.single().title.toString())
    }

    @Test
    fun fullScreenCallScreenDeclines() {
        val ringing = placeCall()
        val intent = Intent(shadowOf(ringing.fullScreenIntent).savedIntent)
        ActivityScenario.launch<HermesCallActivity>(intent).use { scenario ->
            pump(message = "call screen") { compose.onAllNodes(hasText("Hermes · Nightly build")).exists() }
            assertTrue(compose.onAllNodes(hasText("Hermes wants to talk")).exists())
            var activity: HermesCallActivity? = null
            scenario.onActivity { activity = it }
            compose.onNodeWithContentDescription("Decline").performClick()
            pump(message = "call screen closed") { activity!!.isFinishing || activity!!.isDestroyed }
        }
        assertEquals(HermesCallOutcome.DECLINED, queuedOutcomes().single().outcome)
        assertEquals(null, Notifications.posted(HermesCallNotifications.RINGING_ID))
    }
}

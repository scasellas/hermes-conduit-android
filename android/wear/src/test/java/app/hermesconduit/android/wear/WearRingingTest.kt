package app.hermesconduit.android.wear

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.Looper
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.hermesconduit.wearwire.HermesRingIds
import app.hermesconduit.wearwire.WearMessage
import app.hermesconduit.wearwire.WearWire
import com.google.android.gms.wearable.MessageEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * A call from Hermes the phone rings on the watch: the phone's Ring message
 * reaches HermesRingService, the watch posts a full-screen CallStyle
 * notification, and its ringing screen declines it.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w227dp-h227dp-round-watch")
class WearRingingTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app: WearApp get() = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = app.getSystemService(NotificationManager::class.java)

    @Before fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.RECORD_AUDIO)
    }

    private fun pump(what: String, diagnostics: () -> String = { "" }, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("Timed out waiting for $what " + diagnostics())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            Thread.sleep(5)
        }
    }

    /** The phone's Ring, delivered as the Data Layer does: a message to the listener service. */
    private fun ringFromPhone(ring: WearMessage.Ring) {
        val (path, bytes) = WearWire.encode(ring)
        val event = object : MessageEvent {
            override fun getRequestId() = 1
            override fun getPath() = path
            override fun getData() = bytes
            override fun getSourceNodeId() = "phone-node"
        }
        Robolectric.setupService(HermesRingService::class.java).onMessageReceived(event)
    }

    @Test
    fun ringPostsCallStyleAndTheRingingScreenDeclines() {
        val ring = WearMessage.Ring(HermesRingIds.newID(), "Hermes · Nightly build", System.currentTimeMillis())
        ringFromPhone(ring)

        pump("ringing notification", { "canRing=${app.hermesCalls.canRing} ringing=${app.hermesCalls.ringing.value}" }) { shadowOf(manager).getNotification(HermesWatchCalls.NOTIFICATION_ID) != null }
        val notification = shadowOf(manager).getNotification(HermesWatchCalls.NOTIFICATION_ID)
        assertEquals(HermesWatchCalls.CHANNEL, notification.channelId)
        assertEquals(NotificationCompat.CATEGORY_CALL, notification.category)
        assertEquals("android.app.Notification\$CallStyle", notification.extras.getString(Notification.EXTRA_TEMPLATE))
        assertEquals(ring.id, app.hermesCalls.ringing.value?.id)
        val screen = Intent(shadowOf(notification.fullScreenIntent).savedIntent)
        assertEquals(HermesCallActivity::class.java.name, screen.component?.className)

        ActivityScenario.launch<HermesCallActivity>(screen).use { scenario ->
            pump("ringing screen") { compose.onAllNodes(hasText("Hermes · Nightly build")).fetchSemanticsNodes().isNotEmpty() }
            var activity: HermesCallActivity? = null
            scenario.onActivity { activity = it }
            compose.onNodeWithContentDescription("Decline").performClick()
            pump("ringing screen closed") { activity!!.isFinishing || activity!!.isDestroyed }
        }
        assertNull(app.hermesCalls.ringing.value)
        assertNull(shadowOf(manager).getNotification(HermesWatchCalls.NOTIFICATION_ID))
        // Declined calls don't ring again.
        ringFromPhone(ring)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        assertNull(app.hermesCalls.ringing.value)
        assertNotNull(manager.getNotificationChannel(HermesWatchCalls.CHANNEL))
    }
}

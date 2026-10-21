package app.hermesconduit.android.integration

import androidx.core.app.NotificationCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.hermesconduit.android.integration.Integration.awaitGateway
import app.hermesconduit.android.integration.Integration.awaitRpc
import app.hermesconduit.android.integration.Integration.pump
import app.hermesconduit.android.model.str
import app.hermesconduit.android.notify.ConduitNotifications
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** An approval while Conduit is in the background posts Allow/Deny actions that answer Hermes directly. */
@RunWith(AndroidJUnit4::class)
class ApprovalNotificationTest {
    private val fake = FakeHermes()

    @Before fun setUp() {
        fake.start()
        Notifications.allow()
        Integration.signIn(fake)
    }

    @After fun tearDown() {
        Integration.tearDown()
        fake.close()
    }

    @Test
    fun approvalPostsAllowAndDenyAndDenyResponds() {
        Notifications.launchAndBackground().use {
            awaitGateway(fake)
            fake.event(
                "approval.request", "rt-bg",
                """{"request_id":"apr-9","command":"git push --force","description":"Force-push main?","choices":["once","session","always","deny"]}""",
            )

            val id = ConduitNotifications.idFor("approval", "apr-9")
            val notification = Notifications.awaitPosted(id, "approval notification")
            assertEquals(ConduitNotifications.CHANNEL_ATTENTION, notification.channelId)
            assertEquals("Approval needed", notification.extras.getCharSequence(NotificationCompat.EXTRA_TITLE).toString())
            assertEquals("git push --force", notification.extras.getCharSequence(NotificationCompat.EXTRA_TEXT).toString())
            // "Always" needs the app; the notification offers the one-tap choices.
            assertEquals(listOf("Allow once", "Allow for chat", "Deny"), notification.actions.map { it.title.toString() })

            Notifications.fire(notification.actions.single { it.title == "Deny" }.actionIntent)
            val respond = awaitRpc(fake, "approval.respond")
            assertEquals("deny", respond.params["choice"].str)
            assertEquals("rt-bg", respond.params["session_id"].str)
            assertEquals("apr-9", respond.params["request_id"].str)
            pump(message = "approval notification dismissed") { Notifications.posted(id) == null }
        }
    }
}

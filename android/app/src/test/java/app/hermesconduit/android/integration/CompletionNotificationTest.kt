package app.hermesconduit.android.integration

import android.content.Intent
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.hermesconduit.android.integration.Integration.awaitGateway
import app.hermesconduit.android.integration.Integration.awaitRpc
import app.hermesconduit.android.integration.Integration.pump
import app.hermesconduit.android.model.str
import app.hermesconduit.android.notify.ConduitNotifications
import app.hermesconduit.android.notify.ForegroundState
import app.hermesconduit.android.notify.NotificationActionReceiver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * With Conduit in the background, a finished turn posts a MessagingStyle
 * notification whose Reply (a RemoteInput, as Android Auto and Wear send it)
 * goes back to Hermes through NotificationActionReceiver as `prompt.submit`.
 */
@RunWith(AndroidJUnit4::class)
class CompletionNotificationTest {
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
    fun completionPostsMessagingStyleAndReplySubmits() {
        Notifications.launchAndBackground().use {
            awaitGateway(fake)
            assertFalse(ForegroundState.visible)

            fake.event("message.start", "rt-bg")
            fake.event("message.delta", "rt-bg", """{"text":"The report is ready."}""")
            fake.event("session.busy", "rt-bg", """{"busy":false}""")

            val id = ConduitNotifications.idFor("done", "rt-bg")
            val notification = Notifications.awaitPosted(id, "completion notification")
            assertEquals(ConduitNotifications.CHANNEL_COMPLETION_QUIET, notification.channelId)
            assertEquals(NotificationCompat.CATEGORY_MESSAGE, notification.category)
            val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
            assertNotNull("MessagingStyle", style)
            assertEquals("The report is ready.", style!!.messages.single().text.toString())
            assertEquals("Hermes", style.messages.single().person?.name)
            // The progress notification the turn showed is gone.
            pump(message = "progress cleared") { Notifications.posted(ConduitNotifications.idFor("progress", "rt-bg")) == null }

            val reply = notification.actions.single { it.title == "Reply" }
            val remoteInputs = reply.remoteInputs
            assertEquals(NotificationActionReceiver.EXTRA_REPLY, remoteInputs.single().resultKey)
            assertTrue(NotificationCompat.getInvisibleActions(notification).any { it.title == "Mark as read" })

            // The system fills the typed (or spoken) reply into the action's intent.
            val fillIn = Intent()
            RemoteInput.addResultsToIntent(
                arrayOf(RemoteInput.Builder(NotificationActionReceiver.EXTRA_REPLY).build()),
                fillIn,
                Bundle().apply { putCharSequence(NotificationActionReceiver.EXTRA_REPLY, "Send it to Ana") },
            )
            Notifications.fire(reply.actionIntent, fillIn)

            assertEquals("rt-bg", awaitRpc(fake, "session.resume").params["session_id"].str)
            val submit = awaitRpc(fake, "prompt.submit")
            assertEquals("rt-bg", submit.params["session_id"].str)
            assertEquals("Send it to Ana", submit.params["text"].str)
            pump(message = "notification dismissed after the reply") { Notifications.posted(id) == null }
        }
    }
}

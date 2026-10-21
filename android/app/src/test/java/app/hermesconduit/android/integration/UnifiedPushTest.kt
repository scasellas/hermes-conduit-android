package app.hermesconduit.android.integration

import android.app.Notification
import androidx.core.app.NotificationCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.hermesconduit.android.PushTestSeal
import app.hermesconduit.android.calls.HermesCallNotifications
import app.hermesconduit.android.calls.HermesCalls
import app.hermesconduit.android.integration.Integration.app
import app.hermesconduit.android.integration.Integration.awaitGateway
import app.hermesconduit.android.integration.Integration.pump
import app.hermesconduit.android.model.ConduitJson
import app.hermesconduit.android.model.PushKeyRecord
import app.hermesconduit.android.model.obj
import app.hermesconduit.android.model.str
import app.hermesconduit.android.notify.ConduitNotifications
import app.hermesconduit.android.push.ConduitPush
import app.hermesconduit.android.push.ConduitPushService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage
import java.util.Collections

/**
 * Push wake-up through UnifiedPush, end to end on the real app: the
 * connector's endpoint goes to the plugin's `push/android/register` route
 * with a new end-to-end key, and a sealed message delivered to
 * [ConduitPushService] rings or posts a notification. A plugin without the
 * route (404) leaves push marked unsupported.
 */
@RunWith(AndroidJUnit4::class)
class UnifiedPushTest {
    private val fake = FakeHermes()
    private val registrations: MutableList<JsonObject> = Collections.synchronizedList(mutableListOf())
    private lateinit var service: ConduitPushService

    private val push get() = ConduitPush.get(app)

    @Before fun setUp() {
        fake.start()
        fake.routes["/api/plugins/conduit_push/push/android/register"] = { request ->
            val body = ConduitJson.parseToJsonElement(request.body.readUtf8()).obj!!
            registrations += body
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"ok":true,"installation_id":"${body["installation_id"].str}","gateway_id":"gw-test","kid":"${body["e2e"].obj!!["kid"].str}"}""",
            )
        }
        Notifications.allow()
        Integration.signIn(fake)
        app.connection.start()
        awaitGateway(fake)
        service = Robolectric.buildService(ConduitPushService::class.java).create().get()
    }

    @After fun tearDown() {
        Integration.tearDown()
        fake.close()
    }

    /** Push turned on with a distributor that has just handed back its endpoint. */
    private fun register(): PushKeyRecord {
        app.settings.set(ConduitPush.ENABLED_KEY, true)
        service.onNewEndpoint(PushEndpoint("https://ntfy.example/upConduit?up=1", null, false), ConduitPush.INSTANCE)
        pump(message = "registered with the plugin", diagnostics = { push.state.value.toString() }) { push.state.value == ConduitPush.State.Registered }
        return push.records().single()
    }

    private fun deliver(body: ByteArray) = service.onMessage(PushMessage(body, true), ConduitPush.INSTANCE)

    private val nowSeconds get() = System.currentTimeMillis() / 1000

    @Test
    fun endpointRegistersWithThePluginAndAKeyIsKept() {
        val record = register()
        val body = registrations.single()
        assertEquals("https://ntfy.example/upConduit?up=1", body["endpoint"].str)
        assertEquals("android-unifiedpush", body["platform"].str)
        assertEquals(push.installationID, body["installation_id"].str)
        assertEquals(record.kid, body["e2e"].obj!!["kid"].str)
        assertTrue(body["preferences"].obj!!.containsKey("approval_needed"))
        assertEquals("gw-test", record.gatewayID)
        assertEquals(app.connection.connection.value?.id, record.dashboardID)
        assertTrue(push.wakes(record.dashboardID, "default"))
        // Never the iOS relay.
        assertTrue(fake.requests.none { it.requestUrl.toString().contains("push.milim.dev") })
    }

    @Test
    fun sealedCallRingsFullScreen() {
        val record = register()
        val content = buildJsonObject {
            put("session_id", "rt-push-call")
            put("profile", "default")
            put("call", buildJsonObject { put("id", "call-00042"); put("kind", "done"); put("title", "Nightly build"); put("reason", "All tests passed") })
        }
        deliver(PushTestSeal.body(record, "call.requested", content, nowSeconds))

        val ringing = Notifications.awaitPosted(HermesCallNotifications.RINGING_ID, "ringing call from a push")
        assertEquals(HermesCallNotifications.CHANNEL_CALLS, ringing.channelId)
        assertEquals("Hermes · Nightly build", ringing.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("rt-push-call", HermesCalls.get(app).ringingSession.value)
    }

    @Test
    fun sealedResponsePostsANotificationOnce() {
        val record = register()
        val content = buildJsonObject {
            put("title", "Deploy finished"); put("body", "All 3 services are up."); put("session_id", "rt-push-done")
        }
        val bytes = PushTestSeal.body(record, "response.ready", content, nowSeconds, compress = true)
        deliver(bytes)

        val id = ConduitNotifications.idFor("done", "rt-push-done")
        val notification = Notifications.awaitPosted(id, "response notification from a push")
        assertEquals(ConduitNotifications.CHANNEL_COMPLETION_QUIET, notification.channelId)
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)!!
        assertEquals("Deploy finished", style.conversationTitle.toString())
        assertEquals("All 3 services are up.", style.messages.single().text.toString())

        // The same sealed message again is a replay: nothing is posted.
        ConduitNotifications.cancel(app, id)
        deliver(bytes)
        Integration.settle(500)
        assertNull(Notifications.posted(id))
    }

    @Test
    fun undecryptablePushOnlyWakesTheConnection() {
        val record = register()
        val forged = PushTestSeal.body(record.copy(secret = ByteArray(32) { 7 }), "response.ready", buildJsonObject { put("session_id", "rt-forged") }, nowSeconds)
        val before = fake.calls("session.active_list").size
        deliver(forged)
        // The sweep runs once; nothing is shown from the forged content.
        pump(message = "wake-up sweep") { fake.calls("session.active_list").size > before }
        assertNull(Notifications.posted(ConduitNotifications.idFor("done", "rt-forged")))
    }

    @Test
    fun pluginWithoutTheRouteIsUnsupported() {
        fake.routes["/api/plugins/conduit_push/push/android/register"] = { MockResponse().setResponseCode(404) }
        app.settings.set(ConduitPush.ENABLED_KEY, true)
        service.onNewEndpoint(PushEndpoint("https://ntfy.example/upConduit", null, false), ConduitPush.INSTANCE)
        pump(message = "unsupported") { push.state.value == ConduitPush.State.Unsupported }
        assertTrue(push.records().isEmpty())
    }
}

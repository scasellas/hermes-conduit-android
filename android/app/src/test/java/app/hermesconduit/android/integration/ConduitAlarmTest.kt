package app.hermesconduit.android.integration

import android.app.AlarmManager
import android.app.Notification
import android.media.AudioAttributes
import androidx.core.app.NotificationCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.hermesconduit.android.integration.Integration.app
import app.hermesconduit.android.integration.Integration.pump
import app.hermesconduit.android.model.PhoneChange
import app.hermesconduit.android.model.PhoneSyncPreferences
import app.hermesconduit.android.model.SyncDestination
import app.hermesconduit.android.model.arr
import app.hermesconduit.android.model.jsonOf
import app.hermesconduit.android.model.obj
import app.hermesconduit.android.model.str
import app.hermesconduit.android.ui.device.SyncOutcome
import app.hermesconduit.android.ui.device.SyncRequest
import app.hermesconduit.android.ui.phone.AlarmRingActivity
import app.hermesconduit.android.ui.phone.ConduitAlarmNotifications
import app.hermesconduit.android.ui.phone.ConduitAlarms
import app.hermesconduit.android.ui.phone.PhoneSync
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager
import java.time.Duration
import java.time.Instant

/**
 * Hermes sets an alarm through a sealed exchange; Conduit schedules it as
 * an exact alarm clock, rings it as a full-screen notification on the
 * Alarms channel when it goes off, reports it as alerting, and Stop ends it.
 */
@RunWith(AndroidJUnit4::class)
class ConduitAlarmTest {
    private val host = FakePhoneHost()
    private val request: SyncRequest = { method, path, query, body -> host.handle(method, path, query, body) }
    private val alarmManager: AlarmManager get() = app.getSystemService(AlarmManager::class.java)

    @Before fun setUp() {
        Notifications.allow()
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After fun tearDown() {
        Integration.tearDown()
    }

    private fun sync(): SyncOutcome = runBlocking { PhoneSync.of(app).engine.sync(Instant.MAX, request) }

    @Test
    fun hermesSetsAnAlarmThatRingsFullScreen() {
        val engine = PhoneSync.of(app).engine
        assertTrue("Exact alarms and notifications allowed", engine.alarmSource.authorized)
        engine.savePreferences(
            PhoneSyncPreferences(destination = SyncDestination("d1", "default", "hermes.local"), calendar = "off", autoAdd = true).withAlarms(true),
        )
        // Two days ahead: more than the Clock app's AlarmClock intents could take.
        val at = Instant.now().plus(Duration.ofDays(2)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
        host.changes = listOf(
            jsonOf(mapOf("id" to "ch_alarm1", "kind" to "set_alarm", "auto" to true, "fields" to mapOf("label" to "Flight", "at" to at.toString()))) as JsonObject,
        )
        assertEquals(SyncOutcome.Synced, sync())

        // Applied without a card, with Undo, and reported to the host.
        val added = engine.autoAdded.value
        assertNotNull(added)
        assertTrue(added!!.isAlarm)
        sync()
        val result = host.reported.single { it["id"].str == "ch_alarm1" }
        assertEquals("applied", result["outcome"].str)
        val alarmID = result["item_id"].str!!
        assertEquals(alarmID, added.itemID)
        val listed = host.batches.last()["alarms"].arr!!.single().obj!!
        assertEquals(alarmID, listed["id"].str)
        assertEquals("alarm", listed["kind"].str)
        assertEquals("Flight", listed["label"].str)
        assertEquals(at, Instant.from(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(listed["fires_at"].str)))
        assertEquals("scheduled", listed["state"].str)

        // An exact alarm clock, shown in the status bar.
        val shadow = shadowOf(alarmManager)
        val scheduled = shadow.scheduledAlarms.single { it.alarmClockInfo != null }
        assertEquals(at.toEpochMilli(), scheduled.alarmClockInfo!!.triggerTime)
        assertEquals(at.toEpochMilli(), scheduled.triggerAtTime)
        assertEquals(AlarmManager.RTC_WAKEUP, scheduled.type)

        // It goes off: a full-screen alarm notification that loops the alarm sound.
        shadow.fireAlarm(scheduled)
        val id = ConduitAlarmNotifications.notificationID(alarmID)
        val ringing = Notifications.awaitPosted(id, "ringing alarm")
        assertEquals(ConduitAlarmNotifications.CHANNEL_ALARMS, ringing.channelId)
        assertEquals(NotificationCompat.CATEGORY_ALARM, ringing.category)
        assertEquals("Flight", ringing.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertTrue("rings until stopped", ringing.flags and Notification.FLAG_INSISTENT != 0)
        assertEquals(AlarmRingActivity::class.java.name, shadowOf(ringing.fullScreenIntent).savedIntent.component?.className)
        assertEquals("Stop", ringing.actions.single().title.toString())
        val channel = Notifications.manager.getNotificationChannel(ConduitAlarmNotifications.CHANNEL_ALARMS)
        assertNotNull(channel)
        assertEquals(AudioAttributes.USAGE_ALARM, channel.audioAttributes.usage)
        assertTrue("bypasses Do Not Disturb", channel.canBypassDnd())
        assertTrue(channel.shouldVibrate())
        assertTrue(alarmID in ConduitAlarms.of(app).ringing.value)

        // Hermes hears it ringing.
        sync()
        assertEquals("alerting", host.batches.last()["alarms"].arr!!.single().obj!!["state"].str)

        // Stop: the ring ends and a one-off alarm is done.
        Notifications.fire(ringing.actions.single().actionIntent)
        pump(message = "alarm stopped") { Notifications.posted(id) == null }
        assertTrue(ConduitAlarms.of(app).ringing.value.isEmpty())
        assertNull(ConduitAlarms.of(app).record(alarmID))
        sync()
        assertEquals(0, host.batches.last()["alarms"].arr!!.size)
    }

    @Test
    fun rebootSetsTheAlarmsAgainAndUndoTakesOneBack() {
        val source = PhoneSync.of(app).engine.alarmSource
        val change = PhoneChange.parse(
            jsonOf(mapOf("id" to "ch_t", "kind" to "set_timer", "fields" to mapOf("seconds" to 600))) as JsonObject,
        )!!
        val alarmID = runBlocking { source.apply(change, Instant.now()) }
        val shadow = shadowOf(alarmManager)
        assertEquals(1, shadow.scheduledAlarms.count { it.alarmClockInfo != null })

        // A reboot forgets every alarm; Conduit sets its own again.
        alarmManager.cancel(ConduitAlarms.of(app).fireIntent(alarmID))
        assertTrue(shadow.scheduledAlarms.none { it.alarmClockInfo != null })
        app.sendBroadcast(android.content.Intent(android.content.Intent.ACTION_BOOT_COMPLETED).setPackage(app.packageName))
        pump(message = "alarms set again") { shadowOf(alarmManager).scheduledAlarms.any { it.alarmClockInfo != null } }

        source.cancel(alarmID)
        assertTrue(shadowOf(alarmManager).scheduledAlarms.none { it.alarmClockInfo != null })
        assertEquals(emptyList<JsonObject>(), source.alarms(Instant.now(), java.time.ZoneId.systemDefault()))
    }
}

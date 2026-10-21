package app.hermesconduit.android.integration

import android.Manifest
import android.app.Service
import android.content.Intent
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.hermesconduit.android.MainActivity
import app.hermesconduit.android.integration.Integration.app
import app.hermesconduit.android.integration.Integration.exists
import app.hermesconduit.android.integration.Integration.pump
import app.hermesconduit.android.ui.nav.feature
import app.hermesconduit.android.ui.voice.ReadAloudService
import app.hermesconduit.android.ui.voice.VoiceConversationService
import app.hermesconduit.android.ui.voice.VoiceHub
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The voice buttons on the real framework ("the voice button crashes the
 * app"): the composer's voice-conversation button, its dictation mic and the
 * drawer's Voice conversation entry, with and without the microphone
 * permission, with voice on and off for the profile, classic and live. None
 * may crash, and each shows what iOS shows instead: with voice off, why and
 * the way to Settings > Voice, before any microphone request or foreground
 * service; with voice on, the permission request or the conversation.
 */
abstract class VoiceButtonsScenarios {
    @get:Rule val compose = createEmptyComposeRule()
    private val fake = FakeHermes()

    @Before fun setUp() {
        fake.start()
        Integration.signIn(fake)
    }

    @After fun tearDown() {
        runCatching { VoiceHub.peek()?.closeConversation() }
        runCatching { VoiceHub.peek()?.liveCalls?.close() }
        Integration.tearDown()
        fake.close()
    }

    protected fun diagnostics() = compose.onAllNodes(isRoot()).printToString(Int.MAX_VALUE)

    private fun waitForText(text: String, substring: Boolean = false) =
        pump(message = "\"$text\" on screen", diagnostics = ::diagnostics) { compose.onAllNodes(hasText(text, substring = substring)).exists() }

    /** Lets the screens' effects run (they wait on the compose clock) and the sockets deliver, for [ms] of wall time. */
    protected fun settle(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            compose.waitForIdle()
            Integration.settle(20)
        }
    }

    private fun lastRequestedPermission(scenario: ActivityScenario<MainActivity>): String? {
        var asked: String? = null
        scenario.onActivity { asked = shadowOf(it).lastRequestedPermission?.requestedPermissions?.firstOrNull() }
        return asked
    }

    private fun awaitMicrophoneRequest(scenario: ActivityScenario<MainActivity>) =
        pump(message = "microphone permission requested", diagnostics = ::diagnostics) {
            compose.waitForIdle()
            lastRequestedPermission(scenario) == Manifest.permission.RECORD_AUDIO
        }

    private fun grantMicrophone() = shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)

    private fun setVoice(enabled: Boolean, keepListeningWhenLocked: Boolean = false, geminiLive: Boolean = false) {
        val hub = VoiceHub.get(app)
        check(hub.setVoiceEnabled(enabled))
        if (keepListeningWhenLocked || geminiLive) {
            hub.updatePreferences { it.copy(keepListeningWhenLocked = keepListeningWhenLocked, geminiLiveEnabled = geminiLive) }
        }
    }

    /** Phones keep the chat list and the tools in a drawer. */
    protected open fun openDrawer() = Unit

    private fun openChat() {
        pump(message = "chat list", diagnostics = ::diagnostics) { compose.onAllNodes(hasText("First chat")).exists() }
        openDrawer()
        compose.onNodeWithText("First chat").performClick()
        waitForText("Milk and eggs.")
    }

    private val composerVoiceButton = hasContentDescription("Voice conversation") and hasClickAction()

    private fun tapComposerVoiceButton() {
        pump(message = "composer voice button", diagnostics = ::diagnostics) { compose.onAllNodes(composerVoiceButton).exists() }
        compose.onAllNodes(composerVoiceButton)[0].performClick()
    }

    private fun assertActivityAlive(scenario: ActivityScenario<MainActivity>) {
        var finishing = true
        scenario.onActivity { finishing = it.isFinishing }
        assertFalse("MainActivity went away", finishing)
    }

    /** Forgets the services started and stopped so far (sign-in starts the connection's). */
    private fun forgetServices() {
        val shadow = shadowOf(app)
        while (shadow.nextStartedService != null) Unit
        while (shadow.nextStoppedService != null) Unit
    }

    private fun startedVoiceService(): Intent? {
        val shadow = shadowOf(app)
        var found: Intent? = null
        while (true) {
            val next = shadow.nextStartedService ?: break
            if (next.component?.className == VoiceConversationService::class.java.name) found = next
        }
        return found
    }

    /** Voice off: the reason and Settings, no microphone request, no microphone service. */
    private fun assertVoiceOffPrompt(scenario: ActivityScenario<MainActivity>) {
        waitForText("Voice is off for this profile", substring = true)
        waitForText("Open Settings")
        settle(500)
        assertNull("asked for the microphone with voice off", lastRequestedPermission(scenario))
        assertNull("started the microphone service with voice off", startedVoiceService())
        assertActivityAlive(scenario)
    }

    private fun voiceConversation(granted: Boolean, voiceOn: Boolean, keepListeningWhenLocked: Boolean = false) {
        if (granted) grantMicrophone()
        setVoice(voiceOn, keepListeningWhenLocked)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openChat()
            forgetServices()
            tapComposerVoiceButton()
            if (!voiceOn) {
                assertVoiceOffPrompt(scenario)
                compose.onNodeWithText("Open Settings").performClick()
                waitForText("Enable voice on this device")
                assertActivityAlive(scenario)
                return
            }
            waitForText("Classic voice", substring = true)
            if (!granted) {
                awaitMicrophoneRequest(scenario)
                assertNull("started the microphone service without the permission", startedVoiceService())
            }
            settle(500)
            assertActivityAlive(scenario)
            // End goes back to the chat.
            compose.onNodeWithText("End").performClick()
            waitForText("Milk and eggs.")
            assertActivityAlive(scenario)
        }
    }

    @Test fun voiceButtonVoiceOffNoMicrophone() = voiceConversation(granted = false, voiceOn = false)

    @Test fun voiceButtonVoiceOffMicrophoneGranted() = voiceConversation(granted = true, voiceOn = false)

    @Test fun voiceButtonVoiceOnNoMicrophone() = voiceConversation(granted = false, voiceOn = true)

    @Test fun voiceButtonVoiceOnMicrophoneGranted() = voiceConversation(granted = true, voiceOn = true)

    @Test fun voiceButtonKeepListeningWhenLocked() = voiceConversation(granted = true, voiceOn = true, keepListeningWhenLocked = true)

    @Test fun voiceButtonInANewChat() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            pump(message = "chat list", diagnostics = ::diagnostics) { compose.onAllNodes(hasText("First chat")).exists() }
            forgetServices()
            // No chat open: a fresh chat's composer, before anything has made the voice hub.
            tapComposerVoiceButton()
            assertVoiceOffPrompt(scenario)
        }
    }

    private fun liveCall(granted: Boolean, voiceOn: Boolean) {
        if (granted) grantMicrophone()
        setVoice(voiceOn, geminiLive = true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openChat()
            forgetServices()
            tapComposerVoiceButton()
            if (!voiceOn) {
                assertVoiceOffPrompt(scenario)
                assertNull("opened a live call with voice off", VoiceHub.get(app).liveCalls.mode.value)
                return
            }
            waitForText("Gemini Live")
            if (!granted) {
                awaitMicrophoneRequest(scenario)
                settle(500)
                assertNull("started the microphone service without the permission", startedVoiceService())
            } else {
                settle(1_000)
                assertNotNull("a live call keeps its microphone service", startedVoiceService())
            }
            assertActivityAlive(scenario)
        }
    }

    @Test fun liveModeVoiceOffMicrophoneGranted() = liveCall(granted = true, voiceOn = false)

    @Test fun liveModeVoiceOnNoMicrophone() = liveCall(granted = false, voiceOn = true)

    @Test fun liveModeVoiceOnMicrophoneGranted() = liveCall(granted = true, voiceOn = true)

    private fun dictation(granted: Boolean, voiceOn: Boolean) {
        if (granted) grantMicrophone()
        setVoice(voiceOn)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openChat()
            compose.onNode(hasContentDescription("Dictate")).performClick()
            if (!granted) awaitMicrophoneRequest(scenario)
            settle(800)
            assertActivityAlive(scenario)
            // A second tap stops or cancels whatever started.
            compose.onAllNodes(hasContentDescription("Dictate") or hasContentDescription("Stop dictation")).let { nodes ->
                if (nodes.exists()) nodes[0].performClick()
            }
            settle(300)
            assertActivityAlive(scenario)
        }
    }

    @Test fun dictationNoMicrophone() = dictation(granted = false, voiceOn = false)

    @Test fun dictationMicrophoneGranted() = dictation(granted = true, voiceOn = false)

    @Test fun dictationVoiceOnMicrophoneGranted() = dictation(granted = true, voiceOn = true)

    private fun drawerEntry(granted: Boolean, voiceOn: Boolean) {
        if (granted) grantMicrophone()
        setVoice(voiceOn)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            pump(message = "chat list", diagnostics = ::diagnostics) { compose.onAllNodes(hasText("First chat")).exists() }
            forgetServices()
            openDrawer()
            pump(message = "drawer Voice entry", diagnostics = ::diagnostics) { compose.onAllNodes(hasText("Voice conversation")).exists() }
            compose.onAllNodes(hasText("Voice conversation"))[0].performScrollTo().performClick()
            if (!voiceOn) {
                assertVoiceOffPrompt(scenario)
                return
            }
            waitForText("Classic voice", substring = true)
            if (!granted) awaitMicrophoneRequest(scenario)
            settle(500)
            assertActivityAlive(scenario)
        }
    }

    @Test fun drawerVoiceEntryVoiceOff() = drawerEntry(granted = false, voiceOn = false)

    @Test fun drawerVoiceEntryVoiceOnNoMicrophone() = drawerEntry(granted = false, voiceOn = true)

    @Test fun drawerVoiceEntryVoiceOnMicrophoneGranted() = drawerEntry(granted = true, voiceOn = true)

    @Test fun voiceRoutesAreRegistered() {
        ActivityScenario.launch(MainActivity::class.java).use {
            pump(message = "chat list") { compose.onAllNodes(hasText("First chat")).exists() }
            assertNotNull(feature("voice"))
            assertNotNull(feature("voice.live"))
        }
    }
}

/** On a tablet-wide window: the chat list and tools beside the chat. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w900dp-h1000dp")
class VoiceButtonsTest : VoiceButtonsScenarios()

/** On a phone-sized window, as the report came from: the chat list and tools in the drawer. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class VoiceButtonsPhoneTest : VoiceButtonsScenarios() {
    override fun openDrawer() {
        compose.onNode(hasContentDescription("Chats") and hasClickAction()).performClick()
        settle(400)
    }
}

/**
 * The voice foreground services against Android's start contract, which
 * Robolectric doesn't enforce but a device does: a service started with
 * `startForegroundService` has to call `startForeground` (with a type its
 * prerequisites allow: RECORD_AUDIO for the microphone) before it is
 * stopped, or the system kills the app ("Context.startForegroundService() did
 * not then call Service.startForeground()").
 */
@RunWith(AndroidJUnit4::class)
class VoiceForegroundServiceTest {
    private fun <T : Service> deliver(type: Class<T>, intent: Intent): T =
        Robolectric.buildService(type, intent).create().startCommand(0, 1).get()

    private fun voiceServices(next: () -> Intent?): Intent? {
        while (true) {
            val intent = next() ?: return null
            val name = intent.component?.className
            if (name == VoiceConversationService::class.java.name || name == ReadAloudService::class.java.name) return intent
        }
    }

    private fun startedService(): Intent? = voiceServices { shadowOf(app).nextStartedService }

    private fun stoppedService(): Intent? = voiceServices { shadowOf(app).nextStoppedService }

    /** startForeground was called (the notification is gone again once the service stops). */
    private fun wentForeground(service: Service) = shadowOf(service).lastForegroundNotificationId != 0

    @Test fun noMicrophoneServiceWithoutThePermission() {
        VoiceConversationService.start(app)
        assertNull(startedService())
    }

    @Test fun aStopBeforeTheStartArrivesWaitsForTheForeground() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        VoiceConversationService.start(app, "Gemini Live")
        val intent = startedService()
        assertNotNull(intent)
        // The conversation ends at once (a failed open, End): the service isn't brought down before it is foreground.
        VoiceConversationService.stop(app)
        assertNull("stopped before startForeground", stoppedService())
        val service = deliver(VoiceConversationService::class.java, intent!!)
        assertTrue("went foreground", wentForeground(service))
        assertTrue("then stopped itself", shadowOf(service).isStoppedBySelf)
    }

    @Test fun aRunningServiceStopsAtOnce() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        VoiceConversationService.start(app)
        val service = deliver(VoiceConversationService::class.java, startedService()!!)
        assertNotNull(shadowOf(service).lastForegroundNotification)
        assertFalse(shadowOf(service).isStoppedBySelf)
        VoiceConversationService.stop(app)
        assertEquals(VoiceConversationService::class.java.name, stoppedService()?.component?.className)
    }

    @Test fun microphonePermissionGoneByTheTimeTheStartArrives() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        VoiceConversationService.start(app)
        val intent = startedService()!!
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val service = deliver(VoiceConversationService::class.java, intent)
        assertTrue("still went foreground (for playback)", wentForeground(service))
        assertTrue("then stopped itself", shadowOf(service).isStoppedBySelf)
    }

    @Test fun readAloudEndedBeforeItsStartArrived() {
        ReadAloudService.starts.start(app)
        val intent = startedService()!!
        ReadAloudService.starts.stop(app)
        assertNull("stopped before startForeground", stoppedService())
        val service = deliver(ReadAloudService::class.java, intent)
        assertTrue("went foreground", wentForeground(service))
        assertTrue("then stopped itself", shadowOf(service).isStoppedBySelf)
    }
}

package app.hermesconduit.android.integration

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.hermesconduit.android.MainActivity
import app.hermesconduit.android.integration.Integration.awaitGateway
import app.hermesconduit.android.integration.Integration.awaitRpc
import app.hermesconduit.android.integration.Integration.exists
import app.hermesconduit.android.integration.Integration.pump
import app.hermesconduit.android.model.str
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The core loop on the real framework: sign in with a password, see the chat
 * list, open a chat's history, send a message and watch the streamed reply,
 * and answer an approval card.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w900dp-h1000dp")
class SignInAndChatTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val fake = FakeHermes()

    @Before fun setUp() { fake.start() }

    @After fun tearDown() {
        Integration.tearDown()
        fake.close()
    }

    private fun waitForText(text: String, substring: Boolean = false) =
        pump(message = "\"$text\" on screen", diagnostics = { compose.onAllNodes(isRoot()).printToString(Int.MAX_VALUE) }) { compose.onAllNodes(hasText(text, substring = substring)).exists() }

    @Test
    fun signInListOpenSendAndApprove() {
        ActivityScenario.launch(MainActivity::class.java).use {
            // The login form: wrong password first, then the right one.
            waitForText("Dashboard address")
            compose.onNode(hasSetTextAction() and hasText("Dashboard address")).performTextInput(fake.baseUrl)
            compose.onNode(hasSetTextAction() and hasText("Username")).performTextInput(FakeHermes.USER)
            compose.onNode(hasSetTextAction() and hasText("Password")).performTextInput("wrong")
            compose.onNodeWithText("Sign in").performClick()
            waitForText("Hermes rejected that username or password", substring = true)

            compose.onNode(hasSetTextAction() and hasText("Password")).let { field ->
                field.performTextClearance()
                field.performTextInput(FakeHermes.PASSWORD)
            }
            compose.onNodeWithText("Sign in").performClick()

            // Signed in: the chat list shows the dashboard's sessions.
            waitForText("First chat")
            awaitGateway(fake)

            // Opening a chat loads its history and attaches to its runtime.
            compose.onNodeWithText("First chat").performClick()
            waitForText("Milk and eggs.")
            assertEquals("st-1", awaitRpc(fake, "session.resume").params["session_id"].str)

            // Sending: prompt.submit reaches the gateway on the chat's runtime, and the streamed reply renders.
            compose.onNode(hasSetTextAction() and hasText("Message Hermes")).performTextInput("Add bread")
            compose.onNodeWithContentDescription("Send").performClick()
            val submit = awaitRpc(fake, "prompt.submit")
            assertEquals("rt-st-1", submit.params["session_id"].str)
            assertEquals("Add bread", submit.params["text"].str)
            waitForText("Echo: Add bread")

            // An approval event shows the card; Allow once answers it.
            fake.event(
                "approval.request", "rt-st-1",
                """{"request_id":"apr-1","command":"rm -rf build","description":"Delete the build folder?","choices":["once","session","deny"]}""",
            )
            waitForText("Delete the build folder?")
            compose.onNodeWithText("Allow once").performClick()
            val respond = awaitRpc(fake, "approval.respond")
            assertEquals("once", respond.params["choice"].str)
            assertEquals("rt-st-1", respond.params["session_id"].str)
            assertEquals("apr-1", respond.params["request_id"].str)
            pump(message = "approval card gone") { !compose.onAllNodes(hasText("Delete the build folder?")).exists() }
        }
    }
}

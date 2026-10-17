package app.hermesconduit.android.integration

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.hermesconduit.android.MainActivity
import app.hermesconduit.android.integration.Integration.app
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

/** `conduit://session/<id>` opens that chat; text shared from another app lands in a new chat's composer. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w900dp-h1000dp")
class DeepLinkAndShareTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val fake = FakeHermes()

    @Before fun setUp() {
        fake.start()
        fake.sessionsJson = """[
            {"session_id":"rt-st-1","id":"st-1","title":"First chat","message_count":2,"last_active":1700000000},
            {"session_id":"rt-st-2","id":"st-2","title":"Groceries","message_count":2,"last_active":1700000100}
        ]"""
        fake.histories["st-2"] = """[{"id":1,"role":"user","content":"Need anything?"},{"id":2,"role":"assistant","content":"Oat milk, please."}]"""
        Integration.signIn(fake)
    }

    @After fun tearDown() {
        Integration.tearDown()
        fake.close()
    }

    private fun waitForText(text: String) =
        pump(message = "\"$text\" on screen", diagnostics = { compose.onAllNodes(isRoot()).printToString(Int.MAX_VALUE) }) {
            compose.onAllNodes(hasText(text)).exists()
        }

    @Test
    fun sessionLinkFromAnotherAppOpensThatChat() {
        val link = Intent(Intent.ACTION_VIEW, Uri.parse("conduit://session/st-2")).setClass(app, MainActivity::class.java)
        ActivityScenario.launch<MainActivity>(link).use {
            waitForText("Oat milk, please.")
            assertEquals("st-2", awaitRpc(fake, "session.resume").params["session_id"].str)
        }
    }

    @Test
    fun sharedTextPrefillsTheComposer() {
        val share = Intent(Intent.ACTION_SEND)
            .setClass(app, MainActivity::class.java)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Article")
            .putExtra(Intent.EXTRA_TEXT, "https://example.com/story")
        ActivityScenario.launch<MainActivity>(share).use {
            pump(message = "composer prefilled", diagnostics = { compose.onAllNodes(isRoot()).printToString(Int.MAX_VALUE) }) {
                compose.onAllNodes(hasSetTextAction() and hasText("Article\nhttps://example.com/story")).exists()
            }
            // A share starts a new chat: nothing was sent yet.
            assertEquals(emptyList<FakeHermes.Rpc>(), fake.calls("prompt.submit"))
        }
    }
}

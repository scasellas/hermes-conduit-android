package app.hermesconduit.android

import app.hermesconduit.android.core.LinkState
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.hermesconduit.android.model.ApprovalRequest
import app.hermesconduit.android.model.ChatMessage
import app.hermesconduit.android.model.ClarifyRequest
import app.hermesconduit.android.model.MessageRole
import app.hermesconduit.android.model.SessionSummary
import app.hermesconduit.android.model.ToolCall
import app.hermesconduit.android.ui.ChatActions
import app.hermesconduit.android.ui.ChatContent
import app.hermesconduit.android.ui.ChatState
import app.hermesconduit.android.ui.DrawerActions
import app.hermesconduit.android.ui.LoginScreen
import app.hermesconduit.android.ui.Screen
import app.hermesconduit.android.ui.SessionDrawerContent
import app.hermesconduit.android.ui.SessionsState
import app.hermesconduit.android.ui.theme.ConduitTheme
import com.android.resources.NightMode
import org.junit.Rule
import org.junit.Test

/** Renders the main screens on the JVM so layout regressions show up without a device. */
class ScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(nightMode = NightMode.NIGHT), maxPercentDifference = 0.1)

    private val chatActions = object : ChatActions {
        override fun send(text: String) = Unit
        override fun stop() = Unit
        override fun loadEarlier() = Unit
        override fun dismissNotice() = Unit
        override fun respondApproval(choice: String) = Unit
        override fun answerClarify(request: ClarifyRequest, answers: List<String>) = Unit
        override fun reconnectNow() = Unit
        override fun loadModels() = Unit
        override fun selectModel(provider: String, model: String, confirmed: Boolean) = Unit
        override fun cancelModelConfirm() = Unit
    }

    private val drawerActions = object : DrawerActions {
        override fun refreshSessionsAsync() = Unit
        override fun newChat() = Unit
        override fun selectProfile(name: String) = Unit
        override fun openSession(session: SessionSummary) = Unit
        override fun renameSession(id: String, title: String) = Unit
        override fun togglePinned(session: SessionSummary) = Unit
        override fun archiveSession(id: String) = Unit
        override fun deleteSession(id: String) = Unit
        override fun signOut() = Unit
        override fun switchDashboard(id: String) = Unit
        override fun addDashboard() = Unit
    }

    @Test
    fun login() = paparazzi.snapshot {
        ConduitTheme(dark = true) { LoginScreen(Screen.Login("http://192.168.1.10:9119", error = "Incorrect username or password."), onSignIn = { _, _, _, _, _ -> }) }
    }

    @Test
    fun chat() = paparazzi.snapshot {
        ConduitTheme(dark = true) {
            ChatContent(
                chat = ChatState(
                    sessionId = "s",
                    runtimeId = "rt",
                    title = "Fix the flaky deploy",
                    model = "claude-sonnet",
                    contextPercent = 23.0,
                    running = true,
                    messages = listOf(
                        ChatMessage("1", MessageRole.USER, "Why did last night's deploy fail?"),
                        ChatMessage(
                            "2",
                            MessageRole.ASSISTANT,
                            "The **migration step** timed out. Here's the relevant log:\n\n```\nERROR: lock timeout on table jobs\n```\n\n- Retry with a longer `lock_timeout`\n- Or run it off-peak",
                            reasoning = "Check the CI logs first.",
                            toolCalls = listOf(ToolCall("c1", "terminal", "kubectl logs deploy/api", "…")),
                        ),
                        ChatMessage("3", MessageRole.USER, "Retry it now with 30s."),
                        ChatMessage("4", MessageRole.ASSISTANT, "", toolCalls = listOf(ToolCall("c2", "terminal", "make migrate", running = true)), streaming = true),
                    ),
                    approval = ApprovalRequest("q1", "rt", "make migrate LOCK_TIMEOUT=30s", "Run a command on the server", listOf("once", "session", "always", "deny")),
                ),
                link = LinkState.CONNECTED,
                models = null,
                actions = chatActions,
                showMenuButton = true,
                onMenu = {},
            )
        }
    }

    @Test
    fun drawer() = paparazzi.snapshot {
        ConduitTheme(dark = true) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Box {
                    SessionDrawerContent(
                        sessions = SessionsState(
                            listOf(
                                session("a", "Fix the flaky deploy", pinned = true),
                                session("b", "Weekly report draft"),
                                session("c", "Kanban cleanup ideas"),
                            ),
                        ),
                        selectedId = "a",
                        profile = "default",
                        profiles = listOf("default", "work"),
                        dashboardUrl = "http://100.101.102.103:9119",
                        actions = drawerActions,
                        onPicked = {},
                    )
                }
            }
        }
    }

    private fun session(id: String, title: String, pinned: Boolean = false) = SessionSummary(
        id = id, title = title, preview = null, model = "claude-sonnet", profile = "default", pinned = pinned,
        archived = false, unread = false, messageCount = 4, lastActiveMs = 1_760_000_000_000, source = null,
    )
}

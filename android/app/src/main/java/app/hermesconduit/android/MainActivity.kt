package app.hermesconduit.android

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.core.LocalLanguage
import app.hermesconduit.android.data.AppSettings
import app.hermesconduit.android.ui.AppViewModel
import app.hermesconduit.android.ui.ConduitApp
import app.hermesconduit.android.ui.theme.ConduitTheme

// FragmentActivity (not ComponentActivity) so BiometricPrompt can attach.
class MainActivity : FragmentActivity() {
    private val viewModel: AppViewModel by viewModels()

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Shortcuts, the tile and notifications are ours; anything else is an external link. */
    private fun isInternal(intent: android.content.Intent) =
        intent.component?.packageName == packageName && (intent.data?.host == "new" || intent.data?.host == "voice" || referrer?.host == packageName)

    /** Notification taps (and later widgets/shortcuts) carry the chat to open. */
    private fun handleIntent(intent: android.content.Intent?) {
        intent ?: return
        when (intent.action) {
            android.content.Intent.ACTION_VIEW -> {
                app.hermesconduit.android.core.AppLink.parse(intent.data, external = !isInternal(intent))?.let(viewModel::handleLink)
                intent.data = null
                return
            }
            android.content.Intent.ACTION_SEND, android.content.Intent.ACTION_SEND_MULTIPLE -> {
                val text = listOfNotNull(
                    intent.getStringExtra(android.content.Intent.EXTRA_SUBJECT),
                    intent.getCharSequenceExtra(android.content.Intent.EXTRA_TEXT)?.toString(),
                ).joinToString("\n").trim()
                @Suppress("DEPRECATION")
                val uris = if (intent.action == android.content.Intent.ACTION_SEND) {
                    listOfNotNull(intent.getParcelableExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM))
                } else {
                    intent.getParcelableArrayListExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM).orEmpty()
                }
                viewModel.receiveShare(text, uris)
                intent.action = null
                return
            }
        }
        val session = intent.getStringExtra(app.hermesconduit.android.notify.ConduitNotifications.EXTRA_SESSION) ?: return
        viewModel.openFromNotification(
            session,
            intent.getStringExtra(app.hermesconduit.android.notify.ConduitNotifications.EXTRA_TITLE),
            intent.getStringExtra(app.hermesconduit.android.notify.ConduitNotifications.EXTRA_DASHBOARD),
            intent.getStringExtra(app.hermesconduit.android.notify.ConduitNotifications.EXTRA_PROFILE),
        )
        intent.removeExtra(app.hermesconduit.android.notify.ConduitNotifications.EXTRA_SESSION)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        val settings = appSettings
        setContent {
            val languagePreference by settings.string(AppSettings.LANGUAGE, "system").collectAsStateWithLifecycle()
            val themeMode by settings.string(AppSettings.THEME, "system").collectAsStateWithLifecycle()
            val language = remember(languagePreference) {
                L10n.load(this, languagePreference)
                L10n.language
            }
            val dark = when (themeMode) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }
            CompositionLocalProvider(LocalLanguage provides language) {
                ConduitTheme(dark = dark) {
                    app.hermesconduit.android.ui.AppLockGate { ConduitApp(viewModel) }
                }
            }
        }
    }
}

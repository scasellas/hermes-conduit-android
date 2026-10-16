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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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

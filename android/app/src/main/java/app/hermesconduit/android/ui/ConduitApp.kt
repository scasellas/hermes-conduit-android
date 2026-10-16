package app.hermesconduit.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun ConduitApp(viewModel: AppViewModel) {
    val screen by viewModel.screen.collectAsStateWithLifecycle()
    when (val current = screen) {
        Screen.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is Screen.Login -> {
            val context = LocalContext.current
            LoginScreen(
                current,
                onSignIn = viewModel::signIn,
                onBrowserSignIn = { url, id, secret ->
                    viewModel.signInWithBrowser(url, id, secret) { authorizeUrl ->
                        CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(authorizeUrl))
                    }
                },
                onCancel = viewModel::cancelAddDashboard,
            )
        }
        Screen.Main -> MainScreen(viewModel)
    }
}

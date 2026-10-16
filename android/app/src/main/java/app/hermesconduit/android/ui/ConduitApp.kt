package app.hermesconduit.android.ui

import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.hermesconduit.android.ui.nav.features

/** The `{arg}` of a `feature/{arg}` route (e.g. a bot or task id), or null. */
val LocalRouteArg = staticCompositionLocalOf<String?> { null }

/** Lets any screen open a feature route (e.g. a chat from the Kanban board). */
val LocalNavigate = staticCompositionLocalOf<(String) -> Unit> { {} }

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
        Screen.Main -> MainNavigation(viewModel)
    }
}

@Composable
private fun MainNavigation(viewModel: AppViewModel) {
    val nav = rememberNavController()
    val navigate: (String) -> Unit = { route ->
        if (route == "home") nav.popBackStack("home", inclusive = false) else nav.navigate(route)
    }
    val context = LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) { app.hermesconduit.android.ui.present.PresentedScreens.sync(context) }
    app.hermesconduit.android.ui.present.PresentedConfirmHost()
    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalNavigate provides navigate) {
            NavHost(nav, startDestination = "home") {
                composable("home") { MainScreen(viewModel, openFeature = navigate) }
                features.forEach { feature ->
                    composable(feature.route) {
                        feature.content({ nav.popBackStack() }, navigate)
                    }
                    composable("${feature.route}/{arg}", arguments = listOf(navArgument("arg") { type = NavType.StringType })) { entry ->
                        CompositionLocalProvider(LocalRouteArg provides entry.arguments?.getString("arg")?.let(Uri::decode)) {
                            feature.content({ nav.popBackStack() }, navigate)
                        }
                    }
                }
            }
        }
        // Calendar changes Hermes asked for, over everything.
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.statusBarsPadding()) { app.hermesconduit.android.ui.phone.PhoneChangeOverlay() }
    }
}

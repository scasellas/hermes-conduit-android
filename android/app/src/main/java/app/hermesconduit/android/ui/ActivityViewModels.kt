package app.hermesconduit.android.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel

/** The activity-wide [AppViewModel] (chat + sessions) from inside a feature screen. */
@Composable
fun appViewModel(): AppViewModel {
    val activity = LocalContext.current as ComponentActivity
    return viewModel(viewModelStoreOwner = activity)
}

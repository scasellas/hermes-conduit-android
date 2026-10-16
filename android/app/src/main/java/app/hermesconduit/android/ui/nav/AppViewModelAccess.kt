package app.hermesconduit.android.ui.nav

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import app.hermesconduit.android.model.SessionSummary
import app.hermesconduit.android.ui.AppViewModel

private tailrec fun Context.componentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.componentActivity()
    else -> null
}

/** The activity's [AppViewModel] (chat, sessions, sign-out), from any feature screen. */
@Composable
fun activityAppViewModel(): AppViewModel? {
    val activity = LocalContext.current.componentActivity() ?: return null
    return viewModel<AppViewModel>(viewModelStoreOwner = activity)
}

/** Opens a stored chat by id and returns to the chat screen. */
fun AppViewModel.openChatById(id: String, title: String?, navigate: (String) -> Unit) {
    openSession(
        SessionSummary(
            id = id, title = title.orEmpty(), preview = null, model = null, profile = null, pinned = false,
            archived = false, unread = false, messageCount = null, lastActiveMs = null, source = null,
        ),
    )
    navigate("home")
}

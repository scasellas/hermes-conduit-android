package app.hermesconduit.android.push

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hermesconduit.android.ConduitApplication
import app.hermesconduit.android.core.t
import app.hermesconduit.android.ui.components.SettingsRow
import app.hermesconduit.android.ui.components.SettingsSection
import kotlinx.coroutines.launch

/**
 * Settings > Notifications > Push through UnifiedPush: the installed
 * distributors, registering with one, what the plugin made of it, and
 * turning it off. Without a ConduitApplication (previews) it shows nothing.
 */
@Composable
fun UnifiedPushSection() {
    val context = LocalContext.current
    if (context.applicationContext !is ConduitApplication) return
    val push = remember { ConduitPush.get(context) }
    val state by push.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // A distributor installed meanwhile shows up when the page comes back.
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumed++
        onPauseOrDispose { }
    }
    val distributors = remember(resumed, state) { push.distributors() }
    val current = remember(resumed, state) { push.distributor() }

    SettingsSection(
        t("Push through UnifiedPush"),
        footer = t("Wakes Conduit for notifications, calls and location requests while it's closed, through a UnifiedPush distributor such as ntfy. Notifications are end-to-end encrypted: the push server can't read them."),
    ) {
        when (val s = state) {
            ConduitPush.State.Off, is ConduitPush.State.Failed -> {
                if (s is ConduitPush.State.Failed) SettingsRow(t("Status"), subtitle = s.message)
                if (distributors.isEmpty()) {
                    SettingsRow(
                        t("No distributor installed"),
                        subtitle = t("Install a UnifiedPush distributor such as ntfy, then come back here."),
                        icon = Icons.Default.CloudQueue,
                    )
                } else {
                    distributors.forEach { distributor ->
                        SettingsRow(
                            t("Use %s", distributor.label),
                            subtitle = distributor.packageName,
                            icon = Icons.Default.CloudQueue,
                            onClick = { push.enable(distributor.packageName) },
                        )
                    }
                }
            }
            else -> {
                SettingsRow(t("Status"), value = when (s) {
                    ConduitPush.State.Waiting -> t("Waiting for the distributor")
                    ConduitPush.State.Registering -> t("Registering with Hermes")
                    ConduitPush.State.Registered -> t("On")
                    else -> null
                })
                if (s == ConduitPush.State.Unsupported) {
                    SettingsRow(
                        t("Your Hermes notifier doesn't support Android push yet"),
                        subtitle = t("Notifications keep coming through the background connection. Conduit tries again each time it connects."),
                    )
                }
                val label = distributors.firstOrNull { it.packageName == current }?.label ?: current
                if (label != null) SettingsRow(t("Distributor"), value = label)
                OutlinedButton(
                    onClick = { scope.launch { push.disable() } },
                    modifier = Modifier.padding(16.dp),
                ) { Text(t("Turn off push")) }
            }
        }
    }
}

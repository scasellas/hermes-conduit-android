package app.hermesconduit.android.ui

import app.hermesconduit.android.core.t
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun LoginScreen(
    state: Screen.Login,
    onSignIn: (String, String, String, String, String) -> Unit,
    onBrowserSignIn: (String, String, String) -> Unit = { _, _, _ -> },
    onCancel: () -> Unit = {},
    onTest: ((String, String, String, String, String) -> Unit)? = null,
    testResult: ConnectionTestResult? = null,
    onDismissTest: () -> Unit = {},
) {
    var url by rememberSaveable { mutableStateOf(state.prefillUrl) }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var useServiceToken by rememberSaveable { mutableStateOf(false) }
    var cfId by rememberSaveable { mutableStateOf("") }
    var cfSecret by rememberSaveable { mutableStateOf("") }

    val submit = {
        if (!state.busy && url.isNotBlank()) {
            onSignIn(url, username, password, if (useServiceToken) cfId else "", if (useServiceToken) cfSecret else "")
        }
    }

    if (testResult != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onDismissTest,
            title = { Text(app.hermesconduit.android.core.t("Connection test")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    testResult.stages.forEach { (label, status) ->
                        val mark = when (status) { "passed" -> "✓"; "failed" -> "✕"; "checking" -> "…"; "waiting" -> "○"; else -> "!" }
                        Text("$mark  $label")
                    }
                    testResult.failure?.let {
                        Text(it.title, style = MaterialTheme.typography.titleSmall)
                        Text(it.detail, style = MaterialTheme.typography.bodySmall)
                    }
                    testResult.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = { TextButton(onClick = onDismissTest) { Text(app.hermesconduit.android.core.t("Done")) } },
        )
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .safeDrawingPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(48.dp))
            Text("Conduit", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
            Text(
                "Connect to your Hermes dashboard",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(32.dp))
            Column(Modifier.widthIn(max = 480.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(t("Dashboard address")) },
                    placeholder = { Text("http://192.168.1.10:9119") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(t("Username")) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(t("Password")) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t("Cloudflare Access service token"), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = useServiceToken, onCheckedChange = { useServiceToken = it })
                }
                if (useServiceToken) {
                    OutlinedTextField(
                        value = cfId,
                        onValueChange = { cfId = it },
                        label = { Text(t("Client ID")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = cfSecret,
                        onValueChange = { cfSecret = it },
                        label = { Text(t("Client secret")) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                Button(onClick = submit, enabled = !state.busy && url.isNotBlank(), modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Text(t("Sign in"))
                }
                OutlinedButton(
                    onClick = { onBrowserSignIn(url, if (useServiceToken) cfId else "", if (useServiceToken) cfSecret else "") },
                    enabled = !state.busy && url.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text(t("Sign in with browser (Google, OIDC…)")) }
                if (onTest != null) {
                    TextButton(
                        onClick = { onTest(url, username, password, if (useServiceToken) cfId else "", if (useServiceToken) cfSecret else "") },
                        enabled = !state.busy && url.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(app.hermesconduit.android.core.t("Test Connection")) }
                }
                if (state.canCancel) {
                    TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text(t("Cancel")) }
                }
                Text(
                    "Conduit connects to the native Hermes dashboard (default port 9119), not the WebUI. " +
                        "Plain HTTP is allowed on your local network and over Tailscale.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

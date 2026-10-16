package app.hermesconduit.android.calls

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.hermesconduit.android.MainActivity
import app.hermesconduit.android.core.t
import app.hermesconduit.android.model.HermesCallCopy
import app.hermesconduit.android.ui.theme.ConduitTheme

/** The full-screen ringing call, over the lock screen (the CallKit screen's counterpart). */
class HermesCallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        val routing = intent.getStringExtra(HermesCallNotifications.EXTRA_CALL)
        val target = HermesCallNotifications.target(routing)
        if (target == null) {
            finish()
            return
        }
        val caller = intent.getStringExtra(EXTRA_CALLER) ?: HermesCallCopy.notificationTitle
        val summary = HermesCallCopy.summary(target.call.kind, target.call.title, target.call.reason)
        setContent {
            ConduitTheme(dark = true) {
                CallScreen(
                    caller, summary,
                    answer = {
                        startActivity(
                            Intent(this, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                .putExtra(HermesCallNotifications.EXTRA_CALL, routing),
                        )
                        finish()
                    },
                    decline = {
                        HermesCalls.get(this).decline()
                        finish()
                    },
                )
            }
        }
    }

    companion object {
        const val EXTRA_CALLER = "hermes_call_caller"
    }
}

@Composable
private fun CallScreen(caller: String, summary: String, answer: () -> Unit, decline: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Spacer(Modifier.height(48.dp))
                Text(t("Hermes wants to talk"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(caller, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                Text(summary, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(72.dp), modifier = Modifier.padding(bottom = 48.dp)) {
                CallButton(Icons.Default.CallEnd, t("Decline"), Color(0xFFD93B3B), decline)
                CallButton(Icons.Default.Call, t("Answer"), Color(0xFF2E9E57), answer)
            }
        }
    }
}

@Composable
private fun CallButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledIconButton(onClick = onClick, modifier = Modifier.size(72.dp), shape = CircleShape, colors = IconButtonDefaults.filledIconButtonColors(containerColor = color, contentColor = Color.White)) {
            Icon(icon, label, Modifier.size(32.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

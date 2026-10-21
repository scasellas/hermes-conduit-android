package app.hermesconduit.android.wear

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * A call from Hermes ringing on the watch, full screen over the watch face
 * (the system call screen's counterpart on the Apple Watch): who's calling,
 * Decline and Answer. It closes once the call stops ringing here.
 */
class HermesCallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        val calls = wearApp.hermesCalls
        val ring = intent.getStringExtra(HermesWatchCalls.EXTRA_RING)
        val ringing = calls.ringing.value?.takeIf { it.id == ring }
        if (ringing == null) {
            finish()
            return
        }
        lifecycleScope.launch {
            calls.ringing.first { it?.id != ringing.id }
            finish()
        }
        setContent {
            MaterialTheme {
                RingingScreen(
                    ringing.callerName,
                    answer = {
                        calls.answer(ringing.id)
                        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
                        finish()
                    },
                    decline = {
                        calls.decline(ringing.id)
                        finish()
                    },
                )
            }
        }
    }
}

@Composable
private fun RingingScreen(caller: String, answer: () -> Unit, decline: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        Text(t("Calls from Hermes"), style = MaterialTheme.typography.caption2, color = MaterialTheme.colors.onSurfaceVariant)
        Text(caller, style = MaterialTheme.typography.title3, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            RingButton(Icons.Default.CallEnd, t("Decline"), Color(0xFFD93B3B), decline)
            RingButton(Icons.Default.Call, t("Answer"), Color(0xFF2E9E57), answer)
        }
    }
}

@Composable
private fun RingButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Button(onClick = onClick, colors = ButtonDefaults.buttonColors(backgroundColor = color, contentColor = Color.White), modifier = Modifier.size(ButtonDefaults.DefaultButtonSize)) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(26.dp))
    }
}

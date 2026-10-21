package app.hermesconduit.android.ui.phone

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Stop
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
import androidx.lifecycle.lifecycleScope
import app.hermesconduit.android.core.t
import app.hermesconduit.android.ui.theme.ConduitTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant

/** A ringing alarm or timer, full screen over the lock screen (AlarmKit's alert), with Stop. */
class AlarmRingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val alarms = ConduitAlarms.of(this)
        val id = intent.getStringExtra(ConduitAlarms.EXTRA_ID)
        val record = id?.let(alarms::record)
        if (id == null || record == null || id !in alarms.ringing.value) {
            finish()
            return
        }
        // Stopped from the notification, or the ring ran out: the screen goes with it.
        lifecycleScope.launch {
            alarms.ringing.first { id !in it }
            finish()
        }
        val title = ConduitAlarmNotifications.title(record)
        val time = ConduitAlarmNotifications.timeLine(record.ringingSince?.let(Instant::ofEpochMilli) ?: Instant.now())
        setContent {
            ConduitTheme(dark = true) {
                AlarmScreen(title, time) {
                    alarms.stop(id)
                    finish()
                }
            }
        }
    }
}

@Composable
private fun AlarmScreen(title: String, time: String, stop: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Spacer(Modifier.height(48.dp))
                Icon(Icons.Default.Alarm, null, Modifier.size(40.dp), tint = Color(0xFFFF9500))
                Text(time, style = MaterialTheme.typography.displayMedium, textAlign = TextAlign.Center)
                Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 48.dp)) {
                val label = t("Stop")
                FilledIconButton(
                    onClick = stop, modifier = Modifier.size(88.dp), shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFFF9500), contentColor = Color.White),
                ) {
                    Icon(Icons.Default.Stop, label, Modifier.size(40.dp))
                }
                Text(label, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

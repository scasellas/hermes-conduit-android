package app.hermesconduit.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import app.hermesconduit.android.ui.AppViewModel
import app.hermesconduit.android.ui.ConduitApp
import app.hermesconduit.android.ui.theme.ConduitTheme

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ConduitTheme {
                ConduitApp(viewModel)
            }
        }
    }
}

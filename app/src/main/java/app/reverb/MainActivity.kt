package app.reverb

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.reverb.ui.screen.SpikeScreen
import app.reverb.ui.theme.ReverbTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ReverbTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SpikeScreen(app = ReverbApp.instance)
                }
            }
        }
    }
}

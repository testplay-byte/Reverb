package app.reverb

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import app.reverb.ui.MainScreen
import app.reverb.ui.theme.ReverbTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ReverbTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(app = ReverbApp.instance)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ReverbApp.instance.player.release()
    }
}

package app.reverb.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.reverb.ReverbApp
import app.reverb.ui.screen.BrowseScreen
import app.reverb.ui.screen.DownloadsScreen
import app.reverb.ui.screen.SettingsScreen

/**
 * Main app screen — Scaffold with bottom navigation (Browse / Downloads / Settings).
 * Replaces the Phase 0 SpikeScreen.
 */
@Composable
fun MainScreen(app: ReverbApp) {
    var currentScreen by remember { mutableStateOf(Screen.BROWSE) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Screen.entries.forEach { screen ->
                    NavigationBarItem(
                        selected = currentScreen == screen,
                        onClick = { currentScreen = screen },
                        icon = { Icon(screen.icon, contentDescription = screen.label) },
                        label = { Text(screen.label) },
                    )
                }
            }
        },
    ) { padding ->
        when (currentScreen) {
            Screen.BROWSE -> BrowseScreen(app, modifier = Modifier.fillMaxSize().padding(padding))
            Screen.DOWNLOADS -> DownloadsScreen(app, modifier = Modifier.fillMaxSize().padding(padding))
            Screen.SETTINGS -> SettingsScreen(app, modifier = Modifier.fillMaxSize().padding(padding))
        }
    }
}

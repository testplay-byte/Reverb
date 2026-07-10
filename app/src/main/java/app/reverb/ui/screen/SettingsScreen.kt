package app.reverb.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.reverb.ReverbApp
import app.reverb.core.common.ReverbLog
import app.reverb.data.AppSettings

@Composable
fun SettingsScreen(
    app: ReverbApp,
    modifier: Modifier = Modifier,
) {
    var settings by remember { mutableStateOf(app.dataRepository.getSettings()) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        HorizontalDivider()

        // ── Ad-blocking ──
        SettingsCard(
            title = "Ad-blocking",
            description = "Block ads using EasyList + AdGuard filter lists. The extractor-before-blocker contract ensures video URLs are never blocked.",
            checked = settings.adBlockEnabled,
            onCheckedChange = { v ->
                settings = settings.copy(adBlockEnabled = v)
                app.dataRepository.saveSettings(settings)
                ReverbLog.i("Settings", "Ad-block ${if (v) "enabled" else "disabled"}")
            },
        )

        // ── DNS-over-HTTPS ──
        SettingsCard(
            title = "DNS-over-HTTPS",
            description = "Route DNS queries through Cloudflare 1.1.1.1 to bypass DNS-level blocking.",
            checked = settings.dohEnabled,
            onCheckedChange = { v ->
                settings = settings.copy(dohEnabled = v)
                app.dataRepository.saveSettings(settings)
                ReverbLog.i("Settings", "DoH ${if (v) "enabled" else "disabled"}")
            },
        )

        // ── Cloudflare solver ──
        SettingsCard(
            title = "Cloudflare solver",
            description = "Automatically solve Cloudflare challenges via WebView cookie-polling (up to 30s per challenge).",
            checked = settings.cfSolverEnabled,
            onCheckedChange = { v ->
                settings = settings.copy(cfSolverEnabled = v)
                app.dataRepository.saveSettings(settings)
                ReverbLog.i("Settings", "CF solver ${if (v) "enabled" else "disabled"}")
            },
        )

        // ── Translation ──
        SettingsCard(
            title = "Content translation",
            description = "Translate anime titles, synopses, and episode titles to your language (ML Kit, on-device).",
            checked = settings.translationEnabled,
            onCheckedChange = { v ->
                settings = settings.copy(translationEnabled = v)
                app.dataRepository.saveSettings(settings)
                ReverbLog.i("Settings", "Translation ${if (v) "enabled" else "disabled"}")
            },
        )

        SettingsCard(
            title = "Auto-translate",
            description = "Automatically translate content when the source language differs from your locale.",
            checked = settings.autoTranslate,
            onCheckedChange = { v ->
                settings = settings.copy(autoTranslate = v)
                app.dataRepository.saveSettings(settings)
            },
        )

        HorizontalDivider()

        // ── Downloads ──
        Text("Downloads", style = MaterialTheme.typography.titleMedium)
        SettingsCard(
            title = "Wi-Fi only",
            description = "Only download over Wi-Fi to save mobile data.",
            checked = settings.wifiOnlyDownloads,
            onCheckedChange = { v ->
                settings = settings.copy(wifiOnlyDownloads = v)
                app.dataRepository.saveSettings(settings)
            },
        )

        HorizontalDivider()

        // ── About ──
        Text("About", style = MaterialTheme.typography.titleMedium)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Reverb", style = MaterialTheme.typography.titleLarge)
                Text("Phase 1 MVP • v0.1.0", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("An Android browser that rebuilds the web.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                Text("Green M3 Expressive theme", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

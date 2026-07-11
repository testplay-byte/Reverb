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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.reverb.ReverbApp
import app.reverb.core.common.ReverbLog
import app.reverb.data.AppSettings
import app.reverb.data.LlmConfig
import app.reverb.data.LlmProvider

@Composable
fun SettingsScreen(
    app: ReverbApp,
    modifier: Modifier = Modifier,
) {
    var settings by remember { mutableStateOf(app.dataRepository.getSettings()) }
    var llmConfig by remember { mutableStateOf(app.dataRepository.getLlmConfig()) }

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
        SettingsCard("Ad-blocking", "Block ads using EasyList + AdGuard. Video URLs are never blocked.", settings.adBlockEnabled) { v ->
            settings = settings.copy(adBlockEnabled = v); app.dataRepository.saveSettings(settings)
        }
        SettingsCard("DNS-over-HTTPS", "Route DNS through Cloudflare 1.1.1.1 to bypass DNS-level blocking.", settings.dohEnabled) { v ->
            settings = settings.copy(dohEnabled = v); app.dataRepository.saveSettings(settings)
        }
        SettingsCard("Cloudflare solver", "Auto-solve CF challenges via WebView cookie-polling (up to 30s).", settings.cfSolverEnabled) { v ->
            settings = settings.copy(cfSolverEnabled = v); app.dataRepository.saveSettings(settings)
        }

        HorizontalDivider()

        // ── LLM ──
        Text("LLM (for site analysis)", style = MaterialTheme.typography.titleMedium)
        Text(
            "The LLM analyzes each website's HTML and generates CSS selectors to rebuild the site as a native Android UI. Choose your provider below.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Provider selection.
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Provider", style = MaterialTheme.typography.titleSmall)
                LlmProvider.entries.forEach { provider ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        RadioButton(
                            selected = llmConfig.provider == provider,
                            onClick = {
                                llmConfig = llmConfig.copy(provider = provider)
                                app.dataRepository.saveLlmConfig(llmConfig)
                                app.refreshLlmClient()
                            },
                        )
                        Text(
                            when (provider) {
                                LlmProvider.NONE -> "None (disabled)"
                                LlmProvider.GEMINI -> "Google Gemini (free, recommended)"
                                LlmProvider.OPENAI_COMPATIBLE -> "OpenAI-compatible (GLM, OpenRouter, etc.)"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        }

        // Provider-specific config.
        if (llmConfig.provider == LlmProvider.GEMINI) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Gemini configuration", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Get a free API key at https://aistudio.google.com/apikey\n" +
                        "Free tier: 15 requests/min, 1500/day. Rate limiting is handled automatically.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = llmConfig.apiKey,
                        onValueChange = { v ->
                            llmConfig = llmConfig.copy(apiKey = v)
                            app.dataRepository.saveLlmConfig(llmConfig)
                            app.refreshLlmClient()
                        },
                        label = { Text("Google API key") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = llmConfig.model,
                        onValueChange = { v ->
                            llmConfig = llmConfig.copy(model = v)
                            app.dataRepository.saveLlmConfig(llmConfig)
                            app.refreshLlmClient()
                        },
                        label = { Text("Model (default: gemini-2.0-flash)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Status: ${if (app.llmClient.isConfigured) "✅ ${app.llmClient.name}" else "❌ Not configured"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (app.llmClient.isConfigured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        if (llmConfig.provider == LlmProvider.OPENAI_COMPATIBLE) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("OpenAI-compatible configuration", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Works with GLM (ZAI), OpenAI, OpenRouter, Ollama, vLLM, etc.\n" +
                        "GLM default: endpoint=https://api.z.ai/api/paas/v4/chat/completions, model=glm-4-flash",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = llmConfig.endpoint,
                        onValueChange = { v -> llmConfig = llmConfig.copy(endpoint = v); app.dataRepository.saveLlmConfig(llmConfig); app.refreshLlmClient() },
                        label = { Text("Endpoint URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = llmConfig.apiKey,
                        onValueChange = { v -> llmConfig = llmConfig.copy(apiKey = v); app.dataRepository.saveLlmConfig(llmConfig); app.refreshLlmClient() },
                        label = { Text("API key") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = llmConfig.model,
                        onValueChange = { v -> llmConfig = llmConfig.copy(model = v); app.dataRepository.saveLlmConfig(llmConfig); app.refreshLlmClient() },
                        label = { Text("Model name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Status: ${if (app.llmClient.isConfigured) "✅ ${app.llmClient.name}" else "❌ Not configured"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (app.llmClient.isConfigured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        HorizontalDivider()

        // ── Downloads ──
        Text("Downloads", style = MaterialTheme.typography.titleMedium)
        SettingsCard("Wi-Fi only", "Only download over Wi-Fi to save mobile data.", settings.wifiOnlyDownloads) { v ->
            settings = settings.copy(wifiOnlyDownloads = v); app.dataRepository.saveSettings(settings)
        }

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
                Text("Phase 2 • v0.2.0", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("An Android browser that rebuilds the web.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                Text("LLM: ${app.llmClient.name}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun SettingsCard(title: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
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

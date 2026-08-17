package com.binverse.vision.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var apiKeyField by remember(settings.groqApiKey) { mutableStateOf(settings.groqApiKey) }
    var modelField by remember(settings.groqModel) { mutableStateOf(settings.groqModel) }
    var ipField by remember(settings.esp32Ip) { mutableStateOf(settings.esp32Ip) }
    var portField by remember(settings.esp32Port) { mutableStateOf(settings.esp32Port.toString()) }
    var intervalField by remember(settings.captureIntervalMs) { mutableStateOf(settings.captureIntervalMs.toString()) }
    var thresholdField by remember(settings.confidenceThreshold) { mutableStateOf(settings.confidenceThreshold.toString()) }
    var cooldownField by remember(settings.commandCooldownMs) { mutableStateOf(settings.commandCooldownMs.toString()) }
    var sessionCapField by remember(settings.sessionFrameCap) { mutableStateOf(settings.sessionFrameCap.toString()) }
    var maxRequestsField by remember(settings.maxRequestsPerSession) { mutableStateOf(settings.maxRequestsPerSession.toString()) }

    var testResultText by remember { mutableStateOf<String?>(null) }
    var showApiKey by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("← Back") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            SectionLabel("Groq API Key")
            Text(
                "Stored locally in Android's encrypted storage (Keystore-backed). " +
                    "Never hard-coded into the app. For a production deployment, move this " +
                    "call behind a backend server — see README.",
                fontSize = 12.sp
            )
            OutlinedTextField(
                value = apiKeyField,
                onValueChange = { apiKeyField = it },
                label = { Text("Groq API Key") },
                singleLine = true,
                visualTransformation = if (showApiKey) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { showApiKey = !showApiKey }) {
                        Text(if (showApiKey) "Hide" else "Show", fontSize = 11.sp)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            Button(onClick = { viewModel.updateApiKey(apiKeyField.trim()) }) {
                Text("Save API Key")
            }

            Divider()
            SectionLabel("Groq Model")
            OutlinedTextField(
                value = modelField,
                onValueChange = { modelField = it },
                label = { Text("Model ID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Divider()
            SectionLabel("ESP32 Connection")
            OutlinedTextField(
                value = ipField,
                onValueChange = { ipField = it },
                label = { Text("ESP32 IP Address") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = portField,
                onValueChange = { portField = it.filter { c -> c.isDigit() } },
                label = { Text("Port") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = {
                    scope.launch {
                        val result = viewModel.testEsp32Connection()
                        testResultText = when (result) {
                            is com.binverse.vision.network.Esp32Result.Success -> "✅ Connected: ${result.statusText}"
                            is com.binverse.vision.network.Esp32Result.Error -> "❌ ${result.message}"
                        }
                    }
                }) { Text("TEST CONNECTION") }
            }
            testResultText?.let { Text(it, fontSize = 13.sp) }

            Divider()
            SectionLabel("AI Confidence Threshold")
            OutlinedTextField(
                value = thresholdField,
                onValueChange = { thresholdField = it },
                label = { Text("0.0 – 1.0 (default 0.80)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )

            SectionLabel("Capture Interval")
            OutlinedTextField(
                value = intervalField,
                onValueChange = { intervalField = it.filter { c -> c.isDigit() } },
                label = { Text("Milliseconds (default 1500)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )

            SectionLabel("Command Cooldown (duplicate protection)")
            OutlinedTextField(
                value = cooldownField,
                onValueChange = { cooldownField = it.filter { c -> c.isDigit() } },
                label = { Text("Milliseconds (default 3000)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )

            SectionLabel("Session Frame Cap")
            Text(
                "Max frames auto-detect sends to Groq before pausing itself. " +
                    "Tap START AUTO-DETECT again to run another batch. Manual " +
                    "ANALYZE NOW is never counted against this.",
                fontSize = 12.sp
            )
            OutlinedTextField(
                value = sessionCapField,
                onValueChange = { sessionCapField = it.filter { c -> c.isDigit() } },
                label = { Text("Frames per session (default 3)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )

            Divider()
            SectionLabel("Frame Pre-filter")
            Row(verticalAlignment = Alignment_CenterVertically(), horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("Skip near-identical frames before sending to Groq", fontSize = 13.sp, modifier = Modifier.weight(1f))
                Switch(
                    checked = settings.frameDiffFilterEnabled,
                    onCheckedChange = { checked ->
                        viewModel.updateSettings { it.copy(frameDiffFilterEnabled = checked) }
                    }
                )
            }

            Divider()
            SectionLabel("API Cost Control")
            Row(verticalAlignment = Alignment_CenterVertically(), horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("Enforce maximum requests per session", fontSize = 13.sp, modifier = Modifier.weight(1f))
                Switch(
                    checked = settings.maxRequestsEnabled,
                    onCheckedChange = { checked ->
                        viewModel.updateSettings { it.copy(maxRequestsEnabled = checked) }
                    }
                )
            }
            OutlinedTextField(
                value = maxRequestsField,
                onValueChange = { maxRequestsField = it.filter { c -> c.isDigit() } },
                label = { Text("Maximum API requests") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(onClick = { viewModel.resetRequestCounter() }) {
                Text("Reset request counter")
            }

            Divider()
            SectionLabel("Debug")
            Row(verticalAlignment = Alignment_CenterVertically(), horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("Show approximate image size before upload", fontSize = 13.sp, modifier = Modifier.weight(1f))
                Switch(
                    checked = settings.debugShowImageSize,
                    onCheckedChange = { checked ->
                        viewModel.updateSettings { it.copy(debugShowImageSize = checked) }
                    }
                )
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    viewModel.updateSettings {
                        it.copy(
                            groqModel = modelField.trim(),
                            esp32Ip = ipField.trim(),
                            esp32Port = portField.toIntOrNull() ?: it.esp32Port,
                            captureIntervalMs = intervalField.toLongOrNull() ?: it.captureIntervalMs,
                            confidenceThreshold = thresholdField.toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: it.confidenceThreshold,
                            commandCooldownMs = cooldownField.toLongOrNull() ?: it.commandCooldownMs,
                            sessionFrameCap = sessionCapField.toIntOrNull()?.coerceAtLeast(1) ?: it.sessionFrameCap,
                            maxRequestsPerSession = maxRequestsField.toIntOrNull() ?: it.maxRequestsPerSession
                        )
                    }
                    onBack()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save & Return")
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
}

// Small local helper to avoid importing androidx.compose.ui.Alignment under a name
// clash with Arrangement in this file's dense import list.
private fun Alignment_CenterVertically() = androidx.compose.ui.Alignment.CenterVertically

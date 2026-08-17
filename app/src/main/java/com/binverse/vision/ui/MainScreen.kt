package com.binverse.vision.ui

import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.binverse.vision.camera.BinVerseCameraManager
import com.binverse.vision.detection.ConnectionStatus
import com.binverse.vision.model.DetectionResult
import com.binverse.vision.model.RobotAction
import com.binverse.vision.ui.theme.BinVerseAmber
import com.binverse.vision.ui.theme.BinVerseGreen
import com.binverse.vision.ui.theme.BinVerseRed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    cameraManager: BinVerseCameraManager,
    onOpenSettings: () -> Unit,
    onCameraError: (String) -> Unit
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val pipeline by viewModel.pipelineState.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val autoRunning by viewModel.autoDetectRunning.collectAsStateWithLifecycle()
    val sessionFramesSent by viewModel.sessionFramesSent.collectAsStateWithLifecycle()
    val sessionCapJustReached by viewModel.sessionCapJustReached.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("BINVERSE AI VISION", fontWeight = FontWeight.Bold, letterSpacing = 1.sp) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Text("⚙", fontSize = 20.sp)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // --- Live camera preview -------------------------------------------------
            Card(modifier = Modifier.fillMaxWidth().height(320.dp)) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        PreviewView(ctx).also { pv ->
                            cameraManager.startCamera(pv, onError = onCameraError)
                        }
                    }
                )
            }

            if (pipeline.limitReached) {
                LimitReachedBanner(onReset = { viewModel.resetRequestCounter() })
            }

            if (sessionCapJustReached) {
                SessionCapBanner(
                    cap = settings.sessionFrameCap,
                    onDismiss = { viewModel.dismissSessionCapNotice() }
                )
            }

            // --- Detection result -------------------------------------------------
            DetectionResultCard(pipeline.lastResult)

            // --- Status row -------------------------------------------------
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatusChip("Groq", pipeline.groqStatus, Modifier.weight(1f))
                StatusChip("ESP32", pipeline.esp32Status, Modifier.weight(1f))
            }

            pipeline.lastError?.let { err ->
                Text(
                    text = "⚠ $err",
                    color = BinVerseAmber,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }

            InfoRow("Last command", pipeline.lastResult?.let {
                "${it.objectType.name.uppercase()} · ${it.confidencePercent()}% · ${it.action.name}"
            } ?: "—")
            InfoRow("Capture interval", "${settings.captureIntervalMs} ms")
            InfoRow(
                "Session frames sent",
                if (autoRunning) "$sessionFramesSent / ${settings.sessionFrameCap}" else "— (auto-detect paused)"
            )
            InfoRow(
                "API requests",
                "${pipeline.requestsThisSession}" + if (settings.maxRequestsEnabled) " / ${settings.maxRequestsPerSession}" else ""
            )
            if (settings.debugShowImageSize && pipeline.lastImageSizeBytes != null) {
                InfoRow("Last image size", "${pipeline.lastImageSizeBytes!! / 1024} KB")
            }

            // --- Controls -------------------------------------------------
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = {
                        val next = !autoRunning
                        viewModel.setAutoDetectRunning(next)
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (autoRunning) "STOP AUTO-DETECT" else "START AUTO-DETECT")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(onClick = { viewModel.analyzeNow() }, modifier = Modifier.weight(1f)) {
                    Text("ANALYZE NOW")
                }
                OutlinedButton(onClick = { viewModel.testEsp32() }, modifier = Modifier.weight(1f)) {
                    Text("TEST ESP32")
                }
            }

            // --- History -------------------------------------------------
            Text("Detection History", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Card(modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
                LazyColumn(modifier = Modifier.padding(8.dp)) {
                    items(history) { record -> HistoryRow(record) }
                }
            }
        }
    }
}

@Composable
private fun DetectionResultCard(result: DetectionResult?) {
    val actionColor = when (result?.action) {
        RobotAction.PICKUP -> BinVerseGreen
        RobotAction.WAIT -> BinVerseAmber
        RobotAction.IGNORE -> Color.Gray
        null -> Color.Gray
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Detected object", fontSize = 12.sp, color = Color.Gray)
            Text(
                text = result?.objectType?.name?.uppercase() ?: "—",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column {
                    Text("Confidence", fontSize = 12.sp, color = Color.Gray)
                    Text("${result?.confidencePercent() ?: 0}%", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                }
                Column {
                    Text("Action", fontSize = 12.sp, color = Color.Gray)
                    Text(
                        result?.action?.name ?: "—",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = actionColor
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusChip(label: String, status: ConnectionStatus, modifier: Modifier = Modifier) {
    val (text, color) = when (status) {
        ConnectionStatus.CONNECTED -> "CONNECTED" to BinVerseGreen
        ConnectionStatus.DISCONNECTED -> "DISCONNECTED" to BinVerseRed
        ConnectionStatus.CONNECTING -> "CONNECTING…" to BinVerseAmber
        ConnectionStatus.UNKNOWN -> "UNKNOWN" to Color.Gray
    }
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontSize = 12.sp, color = Color.Gray)
            Text(text, color = color, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Color.Gray, fontSize = 13.sp)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun HistoryRow(result: DetectionResult) {
    val actionColor = when (result.action) {
        RobotAction.PICKUP -> BinVerseGreen
        RobotAction.WAIT -> BinVerseAmber
        RobotAction.IGNORE -> Color.Gray
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(result.formattedTime(), fontSize = 12.sp, color = Color.Gray)
        Text(result.objectType.name.uppercase(), fontSize = 12.sp, modifier = Modifier.weight(1f).padding(start = 8.dp))
        Text("${result.confidencePercent()}%", fontSize = 12.sp)
        Text(
            result.action.name,
            fontSize = 12.sp,
            color = actionColor,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

@Composable
private fun SessionCapBanner(cap: Int, onDismiss: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = BinVerseAmber.copy(alpha = 0.15f))) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("SESSION LIMIT REACHED", color = BinVerseAmber, fontWeight = FontWeight.Bold)
                Text(
                    "Sent $cap frames this session — auto-detect paused. Tap START AUTO-DETECT to run another batch.",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    }
}

@Composable
private fun LimitReachedBanner(onReset: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = BinVerseRed.copy(alpha = 0.15f))) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("API LIMIT REACHED", color = BinVerseRed, fontWeight = FontWeight.Bold)
                Text("Automatic detection paused.", fontSize = 12.sp, color = Color.Gray)
            }
            TextButton(onClick = onReset) { Text("RESET") }
        }
    }
}

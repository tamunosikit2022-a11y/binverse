package com.binverse.vision

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.binverse.vision.camera.BinVerseCameraManager
import com.binverse.vision.ui.MainScreen
import com.binverse.vision.ui.MainViewModel
import com.binverse.vision.ui.SettingsScreen
import com.binverse.vision.ui.theme.BinVerseVisionTheme

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: MainViewModel
    private var cameraManager: BinVerseCameraManager? = null

    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> cameraPermissionGranted.value = granted }

    private val cameraPermissionGranted = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = application as BinVerseApp
        viewModel = MainViewModel(
            settingsManager = app.settingsManager,
            detectionController = app.detectionController,
            detectionHistory = app.detectionHistory,
            esp32Service = com.binverse.vision.network.Esp32Service()
        )

        cameraPermissionGranted.value = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        setContent {
            BinVerseVisionTheme {
                var screen by remember { mutableStateOf(Screen.Main) }
                var cameraError by remember { mutableStateOf<String?>(null) }

                Surface {
                    if (!cameraPermissionGranted.value) {
                        PermissionRequestScreen(
                            onRequest = { requestCameraPermission.launch(Manifest.permission.CAMERA) }
                        )
                    } else {
                        val manager = remember {
                            BinVerseCameraManager(this@MainActivity, this@MainActivity).also {
                                cameraManager = it
                                viewModel.attachCameraManager(it)
                            }
                        }

                        when (screen) {
                            Screen.Main -> MainScreen(
                                viewModel = viewModel,
                                cameraManager = manager,
                                onOpenSettings = { screen = Screen.Settings },
                                onCameraError = { err -> cameraError = err }
                            )
                            Screen.Settings -> SettingsScreen(
                                viewModel = viewModel,
                                onBack = { screen = Screen.Main }
                            )
                        }

                        cameraError?.let { err ->
                            AlertDialog(
                                onDismissRequest = { cameraError = null },
                                confirmButton = {
                                    TextButton(onClick = { cameraError = null }) { Text("OK") }
                                },
                                title = { Text("Camera unavailable") },
                                text = { Text(err) }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraManager?.stop()
    }
}

private enum class Screen { Main, Settings }

@Composable
private fun PermissionRequestScreen(onRequest: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("BinVerse Vision needs camera access to see waste objects.")
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRequest) { Text("Grant Camera Permission") }
    }
}

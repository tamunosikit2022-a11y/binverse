package com.binverse.vision.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.binverse.vision.camera.BinVerseCameraManager
import com.binverse.vision.detection.DetectionController
import com.binverse.vision.detection.DetectionHistory
import com.binverse.vision.detection.PipelineState
import com.binverse.vision.model.DetectionResult
import com.binverse.vision.network.Esp32Result
import com.binverse.vision.network.Esp32Service
import com.binverse.vision.settings.AppSettings
import com.binverse.vision.settings.SettingsManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(
    private val settingsManager: SettingsManager,
    private val detectionController: DetectionController,
    private val detectionHistory: DetectionHistory,
    private val esp32Service: Esp32Service
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsManager.settings
    val pipelineState: StateFlow<PipelineState> = detectionController.state
    val history: StateFlow<List<DetectionResult>> = detectionHistory.records

    private val _autoDetectRunning = MutableStateFlow(false)
    val autoDetectRunning: StateFlow<Boolean> = _autoDetectRunning.asStateFlow()

    // Frames sent to Groq since auto-detect was last (re)started. Reset to
    // zero every time the user taps START AUTO-DETECT. Once it reaches
    // settings.sessionFrameCap, auto-detect turns itself back off — this is
    // a hard cap on request volume per session, independent of capture
    // interval, so it protects against Groq rate limits even if the
    // interval is set aggressively low.
    private val _sessionFramesSent = MutableStateFlow(0)
    val sessionFramesSent: StateFlow<Int> = _sessionFramesSent.asStateFlow()

    private val _sessionCapJustReached = MutableStateFlow(false)
    val sessionCapJustReached: StateFlow<Boolean> = _sessionCapJustReached.asStateFlow()

    var cameraManager: BinVerseCameraManager? = null

    fun attachCameraManager(manager: BinVerseCameraManager) {
        cameraManager = manager
        manager.setCaptureIntervalMs(settings.value.captureIntervalMs)
        manager.setFrameDiffFilterEnabled(settings.value.frameDiffFilterEnabled)
        manager.onFrameCaptured = { jpeg -> onFrameCaptured(jpeg) }
    }

    private fun onFrameCaptured(jpeg: ByteArray) {
        // Manual "ANALYZE NOW" frames always go through and are never
        // counted against the auto-detect session cap.
        val isAutoFrame = _autoDetectRunning.value

        if (isAutoFrame) {
            val cap = settingsManager.current().sessionFrameCap
            if (_sessionFramesSent.value >= cap) {
                // Cap already hit — stop capturing further frames this session.
                setAutoDetectRunning(false)
                _sessionCapJustReached.value = true
                return
            }
            _sessionFramesSent.value += 1
            if (_sessionFramesSent.value >= cap) {
                // This was the last frame allowed this session — send it,
                // then pause auto-detect so no further frames go out.
                setAutoDetectRunning(false)
                _sessionCapJustReached.value = true
            }
        }

        viewModelScope.launch {
            detectionController.processFrame(jpeg, settingsManager.current())
        }
    }

    fun setAutoDetectRunning(running: Boolean) {
        if (running) {
            _sessionFramesSent.value = 0
            _sessionCapJustReached.value = false
        }
        _autoDetectRunning.value = running
        cameraManager?.setAutoCaptureEnabled(running)
    }

    fun dismissSessionCapNotice() {
        _sessionCapJustReached.value = false
    }

    fun analyzeNow() {
        cameraManager?.requestImmediateAnalysis()
    }

    fun testEsp32() {
        viewModelScope.launch {
            detectionController.testEsp32(settingsManager.current())
        }
    }

    fun resetRequestCounter() = detectionController.resetRequestCounter()

    fun updateApiKey(key: String) = settingsManager.updateApiKey(key)

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        settingsManager.update(transform)
        val updated = settingsManager.current()
        cameraManager?.setCaptureIntervalMs(updated.captureIntervalMs)
        cameraManager?.setFrameDiffFilterEnabled(updated.frameDiffFilterEnabled)
    }

    suspend fun testEsp32Connection(): Esp32Result {
        return esp32Service.sendTestCommand(settings.value.esp32Ip, settings.value.esp32Port)
    }
}

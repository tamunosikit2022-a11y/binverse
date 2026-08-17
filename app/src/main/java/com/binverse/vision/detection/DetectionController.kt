package com.binverse.vision.detection

import com.binverse.vision.model.DetectionResult
import com.binverse.vision.model.RobotAction
import com.binverse.vision.network.DetectionParser
import com.binverse.vision.network.Esp32Result
import com.binverse.vision.network.Esp32Service
import com.binverse.vision.network.GroqErrorReason
import com.binverse.vision.network.GroqResult
import com.binverse.vision.network.GroqVisionService
import com.binverse.vision.settings.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ConnectionStatus { UNKNOWN, CONNECTED, DISCONNECTED, CONNECTING }

data class PipelineState(
    val lastResult: DetectionResult? = null,
    val groqStatus: ConnectionStatus = ConnectionStatus.UNKNOWN,
    val esp32Status: ConnectionStatus = ConnectionStatus.UNKNOWN,
    val lastError: String? = null,
    val requestsThisSession: Int = 0,
    val limitReached: Boolean = false,
    val lastEsp32TransmissionMillis: Long? = null,
    val lastImageSizeBytes: Int? = null,
    val isBusy: Boolean = false
)

/**
 * The safety-critical orchestrator: Camera frame -> Groq -> parse/validate
 * -> confidence threshold -> duplicate/cooldown debounce -> ESP32.
 *
 * Any failure at any stage results in action = WAIT and NOTHING is ever
 * sent to the ESP32 as PICKUP. The ESP32/Arduino remains the final
 * authority over the physical mechanism regardless of what this class does.
 */
class DetectionController(
    private val groqService: GroqVisionService,
    private val esp32Service: Esp32Service,
    private val history: DetectionHistory
) {
    private val _state = MutableStateFlow(PipelineState())
    val state: StateFlow<PipelineState> = _state.asStateFlow()

    // Debounce state: same object type within cooldown window is treated as one detection.
    private var lastSentObjectType: String? = null
    private var lastSentAtMillis: Long = 0L

    suspend fun processFrame(jpegBytes: ByteArray, settings: AppSettings) {
        if (settings.maxRequestsEnabled && _state.value.requestsThisSession >= settings.maxRequestsPerSession) {
            _state.value = _state.value.copy(limitReached = true)
            return
        }

        _state.value = _state.value.copy(
            isBusy = true,
            groqStatus = ConnectionStatus.CONNECTING,
            lastImageSizeBytes = jpegBytes.size
        )

        val groqResult = groqService.classifyImage(
            apiKey = settings.groqApiKey,
            model = settings.groqModel,
            jpegBytes = jpegBytes
        )

        _state.value = _state.value.copy(requestsThisSession = _state.value.requestsThisSession + 1)

        val safeResult: DetectionResult
        when (groqResult) {
            is GroqResult.Success -> {
                val parsed = DetectionParser.parse(groqResult.rawJsonText)
                if (parsed == null) {
                    safeResult = DetectionResult.safeFallback()
                    _state.value = _state.value.copy(
                        groqStatus = ConnectionStatus.CONNECTED, // API reached us fine, payload was just invalid
                        lastError = "Invalid Groq response — could not parse structured JSON. Action forced to WAIT."
                    )
                } else {
                    safeResult = DetectionParser.enforceConfidenceThreshold(parsed, settings.confidenceThreshold)
                    _state.value = _state.value.copy(groqStatus = ConnectionStatus.CONNECTED, lastError = null)
                }
            }
            is GroqResult.Error -> {
                safeResult = DetectionResult.safeFallback()
                _state.value = _state.value.copy(
                    groqStatus = ConnectionStatus.DISCONNECTED,
                    lastError = describeGroqError(groqResult)
                )
            }
        }

        history.add(safeResult)
        _state.value = _state.value.copy(lastResult = safeResult, isBusy = false)

        maybeSendToEsp32(safeResult, settings)
    }

    private suspend fun maybeSendToEsp32(result: DetectionResult, settings: AppSettings) {
        val now = result.timestampMillis

        if (result.action == RobotAction.PICKUP) {
            val sameObjectRecently =
                lastSentObjectType == result.objectType.name &&
                    (now - lastSentAtMillis) < settings.commandCooldownMs
            if (sameObjectRecently) {
                return // duplicate-command protection: skip re-sending PICKUP for the same object
            }
        }

        _state.value = _state.value.copy(esp32Status = ConnectionStatus.CONNECTING)
        when (val esp32Result = esp32Service.sendCommand(settings.esp32Ip, settings.esp32Port, result)) {
            is Esp32Result.Success -> {
                _state.value = _state.value.copy(
                    esp32Status = ConnectionStatus.CONNECTED,
                    lastEsp32TransmissionMillis = now,
                    lastError = null
                )
                if (result.action == RobotAction.PICKUP) {
                    lastSentObjectType = result.objectType.name
                    lastSentAtMillis = now
                }
            }
            is Esp32Result.Error -> {
                _state.value = _state.value.copy(
                    esp32Status = ConnectionStatus.DISCONNECTED,
                    lastError = esp32Result.message
                )
            }
        }
    }

    suspend fun testEsp32(settings: AppSettings): Esp32Result {
        _state.value = _state.value.copy(esp32Status = ConnectionStatus.CONNECTING)
        val result = esp32Service.sendTestCommand(settings.esp32Ip, settings.esp32Port)
        _state.value = when (result) {
            is Esp32Result.Success -> _state.value.copy(
                esp32Status = ConnectionStatus.CONNECTED,
                lastEsp32TransmissionMillis = System.currentTimeMillis(),
                lastError = null
            )
            is Esp32Result.Error -> _state.value.copy(
                esp32Status = ConnectionStatus.DISCONNECTED,
                lastError = result.message
            )
        }
        return result
    }

    fun resetRequestCounter() {
        _state.value = _state.value.copy(requestsThisSession = 0, limitReached = false)
    }

    private fun describeGroqError(error: GroqResult.Error): String = when (error.reason) {
        GroqErrorReason.NO_INTERNET -> "No internet connection reachable — action forced to WAIT."
        GroqErrorReason.TIMEOUT -> "Groq API request timed out — action forced to WAIT."
        GroqErrorReason.AUTH_FAILURE -> "Groq authentication failed — check your API key in Settings."
        GroqErrorReason.RATE_LIMIT -> "Groq API rate limit reached — action forced to WAIT."
        GroqErrorReason.INVALID_RESPONSE -> "Groq returned an unreadable response — action forced to WAIT."
        GroqErrorReason.UNKNOWN -> "Groq error: ${error.message}"
    }
}

package com.binverse.vision.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.binverse.vision.AppConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds every user-editable setting from the Settings screen.
 *
 * The Groq API key is kept ENTIRELY separate from ordinary preferences:
 * it lives in its own EncryptedSharedPreferences file backed by the
 * Android Keystore, so it is encrypted at rest on the device. Ordinary
 * settings (ESP32 IP, thresholds, intervals) are non-secret and use
 * plain SharedPreferences.
 *
 * This is a prototype-grade key storage approach. See the README section
 * "Production API key security" for why the key should ultimately move
 * behind a backend proxy instead of living on the device at all.
 */
data class AppSettings(
    val groqApiKey: String = "",
    val groqModel: String = AppConfig.GROQ_MODEL,
    val esp32Ip: String = AppConfig.DEFAULT_ESP32_IP,
    val esp32Port: Int = AppConfig.DEFAULT_ESP32_PORT,
    val captureIntervalMs: Long = AppConfig.DEFAULT_CAPTURE_INTERVAL_MS,
    val confidenceThreshold: Double = AppConfig.DEFAULT_CONFIDENCE_THRESHOLD,
    val commandCooldownMs: Long = AppConfig.DEFAULT_COMMAND_COOLDOWN_MS,
    val sessionFrameCap: Int = AppConfig.DEFAULT_SESSION_FRAME_CAP,
    val frameDiffFilterEnabled: Boolean = AppConfig.DEFAULT_FRAME_DIFF_ENABLED,
    val maxRequestsEnabled: Boolean = AppConfig.DEFAULT_MAX_REQUESTS_ENABLED,
    val maxRequestsPerSession: Int = AppConfig.DEFAULT_MAX_REQUESTS,
    val debugShowImageSize: Boolean = false
)

class SettingsManager(context: Context) {

    private val appContext = context.applicationContext

    // --- Encrypted store: API key only ---------------------------------
    private val masterKey = MasterKey.Builder(appContext)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val securePrefs: SharedPreferences = EncryptedSharedPreferences.create(
        appContext,
        "binverse_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    // --- Plain store: everything else -----------------------------------
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("binverse_prefs", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadAll())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun loadAll(): AppSettings = AppSettings(
        groqApiKey = securePrefs.getString(KEY_GROQ_API_KEY, "") ?: "",
        groqModel = prefs.getString(KEY_GROQ_MODEL, AppConfig.GROQ_MODEL) ?: AppConfig.GROQ_MODEL,
        esp32Ip = prefs.getString(KEY_ESP32_IP, AppConfig.DEFAULT_ESP32_IP) ?: AppConfig.DEFAULT_ESP32_IP,
        esp32Port = prefs.getInt(KEY_ESP32_PORT, AppConfig.DEFAULT_ESP32_PORT),
        captureIntervalMs = prefs.getLong(KEY_CAPTURE_INTERVAL, AppConfig.DEFAULT_CAPTURE_INTERVAL_MS),
        confidenceThreshold = prefs.getFloat(KEY_CONFIDENCE, AppConfig.DEFAULT_CONFIDENCE_THRESHOLD.toFloat()).toDouble(),
        commandCooldownMs = prefs.getLong(KEY_COOLDOWN, AppConfig.DEFAULT_COMMAND_COOLDOWN_MS),
        sessionFrameCap = prefs.getInt(KEY_SESSION_CAP, AppConfig.DEFAULT_SESSION_FRAME_CAP),
        frameDiffFilterEnabled = prefs.getBoolean(KEY_FRAME_DIFF, AppConfig.DEFAULT_FRAME_DIFF_ENABLED),
        maxRequestsEnabled = prefs.getBoolean(KEY_MAX_REQ_ENABLED, AppConfig.DEFAULT_MAX_REQUESTS_ENABLED),
        maxRequestsPerSession = prefs.getInt(KEY_MAX_REQ, AppConfig.DEFAULT_MAX_REQUESTS),
        debugShowImageSize = prefs.getBoolean(KEY_DEBUG_SIZE, false)
    )

    fun updateApiKey(key: String) {
        securePrefs.edit().putString(KEY_GROQ_API_KEY, key).apply()
        _settings.value = _settings.value.copy(groqApiKey = key)
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        val updated = transform(_settings.value)
        prefs.edit()
            .putString(KEY_GROQ_MODEL, updated.groqModel)
            .putString(KEY_ESP32_IP, updated.esp32Ip)
            .putInt(KEY_ESP32_PORT, updated.esp32Port)
            .putLong(KEY_CAPTURE_INTERVAL, updated.captureIntervalMs)
            .putFloat(KEY_CONFIDENCE, updated.confidenceThreshold.toFloat())
            .putLong(KEY_COOLDOWN, updated.commandCooldownMs)
            .putInt(KEY_SESSION_CAP, updated.sessionFrameCap)
            .putBoolean(KEY_FRAME_DIFF, updated.frameDiffFilterEnabled)
            .putBoolean(KEY_MAX_REQ_ENABLED, updated.maxRequestsEnabled)
            .putInt(KEY_MAX_REQ, updated.maxRequestsPerSession)
            .putBoolean(KEY_DEBUG_SIZE, updated.debugShowImageSize)
            .apply()
        // API key is intentionally not touched here — only updateApiKey() writes it.
        _settings.value = updated.copy(groqApiKey = _settings.value.groqApiKey)
    }

    fun current(): AppSettings = _settings.value

    companion object {
        private const val KEY_GROQ_API_KEY = "groq_api_key"
        private const val KEY_GROQ_MODEL = "groq_model"
        private const val KEY_ESP32_IP = "esp32_ip"
        private const val KEY_ESP32_PORT = "esp32_port"
        private const val KEY_CAPTURE_INTERVAL = "capture_interval_ms"
        private const val KEY_CONFIDENCE = "confidence_threshold"
        private const val KEY_COOLDOWN = "command_cooldown_ms"
        private const val KEY_SESSION_CAP = "session_frame_cap"
        private const val KEY_FRAME_DIFF = "frame_diff_enabled"
        private const val KEY_MAX_REQ_ENABLED = "max_requests_enabled"
        private const val KEY_MAX_REQ = "max_requests"
        private const val KEY_DEBUG_SIZE = "debug_show_image_size"
    }
}

package com.binverse.vision.detection

import com.binverse.vision.AppConfig
import com.binverse.vision.model.DetectionResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bounded history of past detections for the UI panel. Images are never
 * stored here — only the structured result — unless explicitly extended
 * later behind a user-facing toggle.
 */
class DetectionHistory {
    private val _records = MutableStateFlow<List<DetectionResult>>(emptyList())
    val records: StateFlow<List<DetectionResult>> = _records.asStateFlow()

    fun add(result: DetectionResult) {
        val updated = (listOf(result) + _records.value).take(AppConfig.MAX_HISTORY_RECORDS)
        _records.value = updated
    }

    fun clear() {
        _records.value = emptyList()
    }
}

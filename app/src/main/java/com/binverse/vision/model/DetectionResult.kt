package com.binverse.vision.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The only waste categories the AI is allowed to return. */
enum class WasteType {
    plastic, metal, paper, cardboard, glass, organic, other, unknown;

    companion object {
        fun fromStringOrNull(value: String?): WasteType? =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}

/** The only robot actions the AI is allowed to return. */
enum class RobotAction {
    PICKUP, WAIT, IGNORE;

    companion object {
        fun fromStringOrNull(value: String?): RobotAction? =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}

/**
 * A single, validated classification result — either from Groq or a
 * locally-generated safe fallback (e.g. on error).
 */
data class DetectionResult(
    val detected: Boolean,
    val objectType: WasteType,
    val confidence: Double, // 0.0..1.0
    val action: RobotAction,
    val timestampMillis: Long = System.currentTimeMillis(),
    val rawSource: String = "groq" // "groq", "fallback", "test"
) {
    fun formattedTime(): String =
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(timestampMillis))

    fun confidencePercent(): Int = (confidence * 100).toInt()

    /** Safe-by-default fallback used whenever anything goes wrong. */
    companion object {
        fun safeFallback(source: String = "fallback"): DetectionResult = DetectionResult(
            detected = false,
            objectType = WasteType.unknown,
            confidence = 0.0,
            action = RobotAction.WAIT,
            rawSource = source
        )

        fun manualTest(): DetectionResult = DetectionResult(
            detected = true,
            objectType = WasteType.plastic,
            confidence = 1.0,
            action = RobotAction.PICKUP,
            rawSource = "test"
        )
    }
}

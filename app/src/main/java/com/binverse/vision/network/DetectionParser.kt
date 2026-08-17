package com.binverse.vision.network

import com.binverse.vision.model.DetectionResult
import com.binverse.vision.model.RobotAction
import com.binverse.vision.model.WasteType
import org.json.JSONObject

/**
 * Turns the raw text content returned by Groq into a validated
 * DetectionResult. Never trusts the model blindly:
 *  - object_type must be one of the 8 allowed categories, else -> unknown
 *  - action must be one of PICKUP/WAIT/IGNORE, else -> WAIT
 *  - confidence is clamped to [0, 1]
 *  - PICKUP is downgraded to WAIT if confidence is missing/unparseable
 *  - any parse failure returns null so the caller can apply the safe fallback
 */
object DetectionParser {

    fun parse(rawContent: String): DetectionResult? {
        val jsonText = extractJsonObject(rawContent) ?: return null
        return try {
            val json = JSONObject(jsonText)

            val detected = json.optBoolean("detected", false)

            val objectType = WasteType.fromStringOrNull(json.optString("object_type", "unknown"))
                ?: WasteType.unknown

            var confidence = json.optDouble("confidence", 0.0)
            if (confidence.isNaN()) confidence = 0.0
            confidence = confidence.coerceIn(0.0, 1.0)

            var action = RobotAction.fromStringOrNull(json.optString("action", "WAIT"))
                ?: RobotAction.WAIT

            // Defense in depth: even if the model claims PICKUP, an
            // inconsistent payload (e.g. detected=false, or object_type
            // unknown) is never allowed to reach the robot as PICKUP.
            if (action == RobotAction.PICKUP && (!detected || objectType == WasteType.unknown)) {
                action = RobotAction.WAIT
            }

            DetectionResult(
                detected = detected,
                objectType = objectType,
                confidence = confidence,
                action = action,
                rawSource = "groq"
            )
        } catch (e: Exception) {
            null
        }
    }

    /** Groq's JSON-mode responses are normally a bare object, but strip any stray markdown fencing defensively. */
    private fun extractJsonObject(text: String): String? {
        val trimmed = text.trim()
        val fenced = Regex("```(?:json)?\\s*([\\s\\S]*?)```").find(trimmed)?.groupValues?.get(1)?.trim()
        val candidate = fenced ?: trimmed
        val start = candidate.indexOf('{')
        val end = candidate.lastIndexOf('}')
        if (start == -1 || end == -1 || end < start) return null
        return candidate.substring(start, end + 1)
    }

    /**
     * Applies the app-level confidence threshold. Groq may itself return
     * PICKUP, but the app is the final authority: below-threshold
     * confidence is always forced to WAIT before anything reaches ESP32.
     */
    fun enforceConfidenceThreshold(result: DetectionResult, threshold: Double): DetectionResult {
        if (result.action == RobotAction.PICKUP && result.confidence < threshold) {
            return result.copy(action = RobotAction.WAIT)
        }
        return result
    }
}

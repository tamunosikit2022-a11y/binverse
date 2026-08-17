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

    /**
     * Extracts the first complete, balanced JSON object from Groq's raw
     * response text.
     *
     * This does proper brace-depth counting (correctly ignoring braces that
     * appear inside quoted strings) rather than naively taking the text
     * between the first '{' and the last '}'. That naive approach breaks
     * as soon as the model wraps the JSON in any surrounding sentence
     * (e.g. "Here's the result: {...} let me know if you need more!") --
     * which became common once strict JSON mode was removed from the Groq
     * request (see GroqVisionService) to work around a separate Groq 400
     * error. Any stray brace in the surrounding prose would otherwise
     * corrupt the extracted span and fail to parse.
     */
    private fun extractJsonObject(text: String): String? {
        val trimmed = text.trim()
        val fenced = Regex("```(?:json)?\\s*([\\s\\S]*?)```").find(trimmed)?.groupValues?.get(1)?.trim()
        val candidate = fenced ?: trimmed

        val start = candidate.indexOf('{')
        if (start == -1) return null

        var depth = 0
        var inString = false
        var escapeNext = false

        for (i in start until candidate.length) {
            val c = candidate[i]

            if (escapeNext) {
                escapeNext = false
                continue
            }

            when {
                inString -> when (c) {
                    '\\' -> escapeNext = true
                    '"' -> inString = false
                }
                else -> when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            return candidate.substring(start, i + 1)
                        }
                    }
                }
            }
        }

        // Unbalanced braces -- the model's output was truncated or malformed.
        return null
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

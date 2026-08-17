package com.binverse.vision

/**
 * Single place to change the Groq model or any other cross-cutting default.
 * Per-install overrides (capture interval, confidence threshold, ESP32
 * address, etc.) live in SettingsManager and are user-editable at runtime;
 * these are just the shipped defaults.
 */
object AppConfig {

    // Groq -------------------------------------------------------------
    const val GROQ_BASE_URL = "https://api.groq.com/openai/v1"
    const val GROQ_CHAT_COMPLETIONS_PATH = "/chat/completions"

    /**
     * Change the active vision model here. qwen/qwen3.6-27b is Groq's
     * current vision-capable model (image input + JSON mode) as of
     * this build; verify against https://console.groq.com/docs/models
     * before shipping, since Groq deprecates/rotates models.
     */
    const val GROQ_MODEL = "qwen/qwen3.6-27b"

    const val GROQ_REQUEST_TIMEOUT_SECONDS = 20L

    // Capture ------------------------------------------------------------
    const val DEFAULT_CAPTURE_INTERVAL_MS = 1500L
    const val MIN_CAPTURE_INTERVAL_MS = 500L
    const val MAX_CAPTURE_INTERVAL_MS = 10_000L

    // Image optimization ---------------------------------------------------
    const val UPLOAD_MAX_DIMENSION_PX = 768 // longest side, resized before upload
    const val UPLOAD_JPEG_QUALITY = 70

    // Classification -------------------------------------------------------
    const val DEFAULT_CONFIDENCE_THRESHOLD = 0.80
    const val DEFAULT_COMMAND_COOLDOWN_MS = 3000L

    // Session frame cap ---------------------------------------------------
    // Each time auto-detect is started, only this many frames are sent to
    // Groq before auto-detect automatically pauses itself. This bounds
    // request volume per "sorting session" independent of capture interval,
    // which is a much tighter guard against Groq rate limits (HTTP 429)
    // than interval tuning alone. Manual "ANALYZE NOW" is not counted
    // against this cap. Tap "START AUTO-DETECT" again to run another batch.
    const val DEFAULT_SESSION_FRAME_CAP = 3

    // Frame pre-filter -------------------------------------------------------
    const val DEFAULT_FRAME_DIFF_ENABLED = false
    // Fraction of sampled pixels that must change (0..1) before a frame
    // is considered "different enough" to bother sending to Groq.
    const val FRAME_DIFF_THRESHOLD = 0.04

    // API cost control -------------------------------------------------------
    const val DEFAULT_MAX_REQUESTS_ENABLED = false
    const val DEFAULT_MAX_REQUESTS = 100

    // ESP32 -------------------------------------------------------------
    const val DEFAULT_ESP32_IP = "192.168.4.1"
    const val DEFAULT_ESP32_PORT = 80
    const val ESP32_COMMAND_PATH = "/command"
    const val ESP32_TIMEOUT_SECONDS = 4L
    const val ESP32_MAX_RETRIES = 2

    // History -------------------------------------------------------------
    const val MAX_HISTORY_RECORDS = 50

    const val SYSTEM_PROMPT = """
You are the vision system for BinVerse, an autonomous waste-collection robot.
Analyze the supplied camera image.
Determine whether a waste object is visible.
If waste is visible, classify it into exactly one of these categories only:
plastic, metal, paper, cardboard, glass, organic, other, unknown.
Do not invent categories.
Estimate your confidence between 0 and 1.
If you cannot reliably identify the waste, return unknown and action WAIT.
Respond ONLY with a single JSON object matching this exact schema, and
nothing else — no markdown, no commentary:
{"detected": boolean, "object_type": "plastic|metal|paper|cardboard|glass|organic|other|unknown", "confidence": number, "action": "PICKUP|WAIT|IGNORE"}
Rules:
- If no suitable waste object is visible, return detected=false, object_type="unknown", confidence=0, action="WAIT".
- Never return action "PICKUP" unless you are highly confident.
- Prioritize reliable classification over guessing.
"""
}

package com.binverse.vision.network

import android.util.Base64
import com.binverse.vision.AppConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

sealed class GroqResult {
    data class Success(val rawJsonText: String) : GroqResult()
    data class Error(val reason: GroqErrorReason, val message: String) : GroqResult()
}

enum class GroqErrorReason {
    NO_INTERNET, TIMEOUT, AUTH_FAILURE, RATE_LIMIT, INVALID_RESPONSE, UNKNOWN
}

/**
 * Talks to Groq's OpenAI-compatible /chat/completions endpoint using the
 * official multimodal image_url content-block format. No custom or
 * invented parameters are sent.
 */
class GroqVisionService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(AppConfig.GROQ_REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(AppConfig.GROQ_REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(AppConfig.GROQ_REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    suspend fun classifyImage(
        apiKey: String,
        model: String,
        jpegBytes: ByteArray
    ): GroqResult {
        if (apiKey.isBlank()) {
            return GroqResult.Error(GroqErrorReason.AUTH_FAILURE, "No Groq API key configured")
        }

        val base64Image = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
        val dataUrl = "data:image/jpeg;base64,$base64Image"

        val userContent = JSONArray().apply {
            put(JSONObject().apply {
                put("type", "text")
                put("text", "Classify the waste object in this image and return only the required JSON.")
            })
            put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().apply { put("url", dataUrl) })
            })
        }

        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", AppConfig.SYSTEM_PROMPT)
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", userContent)
            })
        }

        val body = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("temperature", 0.2)
            put("max_tokens", 400)
            // qwen/qwen3.6-27b is a hybrid thinking/non-thinking model and
            // reasons by default. Left unset, it burns a large chunk (often
            // all) of max_tokens on internal reasoning before ever emitting
            // the JSON answer, which was the actual cause of both the
            // "invalid response / cannot parse JSON" errors (truncated
            // output) AND premature Groq rate-limit hits (reasoning tokens
            // count against the per-minute token budget same as output).
            // This is a single-label classification task -- it needs none
            // of that, so thinking mode is explicitly disabled. Groq only
            // accepts "default" or "none" for this model's reasoning_effort.
            put("reasoning_effort", "none")
            // Belt-and-braces: if a future default model still emits any
            // reasoning content despite reasoning_effort, keep it out of
            // the message content entirely rather than relying solely on
            // DetectionParser to strip it out.
            put("reasoning_format", "hidden")
            // NOTE: Groq's strict JSON-mode "response_format": {"type":"json_object"}
            // is intentionally NOT sent here. qwen/qwen3.6-27b was observed
            // returning HTTP 400 "json_validate_failed" with an empty
            // failed_generation when this flag was set — i.e. Groq's own
            // validator rejected the model's output before it ever reached
            // the app. Instead we rely on the SYSTEM_PROMPT's explicit
            // "respond ONLY with a single JSON object" instruction, plus
            // DetectionParser's own robust extraction/validation of
            // whatever text comes back (including stray markdown fencing).
            // If a future/alternate model supports structured outputs
            // reliably, this is the place to re-add it.
        }

        val request = Request.Builder()
            .url(AppConfig.GROQ_BASE_URL + AppConfig.GROQ_CHAT_COMPLETIONS_PATH)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            executeAsync(request)
        } catch (e: java.net.SocketTimeoutException) {
            GroqResult.Error(GroqErrorReason.TIMEOUT, "Groq request timed out")
        } catch (e: java.io.IOException) {
            GroqResult.Error(GroqErrorReason.NO_INTERNET, e.message ?: "Network error")
        } catch (e: Exception) {
            GroqResult.Error(GroqErrorReason.UNKNOWN, e.message ?: "Unknown error")
        }
    }

    private suspend fun executeAsync(request: Request): GroqResult = suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                response.use { resp ->
                    val text = resp.body?.string().orEmpty()
                    when (resp.code) {
                        200 -> {
                            val content = extractMessageContent(text)
                            if (content == null) {
                                cont.resumeWith(Result.success(
                                    GroqResult.Error(GroqErrorReason.INVALID_RESPONSE, "Could not parse Groq response body")
                                ))
                            } else {
                                cont.resumeWith(Result.success(GroqResult.Success(content)))
                            }
                        }
                        401, 403 -> cont.resumeWith(Result.success(
                            GroqResult.Error(GroqErrorReason.AUTH_FAILURE, "Groq authentication failed (HTTP ${resp.code})")
                        ))
                        429 -> {
                            val retryAfter = resp.header("retry-after")
                            val suffix = retryAfter?.let { " — retry after ${it}s" } ?: ""
                            cont.resumeWith(Result.success(
                                GroqResult.Error(GroqErrorReason.RATE_LIMIT, "Groq rate limit reached (HTTP 429)$suffix")
                            ))
                        }
                        else -> cont.resumeWith(Result.success(
                            GroqResult.Error(GroqErrorReason.UNKNOWN, "Groq error HTTP ${resp.code}: ${text.take(200)}")
                        ))
                    }
                }
            }
        })
    }

    private fun extractMessageContent(rawBody: String): String? = try {
        val json = JSONObject(rawBody)
        val choices = json.optJSONArray("choices")
        val first = choices?.optJSONObject(0)
        val message = first?.optJSONObject("message")
        message?.optString("content")
    } catch (e: Exception) {
        null
    }
}

package com.binverse.vision.network

import com.binverse.vision.AppConfig
import com.binverse.vision.model.DetectionResult
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

sealed class Esp32Result {
    data class Success(val statusText: String) : Esp32Result()
    data class Error(val message: String) : Esp32Result()
}

/** Talks to the ESP32's simple POST /command HTTP endpoint. */
class Esp32Service {

    private var client = buildClient()

    private fun buildClient() = OkHttpClient.Builder()
        .connectTimeout(AppConfig.ESP32_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(AppConfig.ESP32_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(AppConfig.ESP32_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private fun urlFor(ip: String, port: Int): String = "http://$ip:$port${AppConfig.ESP32_COMMAND_PATH}"

    suspend fun sendCommand(ip: String, port: Int, result: DetectionResult): Esp32Result {
        val payload = JSONObject().apply {
            put("object_type", result.objectType.name)
            put("confidence", result.confidence)
            put("action", result.action.name)
            put("timestamp", result.timestampMillis)
        }
        return sendWithRetries(ip, port, payload)
    }

    /** [ TEST ESP32 ] — sends a fixed test payload without calling Groq at all. */
    suspend fun sendTestCommand(ip: String, port: Int): Esp32Result {
        val payload = JSONObject().apply {
            put("object_type", "plastic")
            put("confidence", 1.0)
            put("action", "TEST")
            put("timestamp", System.currentTimeMillis())
        }
        return sendWithRetries(ip, port, payload)
    }

    private suspend fun sendWithRetries(ip: String, port: Int, payload: JSONObject): Esp32Result {
        var lastError: Esp32Result.Error? = null
        var attempt = 0
        while (attempt <= AppConfig.ESP32_MAX_RETRIES) {
            when (val outcome = sendOnce(ip, port, payload)) {
                is Esp32Result.Success -> return outcome
                is Esp32Result.Error -> lastError = outcome
            }
            attempt++
        }
        return lastError ?: Esp32Result.Error("Unknown ESP32 error")
    }

    private suspend fun sendOnce(ip: String, port: Int, payload: JSONObject): Esp32Result {
        val request = Request.Builder()
            .url(urlFor(ip, port))
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            executeAsync(request)
        } catch (e: java.net.SocketTimeoutException) {
            Esp32Result.Error("ESP32 timed out (no response within ${AppConfig.ESP32_TIMEOUT_SECONDS}s)")
        } catch (e: java.net.ConnectException) {
            Esp32Result.Error("Could not connect to ESP32 at $ip:$port")
        } catch (e: IOException) {
            Esp32Result.Error("ESP32 network error: ${e.message}")
        } catch (e: Exception) {
            Esp32Result.Error("ESP32 error: ${e.message}")
        }
    }

    private suspend fun executeAsync(request: Request): Esp32Result = suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                response.use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string().orEmpty()
                        val status = try {
                            JSONObject(body).optString("status", "received")
                        } catch (e: Exception) {
                            "received"
                        }
                        cont.resumeWith(Result.success(Esp32Result.Success(status)))
                    } else {
                        cont.resumeWith(Result.success(Esp32Result.Error("ESP32 returned malformed/error response (HTTP ${resp.code})")))
                    }
                }
            }
        })
    }
}

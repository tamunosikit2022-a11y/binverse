package com.binverse.vision.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import com.binverse.vision.AppConfig
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * Owns the CameraX pipeline. The preview runs continuously; frames are only
 * pulled out for AI analysis on a fixed interval (never every frame), and
 * only when analysis is not already in flight.
 */
class BinVerseCameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null

    @Volatile private var captureIntervalMs: Long = AppConfig.DEFAULT_CAPTURE_INTERVAL_MS
    @Volatile private var autoCaptureEnabled = false
    @Volatile private var frameDiffFilterEnabled = false

    private val lastCaptureTime = AtomicLong(0L)
    private val busy = AtomicBoolean(false)
    private var lastLumaSample: IntArray? = null

    /** Called with a ready-to-upload JPEG byte array, or null if a frame was skipped by the prefilter. */
    var onFrameCaptured: ((ByteArray) -> Unit)? = null
    var onFrameSkippedByPrefilter: (() -> Unit)? = null

    fun setCaptureIntervalMs(ms: Long) {
        captureIntervalMs = ms.coerceIn(AppConfig.MIN_CAPTURE_INTERVAL_MS, AppConfig.MAX_CAPTURE_INTERVAL_MS)
    }

    fun setFrameDiffFilterEnabled(enabled: Boolean) {
        frameDiffFilterEnabled = enabled
    }

    fun setAutoCaptureEnabled(enabled: Boolean) {
        autoCaptureEnabled = enabled
    }

    fun startCamera(previewView: PreviewView, onError: (String) -> Unit) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisExecutor) { proxy -> onFrame(proxy) }
                imageAnalysis = analysis

                val selector = CameraSelector.DEFAULT_BACK_CAMERA

                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
            } catch (e: Exception) {
                onError(e.message ?: "Failed to start camera")
            }
        }, androidx.core.content.ContextCompat.getMainExecutor(context))
    }

    /** Manual "ANALYZE NOW": force the very next frame through regardless of interval/prefilter. */
    @Volatile private var forceNextFrame = false
    fun requestImmediateAnalysis() {
        forceNextFrame = true
    }

    private fun onFrame(proxy: ImageProxy) {
        val now = System.currentTimeMillis()
        val due = autoCaptureEnabled && (now - lastCaptureTime.get() >= captureIntervalMs)

        if (!forceNextFrame && !due) {
            proxy.close()
            return
        }
        if (busy.get()) {
            // Previous frame still uploading/processing — never queue extra work.
            proxy.close()
            return
        }

        val forced = forceNextFrame
        forceNextFrame = false
        lastCaptureTime.set(now)
        busy.set(true)

        try {
            val bitmap = proxy.toResizedBitmap(AppConfig.UPLOAD_MAX_DIMENSION_PX)

            if (!forced && frameDiffFilterEnabled && !frameChangedEnough(bitmap)) {
                onFrameSkippedByPrefilter?.invoke()
                busy.set(false)
                return
            }

            val jpeg = bitmap.toJpegBytes(AppConfig.UPLOAD_JPEG_QUALITY)
            onFrameCaptured?.invoke(jpeg)
        } catch (e: Exception) {
            // Fail safe: swallow and let the caller's timeout/error handling take over.
        } finally {
            busy.set(false)
            proxy.close()
        }
    }

    /** Cheap luma-histogram diff over a coarse grid — good enough to detect "basically nothing changed". */
    private fun frameChangedEnough(bitmap: Bitmap): Boolean {
        val gridW = 16
        val gridH = 12
        val sample = IntArray(gridW * gridH)
        val stepX = bitmap.width / gridW
        val stepY = bitmap.height / gridH
        var idx = 0
        for (gy in 0 until gridH) {
            for (gx in 0 until gridW) {
                val px = (gx * stepX).coerceIn(0, bitmap.width - 1)
                val py = (gy * stepY).coerceIn(0, bitmap.height - 1)
                val pixel = bitmap.getPixel(px, py)
                val luma = (((pixel shr 16) and 0xFF) * 299 +
                        ((pixel shr 8) and 0xFF) * 587 +
                        (pixel and 0xFF) * 114) / 1000
                sample[idx++] = luma
            }
        }

        val previous = lastLumaSample
        lastLumaSample = sample
        if (previous == null) return true // first frame always goes through

        var changedCells = 0
        for (i in sample.indices) {
            if (abs(sample[i] - previous[i]) > 18) changedCells++
        }
        val fraction = changedCells.toDouble() / sample.size
        return fraction >= AppConfig.FRAME_DIFF_THRESHOLD
    }

    fun stop() {
        cameraProvider?.unbindAll()
        analysisExecutor.shutdown()
    }
}

private fun ImageProxy.toResizedBitmap(maxDimension: Int): Bitmap {
    val bitmap = this.toBitmapCompat()
    val largestSide = maxOf(bitmap.width, bitmap.height)
    if (largestSide <= maxDimension) return bitmap
    val scale = maxDimension.toFloat() / largestSide
    val matrix = Matrix().apply { postScale(scale, scale) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

/** Converts the analyzer's YUV ImageProxy into an RGB Bitmap, applying sensor rotation. */
private fun ImageProxy.toBitmapCompat(): Bitmap {
    val yBuffer = planes[0].buffer
    val uBuffer = planes[1].buffer
    val vBuffer = planes[2].buffer

    val ySize = yBuffer.remaining()
    val uSize = uBuffer.remaining()
    val vSize = vBuffer.remaining()

    val nv21 = ByteArray(ySize + uSize + vSize)
    yBuffer.get(nv21, 0, ySize)
    vBuffer.get(nv21, ySize, vSize)
    uBuffer.get(nv21, ySize + vSize, uSize)

    val yuvImage = android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, width, height, null)
    val out = ByteArrayOutputStream()
    yuvImage.compressToJpeg(android.graphics.Rect(0, 0, width, height), 90, out)
    val bytes = out.toByteArray()
    var bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

    val rotation = imageInfo.rotationDegrees
    if (rotation != 0) {
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
    }
    return bmp
}

private fun Bitmap.toJpegBytes(quality: Int): ByteArray {
    val out = ByteArrayOutputStream()
    compress(Bitmap.CompressFormat.JPEG, quality, out)
    return out.toByteArray()
}

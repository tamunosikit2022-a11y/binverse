package com.binverse.vision.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.binverse.vision.AppConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Owns the CameraX pipeline. The preview runs continuously; still frames are
 * pulled out for AI analysis via ImageCapture -- CameraX's native JPEG
 * capture path -- on a fixed interval (never every preview frame), and only
 * when a capture is not already in flight.
 *
 * NOTE: this intentionally uses ImageCapture rather than hand-decoding
 * ImageAnalysis's raw YUV_420_888 buffers. A naive YUV->NV21 byte copy
 * (as an earlier version of this file did) assumes a specific chroma
 * plane memory layout that most real camera sensors don't actually use
 * (interleaved U/V with pixelStride > 1), which silently produces
 * wrong-colored/corrupted JPEGs on many devices -- exactly the kind of
 * thing that degrades a vision model's classification quality without
 * throwing any error. ImageCapture avoids that entirely by returning an
 * already correctly-encoded JPEG straight from the camera stack.
 */
class BinVerseCameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {
    private val captureExecutor = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(Dispatchers.Default)

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var captureLoopJob: Job? = null

    @Volatile private var captureIntervalMs: Long = AppConfig.DEFAULT_CAPTURE_INTERVAL_MS
    @Volatile private var autoCaptureEnabled = false
    @Volatile private var frameDiffFilterEnabled = false
    @Volatile private var forceNextFrame = false

    private val busy = AtomicBoolean(false)
    private var lastLumaSample: IntArray? = null

    /** Called with a ready-to-upload JPEG byte array. */
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

                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                imageCapture = capture

                val selector = CameraSelector.DEFAULT_BACK_CAMERA

                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, selector, preview, capture)

                startCaptureLoop()
            } catch (e: Exception) {
                onError(e.message ?: "Failed to start camera")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /** Manual "ANALYZE NOW": force the very next loop tick to capture regardless of interval/prefilter/auto-detect state. */
    fun requestImmediateAnalysis() {
        forceNextFrame = true
    }

    private fun startCaptureLoop() {
        captureLoopJob?.cancel()
        captureLoopJob = scope.launch {
            while (isActive) {
                val due = autoCaptureEnabled || forceNextFrame
                if (due && !busy.get()) {
                    captureOnce(forced = forceNextFrame)
                    forceNextFrame = false
                }
                delay(minOf(captureIntervalMs, 250L).coerceAtLeast(100L))
            }
        }
    }

    private fun captureOnce(forced: Boolean) {
        val capture = imageCapture ?: return
        if (!busy.compareAndSet(false, true)) return

        capture.takePicture(captureExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val jpegBytes = image.toOriginalJpegBytes()
                    var bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                    val rotation = image.imageInfo.rotationDegrees
                    if (rotation != 0) {
                        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                        bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                    }
                    bitmap = bitmap.resizedTo(AppConfig.UPLOAD_MAX_DIMENSION_PX)

                    if (!forced && frameDiffFilterEnabled && !frameChangedEnough(bitmap)) {
                        onFrameSkippedByPrefilter?.invoke()
                    } else {
                        val outJpeg = bitmap.toJpegBytes(AppConfig.UPLOAD_JPEG_QUALITY)
                        onFrameCaptured?.invoke(outJpeg)
                    }
                } catch (e: Exception) {
                    // Fail safe: swallow -- the caller's own timeout/error handling covers this.
                } finally {
                    image.close()
                    busy.set(false)
                }
            }

            override fun onError(exception: ImageCaptureException) {
                busy.set(false)
            }
        })
    }

    /** Cheap luma-histogram diff over a coarse grid -- good enough to detect "basically nothing changed". */
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
        captureLoopJob?.cancel()
        cameraProvider?.unbindAll()
        captureExecutor.shutdown()
    }
}

/** ImageCapture's default output format is JPEG, so plane 0's buffer IS the encoded JPEG stream already. */
private fun ImageProxy.toOriginalJpegBytes(): ByteArray {
    val buffer = planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    return bytes
}

private fun Bitmap.resizedTo(maxDimension: Int): Bitmap {
    val largestSide = maxOf(width, height)
    if (largestSide <= maxDimension) return this
    val scale = maxDimension.toFloat() / largestSide
    val matrix = Matrix().apply { postScale(scale, scale) }
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}

private fun Bitmap.toJpegBytes(quality: Int): ByteArray {
    val out = ByteArrayOutputStream()
    compress(Bitmap.CompressFormat.JPEG, quality, out)
    return out.toByteArray()
}

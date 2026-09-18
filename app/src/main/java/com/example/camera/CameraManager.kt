package com.example.camera

import android.content.Context
import android.util.Log
import android.util.Size
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class CameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val listener: FrameListener
) {
    interface FrameListener {
        fun onFrameAvailable(yuvData: ByteArray, width: Int, height: Int, rotationDegrees: Int)
        fun onFpsUpdated(fps: Double)
        fun onCameraError(error: String)
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var surfaceProvider: Preview.SurfaceProvider? = null

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val isStarted = AtomicBoolean(false)
    var isFrontCamera: Boolean = false
        private set

    private var targetWidth = 1280
    private var targetHeight = 720

    // FPS calculation
    private var frameCount = AtomicInteger(0)
    private var lastFpsCalcTime = System.currentTimeMillis()

    // Ring buffer of byte arrays to prevent GC pauses and race conditions
    private val bufferRingSize = 3
    private var bufferRing: Array<ByteArray>? = null
    private var bufferIndex = 0
    private var uRowTemp = ByteArray(2048)
    private var vRowTemp = ByteArray(2048)

    fun setTargetResolution(width: Int, height: Int) {
        targetWidth = width
        targetHeight = height
    }

    fun setSurfaceProvider(provider: Preview.SurfaceProvider) {
        this.surfaceProvider = provider
        if (isStarted.get()) {
            preview?.setSurfaceProvider(provider)
        }
    }

    fun startCamera(useFrontCamera: Boolean = false) {
        isFrontCamera = useFrontCamera
        isStarted.set(true)

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCamera()
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to get ProcessCameraProvider: ${e.message}", e)
                listener.onCameraError("Unable to access camera: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun switchCamera() {
        val provider = cameraProvider
        val targetFacing = if (isFrontCamera) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
        val canSwitch = provider?.hasCamera(targetFacing) ?: true
        if (canSwitch) {
            isFrontCamera = !isFrontCamera
            if (isStarted.get()) {
                bindCamera()
            }
        } else {
            Log.w(TAG, "Requested camera facing not available on device")
        }
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return
        try {
            provider.unbindAll()

            // Safe camera selector verification (check if device actually has requested lens facing)
            val requestedSelector = if (isFrontCamera) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }

            val cameraSelector = if (provider.hasCamera(requestedSelector)) {
                requestedSelector
            } else {
                Log.w(TAG, "Requested camera not found, trying fallback lens")
                val fallbackSelector = if (isFrontCamera) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                if (provider.hasCamera(fallbackSelector)) {
                    isFrontCamera = !isFrontCamera
                    fallbackSelector
                } else {
                    Log.e(TAG, "No suitable camera found on device")
                    listener.onCameraError("No camera hardware detected on this device")
                    return
                }
            }

            val previewUseCase = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .build()

            val providerSurface = surfaceProvider
            if (providerSurface != null) {
                previewUseCase.setSurfaceProvider(providerSurface)
            }
            preview = previewUseCase

            val analysisUseCase = ImageAnalysis.Builder()
                .setTargetResolution(Size(targetWidth, targetHeight))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()

            analysisUseCase.setAnalyzer(cameraExecutor) { imageProxy ->
                processImage(imageProxy)
            }
            imageAnalysis = analysisUseCase

            camera = provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                previewUseCase,
                analysisUseCase
            )
            Log.d(TAG, "Camera bound successfully. Lens front=$isFrontCamera")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to bind camera use cases: ${e.message}", e)
            listener.onCameraError("Camera bind error: ${e.message}")
        }
    }

    private fun processImage(image: ImageProxy) {
        try {
            val width = image.width
            val height = image.height
            val rotation = image.imageInfo.rotationDegrees

            val requiredSize = width * height * 3 / 2
            var ring = bufferRing
            if (ring == null || ring[0].size != requiredSize) {
                ring = Array(bufferRingSize) { ByteArray(requiredSize) }
                bufferRing = ring
                bufferIndex = 0
            }

            val buffer = ring[bufferIndex]
            bufferIndex = (bufferIndex + 1) % bufferRingSize

            yuv420ToNv12(image, buffer)
            listener.onFrameAvailable(buffer, width, height, rotation)

            // FPS tracking
            val frames = frameCount.incrementAndGet()
            val now = System.currentTimeMillis()
            val diff = now - lastFpsCalcTime
            if (diff >= 1000) {
                val currentFps = (frames * 1000.0) / diff
                lastFpsCalcTime = now
                frameCount.set(0)
                listener.onFpsUpdated(currentFps)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error processing camera frame: ${e.message}")
        } finally {
            image.close()
        }
    }

    /**
     * Converts YUV_420_888 ImageProxy planes into NV12 byte array (Y followed by interleaved U, V)
     * using bulk memory operations for maximum real-time performance.
     */
    private fun yuv420ToNv12(image: ImageProxy, outNv12: ByteArray) {
        val width = image.width
        val height = image.height

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride

        // 1. Copy Y plane using bulk block reads
        if (yPixelStride == 1 && yRowStride == width) {
            yBuffer.position(0)
            yBuffer.get(outNv12, 0, width * height)
        } else {
            for (row in 0 until height) {
                yBuffer.position(row * yRowStride)
                yBuffer.get(outNv12, row * width, width)
            }
        }

        // 2. Copy and interleave Chroma planes as NV12 (U then V)
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride

        val chromaHeight = height / 2
        val chromaWidth = width / 2
        val ySize = width * height

        if (uPixelStride == 2 && vPixelStride == 2) {
            val uRowLen = Math.min(uRowTemp.size, (chromaWidth - 1) * uPixelStride + 1)
            val vRowLen = Math.min(vRowTemp.size, (chromaWidth - 1) * vPixelStride + 1)

            var dst = ySize
            for (row in 0 until chromaHeight) {
                uBuffer.position(row * uRowStride)
                val uRead = Math.min(uRowLen, uBuffer.remaining())
                uBuffer.get(uRowTemp, 0, uRead)

                vBuffer.position(row * vRowStride)
                val vRead = Math.min(vRowLen, vBuffer.remaining())
                vBuffer.get(vRowTemp, 0, vRead)

                var uIdx = 0
                var vIdx = 0
                for (col in 0 until chromaWidth) {
                    outNv12[dst++] = uRowTemp[uIdx]
                    outNv12[dst++] = vRowTemp[vIdx]
                    uIdx += uPixelStride
                    vIdx += vPixelStride
                }
            }
        } else {
            // Planar I420 (pixelStride == 1)
            var dst = ySize
            for (row in 0 until chromaHeight) {
                uBuffer.position(row * uRowStride)
                val uRead = Math.min(chromaWidth, uBuffer.remaining())
                uBuffer.get(uRowTemp, 0, uRead)

                vBuffer.position(row * vRowStride)
                val vRead = Math.min(chromaWidth, vBuffer.remaining())
                vBuffer.get(vRowTemp, 0, vRead)

                for (col in 0 until chromaWidth) {
                    outNv12[dst++] = uRowTemp[col]
                    outNv12[dst++] = vRowTemp[col]
                }
            }
        }
    }

    fun stopCamera() {
        if (!isStarted.getAndSet(false)) return
        try {
            cameraProvider?.unbindAll()
            camera = null
            preview = null
            imageAnalysis = null
        } catch (e: Throwable) {
            Log.e(TAG, "Error stopping camera: ${e.message}")
        }
    }

    fun release() {
        stopCamera()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "CameraManager"
    }
}

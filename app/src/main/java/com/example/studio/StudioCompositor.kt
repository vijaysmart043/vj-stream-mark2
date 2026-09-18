package com.example.studio

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class StudioCompositor(
    private val studioStateFlow: StateFlow<StudioState>,
    private val frameConsumer: (ByteArray, Int, Int) -> Unit
) {
    private var blendBuffer: ByteArray? = null
    private var blackFrameBuffer: ByteArray? = null
    private var lastCameraFrame: ByteArray? = null
    private var lastCameraWidth = 1280
    private var lastCameraHeight = 720

    private val isPumping = AtomicBoolean(false)
    private var pumpJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    /**
     * Called whenever CameraX produces a raw YUV/NV21 frame.
     */
    fun onCameraFrame(yuvData: ByteArray, width: Int, height: Int) {
        lastCameraWidth = width
        lastCameraHeight = height

        val state = studioStateFlow.value

        if (state.isTransitioning) {
            // Save frame copy only when transition is active
            if (lastCameraFrame == null || lastCameraFrame?.size != yuvData.size) {
                lastCameraFrame = ByteArray(yuvData.size)
            }
            System.arraycopy(yuvData, 0, lastCameraFrame!!, 0, yuvData.size)

            val requiredSize = width * height * 3 / 2
            if (blendBuffer == null || blendBuffer?.size != requiredSize) {
                blendBuffer = ByteArray(requiredSize)
            }

            // Blending transition between outgoing Program and incoming Preview
            val outgoingNv21 = getSourceNv21(state.programSource, yuvData, width, height)
            val incomingNv21 = getSourceNv21(state.previewSource, yuvData, width, height)

            if (outgoingNv21 != null && incomingNv21 != null) {
                ImageSourceHelper.blendNv21(
                    outgoingNv21,
                    incomingNv21,
                    state.transitionProgress,
                    blendBuffer!!,
                    width,
                    height
                )
                frameConsumer(blendBuffer!!, width, height)
            } else {
                frameConsumer(outgoingNv21 ?: incomingNv21 ?: yuvData, width, height)
            }
        } else {
            // Not transitioning - route immediately with zero copying
            when (val program = state.programSource) {
                is StudioSource.Camera -> {
                    frameConsumer(yuvData, width, height)
                }
                is StudioSource.Image -> {
                    val imageNv21 = program.nv21Cache
                    if (imageNv21 != null) {
                        frameConsumer(imageNv21, width, height)
                    } else {
                        frameConsumer(yuvData, width, height)
                    }
                }
            }
        }
    }

    private fun getSourceNv21(source: StudioSource, currentCameraFrame: ByteArray, width: Int, height: Int): ByteArray? {
        return when (source) {
            is StudioSource.Camera -> currentCameraFrame
            is StudioSource.Image -> source.nv21Cache ?: getBlackFrame(width, height)
        }
    }

    private fun getBlackFrame(width: Int, height: Int): ByteArray {
        val size = width * height * 3 / 2
        var black = blackFrameBuffer
        if (black == null || black.size != size) {
            black = ByteArray(size)
            // Y = 16 (black), U = 128, V = 128
            val ySize = width * height
            for (i in 0 until ySize) black[i] = 16.toByte()
            for (i in ySize until size) black[i] = 128.toByte()
            blackFrameBuffer = black
        }
        return black
    }

    /**
     * Starts a 30 FPS synthetic frame pump when Program is an Image and camera is not pushing frames.
     */
    fun startFramePumpIfImage() {
        if (isPumping.getAndSet(true)) return
        pumpJob = scope.launch {
            while (isActive && isPumping.get()) {
                val state = studioStateFlow.value
                if (state.programSource is StudioSource.Image) {
                    val image = state.programSource
                    val nv21 = image.nv21Cache ?: getBlackFrame(lastCameraWidth, lastCameraHeight)
                    frameConsumer(nv21, lastCameraWidth, lastCameraHeight)
                }
                delay(33) // ~30 fps
            }
        }
    }

    fun stopFramePump() {
        isPumping.set(false)
        pumpJob?.cancel()
        pumpJob = null
    }

    companion object {
        private const val TAG = "StudioCompositor"
    }
}

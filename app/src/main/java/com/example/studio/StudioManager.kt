package com.example.studio

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StudioManager(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main)
) {
    private val _studioStateFlow = MutableStateFlow(StudioState())
    val studioStateFlow: StateFlow<StudioState> = _studioStateFlow.asStateFlow()

    val currentState: StudioState get() = _studioStateFlow.value

    private var transitionJob: Job? = null

    /**
     * Updates the preview source.
     */
    fun setPreviewSource(source: StudioSource) {
        _studioStateFlow.value = _studioStateFlow.value.copy(previewSource = source)
    }

    /**
     * Sets preview source back to live camera.
     */
    fun setPreviewCamera() {
        setPreviewSource(StudioSource.Camera)
    }

    /**
     * Loads an image from URI and sets it as the Preview source.
     */
    fun setPreviewImage(
        context: Context,
        uri: Uri,
        targetWidth: Int = 1280,
        targetHeight: Int = 720,
        onLoaded: (Boolean) -> Unit = {}
    ) {
        scope.launch {
            val imageSource = withContext(Dispatchers.IO) {
                ImageSourceHelper.loadImage(context, uri, targetWidth, targetHeight)
            }
            if (imageSource != null) {
                setPreviewSource(imageSource)
                onLoaded(true)
            } else {
                Log.w(TAG, "Failed to load image from URI $uri")
                onLoaded(false)
            }
        }
    }

    /**
     * Direct setter for program source (without transition).
     */
    fun setProgramSource(source: StudioSource) {
        _studioStateFlow.value = _studioStateFlow.value.copy(programSource = source)
    }

    /**
     * Triggers a smooth FADE transition from Preview to Program over the given duration.
     * Prevents overlapping transitions.
     *
     * @return true if transition started, false if already in progress.
     */
    fun startFadeTransition(
        durationMs: Long = StudioState.DEFAULT_FADE_DURATION_MS,
        onComplete: () -> Unit = {}
    ): Boolean {
        val current = _studioStateFlow.value
        if (current.isTransitioning) {
            Log.w(TAG, "Transition already in progress. Ignoring FADE request.")
            return false
        }

        transitionJob?.cancel()
        transitionJob = scope.launch {
            _studioStateFlow.value = current.copy(
                isTransitioning = true,
                transitionProgress = 0f,
                transitionType = TransitionType.FADE
            )

            var elapsed = 0L
            val intervalMs = 16L // ~60fps UI progress updates

            while (true) {
                val rawProgress = (elapsed.toFloat() / durationMs).coerceIn(0f, 1f)

                // Smooth ease-in-out curve
                val progress = smoothStep(rawProgress)
                _studioStateFlow.value = _studioStateFlow.value.copy(transitionProgress = progress)

                if (rawProgress >= 1f) break
                delay(intervalMs)
                elapsed += intervalMs
            }

            // Transition complete: New program source is the preview source.
            // The previous program source becomes the preview source (swap).
            val stateBeforeComplete = _studioStateFlow.value
            val previousProgram = stateBeforeComplete.programSource
            val newProgram = stateBeforeComplete.previewSource

            _studioStateFlow.value = stateBeforeComplete.copy(
                programSource = newProgram,
                previewSource = previousProgram,
                isTransitioning = false,
                transitionProgress = 0f
            )

            Log.i(TAG, "FADE transition complete. Program=$newProgram, Preview=$previousProgram")
            onComplete()
        }
        return true
    }

    /**
     * Smooth Hermite interpolation (smoothstep).
     */
    private fun smoothStep(t: Float): Float {
        return t * t * (3f - 2f * t)
    }

    companion object {
        private const val TAG = "StudioManager"
    }
}

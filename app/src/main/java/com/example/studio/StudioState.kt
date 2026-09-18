package com.example.studio

enum class TransitionType {
    FADE
}

data class StudioState(
    val previewSource: StudioSource = StudioSource.Camera,
    val programSource: StudioSource = StudioSource.Camera,
    val isTransitioning: Boolean = false,
    val transitionProgress: Float = 0f,
    val transitionType: TransitionType = TransitionType.FADE
) {
    companion object {
        const val DEFAULT_FADE_DURATION_MS: Long = 1000L
    }
}

package com.example.streaming.audio

/**
 * Audio lifecycle states for microphone capture.
 * Corresponds to Section 7 & Section 10 of VJStream Phase 3.
 */
enum class AudioState {
    OFF,
    INITIALIZING,
    READY,
    CAPTURING,
    MUTED,
    ERROR,
    STOPPING;

    /**
     * UI Status display string conforming to Section 10 requirements:
     * - MIC: ON
     * - MIC: OFF
     * - MIC: INITIALIZING
     * - MIC: ERROR
     */
    val displayLabel: String
        get() = when (this) {
            OFF -> "MIC: OFF"
            INITIALIZING -> "MIC: INITIALIZING"
            READY -> "MIC: ON"
            CAPTURING -> "MIC: ON"
            MUTED -> "MIC: OFF"
            ERROR -> "MIC: ERROR"
            STOPPING -> "MIC: OFF"
        }

    val isActive: Boolean
        get() = this == CAPTURING || this == READY

    val isCapturing: Boolean
        get() = this == CAPTURING

    val isMuted: Boolean
        get() = this == MUTED
}

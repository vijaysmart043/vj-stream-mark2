package com.example.streaming.encoder

enum class VideoEncoderState(val displayLabel: String) {
    IDLE("ENCODER: IDLE"),
    INITIALIZING("ENCODER: INIT"),
    READY("ENCODER: READY"),
    ENCODING("ENCODER: ON"),
    STOPPING("ENCODER: STOPPING"),
    ERROR("ENCODER: ERROR");

    val isEncoding: Boolean
        get() = this == ENCODING

    val isBusy: Boolean
        get() = this == INITIALIZING || this == STOPPING
}

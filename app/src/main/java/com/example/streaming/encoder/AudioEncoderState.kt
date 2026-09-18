package com.example.streaming.encoder

enum class AudioEncoderState(val displayLabel: String) {
    IDLE("AAC: IDLE"),
    INITIALIZING("AAC: INIT"),
    READY("AAC: READY"),
    ENCODING("AAC: ON"),
    STOPPING("AAC: STOPPING"),
    ERROR("AAC: ERROR");

    val isEncoding: Boolean
        get() = this == ENCODING

    val isBusy: Boolean
        get() = this == INITIALIZING || this == STOPPING
}

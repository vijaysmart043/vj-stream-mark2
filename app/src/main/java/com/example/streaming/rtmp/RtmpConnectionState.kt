package com.example.streaming.rtmp

/**
 * Connection states for the RTMP client connection lifecycle.
 */
enum class RtmpConnectionState(val displayLabel: String) {
    DISCONNECTED("Disconnected"),
    CONNECTING("Connecting"),
    CONNECTED("Connected"),
    PUBLISHING("Publishing"),
    ERROR("Connection Error"),
    DISCONNECTING("Disconnecting");

    val isConnected: Boolean
        get() = this == CONNECTED || this == PUBLISHING

    val isConnecting: Boolean
        get() = this == CONNECTING

    val isBusy: Boolean
        get() = this == CONNECTING || this == DISCONNECTING || this == PUBLISHING
}

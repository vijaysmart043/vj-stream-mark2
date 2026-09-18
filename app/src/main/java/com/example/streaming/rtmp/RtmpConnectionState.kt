package com.example.streaming.rtmp

/**
 * Connection states for the RTMP client connection lifecycle.
 */
enum class RtmpConnectionState(val displayLabel: String) {
    DISCONNECTED("Disconnected"),
    CONNECTING("Connecting"),
    CONNECTED("Connected"),
    ERROR("Connection Error"),
    DISCONNECTING("Disconnecting");

    val isConnected: Boolean
        get() = this == CONNECTED

    val isConnecting: Boolean
        get() = this == CONNECTING

    val isBusy: Boolean
        get() = this == CONNECTING || this == DISCONNECTING
}

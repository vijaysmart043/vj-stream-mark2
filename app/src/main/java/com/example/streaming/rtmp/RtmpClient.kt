package com.example.streaming.rtmp

import android.util.Log
import com.example.streaming.encoder.AudioSpecificConfig
import com.example.streaming.encoder.EncodedAudioFrame
import com.example.streaming.encoder.EncodedVideoFrame
import com.example.streaming.packetizer.FlvAudioPacket
import com.example.streaming.packetizer.FlvAudioPacketizer
import com.example.streaming.packetizer.FlvVideoPacket
import com.example.streaming.packetizer.FlvVideoPacketizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Diagnostic container for RTMP streaming session state and failures.
 */
data class RtmpDiagnostics(
    val failedStage: String? = null,
    val lastSuccessfulStage: String = "NONE",
    val rtmpMessageType: Int? = null,
    val rtmpMessageStreamId: Int? = null,
    val videoFramesEncoded: Long = 0L,
    val audioFramesEncoded: Long = 0L,
    val videoBytesSent: Long = 0L,
    val audioBytesSent: Long = 0L,
    val rtmpBytesSent: Long = 0L,
    val lastServerResponse: String? = null
) {
    fun formatReport(): String {
        return """
            ================ VJSTREAM STREAMING DIAGNOSTICS ================
            FAILED_STAGE: ${failedStage ?: "NONE"}
            LAST_SUCCESSFUL_STAGE: $lastSuccessfulStage
            RTMP_MESSAGE_TYPE: ${rtmpMessageType ?: "NONE"}
            RTMP_MESSAGE_STREAM_ID: ${rtmpMessageStreamId ?: "NONE"}
            VIDEO_FRAMES_ENCODED: $videoFramesEncoded
            AUDIO_FRAMES_ENCODED: $audioFramesEncoded
            VIDEO_BYTES_SENT: $videoBytesSent
            AUDIO_BYTES_SENT: $audioBytesSent
            RTMP_BYTES_SENT: $rtmpBytesSent
            LAST_SERVER_RESPONSE: ${lastServerResponse ?: "NONE"}
            ===============================================================
        """.trimIndent()
    }
}

/**
 * Clean abstraction for RTMP/RTMPS client connections.
 *
 * Responsibilities:
 * - Validates RTMP/RTMPS server URLs prior to network operations
 * - Accepts stream key separately without logging or leaking credentials
 * - Coordinates RTMP handshake, connect, createStream, and publish commands
 * - Reads and handles server chunk stream responses and ping requests
 * - Provides packet interfaces for H.264 (video) and AAC (audio) transmission
 * - Collects diagnostics for transmission failures
 */
interface RtmpClient {

    interface Listener {
        fun onConnected()
        fun onConnectionFailed(reason: String)
        fun onDisconnected()
        fun onConnectionStateChanged(state: RtmpConnectionState) {}
        fun onDroppedFrame() {}
        fun onStatsUpdated(
            bitrateKbps: Int,
            droppedFrames: Long,
            totalBytesSent: Long = 0L,
            videoPackets: Long = 0L,
            audioPackets: Long = 0L
        ) {}
    }

    val connectionStateFlow: StateFlow<RtmpConnectionState>
    val currentState: RtmpConnectionState
    val isConnected: Boolean

    /**
     * Connects to the given RTMP/RTMPS endpoint using the provided stream key.
     * All network operations run asynchronously on a background thread.
     */
    fun connect(
        serverUrl: String,
        streamKey: String,
        width: Int = 1280,
        height: Int = 720,
        fps: Int = 30,
        videoBitrateKbps: Int = 2500
    )

    /**
     * Disconnects cleanly from the RTMP server and releases resources.
     */
    fun disconnect()

    /**
     * Performs a connection test (socket connection + RTMP handshake)
     * without starting media streaming.
     */
    fun testConnection(
        serverUrl: String,
        streamKey: String,
        timeoutMs: Int = 10000,
        callback: (success: Boolean, message: String) -> Unit = { _, _ -> }
    )

    fun getDiagnostics(): RtmpDiagnostics

    // Encoded packet interfaces for Video
    fun setSpsPps(sps: ByteArray, pps: ByteArray)
    fun sendVideo(frame: EncodedVideoFrame)
    fun sendVideo(nalData: ByteArray, isKeyframe: Boolean, timestampMs: Long)
    fun sendFlvVideoPacket(packet: FlvVideoPacket)

    // Encoded packet interfaces for Audio
    fun sendAudio(frame: EncodedAudioFrame)
    fun sendAudio(aacData: ByteArray, timestampMs: Long)
    fun sendAudioConfig(config: AudioSpecificConfig)
    fun sendAudioSequenceHeader(sampleRate: Int, channelCount: Int)
    fun sendFlvAudioPacket(packet: FlvAudioPacket)

    // Backward-compatibility aliases
    fun enqueueVideoFrame(nalData: ByteArray, isKeyframe: Boolean, timestampMs: Long) =
        sendVideo(nalData, isKeyframe, timestampMs)

    fun enqueueAudioFrame(aacData: ByteArray, timestampMs: Long) =
        sendAudio(aacData, timestampMs)

    companion object {
        operator fun invoke(listener: Listener): RtmpClient = DefaultRtmpClient(listener)
    }
}

/**
 * Standard implementation of [RtmpClient].
 */
class DefaultRtmpClient(
    private val listener: RtmpClient.Listener
) : RtmpClient {

    private val _connectionStateFlow = MutableStateFlow(RtmpConnectionState.DISCONNECTED)
    override val connectionStateFlow: StateFlow<RtmpConnectionState> = _connectionStateFlow.asStateFlow()

    override val currentState: RtmpConnectionState
        get() = _connectionStateFlow.value

    override val isConnected: Boolean
        get() = isConnectedFlag.get()

    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null

    private val isConnectedFlag = AtomicBoolean(false)
    private val isStreamingFlag = AtomicBoolean(false)

    private val packetQueue = LinkedBlockingQueue<RtmpPacket>(QUEUE_CAPACITY)
    private var senderThread: Thread? = null
    private var readerThread: Thread? = null

    private val demuxer = RtmpChunkDemuxer()

    private var connectLatch = CountDownLatch(1)
    private var createStreamLatch = CountDownLatch(1)
    private var publishLatch = CountDownLatch(1)

    @Volatile private var connectError: String? = null
    @Volatile private var createStreamError: String? = null
    @Volatile private var publishError: String? = null

    // Telemetry & Diagnostics
    @Volatile private var failedStage: String? = null
    @Volatile private var lastSuccessfulStage: String = "NONE"
    @Volatile private var lastRtmpMessageType: Int? = null
    @Volatile private var lastRtmpMessageStreamId: Int? = null
    @Volatile private var lastServerResponse: String? = null

    private val droppedFramesCount = AtomicLong(0)
    private val totalBytesSent = AtomicLong(0)
    private val videoBytesSent = AtomicLong(0)
    private val audioBytesSent = AtomicLong(0)
    private val videoPacketsSent = AtomicLong(0)
    private val audioPacketsSent = AtomicLong(0)

    private var lastBitrateCalcTime = 0L
    private var lastBytesSentSnapshot = 0L

    private var chunkSize = DEFAULT_CHUNK_SIZE
    private var streamId = 1
    private var streamStartTimeMs = -1L

    @Volatile private var spsBytes: ByteArray? = null
    @Volatile private var ppsBytes: ByteArray? = null
    @Volatile private var hasSentVideoHeader = false
    @Volatile private var hasSentAudioHeader = false

    private val defaultVideoPacketizer = FlvVideoPacketizer()
    private val defaultAudioPacketizer = FlvAudioPacketizer()

    private fun updateState(newState: RtmpConnectionState) {
        _connectionStateFlow.value = newState
        listener.onConnectionStateChanged(newState)
    }

    override fun getDiagnostics(): RtmpDiagnostics {
        return RtmpDiagnostics(
            failedStage = failedStage,
            lastSuccessfulStage = lastSuccessfulStage,
            rtmpMessageType = lastRtmpMessageType,
            rtmpMessageStreamId = lastRtmpMessageStreamId,
            videoFramesEncoded = videoPacketsSent.get(),
            audioFramesEncoded = audioPacketsSent.get(),
            videoBytesSent = videoBytesSent.get(),
            audioBytesSent = audioBytesSent.get(),
            rtmpBytesSent = totalBytesSent.get(),
            lastServerResponse = lastServerResponse
        )
    }

    /**
     * Sanitizes stream key by removing any accidentally prepended URLs, path segments,
     * quotes, newlines, or whitespace.
     */
    private fun sanitizeStreamKey(rawKey: String): String {
        var key = rawKey.trim()
        if (key.contains("/live2/")) {
            key = key.substringAfterLast("/live2/").trim()
        } else if (key.contains("/")) {
            key = key.substringAfterLast("/").trim()
        }
        return key.trim().removeSurrounding("\"").removeSurrounding("'")
    }

    override fun connect(
        serverUrl: String,
        streamKey: String,
        width: Int,
        height: Int,
        fps: Int,
        videoBitrateKbps: Int
    ) {
        val validationResult = RtmpUrlValidator.validate(serverUrl)
        if (validationResult is RtmpUrlValidator.ValidationResult.Invalid) {
            Log.e(TAG, "RTMP connection rejected: ${validationResult.reason}")
            updateState(RtmpConnectionState.ERROR)
            listener.onConnectionFailed(validationResult.reason)
            return
        }

        val parsed = validationResult as RtmpUrlValidator.ValidationResult.Valid
        val cleanKey = sanitizeStreamKey(streamKey)

        if (cleanKey.isBlank()) {
            val err = "Stream key cannot be empty"
            Log.e(TAG, "RTMP connection rejected: $err")
            updateState(RtmpConnectionState.ERROR)
            listener.onConnectionFailed(err)
            return
        }

        updateState(RtmpConnectionState.CONNECTING)

        Thread {
            try {
                failedStage = null
                lastSuccessfulStage = "NONE"
                lastServerResponse = null
                connectLatch = CountDownLatch(1)
                createStreamLatch = CountDownLatch(1)
                publishLatch = CountDownLatch(1)
                connectError = null
                createStreamError = null
                publishError = null

                Log.i(TAG, "Connecting to RTMP endpoint: ${parsed.host}:${parsed.port}/${parsed.app} (streamKey=<REDACTED>, TLS=${parsed.isSsl})")

                failedStage = "SOCKET_CONNECT"
                val baseSocket = if (parsed.isSsl) {
                    val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                    val sslSock = factory.createSocket() as SSLSocket
                    sslSock.connect(InetSocketAddress(parsed.host, parsed.port), CONNECT_TIMEOUT_MS)
                    sslSock.soTimeout = SOCKET_TIMEOUT_MS
                    sslSock.startHandshake()
                    sslSock
                } else {
                    val sock = Socket()
                    sock.connect(InetSocketAddress(parsed.host, parsed.port), CONNECT_TIMEOUT_MS)
                    sock.soTimeout = SOCKET_TIMEOUT_MS
                    sock
                }

                baseSocket.tcpNoDelay = true
                baseSocket.sendBufferSize = 128 * 1024
                socket = baseSocket
                outputStream = BufferedOutputStream(baseSocket.getOutputStream(), 64 * 1024)
                inputStream = BufferedInputStream(baseSocket.getInputStream(), 64 * 1024)
                lastSuccessfulStage = "SOCKET_CONNECTED"
                Log.i(TAG, "Socket connected. Starting RTMP handshake...")

                // Handshake C0/C1 and S0/S1/S2
                failedStage = "HANDSHAKE"
                performHandshake()
                lastSuccessfulStage = "HANDSHAKE_SUCCESS"
                Log.i(TAG, "RTMP Handshake successful")

                // Start reader loop to process server packets
                startReaderLoop()

                // Set Chunk Size to 4096
                sendSetChunkSize(DEFAULT_CHUNK_SIZE)
                Log.i(TAG, "Set Chunk Size $DEFAULT_CHUNK_SIZE sent")

                // Connect Command
                failedStage = "CONNECT"
                val tcUrl = parsed.normalizedUrl
                sendConnectCommand(parsed.app, tcUrl)
                Log.i(TAG, "RTMP connect command sent for app: ${parsed.app}")

                // Await server connect response
                val connectOk = connectLatch.await(10, TimeUnit.SECONDS)
                if (!connectOk || connectError != null) {
                    val err = connectError ?: "Timeout waiting for RTMP connect response"
                    throw IllegalStateException(err)
                }
                lastSuccessfulStage = "CONNECT_SUCCESS"
                Log.i(TAG, "RTMP connect acknowledged by server")

                // ReleaseStream & FCPublish & CreateStream
                failedStage = "CREATE_STREAM"
                sendReleaseStream(cleanKey)
                sendFCPublish(cleanKey)
                sendCreateStream()
                Log.i(TAG, "RTMP createStream command sent")

                // Await server createStream response with assigned streamId
                val createStreamOk = createStreamLatch.await(10, TimeUnit.SECONDS)
                if (!createStreamOk || createStreamError != null) {
                    val err = createStreamError ?: "Timeout waiting for RTMP createStream response"
                    throw IllegalStateException(err)
                }
                lastSuccessfulStage = "CREATE_STREAM_SUCCESS"
                Log.i(TAG, "RTMP createStream confirmed with server streamId: $streamId")

                // Publish on the assigned streamId
                failedStage = "PUBLISH"
                sendPublish(cleanKey)
                updateState(RtmpConnectionState.PUBLISHING)
                Log.i(TAG, "RTMP publish command sent (streamId=$streamId, streamKey=<REDACTED>, mode=live)")

                // Await onStatus or brief timeout
                publishLatch.await(3, TimeUnit.SECONDS)
                if (publishError != null) {
                    throw IllegalStateException("Publish rejected by server: $publishError")
                }
                lastSuccessfulStage = "PUBLISH_SUCCESS"
                Log.i(TAG, "RTMP publish acknowledged (status: ${lastServerResponse ?: "OK"})")

                // Metadata
                failedStage = "METADATA"
                sendMetaData(width, height, fps, videoBitrateKbps)
                lastSuccessfulStage = "METADATA_SENT"
                Log.i(TAG, "RTMP @setDataFrame onMetaData sent (streamId=$streamId, ${width}x$height, ${fps}fps, ${videoBitrateKbps}kbps)")

                // Sequence headers if already configured
                failedStage = "SEQUENCE_HEADERS"
                if (spsBytes != null && ppsBytes != null && !hasSentVideoHeader) {
                    defaultVideoPacketizer.buildSequenceHeader()?.let { packet ->
                        sendFlvVideoPacket(packet)
                        hasSentVideoHeader = true
                        Log.i(TAG, "AVC sequence header sent")
                    }
                }
                if (!hasSentAudioHeader) {
                    defaultAudioPacketizer.buildSequenceHeader()?.let { packet ->
                        sendFlvAudioPacket(packet)
                        hasSentAudioHeader = true
                        Log.i(TAG, "AAC sequence header sent")
                    }
                }
                lastSuccessfulStage = "SEQUENCE_HEADERS_SENT"

                isConnectedFlag.set(true)
                isStreamingFlag.set(true)
                streamStartTimeMs = System.currentTimeMillis()
                lastBitrateCalcTime = System.currentTimeMillis()

                startSenderLoop()

                failedStage = null
                updateState(RtmpConnectionState.CONNECTED)
                Log.i(TAG, "RTMP pipeline ready for media transmission")
                listener.onConnected()
            } catch (e: Exception) {
                val cleanError = mapToUserFriendlyError(e)
                Log.e(TAG, "RTMP connection failed: $cleanError")
                reportFailure(failedStage ?: "UNKNOWN", cleanError)
            }
        }.start()
    }

    private fun startReaderLoop() {
        readerThread = Thread {
            try {
                val inStream = inputStream ?: return@Thread
                while (isConnectedFlag.get() || currentState.isBusy) {
                    val packet = demuxer.readPacket(inStream) ?: break
                    handleIncomingPacket(packet)
                }
            } catch (e: Exception) {
                if (isStreamingFlag.get() || currentState.isBusy) {
                    val err = "Server connection lost: ${e.message}"
                    lastServerResponse = err
                    Log.e(TAG, err)
                    reportFailure("SOCKET_READ", err)
                }
            }
        }.apply {
            name = "rtmp-reader"
            start()
        }
    }

    private fun handleIncomingPacket(packet: RtmpPacket) {
        lastRtmpMessageType = packet.messageType.toInt()
        lastRtmpMessageStreamId = packet.messageStreamId

        when (packet.messageType) {
            RtmpPacket.TYPE_SET_CHUNK_SIZE -> {
                Log.i(TAG, "Server updated chunk size to: ${demuxer.inChunkSize}")
            }
            0x04.toByte() -> { // User Control Message
                if (packet.data.size >= 6) {
                    val eventType = ((packet.data[0].toInt() and 0xFF) shl 8) or (packet.data[1].toInt() and 0xFF)
                    if (eventType == 6) { // PingRequest
                        val pong = ByteArray(6)
                        pong[0] = 0x00
                        pong[1] = 0x07 // PingResponse
                        pong[2] = packet.data[2]
                        pong[3] = packet.data[3]
                        pong[4] = packet.data[4]
                        pong[5] = packet.data[5]
                        try {
                            writePacketDirect(
                                RtmpPacket(
                                    messageType = 0x04.toByte(),
                                    chunkStreamId = RtmpPacket.CSID_CONTROL,
                                    messageStreamId = 0,
                                    timestamp = 0,
                                    data = pong
                                )
                            )
                            Log.d(TAG, "Responded to RTMP PingRequest")
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to respond to PingRequest: ${e.message}")
                        }
                    }
                }
            }
            RtmpPacket.TYPE_COMMAND_AMF0, 0x11.toByte() -> {
                val payload = if (packet.messageType == 0x11.toByte() && packet.data.isNotEmpty() && packet.data[0] == 0.toByte()) {
                    packet.data.copyOfRange(1, packet.data.size)
                } else {
                    packet.data
                }
                val items = Amf0.decode(payload)
                if (items.isNotEmpty()) {
                    val cmd = items.getOrNull(0) as? String
                    val txId = (items.getOrNull(1) as? Number)?.toDouble() ?: 0.0

                    Log.i(TAG, "RTMP server command received: cmd=$cmd txId=$txId")

                    when (cmd) {
                        "_result" -> {
                            when (txId) {
                                1.0 -> { // Connect response
                                    val info = items.getOrNull(3) as? Map<*, *>
                                    val code = info?.get("code") as? String ?: "NetConnection.Connect.Success"
                                    lastServerResponse = code
                                    Log.i(TAG, "RTMP connect acknowledged: $code")
                                    connectLatch.countDown()
                                }
                                4.0 -> { // CreateStream response
                                    val serverStreamId = (items.getOrNull(3) as? Number)?.toInt() ?: 1
                                    this.streamId = serverStreamId
                                    lastServerResponse = "StreamId=$serverStreamId"
                                    Log.i(TAG, "RTMP createStream acknowledged, assigned streamId: $serverStreamId")
                                    createStreamLatch.countDown()
                                }
                                else -> {
                                    Log.d(TAG, "RTMP _result for txId $txId")
                                }
                            }
                        }
                        "_error" -> {
                            val info = items.getOrNull(3) as? Map<*, *>
                            val code = info?.get("code") as? String ?: info?.get("description") as? String ?: "Command error"
                            lastServerResponse = "ERROR: $code"
                            Log.e(TAG, "RTMP server returned error: $code for txId $txId")
                            if (txId == 1.0) {
                                connectError = code
                                connectLatch.countDown()
                            } else if (txId == 4.0) {
                                createStreamError = code
                                createStreamLatch.countDown()
                            }
                        }
                        "onStatus" -> {
                            val info = items.getOrNull(3) as? Map<*, *>
                            val code = info?.get("code") as? String ?: "Unknown"
                            val level = info?.get("level") as? String ?: "status"
                            val desc = info?.get("description") as? String ?: ""
                            lastServerResponse = "$code ($level: $desc)"
                            Log.i(TAG, "RTMP onStatus: $code ($level: $desc)")

                            if (code == "NetStream.Publish.Start") {
                                publishLatch.countDown()
                            } else if (code == "NetStream.Publish.BadName" || level == "error") {
                                publishError = code
                                publishLatch.countDown()
                                if (isStreamingFlag.get()) {
                                    reportFailure("PUBLISH", "YouTube rejected stream key: $code")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun reportFailure(stage: String, error: String) {
        failedStage = stage
        val diag = getDiagnostics().formatReport()
        Log.e(TAG, "Streaming failure reported:\n$diag")
        closeInternal()
        updateState(RtmpConnectionState.ERROR)
        listener.onConnectionFailed(error)
    }

    override fun testConnection(
        serverUrl: String,
        streamKey: String,
        timeoutMs: Int,
        callback: (success: Boolean, message: String) -> Unit
    ) {
        val validationResult = RtmpUrlValidator.validate(serverUrl)
        if (validationResult is RtmpUrlValidator.ValidationResult.Invalid) {
            updateState(RtmpConnectionState.ERROR)
            callback(false, validationResult.reason)
            return
        }

        val parsed = validationResult as RtmpUrlValidator.ValidationResult.Valid
        val cleanKey = sanitizeStreamKey(streamKey)

        if (cleanKey.isBlank()) {
            updateState(RtmpConnectionState.ERROR)
            callback(false, "Stream key cannot be empty")
            return
        }

        Thread {
            var testSocket: Socket? = null
            try {
                Log.i(TAG, "Testing connection to ${parsed.host}:${parsed.port} (TLS=${parsed.isSsl})")

                testSocket = if (parsed.isSsl) {
                    val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                    val sslSock = factory.createSocket() as SSLSocket
                    sslSock.connect(InetSocketAddress(parsed.host, parsed.port), timeoutMs)
                    sslSock.soTimeout = timeoutMs
                    sslSock.startHandshake()
                    sslSock
                } else {
                    val sock = Socket()
                    sock.connect(InetSocketAddress(parsed.host, parsed.port), timeoutMs)
                    sock.soTimeout = timeoutMs
                    sock
                }

                testSocket.tcpNoDelay = true
                val outStream = BufferedOutputStream(testSocket.getOutputStream(), 4096)
                val inStream = BufferedInputStream(testSocket.getInputStream(), 4096)

                // Simple handshake verification
                outStream.write(0x03)
                val c1 = ByteArray(1536)
                Random().nextBytes(c1)
                // C1 time = 0
                c1[0] = 0; c1[1] = 0; c1[2] = 0; c1[3] = 0
                // C1 zero = 0
                c1[4] = 0; c1[5] = 0; c1[6] = 0; c1[7] = 0
                outStream.write(c1)
                outStream.flush()

                val s0 = inStream.read()
                if (s0 != 3) {
                    callback(false, "Invalid RTMP handshake version: $s0")
                    return@Thread
                }

                val s1 = ByteArray(1536)
                var s1Read = 0
                while (s1Read < 1536) {
                    val r = inStream.read(s1, s1Read, 1536 - s1Read)
                    if (r < 0) throw SocketTimeoutException("EOF during S1 read")
                    s1Read += r
                }

                // Send C2
                outStream.write(s1)
                outStream.flush()

                // Read S2
                val s2 = ByteArray(1536)
                var s2Read = 0
                while (s2Read < 1536) {
                    val r = inStream.read(s2, s2Read, 1536 - s2Read)
                    if (r < 0) throw SocketTimeoutException("EOF during S2 read")
                    s2Read += r
                }

                Log.i(TAG, "Connection test successful: RTMP handshake completed with ${parsed.host}")
                callback(true, "Successfully connected to ${parsed.host} (TLS=${parsed.isSsl})")
            } catch (e: Exception) {
                val cleanError = mapToUserFriendlyError(e)
                Log.e(TAG, "Connection test failed: $cleanError")
                callback(false, cleanError)
            } finally {
                try {
                    testSocket?.close()
                } catch (_: Exception) {}
            }
        }.start()
    }

    private fun mapToUserFriendlyError(e: Exception): String {
        return when (e) {
            is UnknownHostException -> "Server not found. Check your RTMP URL."
            is ConnectException -> "Connection refused by server. Check URL and port."
            is SocketTimeoutException -> "Connection timed out. Check network or server status."
            is SSLException -> "SSL/TLS handshake failed. Check endpoint certificate."
            is IllegalStateException -> e.message ?: "Connection state error"
            else -> e.message ?: "Network error occurred"
        }
    }

    private fun performHandshake() {
        val out = outputStream ?: throw IllegalStateException("Output stream is null")
        val input = inputStream ?: throw IllegalStateException("Input stream is null")

        // 1. C0
        out.write(0x03)

        // 2. C1 (1536 bytes)
        val c1 = ByteArray(1536)
        Random().nextBytes(c1)
        c1[0] = 0; c1[1] = 0; c1[2] = 0; c1[3] = 0
        c1[4] = 0; c1[5] = 0; c1[6] = 0; c1[7] = 0
        out.write(c1)
        out.flush()

        // 3. S0
        val s0 = input.read()
        if (s0 != 3) {
            throw IllegalStateException("Invalid RTMP version received: $s0")
        }

        // 4. S1 (1536 bytes)
        val s1 = ByteArray(1536)
        var s1Read = 0
        while (s1Read < 1536) {
            val r = input.read(s1, s1Read, 1536 - s1Read)
            if (r < 0) throw SocketTimeoutException("EOF reached during S1 read")
            s1Read += r
        }

        // 5. C2 (echo of S1)
        out.write(s1)
        out.flush()

        // 6. S2 (1536 bytes)
        val s2 = ByteArray(1536)
        var s2Read = 0
        while (s2Read < 1536) {
            val r = input.read(s2, s2Read, 1536 - s2Read)
            if (r < 0) throw SocketTimeoutException("EOF reached during S2 read")
            s2Read += r
        }
    }

    private fun sendSetChunkSize(size: Int) {
        chunkSize = size
        val data = ByteArray(4)
        data[0] = ((size shr 24) and 0x7F).toByte()
        data[1] = ((size shr 16) and 0xFF).toByte()
        data[2] = ((size shr 8) and 0xFF).toByte()
        data[3] = (size and 0xFF).toByte()

        val packet = RtmpPacket(
            messageType = RtmpPacket.TYPE_SET_CHUNK_SIZE,
            chunkStreamId = RtmpPacket.CSID_CONTROL,
            messageStreamId = 0,
            timestamp = 0,
            data = data
        )
        writePacketDirect(packet)
    }

    private fun sendConnectCommand(app: String, tcUrl: String) {
        val baos = ByteArrayOutputStream()
        Amf0.writeString(baos, "connect")
        Amf0.writeNumber(baos, 1.0)

        val commandObject = linkedMapOf<String, Any?>(
            "app" to app,
            "flashVer" to "FMLE/3.0 (compatible; FMSc/1.0)",
            "swfUrl" to "",
            "tcUrl" to tcUrl,
            "fpad" to false,
            "capabilities" to 15.0,
            "audioCodecs" to 3575.0,
            "videoCodecs" to 252.0,
            "videoFunction" to 1.0,
            "objectEncoding" to 0.0
        )
        Amf0.writeObject(baos, commandObject)

        val packet = RtmpPacket(
            messageType = RtmpPacket.TYPE_COMMAND_AMF0,
            chunkStreamId = RtmpPacket.CSID_COMMAND,
            messageStreamId = 0,
            timestamp = 0,
            data = baos.toByteArray()
        )
        writePacketDirect(packet)
    }

    private fun sendReleaseStream(streamKey: String) {
        val baos = ByteArrayOutputStream()
        Amf0.writeString(baos, "releaseStream")
        Amf0.writeNumber(baos, 2.0)
        Amf0.writeNull(baos)
        Amf0.writeString(baos, streamKey)

        writePacketDirect(RtmpPacket(RtmpPacket.TYPE_COMMAND_AMF0, RtmpPacket.CSID_COMMAND, 0, 0, baos.toByteArray()))
    }

    private fun sendFCPublish(streamKey: String) {
        val baos = ByteArrayOutputStream()
        Amf0.writeString(baos, "FCPublish")
        Amf0.writeNumber(baos, 3.0)
        Amf0.writeNull(baos)
        Amf0.writeString(baos, streamKey)

        writePacketDirect(RtmpPacket(RtmpPacket.TYPE_COMMAND_AMF0, RtmpPacket.CSID_COMMAND, 0, 0, baos.toByteArray()))
    }

    private fun sendCreateStream() {
        val baos = ByteArrayOutputStream()
        Amf0.writeString(baos, "createStream")
        Amf0.writeNumber(baos, 4.0)
        Amf0.writeNull(baos)

        writePacketDirect(RtmpPacket(RtmpPacket.TYPE_COMMAND_AMF0, RtmpPacket.CSID_COMMAND, 0, 0, baos.toByteArray()))
    }

    private fun sendPublish(streamKey: String) {
        val baos = ByteArrayOutputStream()
        Amf0.writeString(baos, "publish")
        Amf0.writeNumber(baos, 5.0)
        Amf0.writeNull(baos)
        Amf0.writeString(baos, streamKey)
        Amf0.writeString(baos, "live")

        writePacketDirect(RtmpPacket(RtmpPacket.TYPE_COMMAND_AMF0, RtmpPacket.CSID_COMMAND, streamId, 0, baos.toByteArray()))
    }

    private fun sendMetaData(width: Int, height: Int, fps: Int, videoBitrateKbps: Int) {
        val baos = ByteArrayOutputStream()
        Amf0.writeString(baos, "@setDataFrame")
        Amf0.writeString(baos, "onMetaData")

        val meta = linkedMapOf<String, Any?>(
            "duration" to 0.0,
            "width" to width.toDouble(),
            "height" to height.toDouble(),
            "videodatarate" to videoBitrateKbps.toDouble(),
            "framerate" to fps.toDouble(),
            "videocodecid" to 7.0, // AVC
            "audiodatarate" to 128.0,
            "audiosamplerate" to 48000.0,
            "audiosamplesize" to 16.0,
            "stereo" to false,
            "audiocodecid" to 10.0 // AAC
        )
        Amf0.writeEcmaArray(baos, meta)

        val packet = RtmpPacket(
            messageType = RtmpPacket.TYPE_DATA_AMF0,
            chunkStreamId = RtmpPacket.CSID_COMMAND,
            messageStreamId = streamId,
            timestamp = 0,
            data = baos.toByteArray()
        )
        writePacketDirect(packet)
    }

    override fun setSpsPps(sps: ByteArray, pps: ByteArray) {
        this.spsBytes = sps
        this.ppsBytes = pps
        defaultVideoPacketizer.setSpsPps(sps, pps)
        if (isStreamingFlag.get() && !hasSentVideoHeader) {
            defaultVideoPacketizer.buildSequenceHeader()?.let { packet ->
                sendFlvVideoPacket(packet)
                hasSentVideoHeader = true
                Log.i(TAG, "AVC sequence header sent")
            }
        }
    }

    override fun sendAudioConfig(config: AudioSpecificConfig) {
        defaultAudioPacketizer.setAudioConfig(config)
        if (isStreamingFlag.get() && !hasSentAudioHeader) {
            defaultAudioPacketizer.buildSequenceHeader()?.let { packet ->
                sendFlvAudioPacket(packet)
                hasSentAudioHeader = true
                Log.i(TAG, "AAC sequence header sent")
            }
        }
    }

    override fun sendAudioSequenceHeader(sampleRate: Int, channelCount: Int) {
        defaultAudioPacketizer.setAudioConfig(sampleRate, channelCount)
        if (isStreamingFlag.get() && !hasSentAudioHeader) {
            defaultAudioPacketizer.buildSequenceHeader()?.let { packet ->
                sendFlvAudioPacket(packet)
                hasSentAudioHeader = true
                Log.i(TAG, "AAC sequence header sent")
            }
        }
    }

    override fun sendFlvVideoPacket(packet: FlvVideoPacket) {
        if (!isStreamingFlag.get()) return

        val rtmpPacket = RtmpPacket(
            messageType = RtmpPacket.TYPE_VIDEO,
            chunkStreamId = RtmpPacket.CSID_VIDEO,
            messageStreamId = streamId,
            timestamp = packet.timestampMs,
            data = packet.payload,
            isKeyframe = packet.isKeyframe
        )
        videoPacketsSent.incrementAndGet()
        val totalBytes = videoBytesSent.addAndGet(packet.payload.size.toLong())
        Log.d(TAG, "VIDEO_PACKET_SENT size=${packet.payload.size} totalBytes=$totalBytes key=${packet.isKeyframe}")
        enqueuePacket(rtmpPacket)
    }

    override fun sendFlvAudioPacket(packet: FlvAudioPacket) {
        if (!isStreamingFlag.get()) return

        val rtmpPacket = RtmpPacket(
            messageType = RtmpPacket.TYPE_AUDIO,
            chunkStreamId = RtmpPacket.CSID_AUDIO,
            messageStreamId = streamId,
            timestamp = packet.timestampMs,
            data = packet.payload,
            isKeyframe = packet.isConfig
        )
        audioPacketsSent.incrementAndGet()
        val totalBytes = audioBytesSent.addAndGet(packet.payload.size.toLong())
        Log.d(TAG, "AUDIO_PACKET_SENT size=${packet.payload.size} totalBytes=$totalBytes")
        enqueuePacket(rtmpPacket)
    }

    override fun sendVideo(frame: EncodedVideoFrame) {
        if (!isStreamingFlag.get()) return
        val packets = defaultVideoPacketizer.packetize(frame)
        for (packet in packets) {
            sendFlvVideoPacket(packet)
            if (packet.isSequenceHeader) {
                hasSentVideoHeader = true
                Log.i(TAG, "AVC sequence header sent")
            }
        }
    }

    override fun sendVideo(nalData: ByteArray, isKeyframe: Boolean, timestampMs: Long) {
        if (!isStreamingFlag.get()) return
        val frame = EncodedVideoFrame(
            nalData = nalData,
            isKeyframe = isKeyframe,
            isConfig = false,
            timestampUs = timestampMs * 1000L,
            size = nalData.size
        )
        sendVideo(frame)
    }

    override fun sendAudio(frame: EncodedAudioFrame) {
        if (!isStreamingFlag.get()) return
        val packets = defaultAudioPacketizer.packetize(frame)
        for (packet in packets) {
            sendFlvAudioPacket(packet)
            if (packet.isSequenceHeader) {
                hasSentAudioHeader = true
                Log.i(TAG, "AAC sequence header sent")
            }
        }
    }

    override fun sendAudio(aacData: ByteArray, timestampMs: Long) {
        if (!isStreamingFlag.get()) return
        val frame = EncodedAudioFrame(
            aacData = aacData,
            isConfig = false,
            timestampUs = timestampMs * 1000L,
            sampleRate = 48000,
            channelCount = 1
        )
        sendAudio(frame)
    }

    private fun enqueuePacket(packet: RtmpPacket) {
        if (!isStreamingFlag.get()) return

        // Non-blocking attempt to insert
        if (packetQueue.offer(packet)) {
            return
        }

        // Queue is congested: drop oldest non-keyframe video packet to preserve audio and keyframes
        synchronized(packetQueue) {
            val iterator = packetQueue.iterator()
            var droppedVideo = false
            while (iterator.hasNext()) {
                val candidate = iterator.next()
                // Only drop non-keyframe video packets
                if (candidate.messageType == RtmpPacket.TYPE_VIDEO && !candidate.isKeyframe) {
                    iterator.remove()
                    droppedFramesCount.incrementAndGet()
                    listener.onDroppedFrame()
                    droppedVideo = true
                    Log.w(TAG, "RTMP queue congested: dropped stale non-keyframe video packet (ts=${candidate.timestamp})")
                    break
                }
            }

            if (!droppedVideo) {
                // If all queued packets are audio or keyframes, drop oldest non-audio packet if any
                val secondPass = packetQueue.iterator()
                while (secondPass.hasNext()) {
                    val candidate = secondPass.next()
                    if (candidate.messageType != RtmpPacket.TYPE_AUDIO) {
                        secondPass.remove()
                        droppedFramesCount.incrementAndGet()
                        listener.onDroppedFrame()
                        Log.w(TAG, "RTMP queue congested: dropped video packet (key=${candidate.isKeyframe})")
                        break
                    }
                }
            }

            packetQueue.offer(packet)
        }
    }

    private fun startSenderLoop() {
        senderThread = Thread {
            Log.d(TAG, "RTMP sender thread started")
            while (isStreamingFlag.get() && !Thread.currentThread().isInterrupted) {
                try {
                    val packet = packetQueue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                    writePacketDirect(packet)
                    if (lastSuccessfulStage != "MEDIA_TRANSMITTING" &&
                        (packet.messageType == RtmpPacket.TYPE_VIDEO || packet.messageType == RtmpPacket.TYPE_AUDIO)) {
                        lastSuccessfulStage = "MEDIA_TRANSMITTING"
                    }
                    updateStats(packet.data.size)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                } catch (e: Exception) {
                    if (isStreamingFlag.get()) {
                        val cleanError = mapToUserFriendlyError(e)
                        Log.e(TAG, "Error in RTMP sender loop: $cleanError")
                        reportFailure("TRANSMISSION", cleanError)
                    }
                    break
                }
            }
            Log.d(TAG, "RTMP sender thread exited")
        }.apply {
            name = "rtmp-sender"
            start()
        }
    }

    @Synchronized
    private fun writePacketDirect(packet: RtmpPacket) {
        val out = outputStream ?: return
        val payload = packet.data
        val payloadSize = payload.size
        var offset = 0
        var isFirstChunk = true

        val csid = packet.chunkStreamId
        val msgType = packet.messageType
        val timestamp = packet.timestamp
        val hasExtendedTimestamp = timestamp >= 0xFFFFFFL

        while (offset < payloadSize) {
            val remaining = payloadSize - offset
            val currentChunkLength = Math.min(remaining, chunkSize)

            if (isFirstChunk) {
                // Chunk Header Type 0 (11 bytes header)
                out.write(csid and 0x3F)

                // 3 bytes Timestamp
                val ts = if (hasExtendedTimestamp) 0xFFFFFFL else timestamp
                out.write(((ts shr 16) and 0xFF).toInt())
                out.write(((ts shr 8) and 0xFF).toInt())
                out.write((ts and 0xFF).toInt())

                // 3 bytes Message Length
                out.write((payloadSize shr 16) and 0xFF)
                out.write((payloadSize shr 8) and 0xFF)
                out.write(payloadSize and 0xFF)

                // 1 byte Message Type ID
                out.write(msgType.toInt())

                // 4 bytes Message Stream ID (Little Endian)
                out.write(packet.messageStreamId and 0xFF)
                out.write((packet.messageStreamId shr 8) and 0xFF)
                out.write((packet.messageStreamId shr 16) and 0xFF)
                out.write((packet.messageStreamId shr 24) and 0xFF)

                if (hasExtendedTimestamp) {
                    out.write(((timestamp shr 24) and 0xFF).toInt())
                    out.write(((timestamp shr 16) and 0xFF).toInt())
                    out.write(((timestamp shr 8) and 0xFF).toInt())
                    out.write((timestamp and 0xFF).toInt())
                }

                isFirstChunk = false
            } else {
                // Chunk Header Type 3 (continuation)
                out.write(0xC0 or (csid and 0x3F))
                if (hasExtendedTimestamp) {
                    out.write(((timestamp shr 24) and 0xFF).toInt())
                    out.write(((timestamp shr 16) and 0xFF).toInt())
                    out.write(((timestamp shr 8) and 0xFF).toInt())
                    out.write((timestamp and 0xFF).toInt())
                }
            }

            out.write(payload, offset, currentChunkLength)
            offset += currentChunkLength
        }
        out.flush()
    }

    private fun updateStats(bytesJustSent: Int) {
        val total = totalBytesSent.addAndGet(bytesJustSent.toLong())
        val now = System.currentTimeMillis()
        val elapsed = now - lastBitrateCalcTime
        if (elapsed >= 1000) {
            val bytesInWindow = total - lastBytesSentSnapshot
            val bitrateBps = (bytesInWindow * 8 * 1000) / elapsed
            val bitrateKbps = (bitrateBps / 1000).toInt()

            lastBitrateCalcTime = now
            lastBytesSentSnapshot = total

            listener.onStatsUpdated(
                bitrateKbps = bitrateKbps,
                droppedFrames = droppedFramesCount.get(),
                totalBytesSent = total,
                videoPackets = videoPacketsSent.get(),
                audioPackets = audioPacketsSent.get()
            )
        }
    }

    override fun disconnect() {
        updateState(RtmpConnectionState.DISCONNECTING)
        closeInternal()
        updateState(RtmpConnectionState.DISCONNECTED)
        Log.i(TAG, "RTMP disconnected cleanly")
        listener.onDisconnected()
    }

    private fun closeInternal() {
        isStreamingFlag.set(false)
        isConnectedFlag.set(false)
        senderThread?.interrupt()
        senderThread = null
        readerThread?.interrupt()
        readerThread = null
        packetQueue.clear()

        try {
            outputStream?.close()
        } catch (_: Exception) {}
        try {
            inputStream?.close()
        } catch (_: Exception) {}
        try {
            socket?.close()
        } catch (_: Exception) {}

        outputStream = null
        inputStream = null
        socket = null
        hasSentVideoHeader = false
        hasSentAudioHeader = false
        spsBytes = null
        ppsBytes = null
        defaultVideoPacketizer.reset()
        defaultAudioPacketizer.reset()
    }

    companion object {
        private const val TAG = "VJStream/RTMP"
        private const val DEFAULT_CHUNK_SIZE = 4096
        private const val CONNECT_TIMEOUT_MS = 10000
        private const val SOCKET_TIMEOUT_MS = 10000
        private const val QUEUE_CAPACITY = 120
    }
}

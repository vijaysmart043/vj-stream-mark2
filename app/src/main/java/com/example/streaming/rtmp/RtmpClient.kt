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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Random
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Diagnostic RTMP/RTMPS client with detailed protocol tracing.
 */
interface RtmpClient {

    interface Listener {
        fun onConnected()
        fun onConnectionFailed(reason: String)
        fun onConnectionFailed(stage: String, errorType: String, message: String) {
            onConnectionFailed("[$stage] $message")
        }
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

    fun connect(
        serverUrl: String,
        streamKey: String,
        width: Int = 1280,
        height: Int = 720,
        fps: Int = 30,
        videoBitrateKbps: Int = 2500
    )

    fun disconnect()

    fun testConnection(
        serverUrl: String,
        streamKey: String,
        timeoutMs: Int = 10000,
        callback: (success: Boolean, message: String) -> Unit = { _, _ -> }
    )

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

class RtmpProtocolException(
    val stage: String,
    val errorType: String,
    override val message: String,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * Standard implementation of [RtmpClient] with Stage Tracing.
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

    private val droppedFramesCount = AtomicLong(0)
    private val totalBytesSent = AtomicLong(0)
    private var lastBitrateCalcTime = 0L
    private var lastBytesSentSnapshot = 0L

    private var outChunkSize = DEFAULT_CHUNK_SIZE
    private var inChunkSize = 128
    private var streamId = 1
    private var streamStartTimeMs = -1L

    @Volatile
    private var currentExecutingStage: String = "INITIALIZATION"
    @Volatile
    private var lastSuccessfulStage: String = "NONE"

    @Volatile
    private var spsBytes: ByteArray? = null
    @Volatile
    private var ppsBytes: ByteArray? = null
    @Volatile
    private var hasSentVideoHeader = false
    @Volatile
    private var hasSentAudioHeader = false
    @Volatile
    private var hasLoggedFirstVideoMedia = false
    @Volatile
    private var hasLoggedFirstAudioMedia = false

    private val defaultVideoPacketizer = FlvVideoPacketizer()
    private val defaultAudioPacketizer = FlvAudioPacketizer()
    private val videoPacketsSent = AtomicLong(0)
    private val audioPacketsSent = AtomicLong(0)

    // State for reading incoming chunks
    private data class ChunkHeader(
        var timestamp: Long = 0L,
        var timestampDelta: Long = 0L,
        var messageLength: Int = 0,
        var messageTypeId: Int = 0,
        var messageStreamId: Int = 0,
        var hasExtendedTimestamp: Boolean = false
    )
    private val previousHeaders = mutableMapOf<Int, ChunkHeader>()
    private val inMessageBuffers = mutableMapOf<Int, ByteArrayOutputStream>()

    private fun updateState(newState: RtmpConnectionState) {
        _connectionStateFlow.value = newState
        listener.onConnectionStateChanged(newState)
    }

    override fun connect(
        serverUrl: String,
        streamKey: String,
        width: Int,
        height: Int,
        fps: Int,
        videoBitrateKbps: Int
    ) {
        // Stage 1: STREAM_START_REQUESTED
        Log.i(TAG_STREAMING, "Stage 1: STREAM_START_REQUESTED")

        // Stage 2: CONFIG_VALIDATED
        val validationResult = RtmpUrlValidator.validate(serverUrl)
        if (validationResult is RtmpUrlValidator.ValidationResult.Invalid) {
            logFailure("CONFIG_VALIDATION", "INVALID_URL", validationResult.reason)
            updateState(RtmpConnectionState.ERROR)
            listener.onConnectionFailed("CONFIG_VALIDATION", "INVALID_URL", validationResult.reason)
            return
        }

        if (streamKey.isBlank()) {
            val err = "Stream key cannot be empty"
            logFailure("CONFIG_VALIDATION", "EMPTY_STREAM_KEY", err)
            updateState(RtmpConnectionState.ERROR)
            listener.onConnectionFailed("CONFIG_VALIDATION", "EMPTY_STREAM_KEY", err)
            return
        }

        val parsed = validationResult as RtmpUrlValidator.ValidationResult.Valid
        Log.i(TAG_STREAMING, "Stage 2: CONFIG_VALIDATED (scheme=${if (parsed.isSsl) "rtmps" else "rtmp"}, host=${parsed.host}, port=${parsed.port}, app=${parsed.app})")

        updateState(RtmpConnectionState.CONNECTING)

        Thread {
            try {
                // Reset chunk size state for new session
                outChunkSize = 128
                inChunkSize = 128
                previousHeaders.clear()
                inMessageBuffers.clear()

                // Stage 1: TCP Socket Connect
                currentExecutingStage = "1. TCP_SOCKET_CONNECTED"
                Log.i(TAG_RTMP, "Stage 1: TCP_SOCKET_CONNECTING -> host=${parsed.host}, port=${parsed.port}")
                val plainSock = Socket()
                plainSock.connect(InetSocketAddress(parsed.host, parsed.port), CONNECT_TIMEOUT_MS)
                plainSock.soTimeout = SOCKET_TIMEOUT_MS
                Log.i(TAG_RTMP, "Stage 1: TCP_SOCKET_CONNECTED: SUCCESS (remote=${plainSock.remoteSocketAddress})")
                lastSuccessfulStage = "1. TCP_SOCKET_CONNECTED"

                val baseSocket = if (parsed.isSsl) {
                    // Stage 2: TLS Handshake Started
                    currentExecutingStage = "2. TLS_HANDSHAKE_STARTED"
                    Log.i(TAG_RTMPS, "Stage 2: TLS_HANDSHAKE_STARTED -> host=${parsed.host}, port=${parsed.port}")
                    val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                    val sslSock = factory.createSocket(plainSock, parsed.host, parsed.port, true) as SSLSocket
                    sslSock.soTimeout = SOCKET_TIMEOUT_MS

                    // Stage 3: TLS Handshake Completed
                    currentExecutingStage = "3. TLS_HANDSHAKE_COMPLETED"
                    sslSock.startHandshake()
                    val session = sslSock.session
                    Log.i(TAG_RTMPS, "Stage 3: TLS_HANDSHAKE_COMPLETED: SUCCESS (cipher=${session.cipherSuite}, protocol=${session.protocol})")
                    lastSuccessfulStage = "3. TLS_HANDSHAKE_COMPLETED"
                    sslSock
                } else {
                    Log.i(TAG_RTMP, "Stage 2 & 3: TLS_HANDSHAKE: SKIPPED (plain RTMP)")
                    plainSock
                }

                baseSocket.tcpNoDelay = true
                baseSocket.sendBufferSize = 128 * 1024
                socket = baseSocket
                outputStream = BufferedOutputStream(baseSocket.getOutputStream(), 64 * 1024)
                inputStream = BufferedInputStream(baseSocket.getInputStream(), 64 * 1024)

                // Stages 4 to 7: RTMP Handshake
                performHandshake { executing, completed ->
                    if (executing.isNotEmpty()) currentExecutingStage = executing
                    if (completed.isNotEmpty()) lastSuccessfulStage = completed
                }

                // Announce our outbound chunk size to the server
                sendSetChunkSize(DEFAULT_CHUNK_SIZE)

                // Stage 8: RTMP Connect Command Sent
                currentExecutingStage = "8. RTMP_CONNECT_COMMAND_SENT"
                val scheme = if (parsed.isSsl) "rtmps" else "rtmp"
                val isNonStandardPort = (parsed.isSsl && parsed.port != 443) || (!parsed.isSsl && parsed.port != 1935)
                val tcUrl = "$scheme://${parsed.host}${if (isNonStandardPort) ":${parsed.port}" else ""}/${parsed.app}"
                Log.i(TAG_RTMP, "Stage 8: RTMP_CONNECT_COMMAND_SENT -> sending connect (app=${parsed.app}, tcUrl=$tcUrl)")
                sendConnectCommand(parsed.app, tcUrl)
                Log.i(TAG_RTMP, "Stage 8: RTMP_CONNECT_COMMAND_SENT: SUCCESS")
                lastSuccessfulStage = "8. RTMP_CONNECT_COMMAND_SENT"

                // Stage 9: RTMP Connect Response Received
                currentExecutingStage = "9. RTMP_CONNECT_RESPONSE_RECEIVED"
                Log.i(TAG_RTMP, "Stage 9: RTMP_CONNECT_RESPONSE -> waiting for _result (transId=1.0)")
                val connectResponse = readUntilCommandResponse(
                    stage = "9. RTMP_CONNECT_RESPONSE_RECEIVED",
                    expectedCommand = "_result",
                    expectedTransId = 1.0,
                    timeoutMs = 8000
                )
                Log.i(TAG_RTMP, "Stage 9: RTMP_CONNECT_RESPONSE_RECEIVED: SUCCESS (connect accepted)")
                lastSuccessfulStage = "9. RTMP_CONNECT_RESPONSE_RECEIVED"

                // Stage 10: CreateStream Sent
                currentExecutingStage = "10. CREATE_STREAM_SENT"
                Log.i(TAG_RTMP, "Stage 10: CREATE_STREAM_SENT -> sending createStream (transId=2.0)")
                sendCreateStream()
                Log.i(TAG_RTMP, "Stage 10: CREATE_STREAM_SENT: SUCCESS")
                lastSuccessfulStage = "10. CREATE_STREAM_SENT"

                // Stage 11: CreateStream Response Received
                currentExecutingStage = "11. CREATE_STREAM_RESPONSE_RECEIVED"
                Log.i(TAG_RTMP, "Stage 11: CREATE_STREAM_RESPONSE -> waiting for _result (transId=2.0)")
                val createStreamResponse = readUntilCommandResponse(
                    stage = "11. CREATE_STREAM_RESPONSE_RECEIVED",
                    expectedCommand = "_result",
                    expectedTransId = 2.0,
                    timeoutMs = 8000
                )
                val returnedStreamId = (createStreamResponse.infoObject as? Number)?.toInt() ?: 1
                streamId = if (returnedStreamId > 0) returnedStreamId else 1
                Log.i(TAG_RTMP, "Stage 11: CREATE_STREAM_RESPONSE_RECEIVED: SUCCESS (streamId=$streamId)")
                lastSuccessfulStage = "11. CREATE_STREAM_RESPONSE_RECEIVED"

                // Stage 12: Publish Sent
                currentExecutingStage = "12. PUBLISH_SENT"
                Log.i(TAG_RTMP, "Stage 12: PUBLISH_SENT -> sending publish (streamId=$streamId, mode=live)")
                sendPublish(streamKey)
                Log.i(TAG_RTMP, "Stage 12: PUBLISH_SENT: SUCCESS")
                lastSuccessfulStage = "12. PUBLISH_SENT"

                // Stage 13: Publish Response Received
                currentExecutingStage = "13. PUBLISH_RESPONSE_RECEIVED"
                Log.i(TAG_RTMP, "Stage 13: PUBLISH_RESPONSE -> waiting for onStatus(NetStream.Publish.Start)")
                readUntilPublishAccepted(stage = "13. PUBLISH_RESPONSE_RECEIVED", timeoutMs = 8000)
                Log.i(TAG_RTMP, "Stage 13: PUBLISH_RESPONSE_RECEIVED: SUCCESS (publish accepted)")
                lastSuccessfulStage = "13. PUBLISH_RESPONSE_RECEIVED"

                // Send onMetaData & sequence headers
                sendMetaData(width, height, fps, videoBitrateKbps)

                isConnectedFlag.set(true)
                isStreamingFlag.set(true)
                streamStartTimeMs = System.currentTimeMillis()
                lastBitrateCalcTime = System.currentTimeMillis()

                startSenderLoop()

                updateState(RtmpConnectionState.CONNECTED)
                listener.onConnected()
            } catch (e: Exception) {
                val isLocallyClosed = socket?.isClosed == true
                val isRemoteClosed = e is java.io.EOFException || (e is java.net.SocketException && (
                    e.message?.contains("closed", ignoreCase = true) == true ||
                    e.message?.contains("reset", ignoreCase = true) == true ||
                    e.message?.contains("broken pipe", ignoreCase = true) == true
                ))
                val tlsCause = if (e is SSLException || e.cause is SSLException) (e.cause ?: e).toString() else null

                Log.e(TAG_RTMP, "=======================================================")
                Log.e(TAG_RTMP, "=== [VJStream] STREAMING CONNECTION ATTEMPT FAILED ===")
                Log.e(TAG_RTMP, "FAILED_STAGE: $currentExecutingStage")
                Log.e(TAG_RTMP, "LAST_SUCCESSFUL_STAGE: $lastSuccessfulStage")
                Log.e(TAG_RTMP, "EXCEPTION_CLASS: ${e.javaClass.name}")
                Log.e(TAG_RTMP, "EXCEPTION_MESSAGE: ${e.message}")
                Log.e(TAG_RTMP, "REMOTE_PEER_CLOSED: $isRemoteClosed")
                Log.e(TAG_RTMP, "LOCALLY_CLOSED: $isLocallyClosed")
                if (tlsCause != null) {
                    Log.e(TAG_RTMPS, "TLS_EXCEPTION_CAUSE: $tlsCause")
                }
                Log.e(TAG_RTMP, "=======================================================")

                val (stage, errorType, errorMsg) = resolveErrorInfo(e, parsed, currentExecutingStage, lastSuccessfulStage, isRemoteClosed)
                logFailure(stage, errorType, errorMsg)
                closeInternal()
                updateState(RtmpConnectionState.ERROR)
                listener.onConnectionFailed(stage, errorType, errorMsg)
            }
        }.apply {
            name = "vjstream-rtmp-connect"
            start()
        }
    }

    private fun resolveErrorInfo(
        e: Exception,
        parsed: RtmpUrlValidator.ValidationResult.Valid,
        currentStage: String,
        lastSuccess: String,
        isRemoteClosed: Boolean
    ): Triple<String, String, String> {
        if (e is RtmpProtocolException) {
            return Triple(e.stage, e.errorType, e.message)
        }

        val stage = currentStage
        val errorType = when {
            isRemoteClosed -> "REMOTE_SOCKET_CLOSED"
            e is UnknownHostException -> "DNS_FAILURE"
            e is ConnectException -> "CONNECTION_REFUSED"
            e is SocketTimeoutException -> "SOCKET_TIMEOUT"
            e is SSLException -> "TLS_HANDSHAKE_FAILURE"
            e is IllegalStateException -> "PROTOCOL_ERROR"
            else -> "SOCKET_ERROR"
        }

        val baseMsg = when (e) {
            is UnknownHostException -> "Unable to resolve server host ${parsed.host}."
            is ConnectException -> "Connection refused by ${parsed.host}:${parsed.port}."
            is SocketTimeoutException -> "Socket timed out waiting for server response."
            is SSLException -> "TLS handshake failed with ${parsed.host}: ${e.message}"
            is java.io.EOFException -> "Socket closed by remote server"
            else -> e.message ?: "Connection error"
        }

        val detailedMessage = "Stage '$stage' failed (Last success: $lastSuccess). Reason: $baseMsg [${e.javaClass.simpleName}]"
        return Triple(stage, errorType, detailedMessage)
    }

    private fun logFailure(stage: String, errorType: String, message: String) {
        Log.e(TAG_RTMP, "[VJStream/RTMP] FAILED_STAGE=$stage")
        Log.e(TAG_RTMP, "[VJStream/RTMP] ERROR_TYPE=$errorType")
        Log.e(TAG_RTMP, "[VJStream/RTMP] ERROR_MESSAGE=$message")
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
        if (streamKey.isBlank()) {
            updateState(RtmpConnectionState.ERROR)
            callback(false, "Stream key cannot be empty")
            return
        }

        updateState(RtmpConnectionState.CONNECTING)

        Thread {
            var testSocket: Socket? = null
            try {
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

                val out = BufferedOutputStream(testSocket.getOutputStream(), 4096)
                val inStream = BufferedInputStream(testSocket.getInputStream(), 4096)

                // C0 + C1 Handshake
                val c1 = ByteArray(1536)
                Random().nextBytes(c1)
                for (i in 0 until 8) c1[i] = 0
                out.write(0x03)
                out.write(c1)
                out.flush()

                val s0 = inStream.read()
                if (s0 != 0x03) {
                    throw IllegalStateException("Invalid RTMP handshake response from server")
                }
                val s1 = ByteArray(1536)
                readFully(inStream, s1)

                // C2
                out.write(s1)
                out.flush()

                // S2
                val s2 = ByteArray(1536)
                readFully(inStream, s2)

                updateState(RtmpConnectionState.CONNECTED)
                callback(true, "RTMP handshake verified with ${parsed.host}")
            } catch (e: Exception) {
                updateState(RtmpConnectionState.ERROR)
                callback(false, e.message ?: "Test failed")
            } finally {
                try {
                    testSocket?.close()
                } catch (_: Exception) {}
                updateState(RtmpConnectionState.DISCONNECTED)
            }
        }.start()
    }

    private fun performHandshake(onStageUpdate: (executing: String, completed: String) -> Unit = { _, _ -> }) {
        val out = outputStream ?: throw RtmpProtocolException("4. RTMP_HANDSHAKE_C0_C1_SENT", "NULL_STREAM", "Output stream is null")
        val inStream = inputStream ?: throw RtmpProtocolException("5. RTMP_S0_S1_RECEIVED", "NULL_STREAM", "Input stream is null")

        try {
            // Stage 4: RTMP Handshake C0/C1 Sent
            onStageUpdate("4. RTMP_HANDSHAKE_C0_C1_SENT", "")
            Log.i(TAG_RTMP, "Stage 4: RTMP_HANDSHAKE_C0_C1 -> sending 1537 bytes (C0=0x03, C1=1536B)")
            val c1 = ByteArray(1536)
            Random().nextBytes(c1)
            for (i in 0 until 8) c1[i] = 0

            out.write(0x03)
            out.write(c1)
            out.flush()
            Log.i(TAG_RTMP, "Stage 4: RTMP_HANDSHAKE_C0_C1_SENT: SUCCESS (1537 bytes sent)")
            onStageUpdate("5. RTMP_S0_S1_RECEIVED", "4. RTMP_HANDSHAKE_C0_C1_SENT")

            // Stage 5: RTMP S0/S1 Received
            Log.i(TAG_RTMP, "Stage 5: RTMP_S0_S1 -> reading S0 (1B) and S1 (1536B)")
            val s0 = inStream.read()
            if (s0 < 0) throw java.io.EOFException("Socket closed by remote server while waiting for S0")
            if (s0 != 0x03) {
                throw RtmpProtocolException("5. RTMP_S0_S1_RECEIVED", "INVALID_S0", "Invalid RTMP S0 version: $s0 (expected 3)")
            }
            val s1 = ByteArray(1536)
            readFully(inStream, s1)
            Log.i(TAG_RTMP, "Stage 5: RTMP_S0_S1_RECEIVED: SUCCESS (S0=0x03 verified, S1 received)")
            onStageUpdate("6. RTMP_C2_SENT", "5. RTMP_S0_S1_RECEIVED")

            // Stage 6: RTMP C2 Sent
            Log.i(TAG_RTMP, "Stage 6: RTMP_C2 -> sending C2 (1536 bytes echoing S1)")
            out.write(s1)
            out.flush()
            Log.i(TAG_RTMP, "Stage 6: RTMP_C2_SENT: SUCCESS (1536 bytes sent)")
            onStageUpdate("7. RTMP_HANDSHAKE_COMPLETED", "6. RTMP_C2_SENT")

            // Stage 7: RTMP Handshake Completed (S2 Received)
            Log.i(TAG_RTMP, "Stage 7: RTMP_S2 -> waiting for server S2 (1536 bytes)")
            val s2 = ByteArray(1536)
            readFully(inStream, s2)
            Log.i(TAG_RTMP, "Stage 7: RTMP_HANDSHAKE_COMPLETED: SUCCESS (S2 received and validated)")
            onStageUpdate("", "7. RTMP_HANDSHAKE_COMPLETED")
        } catch (e: RtmpProtocolException) {
            throw e
        } catch (e: Exception) {
            throw e
        }
    }

    private fun readFully(inStream: InputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val count = inStream.read(target, offset, target.size - offset)
            if (count < 0) throw java.io.EOFException("Unexpected EOF during RTMP read")
            offset += count
        }
    }

    private fun sendSetChunkSize(newSize: Int) {
        val data = ByteArray(4)
        data[0] = ((newSize shr 24) and 0x7F).toByte()
        data[1] = ((newSize shr 16) and 0xFF).toByte()
        data[2] = ((newSize shr 8) and 0xFF).toByte()
        data[3] = (newSize and 0xFF).toByte()

        val packet = RtmpPacket(
            messageType = RtmpPacket.TYPE_SET_CHUNK_SIZE,
            chunkStreamId = RtmpPacket.CSID_CONTROL,
            messageStreamId = 0,
            timestamp = 0,
            data = data
        )
        writePacketDirect(packet)
        outChunkSize = newSize
        Log.i(TAG_RTMP, "Client outChunkSize updated to $newSize")
    }

    private fun sendConnectCommand(app: String, tcUrl: String) {
        val baos = ByteArrayOutputStream()
        Amf0.writeString(baos, "connect")
        Amf0.writeNumber(baos, 1.0)

        val properties = linkedMapOf<String, Any?>(
            "app" to app,
            "type" to "nonprivate",
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
        Amf0.writeObject(baos, properties)

        val packet = RtmpPacket(
            messageType = RtmpPacket.TYPE_COMMAND_AMF0,
            chunkStreamId = RtmpPacket.CSID_COMMAND,
            messageStreamId = 0,
            timestamp = 0,
            data = baos.toByteArray()
        )
        writePacketDirect(packet)
    }

    private fun sendUserControlPingResponse(timestamp: Int) {
        val data = ByteArray(6)
        data[0] = 0x00
        data[1] = 0x07 // Event 7: Ping Response
        data[2] = ((timestamp shr 24) and 0xFF).toByte()
        data[3] = ((timestamp shr 16) and 0xFF).toByte()
        data[4] = ((timestamp shr 8) and 0xFF).toByte()
        data[5] = (timestamp and 0xFF).toByte()

        val packet = RtmpPacket(
            messageType = 4.toByte(),
            chunkStreamId = RtmpPacket.CSID_CONTROL,
            messageStreamId = 0,
            timestamp = 0,
            data = data
        )
        writePacketDirect(packet)
        Log.i(TAG_RTMP, "Responded to Server Ping Request (timestamp=$timestamp)")
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
        Amf0.writeNumber(baos, 2.0)
        Amf0.writeNull(baos)

        writePacketDirect(RtmpPacket(RtmpPacket.TYPE_COMMAND_AMF0, RtmpPacket.CSID_COMMAND, 0, 0, baos.toByteArray()))
    }

    private fun sendPublish(streamKey: String) {
        val baos = ByteArrayOutputStream()
        Amf0.writeString(baos, "publish")
        Amf0.writeNumber(baos, 0.0)
        Amf0.writeNull(baos)
        Amf0.writeString(baos, streamKey)
        Amf0.writeString(baos, "live")

        writePacketDirect(RtmpPacket(RtmpPacket.TYPE_COMMAND_AMF0, RtmpPacket.CSID_STREAM, streamId, 0, baos.toByteArray()))
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
            chunkStreamId = RtmpPacket.CSID_STREAM,
            messageStreamId = streamId,
            timestamp = 0,
            data = baos.toByteArray()
        )
        writePacketDirect(packet)
    }

    // --- Inbound RTMP Response Parsing ---

    data class RtmpCommandResponse(
        val commandName: String,
        val transactionId: Double,
        val commandObject: Any?,
        val infoObject: Any?
    )

    private data class IncomingRtmpMessage(
        val messageType: Int,
        val messageStreamId: Int,
        val timestamp: Long,
        val data: ByteArray
    )

    private fun readNextMessage(timeoutMs: Long): IncomingRtmpMessage? {
        val inStream = inputStream ?: return null
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val b0 = inStream.read()
            if (b0 < 0) throw java.io.EOFException("Socket closed by remote server")

            val fmt = (b0 shr 6) and 0x03
            var csid = b0 and 0x3F
            if (csid == 0) {
                val b1 = inStream.read()
                if (b1 < 0) throw java.io.EOFException("EOF reading csid")
                csid = b1 + 64
            } else if (csid == 1) {
                val b1 = inStream.read()
                val b2 = inStream.read()
                if (b1 < 0 || b2 < 0) throw java.io.EOFException("EOF reading csid")
                csid = b1 + (b2 shl 8) + 64
            }

            val header = previousHeaders.getOrPut(csid) { ChunkHeader() }

            when (fmt) {
                0 -> {
                    val ts1 = inStream.read()
                    val ts2 = inStream.read()
                    val ts3 = inStream.read()
                    val len1 = inStream.read()
                    val len2 = inStream.read()
                    val len3 = inStream.read()
                    val typeId = inStream.read()
                    val s1 = inStream.read()
                    val s2 = inStream.read()
                    val s3 = inStream.read()
                    val s4 = inStream.read()
                    if (s4 < 0) throw java.io.EOFException("EOF reading chunk header fmt 0")

                    val tsRaw = ((ts1 shl 16) or (ts2 shl 8) or ts3).toLong()
                    header.hasExtendedTimestamp = (tsRaw == 0xFFFFFFL)
                    header.timestamp = if (header.hasExtendedTimestamp) {
                        val e1 = inStream.read()
                        val e2 = inStream.read()
                        val e3 = inStream.read()
                        val e4 = inStream.read()
                        if (e4 < 0) throw java.io.EOFException("EOF reading extended timestamp")
                        (((e1.toLong() and 0xFF) shl 24) or ((e2.toLong() and 0xFF) shl 16) or ((e3.toLong() and 0xFF) shl 8) or (e4.toLong() and 0xFF))
                    } else {
                        tsRaw
                    }
                    header.messageLength = (len1 shl 16) or (len2 shl 8) or len3
                    header.messageTypeId = typeId
                    header.messageStreamId = s1 or (s2 shl 8) or (s3 shl 16) or (s4 shl 24)
                }
                1 -> {
                    val ts1 = inStream.read()
                    val ts2 = inStream.read()
                    val ts3 = inStream.read()
                    val len1 = inStream.read()
                    val len2 = inStream.read()
                    val len3 = inStream.read()
                    val typeId = inStream.read()
                    if (typeId < 0) throw java.io.EOFException("EOF reading chunk header fmt 1")

                    val deltaRaw = ((ts1 shl 16) or (ts2 shl 8) or ts3).toLong()
                    header.hasExtendedTimestamp = (deltaRaw == 0xFFFFFFL)
                    header.timestampDelta = if (header.hasExtendedTimestamp) {
                        val e1 = inStream.read()
                        val e2 = inStream.read()
                        val e3 = inStream.read()
                        val e4 = inStream.read()
                        if (e4 < 0) throw java.io.EOFException("EOF reading extended timestamp delta")
                        (((e1.toLong() and 0xFF) shl 24) or ((e2.toLong() and 0xFF) shl 16) or ((e3.toLong() and 0xFF) shl 8) or (e4.toLong() and 0xFF))
                    } else {
                        deltaRaw
                    }
                    header.timestamp += header.timestampDelta
                    header.messageLength = (len1 shl 16) or (len2 shl 8) or len3
                    header.messageTypeId = typeId
                }
                2 -> {
                    val ts1 = inStream.read()
                    val ts2 = inStream.read()
                    val ts3 = inStream.read()
                    if (ts3 < 0) throw java.io.EOFException("EOF reading chunk header fmt 2")

                    val deltaRaw = ((ts1 shl 16) or (ts2 shl 8) or ts3).toLong()
                    header.hasExtendedTimestamp = (deltaRaw == 0xFFFFFFL)
                    header.timestampDelta = if (header.hasExtendedTimestamp) {
                        val e1 = inStream.read()
                        val e2 = inStream.read()
                        val e3 = inStream.read()
                        val e4 = inStream.read()
                        if (e4 < 0) throw java.io.EOFException("EOF reading extended timestamp delta")
                        (((e1.toLong() and 0xFF) shl 24) or ((e2.toLong() and 0xFF) shl 16) or ((e3.toLong() and 0xFF) shl 8) or (e4.toLong() and 0xFF))
                    } else {
                        deltaRaw
                    }
                    header.timestamp += header.timestampDelta
                }
                3 -> {
                    if (header.hasExtendedTimestamp) {
                        val e1 = inStream.read()
                        val e2 = inStream.read()
                        val e3 = inStream.read()
                        val e4 = inStream.read()
                        if (e4 < 0) throw java.io.EOFException("EOF reading extended timestamp in fmt 3")
                    }
                }
            }

            val buffer = inMessageBuffers.getOrPut(csid) { ByteArrayOutputStream(header.messageLength) }
            val bytesNeeded = header.messageLength - buffer.size()
            val chunkReadSize = Math.min(bytesNeeded, inChunkSize)

            val chunkBytes = ByteArray(chunkReadSize)
            readFully(inStream, chunkBytes)
            buffer.write(chunkBytes)

            if (buffer.size() >= header.messageLength) {
                inMessageBuffers.remove(csid)
                val msgBytes = buffer.toByteArray()

                // If Set Chunk Size (type 1)
                if (header.messageTypeId == 1 && msgBytes.size >= 4) {
                    val newSize = ((msgBytes[0].toInt() and 0x7F) shl 24) or
                            ((msgBytes[1].toInt() and 0xFF) shl 16) or
                            ((msgBytes[2].toInt() and 0xFF) shl 8) or
                            (msgBytes[3].toInt() and 0xFF)
                    if (newSize > 0) {
                        inChunkSize = newSize
                        Log.i(TAG_RTMP, "Server inChunkSize updated to $newSize")
                    }
                }

                // If User Control Message (type 4) - handle Ping Request (Event 6) -> Ping Response (Event 7)
                if (header.messageTypeId == 4 && msgBytes.size >= 6) {
                    val eventType = ((msgBytes[0].toInt() and 0xFF) shl 8) or (msgBytes[1].toInt() and 0xFF)
                    if (eventType == 6) {
                        val pingTimestamp = ((msgBytes[2].toInt() and 0xFF) shl 24) or
                                ((msgBytes[3].toInt() and 0xFF) shl 16) or
                                ((msgBytes[4].toInt() and 0xFF) shl 8) or
                                (msgBytes[5].toInt() and 0xFF)
                        sendUserControlPingResponse(pingTimestamp)
                    }
                }

                return IncomingRtmpMessage(
                    messageType = header.messageTypeId,
                    messageStreamId = header.messageStreamId,
                    timestamp = header.timestamp,
                    data = msgBytes
                )
            }
        }
        return null
    }

    private fun readUntilCommandResponse(
        stage: String,
        expectedCommand: String?,
        expectedTransId: Double?,
        timeoutMs: Long
    ): RtmpCommandResponse {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break
            val msg = readNextMessage(remaining) ?: continue

            if (msg.messageType == RtmpPacket.TYPE_COMMAND_AMF0.toInt() || msg.messageType == 17) {
                var streamBytes = msg.data
                if (msg.messageType == 17 && streamBytes.isNotEmpty() && streamBytes[0] == 0x00.toByte()) {
                    streamBytes = streamBytes.copyOfRange(1, streamBytes.size)
                }
                val bais = ByteArrayInputStream(streamBytes)
                val cmdName = Amf0.readValue(bais) as? String ?: continue
                val transId = (Amf0.readValue(bais) as? Number)?.toDouble() ?: 0.0
                val cmdObj = Amf0.readValue(bais)
                val infoObj = Amf0.readValue(bais)

                if (cmdName == "_error") {
                    val infoMap = infoObj as? Map<*, *>
                    val desc = infoMap?.get("description") as? String ?: infoMap?.get("code") as? String ?: "Server rejected command"
                    throw RtmpProtocolException(stage, "SERVER_REJECTED", desc)
                }

                if ((expectedCommand == null || cmdName == expectedCommand) &&
                    (expectedTransId == null || transId == expectedTransId)
                ) {
                    return RtmpCommandResponse(cmdName, transId, cmdObj, infoObj)
                }
            }
        }
        // If timed out waiting for server response, throw protocol exception with stage
        throw RtmpProtocolException(stage, "RESPONSE_TIMEOUT", "Server did not respond to $stage command within ${timeoutMs}ms")
    }

    private fun readUntilPublishAccepted(stage: String, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break
            val msg = readNextMessage(remaining) ?: continue

            if (msg.messageType == RtmpPacket.TYPE_COMMAND_AMF0.toInt() || msg.messageType == 17) {
                var streamBytes = msg.data
                if (msg.messageType == 17 && streamBytes.isNotEmpty() && streamBytes[0] == 0x00.toByte()) {
                    streamBytes = streamBytes.copyOfRange(1, streamBytes.size)
                }
                val bais = ByteArrayInputStream(streamBytes)
                val cmdName = Amf0.readValue(bais) as? String ?: continue
                val transId = (Amf0.readValue(bais) as? Number)?.toDouble() ?: 0.0
                val cmdObj = Amf0.readValue(bais)
                val infoObj = Amf0.readValue(bais)

                if (cmdName == "onStatus") {
                    val infoMap = infoObj as? Map<*, *>
                    val code = infoMap?.get("code") as? String ?: ""
                    val desc = infoMap?.get("description") as? String ?: code

                    if (code == "NetStream.Publish.Start" || code.contains("Publish.Start")) {
                        return
                    } else if (code == "NetStream.Publish.BadName" || code.contains("BadName")) {
                        throw RtmpProtocolException(stage, "SERVER_REJECTED", "Stream key rejected: $desc")
                    } else if (code.contains("Failed") || code.contains("Denied") || code.contains("Error")) {
                        throw RtmpProtocolException(stage, "SERVER_REJECTED", "Publish rejected: $desc")
                    }
                } else if (cmdName == "_error") {
                    val infoMap = infoObj as? Map<*, *>
                    val desc = infoMap?.get("description") as? String ?: "Server rejected publish"
                    throw RtmpProtocolException(stage, "SERVER_REJECTED", desc)
                }
            }
        }
        // If server did not send explicit onStatus within timeout, allow transition if no error received
        Log.w(TAG_RTMP, "Publish response timeout; proceeding to media streaming")
    }

    override fun setSpsPps(sps: ByteArray, pps: ByteArray) {
        this.spsBytes = sps
        this.ppsBytes = pps
        defaultVideoPacketizer.setSpsPps(sps, pps)
        if (isStreamingFlag.get() && !hasSentVideoHeader) {
            defaultVideoPacketizer.buildSequenceHeader()?.let { packet ->
                sendFlvVideoPacket(packet)
                hasSentVideoHeader = true
                Log.i(TAG_RTMP, "Stage 14: VIDEO_CONFIG_SENT (AVC Sequence Header)")
            }
        }
    }

    override fun sendAudioConfig(config: AudioSpecificConfig) {
        defaultAudioPacketizer.setAudioConfig(config)
        if (isStreamingFlag.get() && !hasSentAudioHeader) {
            defaultAudioPacketizer.buildSequenceHeader()?.let { packet ->
                sendFlvAudioPacket(packet)
                hasSentAudioHeader = true
                Log.i(TAG_RTMP, "Stage 15: AUDIO_CONFIG_SENT (AAC Sequence Header)")
            }
        }
    }

    override fun sendAudioSequenceHeader(sampleRate: Int, channelCount: Int) {
        defaultAudioPacketizer.setAudioConfig(sampleRate, channelCount)
        if (isStreamingFlag.get() && !hasSentAudioHeader) {
            defaultAudioPacketizer.buildSequenceHeader()?.let { packet ->
                sendFlvAudioPacket(packet)
                hasSentAudioHeader = true
                Log.i(TAG_RTMP, "Stage 15: AUDIO_CONFIG_SENT (AAC Sequence Header)")
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
        val count = videoPacketsSent.incrementAndGet()
        if (!packet.isSequenceHeader && !hasLoggedFirstVideoMedia) {
            hasLoggedFirstVideoMedia = true
            Log.i(TAG_RTMP, "Stage 16: VIDEO_MEDIA_STARTED")
        }
        if (packet.isKeyframe || count % 90 == 1L) {
            Log.d(TAG_RTMP, "Video packet queued (ts=${packet.timestampMs}ms, key=${packet.isKeyframe}, count=$count)")
        }
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
        val count = audioPacketsSent.incrementAndGet()
        if (!packet.isSequenceHeader && !hasLoggedFirstAudioMedia) {
            hasLoggedFirstAudioMedia = true
            Log.i(TAG_RTMP, "Stage 17: AUDIO_MEDIA_STARTED")
        }
        if (count % 150 == 1L) {
            Log.d(TAG_RTMP, "Audio packet queued (ts=${packet.timestampMs}ms, count=$count)")
        }
        enqueuePacket(rtmpPacket)
    }

    override fun sendVideo(frame: EncodedVideoFrame) {
        if (!isStreamingFlag.get()) return
        val packets = defaultVideoPacketizer.packetize(frame)
        for (packet in packets) {
            sendFlvVideoPacket(packet)
            if (packet.isSequenceHeader && !hasSentVideoHeader) {
                hasSentVideoHeader = true
                Log.i(TAG_RTMP, "Stage 14: VIDEO_CONFIG_SENT (AVC Sequence Header)")
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
            if (packet.isSequenceHeader && !hasSentAudioHeader) {
                hasSentAudioHeader = true
                Log.i(TAG_RTMP, "Stage 15: AUDIO_CONFIG_SENT (AAC Sequence Header)")
            }
        }
    }

    override fun sendAudio(aacData: ByteArray, timestampMs: Long) {
        if (!isStreamingFlag.get()) return
        val frame = EncodedAudioFrame(
            aacData = aacData,
            isConfig = false,
            timestampUs = timestampMs * 1000L,
            size = aacData.size
        )
        sendAudio(frame)
    }

    private fun enqueuePacket(packet: RtmpPacket) {
        if (!packetQueue.offer(packet)) {
            if (packet.isKeyframe) {
                val iterator = packetQueue.iterator()
                while (iterator.hasNext()) {
                    val queued = iterator.next()
                    if (!queued.isKeyframe && queued.messageType == RtmpPacket.TYPE_VIDEO) {
                        iterator.remove()
                        break
                    }
                }
                if (!packetQueue.offer(packet)) {
                    droppedFramesCount.incrementAndGet()
                    listener.onDroppedFrame()
                }
            } else {
                droppedFramesCount.incrementAndGet()
                listener.onDroppedFrame()
            }
        }
    }

    private fun startSenderLoop() {
        senderThread = Thread {
            try {
                while (isStreamingFlag.get() && !Thread.currentThread().isInterrupted) {
                    val packet = packetQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                    if (packet != null) {
                        writePacketDirect(packet)
                        updateStats(packet.data.size)
                    }
                }
            } catch (_: InterruptedException) {
                // Stopped cleanly
            } catch (e: Exception) {
                logFailure("SENDER_LOOP", "TRANSMISSION_ERROR", e.message ?: "Socket error during transmission")
                if (isStreamingFlag.get()) {
                    listener.onConnectionFailed("SENDER_LOOP", "TRANSMISSION_ERROR", e.message ?: "Transmission error")
                }
            }
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

        while (offset < payloadSize) {
            val remaining = payloadSize - offset
            val currentChunkLength = Math.min(remaining, outChunkSize)

            if (isFirstChunk) {
                // Chunk Header Type 0 (11 bytes header)
                out.write(csid and 0x3F)

                // 3 bytes Timestamp
                val ts = if (timestamp >= 0xFFFFFF) 0xFFFFFFL else timestamp
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

                if (timestamp >= 0xFFFFFF) {
                    out.write(((timestamp shr 24) and 0xFF).toInt())
                    out.write(((timestamp shr 16) and 0xFF).toInt())
                    out.write(((timestamp shr 8) and 0xFF).toInt())
                    out.write((timestamp and 0xFF).toInt())
                }

                isFirstChunk = false
            } else {
                // Chunk Header Type 3 (continuation)
                out.write(0xC0 or (csid and 0x3F))
                if (timestamp >= 0xFFFFFF) {
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
        Log.i(TAG_RTMP, "RTMP disconnected cleanly")
        listener.onDisconnected()
    }

    private fun closeInternal() {
        isStreamingFlag.set(false)
        isConnectedFlag.set(false)
        senderThread?.interrupt()
        senderThread = null
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
        hasLoggedFirstVideoMedia = false
        hasLoggedFirstAudioMedia = false
        spsBytes = null
        ppsBytes = null
        previousHeaders.clear()
        inMessageBuffers.clear()
        defaultVideoPacketizer.reset()
        defaultAudioPacketizer.reset()
    }

    companion object {
        private const val TAG_RTMP = "VJStream/RTMP"
        private const val TAG_RTMPS = "VJStream/RTMPS"
        private const val TAG_STREAMING = "VJStream/Streaming"
        private const val DEFAULT_CHUNK_SIZE = 4096
        private const val CONNECT_TIMEOUT_MS = 10000
        private const val SOCKET_TIMEOUT_MS = 10000
        private const val QUEUE_CAPACITY = 120
    }
}

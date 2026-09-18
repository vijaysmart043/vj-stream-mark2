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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Clean abstraction for RTMP/RTMPS client connections.
 *
 * Responsibilities:
 * - Validates RTMP/RTMPS server URLs prior to network operations
 * - Accepts stream key separately without logging or leaking credentials
 * - Manages explicit connection states (DISCONNECTED, CONNECTING, CONNECTED, ERROR, DISCONNECTING)
 * - Executes all networking asynchronously off the Android main thread
 * - Provides packet interfaces for future H.264 (video) and AAC (audio) transmission
 * - Supports lightweight connection testing without media streaming
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
     * Performs a lightweight connection test (socket connection + RTMP handshake)
     * without starting full media streaming.
     */
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

/**
 * Default standard implementation of [RtmpClient].
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

    private var chunkSize = DEFAULT_CHUNK_SIZE
    private var streamId = 1
    private var streamStartTimeMs = -1L

    @Volatile
    private var spsBytes: ByteArray? = null
    @Volatile
    private var ppsBytes: ByteArray? = null
    @Volatile
    private var hasSentVideoHeader = false
    @Volatile
    private var hasSentAudioHeader = false

    private val defaultVideoPacketizer = FlvVideoPacketizer()
    private val defaultAudioPacketizer = FlvAudioPacketizer()
    private val videoPacketsSent = AtomicLong(0)
    private val audioPacketsSent = AtomicLong(0)

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
        // 1. URL Validation before doing any network operations
        val validationResult = RtmpUrlValidator.validate(serverUrl)
        if (validationResult is RtmpUrlValidator.ValidationResult.Invalid) {
            Log.e(TAG, "RTMP connection rejected: ${validationResult.reason}")
            updateState(RtmpConnectionState.ERROR)
            listener.onConnectionFailed(validationResult.reason)
            return
        }

        val parsed = validationResult as RtmpUrlValidator.ValidationResult.Valid

        // 2. Validate stream key presence without logging value
        if (streamKey.isBlank()) {
            val err = "Stream key cannot be empty"
            Log.e(TAG, "RTMP connection rejected: $err")
            updateState(RtmpConnectionState.ERROR)
            listener.onConnectionFailed(err)
            return
        }

        updateState(RtmpConnectionState.CONNECTING)

        // 3. Background network execution
        Thread {
            try {
                Log.i(TAG, "Connecting to RTMP endpoint: ${parsed.host}:${parsed.port}/${parsed.app} (TLS=${parsed.isSsl})")

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
                Log.i(TAG, "Socket connected. Starting RTMP handshake...")

                // Handshake
                performHandshake()
                Log.i(TAG, "RTMP Handshake successful")

                // Set Chunk Size to 4096
                sendSetChunkSize(DEFAULT_CHUNK_SIZE)
                Log.i(TAG, "Set Chunk Size $DEFAULT_CHUNK_SIZE sent")

                // Connect Command
                val tcUrl = parsed.normalizedUrl
                sendConnectCommand(parsed.app, tcUrl)
                Log.i(TAG, "RTMP connect command sent for app: ${parsed.app}")
                updateState(RtmpConnectionState.CONNECTED)

                // ReleaseStream & FCPublish
                sendReleaseStream(streamKey)
                sendFCPublish(streamKey)
                Log.i(TAG, "RTMP releaseStream and FCPublish commands sent")

                // CreateStream
                sendCreateStream()
                Log.i(TAG, "RTMP createStream command sent")

                Thread.sleep(150)

                // Publish
                sendPublish(streamKey)
                updateState(RtmpConnectionState.PUBLISHING)
                Log.i(TAG, "RTMP publish command sent (stream mode: live)")

                // Metadata
                sendMetaData(width, height, fps, videoBitrateKbps)
                Log.i(TAG, "RTMP @setDataFrame onMetaData sent (resolution=${width}x$height, fps=$fps, bitrate=${videoBitrateKbps}kbps)")

                isConnectedFlag.set(true)
                isStreamingFlag.set(true)
                streamStartTimeMs = System.currentTimeMillis()
                lastBitrateCalcTime = System.currentTimeMillis()

                startSenderLoop()

                updateState(RtmpConnectionState.CONNECTED)
                Log.i(TAG, "RTMP publishing session active and connected")
                listener.onConnected()
            } catch (e: Exception) {
                val cleanError = mapToUserFriendlyError(e)
                Log.e(TAG, "RTMP connection failed: $cleanError")
                closeInternal()
                updateState(RtmpConnectionState.ERROR)
                listener.onConnectionFailed(cleanError)
            }
        }.start()
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
                val cleanMsg = mapToUserFriendlyError(e)
                updateState(RtmpConnectionState.ERROR)
                callback(false, cleanMsg)
            } finally {
                try {
                    testSocket?.close()
                } catch (_: Exception) {}
                updateState(RtmpConnectionState.DISCONNECTED)
            }
        }.start()
    }

    private fun mapToUserFriendlyError(e: Exception): String {
        return when (e) {
            is UnknownHostException -> "Unable to reach server. Please check your network connection."
            is SocketTimeoutException -> "Connection timed out. Server is unreachable."
            is ConnectException -> "Connection refused by server. Verify endpoint URL and port."
            is SSLException -> "Secure connection (TLS/SSL) handshake failed."
            is IllegalStateException -> e.message ?: "Connection protocol error"
            else -> e.message?.takeIf { it.isNotBlank() } ?: "An unexpected network error occurred"
        }
    }

    private fun performHandshake() {
        val out = outputStream ?: throw IllegalStateException("Output stream is null")
        val inStream = inputStream ?: throw IllegalStateException("Input stream is null")

        // C0 (0x03) + C1 (1536 bytes)
        val c1 = ByteArray(1536)
        Random().nextBytes(c1)
        for (i in 0 until 8) c1[i] = 0

        out.write(0x03)
        out.write(c1)
        out.flush()

        // S0 (1 byte) + S1 (1536 bytes)
        val s0 = inStream.read()
        if (s0 != 0x03) {
            throw IllegalStateException("Invalid RTMP S0 version: $s0")
        }
        val s1 = ByteArray(1536)
        readFully(inStream, s1)

        // C2 (matching S1)
        out.write(s1)
        out.flush()

        // S2 (1536 bytes)
        val s2 = ByteArray(1536)
        readFully(inStream, s2)
    }

    private fun readFully(inStream: InputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val count = inStream.read(target, offset, target.size - offset)
            if (count < 0) throw IllegalStateException("Unexpected EOF during RTMP handshake")
            offset += count
        }
    }

    private fun sendSetChunkSize(newSize: Int) {
        chunkSize = newSize
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
    }

    private fun sendConnectCommand(app: String, tcUrl: String) {
        val baos = ByteArrayOutputStream()
        Amf0.writeString(baos, "connect")
        Amf0.writeNumber(baos, 1.0)

        val properties = linkedMapOf<String, Any?>(
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
        val count = videoPacketsSent.incrementAndGet()
        if (packet.isKeyframe || count % 90 == 1L) {
            Log.d(TAG, "Video packet queued (ts=${packet.timestampMs}ms, key=${packet.isKeyframe}, count=$count)")
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
        if (count % 150 == 1L) {
            Log.d(TAG, "Audio packet queued (ts=${packet.timestampMs}ms, count=$count)")
        }
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
                // Sender loop interrupted during stop
            } catch (e: Exception) {
                Log.e(TAG, "Error in RTMP sender loop: ${e.message}")
                if (isStreamingFlag.get()) {
                    listener.onConnectionFailed(mapToUserFriendlyError(e))
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
            val currentChunkLength = Math.min(remaining, chunkSize)

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

                isFirstChunk = false
            } else {
                // Chunk Header Type 3 (continuation)
                out.write(0xC0 or (csid and 0x3F))
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

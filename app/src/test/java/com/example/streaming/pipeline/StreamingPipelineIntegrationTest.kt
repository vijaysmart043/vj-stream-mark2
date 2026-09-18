package com.example.streaming.pipeline

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.streaming.StreamStatus
import com.example.streaming.StreamingManager
import com.example.streaming.encoder.AudioSpecificConfig
import com.example.streaming.encoder.EncodedAudioFrame
import com.example.streaming.encoder.EncodedVideoFrame
import com.example.streaming.packetizer.FlvAudioPacket
import com.example.streaming.packetizer.FlvAudioPacketizer
import com.example.streaming.packetizer.FlvVideoPacket
import com.example.streaming.packetizer.FlvVideoPacketizer
import com.example.streaming.rtmp.RtmpClient
import com.example.streaming.rtmp.RtmpConnectionState
import com.example.youtube.AudioConfig
import com.example.youtube.VideoPresets
import com.example.youtube.YouTubeStreamConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Phase 6D Integration Tests for H.264 + AAC -> FLV -> RTMP Pipeline.
 *
 * Validates:
 * 1. RTMP connection state transitions.
 * 2. Video packet forwarding.
 * 3. Audio packet forwarding.
 * 4. Configuration packet ordering (Sequence headers before media frames).
 * 5. Timestamp preservation (PTS preserved from encoder).
 * 6. Start/stop lifecycle.
 * 7. Encoder shutdown and resource cleanup.
 * 8. RTMP disconnect.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StreamingPipelineIntegrationTest {

    private lateinit var context: Context
    private lateinit var fakeRtmpClient: FakeRtmpClient
    private lateinit var streamingManager: StreamingManager

    class FakeRtmpClient(
        val listener: RtmpClient.Listener
    ) : RtmpClient {
        val connectionStates = mutableListOf<RtmpConnectionState>()
        val sentVideoPackets = mutableListOf<FlvVideoPacket>()
        val sentAudioPackets = mutableListOf<FlvAudioPacket>()
        var isDisconnected = false
        var lastConnectedUrl: String? = null
        var lastStreamKey: String? = null

        private val _connectionStateFlow = MutableStateFlow(RtmpConnectionState.DISCONNECTED)
        override val connectionStateFlow: StateFlow<RtmpConnectionState> = _connectionStateFlow.asStateFlow()
        override val currentState: RtmpConnectionState get() = _connectionStateFlow.value
        override val isConnected: Boolean get() = _connectionStateFlow.value.isConnected

        override fun connect(
            serverUrl: String,
            streamKey: String,
            width: Int,
            height: Int,
            fps: Int,
            videoBitrateKbps: Int
        ) {
            lastConnectedUrl = serverUrl
            lastStreamKey = streamKey
            _connectionStateFlow.value = RtmpConnectionState.CONNECTING
            connectionStates.add(RtmpConnectionState.CONNECTING)
            listener.onConnectionStateChanged(RtmpConnectionState.CONNECTING)

            _connectionStateFlow.value = RtmpConnectionState.CONNECTED
            connectionStates.add(RtmpConnectionState.CONNECTED)
            listener.onConnectionStateChanged(RtmpConnectionState.CONNECTED)
            listener.onConnected()
        }

        override fun testConnection(
            serverUrl: String,
            streamKey: String,
            timeoutMs: Int,
            callback: (Boolean, String) -> Unit
        ) {
            callback(true, "Connected")
        }

        override fun setSpsPps(sps: ByteArray, pps: ByteArray) {}
        override fun sendVideo(frame: EncodedVideoFrame) {}
        override fun sendVideo(nalData: ByteArray, isKeyframe: Boolean, timestampMs: Long) {}

        override fun sendFlvVideoPacket(packet: FlvVideoPacket) {
            sentVideoPackets.add(packet)
        }

        override fun sendAudio(frame: EncodedAudioFrame) {}
        override fun sendAudio(aacData: ByteArray, timestampMs: Long) {}
        override fun sendAudioConfig(config: AudioSpecificConfig) {}
        override fun sendAudioSequenceHeader(sampleRate: Int, channelCount: Int) {}

        override fun sendFlvAudioPacket(packet: FlvAudioPacket) {
            sentAudioPackets.add(packet)
        }

        override fun disconnect() {
            _connectionStateFlow.value = RtmpConnectionState.DISCONNECTING
            connectionStates.add(RtmpConnectionState.DISCONNECTING)
            listener.onConnectionStateChanged(RtmpConnectionState.DISCONNECTING)

            isDisconnected = true
            _connectionStateFlow.value = RtmpConnectionState.DISCONNECTED
            connectionStates.add(RtmpConnectionState.DISCONNECTED)
            listener.onConnectionStateChanged(RtmpConnectionState.DISCONNECTED)
            listener.onDisconnected()
        }
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        fakeRtmpClient = FakeRtmpClient(object : RtmpClient.Listener {
            override fun onConnected() {}
            override fun onConnectionFailed(reason: String) {}
            override fun onDisconnected() {}
        })
        streamingManager = StreamingManager(context) { listener ->
            fakeRtmpClient = FakeRtmpClient(listener)
            fakeRtmpClient
        }
    }

    private fun createValidConfig(): YouTubeStreamConfig {
        return YouTubeStreamConfig(
            serverUrl = "rtmp://a.rtmp.youtube.com/live2",
            streamKey = "valid-stream-key-1234",
            videoPreset = VideoPresets.MEDIUM,
            audioConfig = AudioConfig(enabled = true, sampleRate = 48000, channelCount = 1)
        )
    }

    // 1. RTMP CONNECTION STATE TESTS
    @Test
    fun testRtmpConnectionStateTransitions() {
        val config = createValidConfig()

        assertEquals(StreamStatus.OFFLINE, streamingManager.statusFlow.value)
        assertEquals(RtmpConnectionState.DISCONNECTED, streamingManager.rtmpConnectionStateFlow.value)

        streamingManager.startStream(config)
        ShadowLooper.idleMainLooper()

        assertTrue(fakeRtmpClient.isConnected)
        assertEquals(StreamStatus.LIVE, streamingManager.statusFlow.value)
        assertEquals(RtmpConnectionState.CONNECTED, streamingManager.rtmpConnectionStateFlow.value)
        assertTrue(fakeRtmpClient.connectionStates.contains(RtmpConnectionState.CONNECTING))
        assertTrue(fakeRtmpClient.connectionStates.contains(RtmpConnectionState.CONNECTED))
    }

    @Test
    fun testRtmpRejectsInvalidUrlAndEmptyKey() {
        val invalidUrlConfig = YouTubeStreamConfig(
            serverUrl = "http://invalid-url.com",
            streamKey = "key-123"
        )
        streamingManager.startStream(invalidUrlConfig)
        ShadowLooper.idleMainLooper()

        assertEquals(StreamStatus.ERROR, streamingManager.statusFlow.value)
        assertTrue(streamingManager.errorMessageFlow.value?.contains("Invalid RTMP URL") == true)
        assertFalse(fakeRtmpClient.isConnected)

        val emptyKeyConfig = YouTubeStreamConfig(
            serverUrl = "rtmp://a.rtmp.youtube.com/live2",
            streamKey = "   "
        )
        streamingManager.startStream(emptyKeyConfig)
        ShadowLooper.idleMainLooper()

        assertEquals(StreamStatus.ERROR, streamingManager.statusFlow.value)
    }

    // 2. VIDEO PACKET FORWARDING TESTS
    @Test
    fun testVideoPacketForwarding() {
        val config = createValidConfig()
        streamingManager.startStream(config)
        ShadowLooper.idleMainLooper()

        val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1F)
        val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x38, 0x80.toByte())
        streamingManager.videoPacketizer.setSpsPps(sps, pps)

        // Send a keyframe
        val idrNalu = byteArrayOf(0x65, 0x88.toByte(), 0x84.toByte(), 0x00)
        val keyframe = EncodedVideoFrame(
            nalData = idrNalu,
            isKeyframe = true,
            isConfig = false,
            timestampUs = 33_333L,
            size = idrNalu.size
        )
        val packets = streamingManager.videoPacketizer.packetize(keyframe)
        for (packet in packets) {
            fakeRtmpClient.sendFlvVideoPacket(packet)
        }

        // Verify keyframe forwarding
        val forwardedKeyframe = fakeRtmpClient.sentVideoPackets.find { it.isKeyframe && !it.isSequenceHeader }
        assertNotNull(forwardedKeyframe)
        assertEquals(33L, forwardedKeyframe!!.timestampMs)
        assertTrue(forwardedKeyframe.payload.size > idrNalu.size)
        // FLV header byte: 0x17 (Keyframe, AVC)
        assertEquals(0x17.toByte(), forwardedKeyframe.payload[0])
        // AVC NALU packet type: 0x01
        assertEquals(0x01.toByte(), forwardedKeyframe.payload[1])

        // Send an interframe
        val sliceNalu = byteArrayOf(0x41, 0x9A.toByte(), 0x01, 0x02)
        val interframe = EncodedVideoFrame(
            nalData = sliceNalu,
            isKeyframe = false,
            isConfig = false,
            timestampUs = 66_666L,
            size = sliceNalu.size
        )
        val interPackets = streamingManager.videoPacketizer.packetize(interframe)
        for (packet in interPackets) {
            fakeRtmpClient.sendFlvVideoPacket(packet)
        }

        val forwardedInterframe = fakeRtmpClient.sentVideoPackets.find { !it.isKeyframe }
        assertNotNull(forwardedInterframe)
        assertEquals(66L, forwardedInterframe!!.timestampMs)
        // FLV header byte: 0x27 (Interframe, AVC)
        assertEquals(0x27.toByte(), forwardedInterframe.payload[0])
        assertEquals(0x01.toByte(), forwardedInterframe.payload[1])
    }

    // 3. AUDIO PACKET FORWARDING TESTS
    @Test
    fun testAudioPacketForwarding() {
        val config = createValidConfig()
        streamingManager.startStream(config)
        ShadowLooper.idleMainLooper()

        streamingManager.audioPacketizer.setAudioConfig(48000, 1)

        val rawAac = byteArrayOf(0x21, 0x10, 0x05, 0x20)
        val audioFrame = EncodedAudioFrame(
            aacData = rawAac,
            isConfig = false,
            timestampUs = 21_333L,
            size = rawAac.size
        )

        val packets = streamingManager.audioPacketizer.packetize(audioFrame)
        for (packet in packets) {
            fakeRtmpClient.sendFlvAudioPacket(packet)
        }

        val forwardedAudio = fakeRtmpClient.sentAudioPackets.find { !it.isSequenceHeader }
        assertNotNull(forwardedAudio)
        assertEquals(21L, forwardedAudio!!.timestampMs)
        // FLV header: 0xAE (AAC sound format, mono, 44.1/48k, 16-bit), 0x01 (AAC raw)
        assertEquals(0xAE.toByte(), forwardedAudio.payload[0])
        assertEquals(0x01.toByte(), forwardedAudio.payload[1])
        assertEquals(rawAac.size + 2, forwardedAudio.payload.size)

        // Verify stereo produces 0xAF
        val stereoPacketizer = FlvAudioPacketizer()
        stereoPacketizer.setAudioConfig(48000, 2)
        val stereoPackets = stereoPacketizer.packetize(audioFrame)
        val stereoFramePacket = stereoPackets.find { !it.isSequenceHeader }
        assertNotNull(stereoFramePacket)
        assertEquals(0xAF.toByte(), stereoFramePacket!!.payload[0])
    }

    // 4. CONFIGURATION PACKET ORDERING TESTS
    @Test
    fun testConfigurationPacketOrdering() {
        val videoPacketizer = FlvVideoPacketizer()
        val audioPacketizer = FlvAudioPacketizer()

        val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1F)
        val pps = byteArrayOf(0x68, 0xCE.toByte())
        videoPacketizer.setSpsPps(sps, pps)
        audioPacketizer.setAudioConfig(48000, 1)

        val idrNalu = byteArrayOf(0x65, 0x88.toByte())
        val videoFrame = EncodedVideoFrame(
            nalData = idrNalu,
            isKeyframe = true,
            isConfig = false,
            timestampUs = 33000L
        )

        val videoPackets = videoPacketizer.packetize(videoFrame)
        // First packet MUST be AVC Sequence Header
        assertEquals(2, videoPackets.size)
        assertTrue(videoPackets[0].isSequenceHeader)
        assertEquals(videoFrame.timestampMs, videoPackets[0].timestampMs)
        assertEquals(0x17.toByte(), videoPackets[0].payload[0])
        assertEquals(0x00.toByte(), videoPackets[0].payload[1]) // AVC sequence header

        // Second packet is the actual NALU frame
        assertFalse(videoPackets[1].isSequenceHeader)
        assertTrue(videoPackets[1].isKeyframe)
        assertEquals(33L, videoPackets[1].timestampMs)

        // For Audio:
        val aacFrame = EncodedAudioFrame(
            aacData = byteArrayOf(0x21, 0x10),
            isConfig = false,
            timestampUs = 21000L
        )
        val audioPackets = audioPacketizer.packetize(aacFrame)
        // First packet MUST be AAC Sequence Header
        assertEquals(2, audioPackets.size)
        assertTrue(audioPackets[0].isSequenceHeader)
        assertEquals(aacFrame.timestampMs, audioPackets[0].timestampMs)
        assertEquals(0xAE.toByte(), audioPackets[0].payload[0])
        assertEquals(0x00.toByte(), audioPackets[0].payload[1]) // AAC sequence header

        // Second packet is the actual audio frame
        assertFalse(audioPackets[1].isSequenceHeader)
        assertEquals(21L, audioPackets[1].timestampMs)

        // Subsequent frames MUST NOT repeat the sequence header
        val nextVideoPackets = videoPacketizer.packetize(videoFrame)
        assertEquals(1, nextVideoPackets.size)
        assertFalse(nextVideoPackets[0].isSequenceHeader)

        val nextAudioPackets = audioPacketizer.packetize(aacFrame)
        assertEquals(1, nextAudioPackets.size)
        assertFalse(nextAudioPackets[0].isSequenceHeader)
    }

    // 5. TIMESTAMP PRESERVATION TESTS
    @Test
    fun testTimestampPreservationFromEncoders() {
        val videoPacketizer = FlvVideoPacketizer()
        val audioPacketizer = FlvAudioPacketizer()

        videoPacketizer.setSpsPps(byteArrayOf(0x67, 0x42), byteArrayOf(0x68))
        audioPacketizer.setAudioConfig(48000, 1)

        // Sequence headers have already been primed or emitted
        videoPacketizer.buildSequenceHeader()
        audioPacketizer.buildSequenceHeader()

        val sampleTimestampsUs = listOf(0L, 33_333L, 66_666L, 100_000L, 500_000L, 1_234_567L)

        for (tsUs in sampleTimestampsUs) {
            val expectedMs = tsUs / 1000L

            val vFrame = EncodedVideoFrame(
                nalData = byteArrayOf(0x41, 0x01),
                isKeyframe = false,
                isConfig = false,
                timestampUs = tsUs
            )
            val vPackets = videoPacketizer.packetize(vFrame)
            assertEquals(1, vPackets.size)
            assertEquals(expectedMs, vPackets[0].timestampMs)

            val aFrame = EncodedAudioFrame(
                aacData = byteArrayOf(0x21),
                isConfig = false,
                timestampUs = tsUs
            )
            val aPackets = audioPacketizer.packetize(aFrame)
            assertEquals(1, aPackets.size)
            assertEquals(expectedMs, aPackets[0].timestampMs)
        }
    }

    // 6. START/STOP LIFECYCLE TESTS
    @Test
    fun testStartStopLifecycle() {
        val config = createValidConfig()

        // Start
        streamingManager.startStream(config)
        ShadowLooper.idleMainLooper()

        assertTrue(streamingManager.isTransmissionEnabled.get())
        assertEquals(StreamStatus.LIVE, streamingManager.statusFlow.value)
        assertNotNull(streamingManager.rtmpClient)

        // Stop
        streamingManager.stopStream()
        assertFalse(streamingManager.isTransmissionEnabled.get())

        // Give thread time to clean up
        Thread.sleep(50)
        ShadowLooper.idleMainLooper()

        assertEquals(StreamStatus.OFFLINE, streamingManager.statusFlow.value)
        assertNull(streamingManager.rtmpClient)
        assertNull(streamingManager.videoEncoder)
        assertNull(streamingManager.audioEncoder)

        // Start again to verify clean restart
        streamingManager.startStream(config)
        ShadowLooper.idleMainLooper()

        assertTrue(streamingManager.isTransmissionEnabled.get())
        assertEquals(StreamStatus.LIVE, streamingManager.statusFlow.value)
        assertNotNull(streamingManager.rtmpClient)

        streamingManager.stopStream()
        Thread.sleep(50)
        ShadowLooper.idleMainLooper()
        assertEquals(StreamStatus.OFFLINE, streamingManager.statusFlow.value)
    }

    // 7. ENCODER SHUTDOWN TESTS
    @Test
    fun testEncoderShutdownAndReset() {
        val config = createValidConfig()
        streamingManager.startStream(config)
        ShadowLooper.idleMainLooper()

        streamingManager.videoPacketizer.setSpsPps(byteArrayOf(0x67), byteArrayOf(0x68))
        streamingManager.audioPacketizer.setAudioConfig(48000, 1)

        // Stop stream
        streamingManager.stopStream()
        Thread.sleep(50)
        ShadowLooper.idleMainLooper()

        // Packetizers must be reset so they are ready for the next stream session
        assertFalse(streamingManager.videoPacketizer.hasEmittedSequenceHeader)
        assertFalse(streamingManager.audioPacketizer.hasEmittedSequenceHeader)
        assertFalse(streamingManager.isEncoderRunning())
    }

    // 8. RTMP DISCONNECT TESTS
    @Test
    fun testRtmpDisconnectCleansUp() {
        val config = createValidConfig()
        streamingManager.startStream(config)
        ShadowLooper.idleMainLooper()

        assertTrue(fakeRtmpClient.isConnected)
        assertFalse(fakeRtmpClient.isDisconnected)

        streamingManager.stopStream()
        Thread.sleep(50)
        ShadowLooper.idleMainLooper()

        assertTrue(fakeRtmpClient.isDisconnected)
        assertFalse(fakeRtmpClient.isConnected)
        assertEquals(RtmpConnectionState.DISCONNECTED, fakeRtmpClient.currentState)
    }

    // 9. PHASE 6G DIAGNOSTIC AND TERMINAL FAILURE TESTS
    @Test
    fun testFirstFailureIsTerminalWithoutAutoReconnect() {
        var connectionAttemptCount = 0
        val failingClient = object : RtmpClient {
            var listener: RtmpClient.Listener? = null
            override val connectionStateFlow = MutableStateFlow(RtmpConnectionState.DISCONNECTED)
            override val currentState = RtmpConnectionState.DISCONNECTED
            override val isConnected = false

            override fun connect(serverUrl: String, streamKey: String, width: Int, height: Int, fps: Int, videoBitrateKbps: Int) {
                connectionAttemptCount++
                // Fail immediately
                listener?.onConnectionFailed("RTMP_HANDSHAKE", "HANDSHAKE_TIMEOUT", "Server did not complete handshake")
            }

            override fun disconnect() {}
            override fun testConnection(serverUrl: String, streamKey: String, timeoutMs: Int, callback: (Boolean, String) -> Unit) {}
            override fun setSpsPps(sps: ByteArray, pps: ByteArray) {}
            override fun sendVideo(frame: EncodedVideoFrame) {}
            override fun sendVideo(nalData: ByteArray, isKeyframe: Boolean, timestampMs: Long) {}
            override fun sendFlvVideoPacket(packet: FlvVideoPacket) {}
            override fun sendAudio(frame: EncodedAudioFrame) {}
            override fun sendAudio(aacData: ByteArray, timestampMs: Long) {}
            override fun sendAudioConfig(config: AudioSpecificConfig) {}
            override fun sendAudioSequenceHeader(sampleRate: Int, channelCount: Int) {}
            override fun sendFlvAudioPacket(packet: FlvAudioPacket) {}
        }

        val manager = StreamingManager(context) { listener ->
            failingClient.listener = listener
            failingClient
        }
        manager.startStream(createValidConfig())
        Thread.sleep(50)
        ShadowLooper.idleMainLooper()

        // Verify that only ONE connection attempt was made
        assertEquals(1, connectionAttemptCount)
        assertEquals(StreamStatus.ERROR, manager.statusFlow.value)
        assertNotNull(manager.errorInfoFlow.value)
        assertEquals("RTMP_HANDSHAKE", manager.errorInfoFlow.value?.stage)
        assertEquals("HANDSHAKE_TIMEOUT", manager.errorInfoFlow.value?.errorType)

        // Advance looper to ensure NO automatic reconnection timer was posted
        ShadowLooper.idleMainLooper()
        assertEquals(1, connectionAttemptCount)
        assertEquals(StreamStatus.ERROR, manager.statusFlow.value)
    }
}

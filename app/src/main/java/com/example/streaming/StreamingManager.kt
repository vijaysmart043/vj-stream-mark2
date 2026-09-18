package com.example.streaming

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.camera.CameraManager
import com.example.streaming.audio.AudioCaptureManager
import com.example.streaming.audio.AudioFrame
import com.example.streaming.audio.AudioState
import com.example.streaming.encoder.AudioEncoder
import com.example.streaming.encoder.AudioEncoderState
import com.example.streaming.encoder.AudioSpecificConfig
import com.example.streaming.encoder.EncodedAudioFrame
import com.example.streaming.encoder.EncodedVideoFrame
import com.example.streaming.encoder.VideoEncoder
import com.example.streaming.encoder.VideoEncoderCapabilities
import com.example.streaming.encoder.VideoEncoderState
import com.example.streaming.encoder.VideoEncoderStats
import com.example.streaming.packetizer.FlvAudioPacket
import com.example.streaming.packetizer.FlvAudioPacketizer
import com.example.streaming.packetizer.FlvVideoPacket
import com.example.streaming.packetizer.FlvVideoPacketizer
import com.example.streaming.rtmp.RtmpClient
import com.example.streaming.rtmp.RtmpConnectionState
import com.example.streaming.rtmp.RtmpUrlValidator
import com.example.youtube.YouTubeStreamConfig
import com.example.youtube.YouTubeStreamValidator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class StreamingManager(
    private val context: Context,
    private val rtmpClientFactory: ((RtmpClient.Listener) -> RtmpClient)? = null
) : RtmpClient.Listener {

    val videoPacketizer = FlvVideoPacketizer()
    val audioPacketizer = FlvAudioPacketizer()
    val isTransmissionEnabled = AtomicBoolean(false)
    private val videoPacketsSent = AtomicLong(0)
    private val audioPacketsSent = AtomicLong(0)

    private val _statusFlow = MutableStateFlow(StreamStatus.OFFLINE)
    val statusFlow: StateFlow<StreamStatus> = _statusFlow.asStateFlow()

    private val _statsFlow = MutableStateFlow(StreamStatistics())
    val statsFlow: StateFlow<StreamStatistics> = _statsFlow.asStateFlow()

    private val _errorMessageFlow = MutableStateFlow<String?>(null)
    val errorMessageFlow: StateFlow<String?> = _errorMessageFlow.asStateFlow()

    fun setErrorMessage(message: String?) {
        _errorMessageFlow.value = message
    }

    fun clearErrorMessage() {
        _errorMessageFlow.value = null
    }

    // Expose standalone video encoder state and stats
    private val _encoderStateFlow = MutableStateFlow(VideoEncoderState.IDLE)
    val encoderStateFlow: StateFlow<VideoEncoderState> = _encoderStateFlow.asStateFlow()

    private val _encoderStatsFlow = MutableStateFlow(VideoEncoderStats())
    val encoderStatsFlow: StateFlow<VideoEncoderStats> = _encoderStatsFlow.asStateFlow()

    // Expose audio encoder state
    private val _audioEncoderStateFlow = MutableStateFlow(AudioEncoderState.IDLE)
    val audioEncoderStateFlow: StateFlow<AudioEncoderState> = _audioEncoderStateFlow.asStateFlow()

    // Expose RTMP connection state
    private val _rtmpConnectionStateFlow = MutableStateFlow(RtmpConnectionState.DISCONNECTED)
    val rtmpConnectionStateFlow: StateFlow<RtmpConnectionState> = _rtmpConnectionStateFlow.asStateFlow()

    var rtmpClient: RtmpClient? = null
        private set
    var videoEncoder: VideoEncoder? = null
        private set
    var audioEncoder: AudioEncoder? = null
        private set

    val audioCaptureManager: AudioCaptureManager = AudioCaptureManager(
        context = context,
        preferredSampleRate = 48000,
        preferredChannels = 1,
        listener = object : AudioCaptureManager.Listener {
            override fun onAudioFrame(frame: AudioFrame) {
                if (isStreamingActive.get()) {
                    audioEncoder?.encodePcm(frame.data, frame.length, frame.timestampUs)
                }
            }

            override fun onAudioConfigured(sampleRate: Int, channelCount: Int) {
                rtmpClient?.sendAudioSequenceHeader(sampleRate, channelCount)
            }

            override fun onAudioStateChanged(state: AudioState) {
                _statsFlow.value = _statsFlow.value.copy(
                    audioEnabled = state == AudioState.CAPTURING || state == AudioState.READY
                )
            }

            override fun onAudioLevelChanged(rmsLevel: Float, peakLevel: Float) {}

            override fun onAudioError(error: String) {
                Log.e(TAG, "Audio capture error: $error")
            }
        }
    )

    private var currentConfig: YouTubeStreamConfig? = null
    private val isStreamingActive = AtomicBoolean(false)
    private val isEncoderTestActive = AtomicBoolean(false)

    // Reconnection handling
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 3
    private val mainHandler = Handler(Looper.getMainLooper())

    // Statistics timer
    private var statsTimer: Timer? = null
    private var streamStartTime = 0L
    private var lastMeasuredFps = 30.0
    private var currentBitrateKbps = 0
    private var totalDroppedFrames = 0L

    init {
        // Run initial encoder capability discovery
        val discovery = VideoEncoderCapabilities.findBestEncoder(VideoEncoderCapabilities.MIME_AVC)
        if (discovery != null) {
            _statsFlow.value = _statsFlow.value.copy(
                encoderName = discovery.encoderName
            )
        }
    }

    /**
     * Starts standalone Video Encoder test mode without streaming to YouTube.
     */
    fun startEncoderTest(config: YouTubeStreamConfig) {
        if (isStreamingActive.get() || isEncoderTestActive.get()) return

        currentConfig = config
        isEncoderTestActive.set(true)
        Log.i(TAG, "Starting standalone Video Encoder test: ${config.videoPreset.name}")

        initVideoEncoder(config, isStandAloneTest = true)
    }

    /**
     * Stops standalone Video Encoder test mode.
     */
    fun stopEncoderTest() {
        if (!isEncoderTestActive.getAndSet(false)) return
        Log.i(TAG, "Stopping standalone Video Encoder test")
        videoEncoder?.stop()
        videoEncoder = null
        _encoderStateFlow.value = VideoEncoderState.IDLE
        _statsFlow.value = _statsFlow.value.copy(
            isEncoderActive = false
        )
    }

    fun isEncoderRunning(): Boolean {
        return (isStreamingActive.get() || isEncoderTestActive.get()) && videoEncoder != null
    }

    fun startStream(config: YouTubeStreamConfig) {
        if (isStreamingActive.get()) return

        // Stop standalone test if running
        if (isEncoderTestActive.get()) {
            stopEncoderTest()
        }

        val validation = YouTubeStreamValidator.validate(config)
        if (!validation.isValid) {
            val err = validation.errorMessage ?: "YouTube Server URL and Stream Key are required."
            _errorMessageFlow.value = err
            _statusFlow.value = StreamStatus.ERROR
            return
        }

        currentConfig = config
        reconnectAttempts = 0
        _statusFlow.value = StreamStatus.INITIALIZING
        _errorMessageFlow.value = null

        startPipeline(config)
    }

    private fun initVideoEncoder(config: YouTubeStreamConfig, isStandAloneTest: Boolean): VideoEncoder? {
        val discovery = VideoEncoderCapabilities.findBestEncoder(VideoEncoderCapabilities.MIME_AVC)
        val resolvedWidth = if (discovery != null) {
            val resolved = VideoEncoderCapabilities.resolveSupportedResolution(discovery, config.videoPreset.width, config.videoPreset.height)
            resolved.width
        } else {
            config.videoPreset.width
        }
        val resolvedHeight = if (discovery != null) {
            val resolved = VideoEncoderCapabilities.resolveSupportedResolution(discovery, config.videoPreset.width, config.videoPreset.height)
            resolved.height
        } else {
            config.videoPreset.height
        }

        try {
            val encoder = VideoEncoder(
                requestedWidth = resolvedWidth,
                requestedHeight = resolvedHeight,
                fps = config.videoPreset.fps,
                bitrateBps = config.videoPreset.bitrateBps,
                listener = object : VideoEncoder.Listener {
                    override fun onSpsPps(sps: ByteArray, pps: ByteArray) {
                        videoPacketizer.setSpsPps(sps, pps)
                        if (!isStandAloneTest) {
                            rtmpClient?.setSpsPps(sps, pps)
                            if (isTransmissionEnabled.get() && !videoPacketizer.hasEmittedSequenceHeader) {
                                videoPacketizer.buildSequenceHeader()?.let { packet ->
                                    rtmpClient?.sendFlvVideoPacket(packet)
                                    Log.i(TAG, "AVC sequence header sent")
                                }
                            }
                        }
                    }

                    override fun onEncodedFrame(frame: EncodedVideoFrame) {
                        if (!isStandAloneTest && isTransmissionEnabled.get()) {
                            val packets = videoPacketizer.packetize(frame)
                            for (packet in packets) {
                                rtmpClient?.sendFlvVideoPacket(packet)
                                if (packet.isSequenceHeader) {
                                    Log.i(TAG, "AVC sequence header sent")
                                } else {
                                    val count = videoPacketsSent.incrementAndGet()
                                    if (packet.isKeyframe || count % 90 == 1L) {
                                        Log.d(TAG, "Video packet sent (ts=${packet.timestampMs}ms, key=${packet.isKeyframe})")
                                    }
                                }
                            }
                        }
                    }

                    override fun onEncodedFrame(nalData: ByteArray, isKeyframe: Boolean, timestampMs: Long) {
                        if (!isStandAloneTest && isTransmissionEnabled.get()) {
                            val frame = EncodedVideoFrame(
                                nalData = nalData,
                                isKeyframe = isKeyframe,
                                isConfig = false,
                                timestampUs = timestampMs * 1000L,
                                size = nalData.size
                            )
                            val packets = videoPacketizer.packetize(frame)
                            for (packet in packets) {
                                rtmpClient?.sendFlvVideoPacket(packet)
                                if (packet.isSequenceHeader) {
                                    Log.i(TAG, "AVC sequence header sent")
                                } else {
                                    val count = videoPacketsSent.incrementAndGet()
                                    if (packet.isKeyframe || count % 90 == 1L) {
                                        Log.d(TAG, "Video packet sent (ts=${packet.timestampMs}ms, key=${packet.isKeyframe})")
                                    }
                                }
                            }
                        }
                    }

                    override fun onEncoderStateChanged(state: VideoEncoderState) {
                        mainHandler.post {
                            _encoderStateFlow.value = state
                            _statsFlow.value = _statsFlow.value.copy(
                                isEncoderActive = state == VideoEncoderState.ENCODING
                            )
                        }
                    }

                    override fun onEncoderStats(stats: VideoEncoderStats) {
                        mainHandler.post {
                            _encoderStatsFlow.value = stats
                            _statsFlow.value = _statsFlow.value.copy(
                                encoderName = stats.encoderName,
                                encodedFrames = stats.encodedFrameCount,
                                keyframes = stats.keyframeCount,
                                fps = if (stats.currentFps > 0.0) stats.currentFps else lastMeasuredFps,
                                videoBitrateKbps = if (stats.currentBitrateKbps > 0) stats.currentBitrateKbps else _statsFlow.value.videoBitrateKbps
                            )
                        }
                    }

                    override fun onEncoderError(error: String) {
                        handleError(error)
                    }
                }
            ).apply { start() }

            videoEncoder = encoder
            return encoder
        } catch (e: Throwable) {
            val errorMsg = "Video encoder initialization failed: ${e.message}"
            Log.e(TAG, errorMsg, e)
            _errorMessageFlow.value = errorMsg
            _encoderStateFlow.value = VideoEncoderState.ERROR
            handleError(errorMsg)
            return null
        }
    }

    private fun startPipeline(config: YouTubeStreamConfig) {
        try {
            val urlValidation = RtmpUrlValidator.validate(config.serverUrl)
            if (urlValidation is RtmpUrlValidator.ValidationResult.Invalid) {
                _errorMessageFlow.value = "Invalid RTMP URL: ${urlValidation.reason}"
                _statusFlow.value = StreamStatus.ERROR
                return
            }

            videoPacketizer.reset()
            audioPacketizer.reset()
            isTransmissionEnabled.set(false)
            videoPacketsSent.set(0)
            audioPacketsSent.set(0)

            _statusFlow.value = StreamStatus.CONNECTING
            _statsFlow.value = _statsFlow.value.copy(statusMessage = "Connecting to YouTube Live...")

            // 1. Initialize RTMP Client
            val client = rtmpClientFactory?.invoke(this) ?: RtmpClient(this)
            rtmpClient = client.apply {
                connect(
                    serverUrl = config.serverUrl,
                    streamKey = config.streamKey,
                    width = config.videoPreset.width,
                    height = config.videoPreset.height,
                    fps = config.videoPreset.fps,
                    videoBitrateKbps = config.videoPreset.bitrateKbps
                )
            }

            // 2. Initialize Video Encoder
            initVideoEncoder(config, isStandAloneTest = false)

            // 3. Initialize Audio Capture & Audio Encoder
            audioEncoder = AudioEncoder(
                sampleRate = config.audioConfig.sampleRate,
                channelCount = config.audioConfig.channelCount,
                bitrateBps = config.audioConfig.bitrateBps,
                listener = object : AudioEncoder.Listener {
                    override fun onAudioConfig(config: AudioSpecificConfig) {
                        audioPacketizer.setAudioConfig(config)
                        rtmpClient?.sendAudioConfig(config)
                        if (isTransmissionEnabled.get() && !audioPacketizer.hasEmittedSequenceHeader) {
                            audioPacketizer.buildSequenceHeader()?.let { packet ->
                                rtmpClient?.sendFlvAudioPacket(packet)
                                Log.i(TAG, "AAC sequence header sent")
                            }
                        }
                    }

                    override fun onAudioHeader(sampleRate: Int, channelCount: Int) {
                        audioPacketizer.setAudioConfig(sampleRate, channelCount)
                        rtmpClient?.sendAudioSequenceHeader(sampleRate, channelCount)
                        if (isTransmissionEnabled.get() && !audioPacketizer.hasEmittedSequenceHeader) {
                            audioPacketizer.buildSequenceHeader()?.let { packet ->
                                rtmpClient?.sendFlvAudioPacket(packet)
                                Log.i(TAG, "AAC sequence header sent")
                            }
                        }
                    }

                    override fun onEncodedAudio(aacData: ByteArray, timestampMs: Long) {
                        if (isTransmissionEnabled.get()) {
                            val frame = EncodedAudioFrame(
                                aacData = aacData,
                                isConfig = false,
                                timestampUs = timestampMs * 1000L,
                                size = aacData.size
                            )
                            val packets = audioPacketizer.packetize(frame)
                            for (packet in packets) {
                                rtmpClient?.sendFlvAudioPacket(packet)
                                if (packet.isSequenceHeader) {
                                    Log.i(TAG, "AAC sequence header sent")
                                } else {
                                    val count = audioPacketsSent.incrementAndGet()
                                    if (count % 150 == 1L) {
                                        Log.d(TAG, "Audio packet sent (ts=${packet.timestampMs}ms)")
                                    }
                                }
                            }
                        }
                    }

                    override fun onEncodedFrame(frame: EncodedAudioFrame) {
                        if (isTransmissionEnabled.get()) {
                            val packets = audioPacketizer.packetize(frame)
                            for (packet in packets) {
                                rtmpClient?.sendFlvAudioPacket(packet)
                                if (packet.isSequenceHeader) {
                                    Log.i(TAG, "AAC sequence header sent")
                                } else {
                                    val count = audioPacketsSent.incrementAndGet()
                                    if (count % 150 == 1L) {
                                        Log.d(TAG, "Audio packet sent (ts=${packet.timestampMs}ms)")
                                    }
                                }
                            }
                        }
                    }

                    override fun onAudioEncoderStateChanged(state: AudioEncoderState) {
                        mainHandler.post {
                            _audioEncoderStateFlow.value = state
                        }
                    }

                    override fun onAudioError(error: String) {
                        Log.e(TAG, "Audio encoder error: $error")
                        mainHandler.post {
                            _audioEncoderStateFlow.value = AudioEncoderState.ERROR
                        }
                    }
                }
            ).apply { start() }

            if (audioCaptureManager.currentState == AudioState.OFF || audioCaptureManager.currentState == AudioState.ERROR) {
                audioCaptureManager.startCapture()
            }
            audioCaptureManager.setMuted(!config.audioConfig.enabled)

            isStreamingActive.set(true)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting stream pipeline: ${e.message}", e)
            handleError("Pipeline error: ${e.message}")
        }
    }

    fun onCameraFrame(yuvData: ByteArray, width: Int, height: Int) {
        if (isStreamingActive.get() || isEncoderTestActive.get()) {
            videoEncoder?.encodeNv21(yuvData, width, height)
        }
    }

    fun updateCameraFps(fps: Double) {
        lastMeasuredFps = fps
    }

    fun startAudioCapture() {
        audioCaptureManager.startCapture()
    }

    fun stopAudioCapture() {
        audioCaptureManager.stopCapture()
    }

    fun setMicrophoneMuted(muted: Boolean) {
        audioCaptureManager.setMuted(muted)
        _statsFlow.value = _statsFlow.value.copy(audioEnabled = !muted)
    }

    // RtmpClient.Listener implementations
    override fun onConnected() {
        isTransmissionEnabled.set(true)
        if (!videoPacketizer.hasEmittedSequenceHeader) {
            videoPacketizer.buildSequenceHeader()?.let { packet ->
                rtmpClient?.sendFlvVideoPacket(packet)
                Log.i(TAG, "AVC sequence header sent")
            }
        }
        if (!audioPacketizer.hasEmittedSequenceHeader) {
            audioPacketizer.buildSequenceHeader()?.let { packet ->
                rtmpClient?.sendFlvAudioPacket(packet)
                Log.i(TAG, "AAC sequence header sent")
            }
        }

        mainHandler.post {
            reconnectAttempts = 0
            _statusFlow.value = StreamStatus.LIVE
            streamStartTime = System.currentTimeMillis()
            startStatsTimer()
            _statsFlow.value = _statsFlow.value.copy(
                statusMessage = "Live on YouTube",
                networkStatus = NetworkHealth.EXCELLENT
            )
        }
    }

    override fun onConnectionFailed(reason: String) {
        mainHandler.post {
            Log.e(TAG, "RTMP connection failed: $reason")
            if (isStreamingActive.get()) {
                attemptReconnect(reason)
            } else {
                _statusFlow.value = StreamStatus.ERROR
                _errorMessageFlow.value = "Connection failed: $reason"
            }
        }
    }

    private fun attemptReconnect(reason: String) {
        if (reconnectAttempts < maxReconnectAttempts) {
            reconnectAttempts++
            _statusFlow.value = StreamStatus.RECONNECTING
            _statsFlow.value = _statsFlow.value.copy(
                statusMessage = "Connection lost. Reconnecting ($reconnectAttempts/$maxReconnectAttempts)...",
                networkStatus = NetworkHealth.POOR
            )

            mainHandler.postDelayed({
                if (isStreamingActive.get()) {
                    currentConfig?.let {
                        try {
                            rtmpClient?.disconnect()
                            val client = rtmpClientFactory?.invoke(this) ?: RtmpClient(this)
                            rtmpClient = client.apply {
                                connect(
                                    it.serverUrl,
                                    it.streamKey,
                                    it.videoPreset.width,
                                    it.videoPreset.height,
                                    it.videoPreset.fps,
                                    it.videoPreset.bitrateKbps
                                )
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Reconnect attempt failed: ${e.message}")
                        }
                    }
                }
            }, 3000)
        } else {
            handleError("Stream connection lost after $maxReconnectAttempts attempts.")
        }
    }

    override fun onDisconnected() {
        mainHandler.post {
            _rtmpConnectionStateFlow.value = RtmpConnectionState.DISCONNECTED
            if (_statusFlow.value == StreamStatus.STOPPING) {
                _statusFlow.value = StreamStatus.OFFLINE
            }
        }
    }

    override fun onConnectionStateChanged(state: RtmpConnectionState) {
        mainHandler.post {
            _rtmpConnectionStateFlow.value = state
        }
    }

    /**
     * Performs a non-streaming RTMP connectivity test to verify endpoint and credentials.
     */
    fun testRtmpConnection(
        serverUrl: String,
        streamKey: String,
        callback: (Boolean, String) -> Unit
    ) {
        val testListener = object : RtmpClient.Listener {
            override fun onConnected() {}
            override fun onConnectionFailed(reason: String) {}
            override fun onDisconnected() {}
        }
        val testClient = rtmpClientFactory?.invoke(testListener) ?: RtmpClient(testListener)
        testClient.testConnection(serverUrl, streamKey, callback = { success, message ->
            mainHandler.post {
                callback(success, message)
            }
        })
    }

    override fun onDroppedFrame() {
        totalDroppedFrames++
    }

    override fun onStatsUpdated(bitrateKbps: Int, droppedFrames: Long) {
        currentBitrateKbps = bitrateKbps
        totalDroppedFrames = droppedFrames
    }

    private fun startStatsTimer() {
        statsTimer?.cancel()
        statsTimer = Timer("StreamStatsTimer", true).apply {
            scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    mainHandler.post {
                        if (isStreamingActive.get() && _statusFlow.value == StreamStatus.LIVE) {
                            val elapsedSec = (System.currentTimeMillis() - streamStartTime) / 1000
                            val health = when {
                                totalDroppedFrames > 50 -> NetworkHealth.POOR
                                totalDroppedFrames > 10 -> NetworkHealth.FAIR
                                currentBitrateKbps > 1000 -> NetworkHealth.EXCELLENT
                                else -> NetworkHealth.GOOD
                            }

                            _statsFlow.value = _statsFlow.value.copy(
                                durationSeconds = elapsedSec,
                                fps = if (_encoderStatsFlow.value.currentFps > 0.0) _encoderStatsFlow.value.currentFps else lastMeasuredFps,
                                videoBitrateKbps = if (_encoderStatsFlow.value.currentBitrateKbps > 0) _encoderStatsFlow.value.currentBitrateKbps else currentBitrateKbps,
                                audioBitrateKbps = (currentConfig?.audioConfig?.bitrateBps ?: 128000) / 1000,
                                droppedFrames = totalDroppedFrames,
                                networkStatus = health,
                                statusMessage = "Streaming to YouTube Live"
                            )
                        }
                    }
                }
            }, 1000, 1000)
        }
    }

    private fun handleError(message: String) {
        mainHandler.post {
            _statusFlow.value = StreamStatus.ERROR
            _errorMessageFlow.value = message
            stopStream()
            stopEncoderTest()
        }
    }

    fun stopStream() {
        isTransmissionEnabled.set(false)
        if (!isStreamingActive.getAndSet(false)) {
            if (!isEncoderTestActive.get()) {
                _statusFlow.value = StreamStatus.OFFLINE
            }
            return
        }

        _statusFlow.value = StreamStatus.STOPPING
        statsTimer?.cancel()
        statsTimer = null

        Thread {
            try {
                audioEncoder?.stop()
                audioEncoder = null

                videoEncoder?.stop()
                videoEncoder = null

                rtmpClient?.disconnect()
                rtmpClient = null

                videoPacketizer.reset()
                audioPacketizer.reset()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping streaming resources: ${e.message}")
            } finally {
                mainHandler.post {
                    _statusFlow.value = StreamStatus.OFFLINE
                    _rtmpConnectionStateFlow.value = RtmpConnectionState.DISCONNECTED
                    _audioEncoderStateFlow.value = AudioEncoderState.IDLE
                    _statsFlow.value = StreamStatistics(
                        statusMessage = "Stream ended"
                    )
                }
            }
        }.start()
    }

    fun release() {
        stopStream()
        stopEncoderTest()
        audioCaptureManager.stopCapture()
    }

    fun clearError() {
        _errorMessageFlow.value = null
        if (_statusFlow.value == StreamStatus.ERROR) {
            _statusFlow.value = StreamStatus.OFFLINE
        }
    }

    companion object {
        private const val TAG = "StreamingManager"
    }
}

package com.example.streaming.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Production-ready Android hardware H.264/AVC video encoder.
 *
 * Responsibilities:
 * - Discovers compatible hardware encoders (or safest fallback)
 * - Configures MediaCodec for H.264/AVC (width, height, bitrate, 30fps, 2.0s I-frame interval)
 * - Drains encoded NAL units on a dedicated high-priority handler thread
 * - Captures and preserves SPS / PPS sequence parameter sets
 * - Emits typed EncodedVideoFrame access units with monotonic presentation timestamps
 * - Tracks real-time statistics (real FPS, encoded bitrates, keyframes, frame sizes)
 * - Safe lifecycle start/stop/release without blocking the UI thread
 */
class VideoEncoder(
    requestedWidth: Int,
    requestedHeight: Int,
    val fps: Int = 30,
    val bitrateBps: Int = 4_000_000,
    private val listener: Listener
) {
    interface Listener {
        fun onSpsPps(sps: ByteArray, pps: ByteArray)
        fun onEncodedFrame(frame: EncodedVideoFrame)
        fun onEncodedFrame(nalData: ByteArray, isKeyframe: Boolean, timestampMs: Long) {
            // Backward compatibility adapter
        }
        fun onEncoderStateChanged(state: VideoEncoderState) {}
        fun onEncoderStats(stats: VideoEncoderStats) {}
        fun onEncoderError(error: String)
    }

    private val isRunning = AtomicBoolean(false)
    private var codec: MediaCodec? = null

    // Dedicated background thread for MediaCodec input queueing and output draining
    private var encoderThread: HandlerThread? = null
    private var encoderHandler: Handler? = null

    // State flows
    private val _stateFlow = MutableStateFlow(VideoEncoderState.IDLE)
    val stateFlow: StateFlow<VideoEncoderState> = _stateFlow.asStateFlow()

    private val _statsFlow = MutableStateFlow(VideoEncoderStats())
    val statsFlow: StateFlow<VideoEncoderStats> = _statsFlow.asStateFlow()

    // Configured resolution & encoder metadata
    var width: Int = requestedWidth
        private set
    var height: Int = requestedHeight
        private set
    var encoderName: String = "None"
        private set
    var isHardwareAccelerated: Boolean = false
        private set
    var colorFormat: Int = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
        private set

    // Codec configuration parameters (SPS / PPS)
    @Volatile
    private var sps: ByteArray? = null
    @Volatile
    private var pps: ByteArray? = null

    // Presentation timestamp generator
    private val timestampGenerator = VideoTimestampGenerator(fps = fps)
    @Volatile
    private var lastEmittedTimestampUs = -1L

    // Real-time statistics counters
    private val totalEncodedFrames = AtomicLong(0)
    private val totalKeyframes = AtomicLong(0)
    private val totalBytesEncoded = AtomicLong(0)
    private val totalErrors = AtomicLong(0)
    private var lastFpsTimestampMs = 0L
    private var framesSinceLastFpsCalc = 0
    private var bytesSinceLastFpsCalc = 0L
    private var currentFps = 0.0
    private var currentBitrateKbps = 0

    // Throttled logcat diagnostics
    private var lastDiagnosticLogMs = 0L

    init {
        // Resolve capabilities and check resolution support
        val discovery = VideoEncoderCapabilities.findBestEncoder(VideoEncoderCapabilities.MIME_AVC)
        if (discovery != null) {
            encoderName = discovery.encoderName
            isHardwareAccelerated = discovery.isHardwareAccelerated
            colorFormat = discovery.supportedColorFormat

            val resolved = VideoEncoderCapabilities.resolveSupportedResolution(discovery, requestedWidth, requestedHeight)
            width = resolved.width
            height = resolved.height
            if (resolved.isFallback) {
                Log.w(TAG, "Using fallback resolution ${width}x${height} for encoder $encoderName")
            }
        } else {
            Log.w(TAG, "No specific H.264 encoder discovered ahead of initialization")
        }

        updateStats()
    }

    /**
     * Initializes and starts the MediaCodec H.264 encoder on a dedicated thread.
     */
    @Synchronized
    fun start() {
        if (isRunning.get()) return

        _stateFlow.value = VideoEncoderState.INITIALIZING
        listener.onEncoderStateChanged(VideoEncoderState.INITIALIZING)

        val thread = HandlerThread("VJStream-VideoEncoder", Process.THREAD_PRIORITY_DISPLAY).apply {
            start()
        }
        encoderThread = thread
        val handler = Handler(thread.looper)
        encoderHandler = handler

        handler.post {
            try {
                initCodec()
                isRunning.set(true)
                timestampGenerator.reset()
                lastEmittedTimestampUs = -1L
                lastFpsTimestampMs = System.currentTimeMillis()
                framesSinceLastFpsCalc = 0
                bytesSinceLastFpsCalc = 0L

                _stateFlow.value = VideoEncoderState.ENCODING
                listener.onEncoderStateChanged(VideoEncoderState.ENCODING)
                Log.i(TAG, "VideoEncoder initialized successfully: $encoderName (${width}x${height} @ ${fps}fps, ${bitrateBps}bps)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed initializing VideoEncoder: ${e.message}", e)
                totalErrors.incrementAndGet()
                _stateFlow.value = VideoEncoderState.ERROR
                listener.onEncoderStateChanged(VideoEncoderState.ERROR)
                listener.onEncoderError("Failed to initialize video encoder: ${e.message}")
                stopInternal()
            }
        }
    }

    private fun initCodec() {
        val discovery = VideoEncoderCapabilities.findBestEncoder(VideoEncoderCapabilities.MIME_AVC)
        val selectedEncoder = if (discovery != null) {
            encoderName = discovery.encoderName
            isHardwareAccelerated = discovery.isHardwareAccelerated
            colorFormat = discovery.supportedColorFormat
            try {
                MediaCodec.createByCodecName(discovery.encoderName)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create encoder by name ${discovery.encoderName}, falling back to type: ${e.message}")
                MediaCodec.createEncoderByType(VideoEncoderCapabilities.MIME_AVC)
            }
        } else {
            MediaCodec.createEncoderByType(VideoEncoderCapabilities.MIME_AVC)
        }

        val format = MediaFormat.createVideoFormat(VideoEncoderCapabilities.MIME_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2) // 2.0s keyframe interval for streaming
            try {
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            } catch (_: Exception) {
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            }
        }

        selectedEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        selectedEncoder.start()
        codec = selectedEncoder
    }

    /**
     * Enqueues an NV21 frame from CameraX to the encoder.
     */
    fun encodeNv21(yuvData: ByteArray, frameWidth: Int, frameHeight: Int) {
        if (!isRunning.get()) return
        val handler = encoderHandler ?: return

        handler.post {
            val encoder = codec ?: return@post
            if (!isRunning.get()) return@post

            try {
                val inputIndex = encoder.dequeueInputBuffer(0L)
                val finalIndex = if (inputIndex >= 0) inputIndex else encoder.dequeueInputBuffer(4_000L)
                if (finalIndex >= 0) {
                    val inputBuffer = encoder.getInputBuffer(finalIndex)
                    if (inputBuffer != null) {
                        inputBuffer.clear()
                        val toWrite = Math.min(yuvData.size, inputBuffer.remaining())
                        inputBuffer.put(yuvData, 0, toWrite)

                        val ptsUs = timestampGenerator.nextTimestampUs()

                        encoder.queueInputBuffer(
                            finalIndex,
                            0,
                            toWrite,
                            ptsUs,
                            0
                        )
                    }
                }

                drainEncoder(false)
            } catch (e: Exception) {
                Log.e(TAG, "Error encoding frame: ${e.message}")
                totalErrors.incrementAndGet()
            }
        }
    }

    /**
     * Forces immediate generation of an IDR sync keyframe on the active encoder.
     */
    fun requestKeyframe() {
        val handler = encoderHandler ?: return
        handler.post {
            val encoder = codec ?: return@post
            try {
                val params = Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                }
                encoder.setParameters(params)
                Log.d(TAG, "Keyframe requested manually")
            } catch (e: Exception) {
                Log.w(TAG, "Could not request keyframe: ${e.message}")
            }
        }
    }

    /**
     * Dynamically adjusts video bitrate if supported by hardware encoder.
     */
    fun setBitrate(newBitrateBps: Int) {
        val handler = encoderHandler ?: return
        handler.post {
            val encoder = codec ?: return@post
            try {
                val params = Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, newBitrateBps)
                }
                encoder.setParameters(params)
                Log.i(TAG, "Bitrate updated to $newBitrateBps bps")
            } catch (e: Exception) {
                Log.w(TAG, "Could not dynamically adjust bitrate: ${e.message}")
            }
        }
    }

    /**
     * Drains encoded buffers from MediaCodec output queue.
     */
    private fun drainEncoder(endOfStream: Boolean) {
        val encoder = codec ?: return
        val bufferInfo = MediaCodec.BufferInfo()

        while (isRunning.get() || endOfStream) {
            val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 0L)
            when (outputIndex) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) break
                }
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val newFormat = encoder.outputFormat
                    Log.i(TAG, "Encoder output format changed: $newFormat")
                    parseFormatSpsPps(newFormat)
                }
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                    // Deprecated in API 21, handled automatically
                }
                else -> {
                    if (outputIndex >= 0) {
                        try {
                            val outputBuffer = encoder.getOutputBuffer(outputIndex)
                            if (outputBuffer != null && bufferInfo.size > 0) {
                                val offset = bufferInfo.offset.coerceAtLeast(0)
                                val available = (outputBuffer.capacity() - offset).coerceAtLeast(0)
                                val size = bufferInfo.size.coerceAtMost(available)

                                if (size > 0) {
                                    outputBuffer.position(offset)
                                    outputBuffer.limit(offset + size)

                                    // Deep copy encoded video bytes into an independent ByteArray BEFORE release
                                    val outData = ByteArray(size)
                                    outputBuffer.get(outData)

                                    val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                                    val isKeyframe = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

                                    if (isConfig) {
                                        parseAnnexBSpsPps(outData)
                                    } else {
                                        val nals = H264NalParser.splitAnnexB(outData)
                                        val hasIdr = nals.any { H264NalParser.getNalType(it) == H264NalParser.NAL_TYPE_IDR }
                                        val frameIsKey = isKeyframe || hasIdr

                                        // Check for in-band SPS/PPS updates if present
                                        for (nal in nals) {
                                            when (H264NalParser.getNalType(nal)) {
                                                H264NalParser.NAL_TYPE_SPS -> sps = nal
                                                H264NalParser.NAL_TYPE_PPS -> pps = nal
                                            }
                                        }
                                        val s = sps
                                        val p = pps
                                        if (s != null && p != null) {
                                            listener.onSpsPps(s, p)
                                        }

                                        // Ensure output PTS is strictly monotonic, non-negative, and has no duplicate milliseconds
                                        val rawPtsUs = bufferInfo.presentationTimeUs
                                        val frameDeltaUs = if (fps > 0) (1_000_000L / fps) else 33_333L

                                        var finalPtsUs = if (rawPtsUs > 0 && rawPtsUs > lastEmittedTimestampUs) {
                                            rawPtsUs
                                        } else if (lastEmittedTimestampUs < 0) {
                                            if (rawPtsUs >= 0) rawPtsUs else 0L
                                        } else {
                                            lastEmittedTimestampUs + frameDeltaUs
                                        }

                                        // Eliminate duplicate millisecond values (FLV/RTMP packets use millisecond timestamps)
                                        val lastEmittedMs = if (lastEmittedTimestampUs < 0) -1L else (lastEmittedTimestampUs / 1000L)
                                        if ((finalPtsUs / 1000L) <= lastEmittedMs) {
                                            finalPtsUs = (lastEmittedMs + 1L) * 1000L
                                        }
                                        lastEmittedTimestampUs = finalPtsUs

                                        // Encapsulate in an immutable frame holding only independent memory
                                        val encodedFrame = EncodedVideoFrame(
                                            nalData = outData,
                                            isKeyframe = frameIsKey,
                                            isConfig = false,
                                            timestampUs = finalPtsUs,
                                            size = outData.size
                                        )

                                        totalEncodedFrames.incrementAndGet()
                                        if (frameIsKey) {
                                            totalKeyframes.incrementAndGet()
                                        }
                                        totalBytesEncoded.addAndGet(outData.size.toLong())

                                        framesSinceLastFpsCalc++
                                        bytesSinceLastFpsCalc += outData.size

                                        // Dispatch copied frame to downstream packetizers
                                        listener.onEncodedFrame(encodedFrame)
                                        listener.onEncodedFrame(outData, frameIsKey, encodedFrame.timestampMs)
                                    }

                                    trackMetrics()
                                }
                            }
                        } finally {
                            encoder.releaseOutputBuffer(outputIndex, false)
                        }

                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            break
                        }
                    }
                }
            }
        }
    }

    private fun trackMetrics() {
        val now = System.currentTimeMillis()
        val diff = now - lastFpsTimestampMs
        if (diff >= 1000L) {
            currentFps = (framesSinceLastFpsCalc * 1000.0) / diff
            currentBitrateKbps = ((bytesSinceLastFpsCalc * 8L * 1000L) / (diff * 1000L)).toInt()

            framesSinceLastFpsCalc = 0
            bytesSinceLastFpsCalc = 0L
            lastFpsTimestampMs = now

            updateStats()
            logDiagnostics(now)
        }
    }

    private fun logDiagnostics(now: Long) {
        // Throttled logcat every 5 seconds to avoid flooding
        if (now - lastDiagnosticLogMs >= 5000L) {
            lastDiagnosticLogMs = now
            val frames = totalEncodedFrames.get()
            val keys = totalKeyframes.get()
            val avgSize = if (frames > 0) totalBytesEncoded.get() / frames else 0L

            Log.i(
                TAG,
                """
                |=== VJStream Encoder Diagnostics ===
                |H264 encoder: $encoderName (HW=$isHardwareAccelerated)
                |Resolution: ${width}x${height}
                |FPS: ${String.format("%.1f", currentFps)} (Target: $fps)
                |Bitrate: ${currentBitrateKbps} kbps (Target: $bitrateBps bps)
                |Frames encoded: $frames
                |Keyframes: $keys
                |Avg frame size: $avgSize bytes
                |Errors: ${totalErrors.get()}
                |====================================
                """.trimMargin()
            )
        }
    }

    private fun updateStats() {
        val frames = totalEncodedFrames.get()
        val avgSize = if (frames > 0) totalBytesEncoded.get() / frames else 0L

        val stats = VideoEncoderStats(
            encoderName = encoderName,
            isHardwareAccelerated = isHardwareAccelerated,
            width = width,
            height = height,
            targetFps = fps,
            targetBitrateBps = bitrateBps,
            currentFps = currentFps,
            currentBitrateKbps = currentBitrateKbps,
            encodedFrameCount = frames,
            keyframeCount = totalKeyframes.get(),
            errorCount = totalErrors.get(),
            averageFrameSizeBytes = avgSize
        )
        _statsFlow.value = stats
        listener.onEncoderStats(stats)
    }

    private fun parseFormatSpsPps(format: MediaFormat) {
        val csd0 = format.getByteBuffer("csd-0")?.duplicate()
        val csd1 = format.getByteBuffer("csd-1")?.duplicate()
        if (csd0 != null && csd1 != null) {
            val spsBytes = ByteArray(csd0.remaining())
            csd0.get(spsBytes)
            val ppsBytes = ByteArray(csd1.remaining())
            csd1.get(ppsBytes)

            val cleanSps = H264NalParser.stripStartCode(spsBytes)
            val cleanPps = H264NalParser.stripStartCode(ppsBytes)
            sps = cleanSps
            pps = cleanPps
            Log.i(TAG, "Captured SPS (${cleanSps.size} bytes) and PPS (${cleanPps.size} bytes) from csd format buffers")
            listener.onSpsPps(cleanSps, cleanPps)
        }
    }

    private fun parseAnnexBSpsPps(data: ByteArray) {
        val nals = H264NalParser.splitAnnexB(data)
        for (nal in nals) {
            if (nal.isEmpty()) continue
            val nalType = H264NalParser.getNalType(nal)
            if (nalType == H264NalParser.NAL_TYPE_SPS) {
                sps = nal
                Log.d(TAG, "Captured SPS (${nal.size} bytes) from Annex-B stream")
            } else if (nalType == H264NalParser.NAL_TYPE_PPS) {
                pps = nal
                Log.d(TAG, "Captured PPS (${nal.size} bytes) from Annex-B stream")
            }
        }
        val s = sps
        val p = pps
        if (s != null && p != null) {
            listener.onSpsPps(s, p)
        }
    }

    /**
     * Safely stops and releases MediaCodec and worker thread.
     */
    @Synchronized
    fun stop() {
        if (!isRunning.getAndSet(false)) return

        _stateFlow.value = VideoEncoderState.STOPPING
        listener.onEncoderStateChanged(VideoEncoderState.STOPPING)

        val handler = encoderHandler
        if (handler != null) {
            handler.post {
                stopInternal()
            }
        } else {
            stopInternal()
        }
    }

    private fun stopInternal() {
        try {
            drainEncoder(true)
            codec?.stop()
            codec?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping MediaCodec: ${e.message}")
        } finally {
            codec = null
            sps = null
            pps = null
            encoderThread?.quitSafely()
            encoderThread = null
            encoderHandler = null
            _stateFlow.value = VideoEncoderState.IDLE
            listener.onEncoderStateChanged(VideoEncoderState.IDLE)
            Log.i(TAG, "VideoEncoder stopped and released")
        }
    }

    companion object {
        private const val TAG = "VideoEncoder"
    }
}

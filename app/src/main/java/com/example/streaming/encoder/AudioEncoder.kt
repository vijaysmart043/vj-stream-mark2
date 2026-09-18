package com.example.streaming.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Production-ready Android AAC-LC Audio Encoder using MediaCodec.
 *
 * Responsibilities:
 * - Configures MediaCodec for audio/mp4a-latm (AAC-LC)
 * - Standard defaults: 48000 Hz, Mono (1 channel), 128 kbps (128_000 bps)
 * - Consumes raw 16-bit PCM audio buffers
 * - Generates monotonic presentation timestamps (System.nanoTime-anchored)
 * - Obtains and parses AudioSpecificConfig from INFO_OUTPUT_FORMAT_CHANGED and csd-0
 * - Produces RAW AAC access units (strictly NO ADTS headers, required for RTMP / FLV muxing)
 * - Provides lifecycle control (start, encode, stop, release) without thread or codec leaks
 */
class AudioEncoder(
    val sampleRate: Int = 48000,
    val channelCount: Int = 1,
    val bitrateBps: Int = 128_000,
    private val listener: Listener
) {
    interface Listener {
        fun onAudioHeader(sampleRate: Int, channelCount: Int) {}
        fun onAudioConfig(config: AudioSpecificConfig) {}
        fun onEncodedAudio(aacData: ByteArray, timestampMs: Long) {}
        fun onEncodedFrame(frame: EncodedAudioFrame) {}
        fun onAudioEncoderStateChanged(state: AudioEncoderState) {}
        fun onAudioError(error: String)
    }

    private val _stateFlow = MutableStateFlow(AudioEncoderState.IDLE)
    val stateFlow: StateFlow<AudioEncoderState> = _stateFlow.asStateFlow()

    val currentState: AudioEncoderState
        get() = _stateFlow.value

    private var codec: MediaCodec? = null
    private val isRunning = AtomicBoolean(false)

    // Monotonic timestamp timeline
    private var baseTimeNs = -1L
    private var lastTimestampUs = -1L

    // AudioSpecificConfig
    @Volatile
    var audioSpecificConfig: AudioSpecificConfig? = null
        private set

    @Synchronized
    fun start() {
        if (isRunning.get()) return

        updateState(AudioEncoderState.INITIALIZING)
        try {
            val format = MediaFormat.createAudioFormat(MIME_TYPE, sampleRate, channelCount).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }

            val encoder = MediaCodec.createEncoderByType(MIME_TYPE)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            codec = encoder
            isRunning.set(true)

            // Reset monotonic timestamp timeline
            baseTimeNs = -1L
            lastTimestampUs = -1L

            // Generate baseline AudioSpecificConfig
            val initialAsc = AudioSpecificConfig.fromSampleRateAndChannels(sampleRate, channelCount)
            audioSpecificConfig = initialAsc
            listener.onAudioConfig(initialAsc)
            listener.onAudioHeader(sampleRate, channelCount)

            updateState(AudioEncoderState.READY)
            Log.d(TAG, "AudioEncoder started: $sampleRate Hz, $channelCount channel(s), $bitrateBps bps")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AudioEncoder: ${e.message}", e)
            updateState(AudioEncoderState.ERROR)
            listener.onAudioError("Failed to initialize audio encoder: ${e.message}")
        }
    }

    /**
     * Enqueues a chunk of 16-bit PCM audio data to the encoder.
     * @param pcmData raw PCM 16-bit bytes
     * @param length number of valid bytes in the buffer
     * @param timestampUs optional timestamp in microseconds from AudioFrame (monotonic)
     */
    fun encodePcm(pcmData: ByteArray, length: Int, timestampUs: Long = -1L) {
        val encoder = codec ?: return
        if (!isRunning.get() || length <= 0) return

        try {
            val inputIndex = encoder.dequeueInputBuffer(TIMEOUT_USEC)
            if (inputIndex >= 0) {
                val inputBuffer = encoder.getInputBuffer(inputIndex)
                if (inputBuffer != null) {
                    inputBuffer.clear()
                    val toWrite = Math.min(length, inputBuffer.remaining())
                    inputBuffer.put(pcmData, 0, toWrite)

                    val presentationTimeUs = if (timestampUs >= 0) {
                        timestampUs
                    } else {
                        getMonotonicTimestampUs()
                    }

                    encoder.queueInputBuffer(inputIndex, 0, toWrite, presentationTimeUs, 0)
                }
            }
            drainEncoder(false)
        } catch (e: Exception) {
            Log.e(TAG, "Error encoding audio: ${e.message}")
            updateState(AudioEncoderState.ERROR)
            listener.onAudioError("Error encoding audio: ${e.message}")
        }
    }

    private fun drainEncoder(endOfStream: Boolean) {
        val encoder = codec ?: return
        val bufferInfo = MediaCodec.BufferInfo()

        while (isRunning.get() || endOfStream) {
            val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)
            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break else break
            } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val newFormat = encoder.outputFormat
                Log.d(TAG, "Audio encoder output format changed: $newFormat")
                if (newFormat.containsKey("csd-0")) {
                    val csd0 = newFormat.getByteBuffer("csd-0")
                    if (csd0 != null) {
                        val parsedAsc = AudioSpecificConfig.fromByteBuffer(csd0)
                        audioSpecificConfig = parsedAsc
                        listener.onAudioConfig(parsedAsc)
                        listener.onAudioHeader(parsedAsc.sampleRate, parsedAsc.channelCount)
                    }
                } else {
                    listener.onAudioHeader(sampleRate, channelCount)
                }
            } else if (outputIndex >= 0) {
                if (_stateFlow.value != AudioEncoderState.ENCODING && isRunning.get()) {
                    updateState(AudioEncoderState.ENCODING)
                }

                val outputBuffer = encoder.getOutputBuffer(outputIndex)
                if (outputBuffer != null && bufferInfo.size > 0) {
                    outputBuffer.position(bufferInfo.offset)
                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                    val aacData = ByteArray(bufferInfo.size)
                    outputBuffer.get(aacData)

                    val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    if (isConfig) {
                        val parsedAsc = AudioSpecificConfig.fromByteArray(aacData)
                        audioSpecificConfig = parsedAsc
                        listener.onAudioConfig(parsedAsc)
                        listener.onAudioHeader(parsedAsc.sampleRate, parsedAsc.channelCount)
                    } else {
                        // Raw AAC access unit without ADTS header
                        val ptsUs = bufferInfo.presentationTimeUs
                        val timestampMs = ptsUs / 1000L
                        listener.onEncodedAudio(aacData, timestampMs)
                        listener.onEncodedFrame(
                            EncodedAudioFrame(
                                aacData = aacData,
                                isConfig = false,
                                timestampUs = ptsUs,
                                sampleRate = sampleRate,
                                channelCount = channelCount
                            )
                        )
                    }
                }
                encoder.releaseOutputBuffer(outputIndex, false)
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break
                }
            }
        }
    }

    private fun getMonotonicTimestampUs(): Long {
        val nowNs = System.nanoTime()
        if (baseTimeNs < 0) {
            baseTimeNs = nowNs
            lastTimestampUs = 0L
            return 0L
        }
        val elapsedUs = (nowNs - baseTimeNs) / 1000L
        val ts = if (elapsedUs <= lastTimestampUs) lastTimestampUs + 1L else elapsedUs
        lastTimestampUs = ts
        return ts
    }

    private fun updateState(newState: AudioEncoderState) {
        _stateFlow.value = newState
        listener.onAudioEncoderStateChanged(newState)
    }

    @Synchronized
    fun stop() {
        if (!isRunning.getAndSet(false)) return
        updateState(AudioEncoderState.STOPPING)
        try {
            drainEncoder(true)
            codec?.stop()
            codec?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioEncoder: ${e.message}")
        } finally {
            codec = null
            updateState(AudioEncoderState.IDLE)
        }
    }

    fun release() {
        stop()
    }

    companion object {
        private const val TAG = "AudioEncoder"
        const val MIME_TYPE = "audio/mp4a-latm"
        private const val TIMEOUT_USEC = 10000L
    }
}

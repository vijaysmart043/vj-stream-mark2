package com.example.streaming.audio

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Dedicated, production-ready audio capture pipeline for VJStream Phase 3.
 *
 * Responsibilities:
 * - Real-time microphone capture using AudioRecord.
 * - Dedicated high-priority background audio thread (THREAD_PRIORITY_AUDIO).
 * - Pre-allocated, zero-garbage buffer reuse.
 * - Accurate monotonic presentation timestamps (microseconds).
 * - Lightweight RMS & Peak level calculations throttled to ~12-15 updates/sec.
 * - Strict AudioState transitions (OFF, INITIALIZING, READY, CAPTURING, MUTED, ERROR, STOPPING).
 * - Safe lifecycle handling and complete resource cleanup without UI blocking.
 */
class AudioCaptureManager(
    private val context: Context? = null,
    private val preferredSampleRate: Int = 48000,
    private val preferredChannels: Int = 1, // Mono preferred initially for streaming
    private val listener: Listener? = null
) {
    interface Listener {
        fun onAudioFrame(frame: AudioFrame)
        fun onAudioConfigured(sampleRate: Int, channelCount: Int)
        fun onAudioStateChanged(state: AudioState)
        fun onAudioLevelChanged(rmsLevel: Float, peakLevel: Float)
        fun onAudioError(error: String)
    }

    private val _stateFlow = MutableStateFlow(AudioState.OFF)
    val stateFlow: StateFlow<AudioState> = _stateFlow.asStateFlow()

    private val _audioLevelFlow = MutableStateFlow(0f)
    val audioLevelFlow: StateFlow<Float> = _audioLevelFlow.asStateFlow()

    private val _peakLevelFlow = MutableStateFlow(0f)
    val peakLevelFlow: StateFlow<Float> = _peakLevelFlow.asStateFlow()

    private val _errorMessageFlow = MutableStateFlow<String?>(null)
    val errorMessageFlow: StateFlow<String?> = _errorMessageFlow.asStateFlow()

    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    private val isCapturing = AtomicBoolean(false)
    private val isMutedFlag = AtomicBoolean(false)

    var actualSampleRate: Int = preferredSampleRate
        private set
    var actualChannelCount: Int = preferredChannels
        private set

    private val timestampGenerator = AudioTimestampGenerator(preferredSampleRate, preferredChannels)

    // Throttling for UI level updates: 70ms = ~14 updates per second
    private var lastLevelUpdateTimeMs: Long = 0L
    private val levelUpdateIntervalMs: Long = 70L

    val isMuted: Boolean
        get() = isMutedFlag.get()

    val currentState: AudioState
        get() = _stateFlow.value

    /**
     * Checks if RECORD_AUDIO permission is currently granted.
     */
    fun hasRecordPermission(): Boolean {
        val ctx = context ?: return true
        return ContextCompat.checkSelfPermission(
            ctx,
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Starts microphone audio capture asynchronously.
     */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun startCapture() {
        if (isCapturing.get()) {
            Log.d(TAG, "Audio capture is already active.")
            return
        }

        // Permission check
        if (!hasRecordPermission()) {
            val errorMsg = "Microphone permission is required for live audio."
            Log.e(TAG, errorMsg)
            updateState(AudioState.ERROR)
            _errorMessageFlow.value = errorMsg
            listener?.onAudioError(errorMsg)
            return
        }

        updateState(AudioState.INITIALIZING)
        _errorMessageFlow.value = null
        timestampGenerator.reset()

        try {
            val (record, sampleRate, channelCount, bufferSize) = initializeAudioRecord()
            audioRecord = record
            actualSampleRate = sampleRate
            actualChannelCount = channelCount

            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("AudioRecord failed to start recording.")
            }

            isCapturing.set(true)
            val initialState = if (isMutedFlag.get()) AudioState.MUTED else AudioState.CAPTURING
            updateState(initialState)

            listener?.onAudioConfigured(sampleRate, channelCount)

            // Allocate reusable buffers
            val readBufferSize = maxOf(bufferSize, 4096)
            val readBuffer = ByteArray(readBufferSize)
            val silenceBuffer = ByteArray(readBufferSize)

            // Start dedicated capture thread with elevated audio priority
            captureThread = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                Log.d(TAG, "Audio capture thread started: $sampleRate Hz, $channelCount channel(s)")

                while (isCapturing.get() && !Thread.currentThread().isInterrupted) {
                    val bytesRead = record.read(readBuffer, 0, readBuffer.size)
                    if (bytesRead > 0) {
                        val timestampUs = timestampGenerator.nextTimestampUs(bytesRead)

                        if (isMutedFlag.get()) {
                            // Send silence with continuous timestamps to preserve audio/video sync
                            val frame = AudioFrame(
                                data = silenceBuffer,
                                length = bytesRead,
                                timestampUs = timestampUs,
                                sampleRate = sampleRate,
                                channelCount = channelCount
                            )
                            listener?.onAudioFrame(frame)

                            // Throttled UI volume update to 0.0
                            throttleLevelUpdate(0f, 0f)
                        } else {
                            val frame = AudioFrame(
                                data = readBuffer,
                                length = bytesRead,
                                timestampUs = timestampUs,
                                sampleRate = sampleRate,
                                channelCount = channelCount
                            )
                            listener?.onAudioFrame(frame)

                            // Calculate RMS and Peak levels
                            val (rms, peak) = AudioLevelCalculator.calculateLevels(readBuffer, bytesRead)
                            throttleLevelUpdate(rms, peak)
                        }
                    } else if (bytesRead < 0) {
                        when (bytesRead) {
                            AudioRecord.ERROR_INVALID_OPERATION -> Log.e(TAG, "AudioRecord ERROR_INVALID_OPERATION")
                            AudioRecord.ERROR_BAD_VALUE -> Log.e(TAG, "AudioRecord ERROR_BAD_VALUE")
                            AudioRecord.ERROR_DEAD_OBJECT -> {
                                Log.e(TAG, "AudioRecord ERROR_DEAD_OBJECT (audio server died)")
                                isCapturing.set(false)
                                updateState(AudioState.ERROR)
                                listener?.onAudioError("Audio hardware server disconnected.")
                                break
                            }
                            else -> Log.w(TAG, "AudioRecord read returned code: $bytesRead")
                        }
                    }
                }

                Log.d(TAG, "Audio capture thread exited.")
            }, "AudioCaptureThread")

            captureThread?.start()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AudioRecord: ${e.message}", e)
            val errorMsg = "Microphone error: ${e.message ?: "Initialization failed"}"
            updateState(AudioState.ERROR)
            _errorMessageFlow.value = errorMsg
            listener?.onAudioError(errorMsg)
            releaseInternal()
        }
    }

    /**
     * Toggles microphone mute state.
     * When muted, silent audio buffers are emitted to maintain timeline sync,
     * while the UI level meter drops to 0.
     */
    fun setMuted(muted: Boolean) {
        isMutedFlag.set(muted)
        if (isCapturing.get()) {
            updateState(if (muted) AudioState.MUTED else AudioState.CAPTURING)
            if (muted) {
                _audioLevelFlow.value = 0f
                _peakLevelFlow.value = 0f
                listener?.onAudioLevelChanged(0f, 0f)
            }
        }
    }

    /**
     * Stops microphone capture and releases hardware resources.
     */
    @Synchronized
    fun stopCapture() {
        if (!isCapturing.getAndSet(false)) {
            updateState(AudioState.OFF)
            return
        }

        updateState(AudioState.STOPPING)
        releaseInternal()
        updateState(AudioState.OFF)
        _audioLevelFlow.value = 0f
        _peakLevelFlow.value = 0f
    }

    private fun releaseInternal() {
        captureThread?.interrupt()
        captureThread = null

        try {
            audioRecord?.apply {
                if (state == AudioRecord.STATE_INITIALIZED) {
                    stop()
                }
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AudioRecord: ${e.message}")
        } finally {
            audioRecord = null
        }
    }

    private fun throttleLevelUpdate(rms: Float, peak: Float) {
        val now = SystemClock.uptimeMillis()
        if (now - lastLevelUpdateTimeMs >= levelUpdateIntervalMs) {
            lastLevelUpdateTimeMs = now
            _audioLevelFlow.value = rms
            _peakLevelFlow.value = peak
            listener?.onAudioLevelChanged(rms, peak)
        }
    }

    private fun updateState(newState: AudioState) {
        _stateFlow.value = newState
        listener?.onAudioStateChanged(newState)
    }

    /**
     * Resolves the most optimal AudioRecord configuration.
     * Starts with 48000 Hz Mono, falling back through standard rates/channels.
     */
    @SuppressLint("MissingPermission")
    private fun initializeAudioRecord(): ConfigResult {
        val preferredConfig = if (preferredChannels == 2) {
            AudioFormat.CHANNEL_IN_STEREO
        } else {
            AudioFormat.CHANNEL_IN_MONO
        }

        // Test configuration matrix: Preferred rate/channels first
        val rates = intArrayOf(preferredSampleRate, 44100, 16000)
        val configs = if (preferredChannels == 1) {
            intArrayOf(AudioFormat.CHANNEL_IN_MONO, AudioFormat.CHANNEL_IN_STEREO)
        } else {
            intArrayOf(AudioFormat.CHANNEL_IN_STEREO, AudioFormat.CHANNEL_IN_MONO)
        }
        val sources = intArrayOf(
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.DEFAULT
        )

        for (source in sources) {
            for (rate in rates) {
                for (config in configs) {
                    val minBuf = AudioRecord.getMinBufferSize(
                        rate,
                        config,
                        AudioFormat.ENCODING_PCM_16BIT
                    )
                    if (minBuf > 0) {
                        try {
                            val bufferSize = minBuf * 2
                            val record = AudioRecord(
                                source,
                                rate,
                                config,
                                AudioFormat.ENCODING_PCM_16BIT,
                                bufferSize
                            )
                            if (record.state == AudioRecord.STATE_INITIALIZED) {
                                val channelCount = if (config == AudioFormat.CHANNEL_IN_STEREO) 2 else 1
                                Log.i(TAG, "Initialized AudioRecord: Source=$source, Rate=$rate, Channels=$channelCount, BufferSize=$bufferSize")
                                return ConfigResult(record, rate, channelCount, bufferSize)
                            }
                            record.release()
                        } catch (e: Exception) {
                            Log.w(TAG, "AudioRecord config failed for rate=$rate, config=$config: ${e.message}")
                        }
                    }
                }
            }
        }

        throw IllegalStateException("No supported AudioRecord configuration found on this device.")
    }

    private data class ConfigResult(
        val record: AudioRecord,
        val sampleRate: Int,
        val channelCount: Int,
        val bufferSize: Int
    )

    companion object {
        private const val TAG = "AudioCaptureManager"
    }
}

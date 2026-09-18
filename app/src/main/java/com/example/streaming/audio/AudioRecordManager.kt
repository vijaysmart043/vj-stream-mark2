package com.example.streaming.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

class AudioRecordManager(
    private val preferredSampleRate: Int = 48000,
    private val listener: Listener
) {
    interface Listener {
        fun onAudioData(pcmData: ByteArray, length: Int)
        fun onAudioConfigured(sampleRate: Int, channelCount: Int)
        fun onAudioError(error: String)
    }

    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    private val isRecording = AtomicBoolean(false)
    val isMuted = AtomicBoolean(false)

    var actualSampleRate: Int = preferredSampleRate
        private set
    var actualChannelCount: Int = 2
        private set

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRecording.get()) return

        try {
            val (record, sampleRate, channels) = initializeAudioRecord()
            audioRecord = record
            actualSampleRate = sampleRate
            actualChannelCount = channels

            record.startRecording()
            isRecording.set(true)

            listener.onAudioConfigured(sampleRate, channels)

            val minBufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val buffer = ByteArray(Math.max(minBufferSize, 4096))
            val zeroBuffer = ByteArray(buffer.size)

            captureThread = Thread({
                while (isRecording.get() && !Thread.currentThread().isInterrupted) {
                    val readBytes = record.read(buffer, 0, buffer.size)
                    if (readBytes > 0) {
                        if (isMuted.get()) {
                            listener.onAudioData(zeroBuffer, readBytes)
                        } else {
                            listener.onAudioData(buffer, readBytes)
                        }
                    } else if (readBytes < 0) {
                        Log.e(TAG, "AudioRecord read error code: $readBytes")
                    }
                }
            }, "AudioRecordCaptureThread")
            captureThread?.start()
            Log.d(TAG, "AudioRecord started at $sampleRate Hz, $channels channels")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AudioRecord: ${e.message}", e)
            listener.onAudioError("Microphone initialization error: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun initializeAudioRecord(): Triple<AudioRecord, Int, Int> {
        val sampleRates = intArrayOf(preferredSampleRate, 44100, 16000)
        val channelConfigs = intArrayOf(AudioFormat.CHANNEL_IN_STEREO, AudioFormat.CHANNEL_IN_MONO)

        for (rate in sampleRates) {
            for (chConfig in channelConfigs) {
                val minBuf = AudioRecord.getMinBufferSize(rate, chConfig, AudioFormat.ENCODING_PCM_16BIT)
                if (minBuf > 0) {
                    try {
                        val record = AudioRecord(
                            MediaRecorder.AudioSource.MIC,
                            rate,
                            chConfig,
                            AudioFormat.ENCODING_PCM_16BIT,
                            minBuf * 2
                        )
                        if (record.state == AudioRecord.STATE_INITIALIZED) {
                            val count = if (chConfig == AudioFormat.CHANNEL_IN_STEREO) 2 else 1
                            return Triple(record, rate, count)
                        }
                        record.release()
                    } catch (_: Exception) {}
                }
            }
        }
        throw IllegalStateException("No supported AudioRecord configuration found on this device")
    }

    fun setMuted(muted: Boolean) {
        isMuted.set(muted)
    }

    fun stop() {
        if (!isRecording.getAndSet(false)) return

        captureThread?.interrupt()
        captureThread = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AudioRecord: ${e.message}")
        } finally {
            audioRecord = null
        }
    }

    companion object {
        private const val TAG = "AudioRecordManager"
    }
}

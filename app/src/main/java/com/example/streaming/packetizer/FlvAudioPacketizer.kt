package com.example.streaming.packetizer

import com.example.streaming.encoder.AudioSpecificConfig
import com.example.streaming.encoder.EncodedAudioFrame

/**
 * High-performance, zero-external-dependency AAC -> FLV audio packetizer.
 *
 * Responsibilities:
 * - Packages AAC sequence headers containing actual [AudioSpecificConfig] (ASC).
 * - Packages raw AAC access units into standard FLV/RTMP audio packets.
 * - Enforces correct FLV Audio Tag Headers:
 *   - SoundFormat: 10 (AAC)
 *   - SoundRate: 3 (44.1 kHz or 48 kHz per FLV spec)
 *   - SoundSize: 1 (16-bit)
 *   - SoundType: 0 (Mono) or 1 (Stereo) based on actual encoder configuration
 * - Preserves monotonic presentation timestamps in milliseconds directly from the media timeline.
 * - Does not add ADTS headers, MP4 boxes, or network transmission calls.
 */
class FlvAudioPacketizer {

    private var audioConfig: AudioSpecificConfig? = null

    /**
     * Whether the AAC sequence header has been emitted for the active stream.
     */
    var hasEmittedSequenceHeader: Boolean = false
        private set

    /**
     * Updates the active [AudioSpecificConfig].
     */
    @Synchronized
    fun setAudioConfig(config: AudioSpecificConfig) {
        if (this.audioConfig != config) {
            this.audioConfig = config
            this.hasEmittedSequenceHeader = false
        }
    }

    /**
     * Updates the audio configuration using sample rate and channel count.
     */
    @Synchronized
    fun setAudioConfig(sampleRate: Int, channelCount: Int) {
        val newConfig = AudioSpecificConfig(
            sampleRate = sampleRate,
            channelCount = channelCount
        )
        setAudioConfig(newConfig)
    }

    /**
     * Returns true if a valid [AudioSpecificConfig] is present.
     */
    val hasConfiguration: Boolean
        get() = audioConfig != null

    /**
     * Returns the active configuration, if set.
     */
    val currentConfig: AudioSpecificConfig?
        get() = audioConfig

    /**
     * Builds an AAC Sequence Header (FLV Audio Packet Type 0) containing the [AudioSpecificConfig].
     *
     * FLV AAC Sequence Header structure:
     * - Byte 0: 0xAE (Mono) or 0xAF (Stereo)
     * - Byte 1: 0x00 (AAC sequence header)
     * - Bytes 2..N: AudioSpecificConfig (2 bytes for AAC-LC)
     *
     * @param timestampMs Timestamp in milliseconds (typically 0 or starting timestamp)
     */
    @Synchronized
    fun buildSequenceHeader(timestampMs: Long = 0L): FlvAudioPacket? {
        val config = audioConfig ?: return null
        val configBytes = config.configBytes
        if (configBytes.isEmpty()) return null

        val sampleRate = config.sampleRate
        val channelCount = config.channelCount

        val headerByte0 = buildHeaderByte0(channelCount)
        val payload = ByteArray(2 + configBytes.size)
        payload[0] = headerByte0
        payload[1] = AAC_PACKET_TYPE_SEQUENCE_HEADER
        System.arraycopy(configBytes, 0, payload, 2, configBytes.size)

        hasEmittedSequenceHeader = true

        return FlvAudioPacket(
            payload = payload,
            timestampMs = timestampMs,
            packetType = FlvAudioPacketType.SEQUENCE_HEADER,
            isConfig = true,
            sampleRate = sampleRate,
            channelCount = channelCount
        )
    }

    /**
     * Packetizes an [EncodedAudioFrame] into FLV/RTMP-compatible audio packets.
     * If the frame contains codec configuration (`frame.isConfig == true`), updates configuration
     * and returns the sequence header packet.
     * If the sequence header has not yet been emitted, prepends the sequence header packet.
     *
     * @param frame The encoded AAC audio frame from [AudioEncoder]
     * @return List of [FlvAudioPacket]s ready for RTMP transmission
     */
    @Synchronized
    fun packetize(frame: EncodedAudioFrame): List<FlvAudioPacket> {
        if (frame.aacData.isEmpty()) return emptyList()

        val packets = ArrayList<FlvAudioPacket>(2)

        // Handle configuration frames (csd-0)
        if (frame.isConfig) {
            val parsedConfig = AudioSpecificConfig.fromByteArray(frame.aacData)
            setAudioConfig(parsedConfig)
            buildSequenceHeader(frame.timestampMs)?.let { packets.add(it) }
            return packets
        }

        // Auto-configure from frame metadata if not yet configured
        if (audioConfig == null) {
            setAudioConfig(frame.sampleRate, frame.channelCount)
        }

        // Emit sequence header if not yet sent
        if (!hasEmittedSequenceHeader && hasConfiguration) {
            buildSequenceHeader(frame.timestampMs)?.let { packets.add(it) }
        }

        // Build raw AAC packet
        val effectiveChannelCount = audioConfig?.channelCount ?: frame.channelCount
        val effectiveSampleRate = audioConfig?.sampleRate ?: frame.sampleRate
        val rawPacket = buildRawPacket(
            aacData = frame.aacData,
            timestampMs = frame.timestampMs,
            sampleRate = effectiveSampleRate,
            channelCount = effectiveChannelCount
        )
        if (rawPacket != null) {
            packets.add(rawPacket)
        }

        return packets
    }

    /**
     * Direct overload to build a raw AAC audio packet (Packet Type 1).
     *
     * @param aacData Raw AAC access unit without ADTS headers
     * @param timestampMs Presentation timestamp in milliseconds
     * @param sampleRate Sample rate in Hz
     * @param channelCount Channel count (1 for Mono, 2 for Stereo)
     */
    fun buildRawPacket(
        aacData: ByteArray,
        timestampMs: Long,
        sampleRate: Int = audioConfig?.sampleRate ?: 48000,
        channelCount: Int = audioConfig?.channelCount ?: 1
    ): FlvAudioPacket? {
        if (aacData.isEmpty()) return null

        val headerByte0 = buildHeaderByte0(channelCount)
        val payload = ByteArray(2 + aacData.size)
        payload[0] = headerByte0
        payload[1] = AAC_PACKET_TYPE_RAW
        System.arraycopy(aacData, 0, payload, 2, aacData.size)

        return FlvAudioPacket(
            payload = payload,
            timestampMs = timestampMs,
            packetType = FlvAudioPacketType.RAW,
            isConfig = false,
            sampleRate = sampleRate,
            channelCount = channelCount
        )
    }

    /**
     * Computes the FLV Audio Tag Header (Byte 0).
     *
     * Bits 7-4: SoundFormat (10 for AAC -> 0xA0)
     * Bits 3-2: SoundRate (3 for 44.1k/48k per FLV spec -> 0x0C)
     * Bit 1: SoundSize (1 for 16-bit -> 0x02)
     * Bit 0: SoundType (0 for Mono -> 0x00, 1 for Stereo -> 0x01)
     *
     * Results:
     * Mono: 0xA0 | 0x0C | 0x02 | 0x00 = 0xAE
     * Stereo: 0xA0 | 0x0C | 0x02 | 0x01 = 0xAF
     */
    private fun buildHeaderByte0(channelCount: Int): Byte {
        val soundFormat = FLV_SOUND_FORMAT_AAC // 10
        val soundRate = FLV_SOUND_RATE_44_48KHZ // 3
        val soundSize = FLV_SOUND_SIZE_16BIT // 1
        val soundType = if (channelCount >= 2) FLV_SOUND_TYPE_STEREO else FLV_SOUND_TYPE_MONO

        val header = (soundFormat shl 4) or (soundRate shl 2) or (soundSize shl 1) or soundType
        return header.toByte()
    }

    /**
     * Resets sequence header tracking so the next frame re-emits the header.
     */
    @Synchronized
    fun reset() {
        hasEmittedSequenceHeader = false
    }

    /**
     * Clears all stored configuration and state.
     */
    @Synchronized
    fun release() {
        audioConfig = null
        hasEmittedSequenceHeader = false
    }

    companion object {
        const val FLV_SOUND_FORMAT_AAC = 10     // SoundFormat = 10 (AAC)
        const val FLV_SOUND_RATE_44_48KHZ = 3   // SoundRate = 3 (44 kHz / 48 kHz)
        const val FLV_SOUND_SIZE_16BIT = 1      // SoundSize = 1 (16-bit)
        const val FLV_SOUND_TYPE_MONO = 0       // SoundType = 0 (Mono)
        const val FLV_SOUND_TYPE_STEREO = 1     // SoundType = 1 (Stereo)

        const val FLV_HEADER_BYTE_MONO: Byte = 0xAE.toByte()
        const val FLV_HEADER_BYTE_STEREO: Byte = 0xAF.toByte()

        const val AAC_PACKET_TYPE_SEQUENCE_HEADER: Byte = 0x00
        const val AAC_PACKET_TYPE_RAW: Byte = 0x01
    }
}

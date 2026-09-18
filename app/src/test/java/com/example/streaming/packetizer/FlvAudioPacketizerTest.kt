package com.example.streaming.packetizer

import com.example.streaming.encoder.AudioSpecificConfig
import com.example.streaming.encoder.EncodedAudioFrame
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FlvAudioPacketizerTest {

    private lateinit var packetizer: FlvAudioPacketizer

    @Before
    fun setUp() {
        packetizer = FlvAudioPacketizer()
    }

    @Test
    fun `Test 1 - AAC sequence header generation`() {
        val config = AudioSpecificConfig(sampleRate = 48000, channelCount = 1)
        packetizer.setAudioConfig(config)
        assertTrue(packetizer.hasConfiguration)

        val headerPacket = packetizer.buildSequenceHeader(timestampMs = 0L)
        assertNotNull(headerPacket)
        val packet = headerPacket!!

        assertTrue(packet.isConfig)
        assertTrue(packet.isSequenceHeader)
        assertEquals(FlvAudioPacketType.SEQUENCE_HEADER, packet.packetType)
        assertEquals(0L, packet.timestampMs)
        assertEquals(48000, packet.sampleRate)
        assertEquals(1, packet.channelCount)

        // Minimum size: 2 bytes header + 2 bytes ASC
        assertEquals(4, packet.payload.size)
        assertEquals(FlvAudioPacketizer.FLV_HEADER_BYTE_MONO, packet.payload[0])
        assertEquals(FlvAudioPacketizer.AAC_PACKET_TYPE_SEQUENCE_HEADER, packet.payload[1])
    }

    @Test
    fun `Test 2 - AudioSpecificConfig inclusion`() {
        // 48000 Hz, Mono, AAC-LC config: 0x11, 0x88
        val config = AudioSpecificConfig(sampleRate = 48000, channelCount = 1)
        packetizer.setAudioConfig(config)

        val packet = packetizer.buildSequenceHeader(timestampMs = 0L)!!
        val ascBytes = packet.payload.copyOfRange(2, packet.payload.size)

        assertTrue(config.configBytes.contentEquals(ascBytes))
        assertEquals(0x11.toByte(), ascBytes[0])
        assertEquals(0x88.toByte(), ascBytes[1])
    }

    @Test
    fun `Test 3 - Raw AAC frame packet generation`() {
        val config = AudioSpecificConfig(sampleRate = 48000, channelCount = 1)
        packetizer.setAudioConfig(config)
        packetizer.buildSequenceHeader() // Mark sequence header emitted

        val rawAac = byteArrayOf(0x21, 0x43, 0x65, 0x87.toByte())
        val frame = EncodedAudioFrame(
            aacData = rawAac,
            isConfig = false,
            timestampUs = 21_333L, // ~21.33 ms
            sampleRate = 48000,
            channelCount = 1
        )

        val packets = packetizer.packetize(frame)
        assertEquals(1, packets.size)

        val packet = packets[0]
        assertFalse(packet.isConfig)
        assertFalse(packet.isSequenceHeader)
        assertEquals(FlvAudioPacketType.RAW, packet.packetType)
        assertEquals(21L, packet.timestampMs)

        // Total payload: 2 bytes FLV audio header + raw AAC
        assertEquals(2 + rawAac.size, packet.payload.size)
        assertEquals(FlvAudioPacketizer.FLV_HEADER_BYTE_MONO, packet.payload[0])
        assertEquals(FlvAudioPacketizer.AAC_PACKET_TYPE_RAW, packet.payload[1])

        val actualData = packet.payload.copyOfRange(2, packet.payload.size)
        assertTrue(rawAac.contentEquals(actualData))
    }

    @Test
    fun `Test 4 - Correct AAC packet type sequence header 0 and raw AAC 1`() {
        packetizer.setAudioConfig(48000, 1)

        val seqPacket = packetizer.buildSequenceHeader(0L)!!
        assertEquals(0.toByte(), seqPacket.payload[1])
        assertEquals(0, seqPacket.packetType.value)

        val rawAac = byteArrayOf(0x12, 0x34)
        val rawPacket = packetizer.buildRawPacket(rawAac, 100L)!!
        assertEquals(1.toByte(), rawPacket.payload[1])
        assertEquals(1, rawPacket.packetType.value)
    }

    @Test
    fun `Test 5 - Timestamp conversion preserves media timeline from microseconds to milliseconds`() {
        packetizer.setAudioConfig(48000, 1)
        packetizer.buildSequenceHeader()

        val frame1 = EncodedAudioFrame(
            aacData = byteArrayOf(0x01),
            isConfig = false,
            timestampUs = 1_000_000L // 1000 ms
        )
        val frame2 = EncodedAudioFrame(
            aacData = byteArrayOf(0x02),
            isConfig = false,
            timestampUs = 1_021_333L // ~1021 ms
        )
        val frame3 = EncodedAudioFrame(
            aacData = byteArrayOf(0x03),
            isConfig = false,
            timestampUs = 1_042_666L // ~1042 ms
        )

        val p1 = packetizer.packetize(frame1).first()
        val p2 = packetizer.packetize(frame2).first()
        val p3 = packetizer.packetize(frame3).first()

        assertEquals(1000L, p1.timestampMs)
        assertEquals(1021L, p2.timestampMs)
        assertEquals(1042L, p3.timestampMs)
        assertTrue(p2.timestampMs > p1.timestampMs)
        assertTrue(p3.timestampMs > p2.timestampMs)
    }

    @Test
    fun `Test 6 - Mono configuration vs Stereo configuration`() {
        // Mono configuration (channelCount = 1) -> Byte 0 = 0xAE
        packetizer.setAudioConfig(48000, 1)
        val monoSeq = packetizer.buildSequenceHeader(0L)!!
        assertEquals(0xAE.toByte(), monoSeq.payload[0])
        val monoRaw = packetizer.buildRawPacket(byteArrayOf(0x10), 0L, 48000, 1)!!
        assertEquals(0xAE.toByte(), monoRaw.payload[0])

        // Stereo configuration (channelCount = 2) -> Byte 0 = 0xAF
        packetizer.setAudioConfig(48000, 2)
        val stereoSeq = packetizer.buildSequenceHeader(0L)!!
        assertEquals(0xAF.toByte(), stereoSeq.payload[0])
        val stereoRaw = packetizer.buildRawPacket(byteArrayOf(0x10), 0L, 48000, 2)!!
        assertEquals(0xAF.toByte(), stereoRaw.payload[0])
    }

    @Test
    fun `Test 7 - Invalid and empty AAC frame handling`() {
        packetizer.setAudioConfig(48000, 1)

        val emptyFrame = EncodedAudioFrame(
            aacData = ByteArray(0),
            isConfig = false,
            timestampUs = 0L
        )
        val emptyPackets = packetizer.packetize(emptyFrame)
        assertTrue(emptyPackets.isEmpty())

        val nullRaw = packetizer.buildRawPacket(ByteArray(0), 0L)
        assertNull(nullRaw)

        // Without configuration
        val unconfiguredPacketizer = FlvAudioPacketizer()
        val unconfiguredSeq = unconfiguredPacketizer.buildSequenceHeader()
        assertNull(unconfiguredSeq)
    }

    @Test
    fun `Test 8 - Large AAC payload handling`() {
        packetizer.setAudioConfig(48000, 1)
        packetizer.buildSequenceHeader()

        val largePayload = ByteArray(2048) { (it % 256).toByte() }
        val frame = EncodedAudioFrame(
            aacData = largePayload,
            isConfig = false,
            timestampUs = 500_000L
        )

        val packets = packetizer.packetize(frame)
        assertEquals(1, packets.size)

        val packet = packets[0]
        assertEquals(2 + 2048, packet.payload.size)
        assertEquals(500L, packet.timestampMs)

        val extracted = packet.payload.copyOfRange(2, packet.payload.size)
        assertTrue(largePayload.contentEquals(extracted))
    }
}

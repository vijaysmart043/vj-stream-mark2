package com.example.streaming.packetizer

import com.example.streaming.encoder.EncodedVideoFrame
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
class FlvVideoPacketizerTest {

    private lateinit var packetizer: FlvVideoPacketizer

    // Synthetic SPS (Baseline Profile 0x42, Constraint 0x00, Level 3.1 0x1F)
    private val syntheticSps = byteArrayOf(
        0x67, 0x42, 0x00, 0x1F, 0xE9.toByte(), 0x02, 0xC0.toByte(), 0x2D, 0x08
    )

    // Synthetic PPS
    private val syntheticPps = byteArrayOf(
        0x68, 0xCE.toByte(), 0x38, 0x80.toByte()
    )

    @Before
    fun setUp() {
        packetizer = FlvVideoPacketizer()
    }

    @Test
    fun `1 SPS PPS generates valid AVC sequence header`() {
        packetizer.setSpsPps(syntheticSps, syntheticPps)
        assertTrue(packetizer.hasConfiguration)

        val seqHeader = packetizer.buildSequenceHeader(timestampMs = 0L)
        assertNotNull(seqHeader)
        val packet = seqHeader!!

        assertTrue(packet.isKeyframe)
        assertTrue(packet.isSequenceHeader)
        assertEquals(FlvVideoPacketType.SEQUENCE_HEADER, packet.packetType)
        assertEquals(0L, packet.timestampMs)
        assertEquals(0, packet.compositionTimeMs)

        val payload = packet.payload
        // Minimum size: 5 (FLV header) + 11 (AVCDecoderConfigurationRecord overhead) + SPS.size + PPS.size
        assertEquals(5 + 11 + syntheticSps.size + syntheticPps.size, payload.size)

        // 1. FLV Video Header
        assertEquals(0x17.toByte(), payload[0]) // FrameType=1 (Keyframe), CodecID=7 (AVC)
        assertEquals(0x00.toByte(), payload[1]) // AVCPacketType=0 (Sequence Header)
        assertEquals(0x00.toByte(), payload[2]) // Composition time 0
        assertEquals(0x00.toByte(), payload[3])
        assertEquals(0x00.toByte(), payload[4])

        // 2. AVCDecoderConfigurationRecord
        assertEquals(0x01.toByte(), payload[5]) // configurationVersion = 1
        assertEquals(0x42.toByte(), payload[6]) // Profile (extracted from SPS[1])
        assertEquals(0x00.toByte(), payload[7]) // Profile compatibility (SPS[2])
        assertEquals(0x1F.toByte(), payload[8]) // Level (SPS[3])
        assertEquals(0xFF.toByte(), payload[9]) // lengthSizeMinusOne = 3 (4-byte NAL length)
        assertEquals(0xE1.toByte(), payload[10]) // numOfSPS = 1

        // SPS length (2 bytes Big-Endian)
        val spsLen = ((payload[11].toInt() and 0xFF) shl 8) or (payload[12].toInt() and 0xFF)
        assertEquals(syntheticSps.size, spsLen)

        // PPS count and length
        val ppsCountIndex = 13 + syntheticSps.size
        assertEquals(0x01.toByte(), payload[ppsCountIndex])
        val ppsLen = ((payload[ppsCountIndex + 1].toInt() and 0xFF) shl 8) or (payload[ppsCountIndex + 2].toInt() and 0xFF)
        assertEquals(syntheticPps.size, ppsLen)
    }

    @Test
    fun `2 IDR frame creates valid keyframe AVC NALU packet`() {
        packetizer.setSpsPps(syntheticSps, syntheticPps)

        // Synthetic IDR slice
        val idrNal = byteArrayOf(0x65, 0x88.toByte(), 0x84.toByte(), 0x21, 0x55)
        val idrAnnexB = byteArrayOf(0x00, 0x00, 0x00, 0x01) + idrNal

        val frame = EncodedVideoFrame(
            nalData = idrAnnexB,
            isKeyframe = true,
            isConfig = false,
            timestampUs = 33_333L
        )

        // Sequence header was not yet emitted, so packetize should emit sequence header + IDR packet
        val packets = packetizer.packetize(frame)
        assertEquals(2, packets.size)

        val seqHeader = packets[0]
        assertEquals(FlvVideoPacketType.SEQUENCE_HEADER, seqHeader.packetType)

        val idrPacket = packets[1]
        assertTrue(idrPacket.isKeyframe)
        assertEquals(FlvVideoPacketType.NALU, idrPacket.packetType)
        assertEquals(33L, idrPacket.timestampMs)

        val payload = idrPacket.payload
        // 5 bytes FLV header + 4 bytes NAL length + idrNal.size
        assertEquals(5 + 4 + idrNal.size, payload.size)

        // FLV Header: 0x17 (Keyframe + AVC), 0x01 (AVC NALU), 0x000000 (CTS)
        assertEquals(0x17.toByte(), payload[0])
        assertEquals(0x01.toByte(), payload[1])
        assertEquals(0x00.toByte(), payload[2])
        assertEquals(0x00.toByte(), payload[3])
        assertEquals(0x00.toByte(), payload[4])

        // 4 bytes NAL length
        assertEquals(0x00.toByte(), payload[5])
        assertEquals(0x00.toByte(), payload[6])
        assertEquals(0x00.toByte(), payload[7])
        assertEquals(idrNal.size.toByte(), payload[8])

        // NAL data matches (without start code)
        val actualNal = payload.copyOfRange(9, 9 + idrNal.size)
        assertTrue(idrNal.contentEquals(actualNal))
    }

    @Test
    fun `3 P frame creates valid inter-frame AVC NALU packet`() {
        packetizer.setSpsPps(syntheticSps, syntheticPps)
        packetizer.buildSequenceHeader(0L) // mark sequence header emitted

        // Synthetic non-IDR slice (NAL type 1)
        val pSliceNal = byteArrayOf(0x41, 0x9A.toByte(), 0x24, 0x12)
        val pSliceAnnexB = byteArrayOf(0x00, 0x00, 0x01) + pSliceNal

        val frame = EncodedVideoFrame(
            nalData = pSliceAnnexB,
            isKeyframe = false,
            isConfig = false,
            timestampUs = 66_666L
        )

        val packets = packetizer.packetize(frame)
        assertEquals(1, packets.size)

        val pPacket = packets[0]
        assertFalse(pPacket.isKeyframe)
        assertEquals(FlvVideoPacketType.NALU, pPacket.packetType)
        assertEquals(66L, pPacket.timestampMs)

        val payload = pPacket.payload
        assertEquals(5 + 4 + pSliceNal.size, payload.size)

        // FLV Header: 0x27 (Inter frame + AVC), 0x01 (AVC NALU), 0x000000 (CTS)
        assertEquals(0x27.toByte(), payload[0])
        assertEquals(0x01.toByte(), payload[1])
        assertEquals(0x00.toByte(), payload[2])
        assertEquals(0x00.toByte(), payload[3])
        assertEquals(0x00.toByte(), payload[4])

        // 4 bytes length
        val nalLen = ((payload[5].toInt() and 0xFF) shl 24) or
                ((payload[6].toInt() and 0xFF) shl 16) or
                ((payload[7].toInt() and 0xFF) shl 8) or
                (payload[8].toInt() and 0xFF)
        assertEquals(pSliceNal.size, nalLen)
    }

    @Test
    fun `4 Annex-B to AVCC converts 3-byte and 4-byte start codes correctly`() {
        val nal1 = byteArrayOf(0x65, 0x01, 0x02)
        val nal2 = byteArrayOf(0x41, 0x03, 0x04, 0x05)

        // Buffer containing one 4-byte start code and one 3-byte start code
        val annexB = byteArrayOf(0x00, 0x00, 0x00, 0x01) + nal1 +
                byteArrayOf(0x00, 0x00, 0x01) + nal2

        val avcc = packetizer.annexBToAvcc(annexB)
        assertEquals(4 + nal1.size + 4 + nal2.size, avcc.size)

        // First NAL length prefix
        val len1 = ((avcc[0].toInt() and 0xFF) shl 24) or
                ((avcc[1].toInt() and 0xFF) shl 16) or
                ((avcc[2].toInt() and 0xFF) shl 8) or
                (avcc[3].toInt() and 0xFF)
        assertEquals(nal1.size, len1)
        assertTrue(nal1.contentEquals(avcc.copyOfRange(4, 4 + nal1.size)))

        // Second NAL length prefix
        val offset2 = 4 + nal1.size
        val len2 = ((avcc[offset2].toInt() and 0xFF) shl 24) or
                ((avcc[offset2 + 1].toInt() and 0xFF) shl 16) or
                ((avcc[offset2 + 2].toInt() and 0xFF) shl 8) or
                (avcc[offset2 + 3].toInt() and 0xFF)
        assertEquals(nal2.size, len2)
        assertTrue(nal2.contentEquals(avcc.copyOfRange(offset2 + 4, offset2 + 4 + nal2.size)))
    }

    @Test
    fun `5 NALU length encoding handles large multi-byte sizes correctly`() {
        val largeNal = ByteArray(300) { 0x41 }
        val annexB = byteArrayOf(0x00, 0x00, 0x00, 0x01) + largeNal

        val avcc = packetizer.annexBToAvcc(annexB)
        assertEquals(4 + 300, avcc.size)

        assertEquals(0.toByte(), avcc[0])
        assertEquals(0.toByte(), avcc[1])
        assertEquals(1.toByte(), avcc[2]) // 300 shr 8 = 1
        assertEquals(44.toByte(), avcc[3]) // 300 and 0xFF = 44 (256 + 44 = 300)
    }

    @Test
    fun `6 Timestamp conversion converts microseconds to milliseconds preserving timeline`() {
        packetizer.setSpsPps(syntheticSps, syntheticPps)
        packetizer.buildSequenceHeader()

        val frame1 = EncodedVideoFrame(
            nalData = byteArrayOf(0x41, 0x01),
            isKeyframe = false,
            isConfig = false,
            timestampUs = 1_000_000L // 1.0 second
        )
        val frame2 = EncodedVideoFrame(
            nalData = byteArrayOf(0x41, 0x02),
            isKeyframe = false,
            isConfig = false,
            timestampUs = 1_033_333L // ~1.033 seconds
        )

        val p1 = packetizer.packetize(frame1).first()
        val p2 = packetizer.packetize(frame2).first()

        assertEquals(1000L, p1.timestampMs)
        assertEquals(1033L, p2.timestampMs)
    }

    @Test
    fun `7 Keyframe detection detects IDR NAL unit type automatically`() {
        packetizer.setSpsPps(syntheticSps, syntheticPps)
        packetizer.buildSequenceHeader()

        // NAL type 5 (IDR), even if frame flag is set to false
        val idrNal = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x65, 0x11, 0x22)
        val frame = EncodedVideoFrame(
            nalData = idrNal,
            isKeyframe = false, // false flag, but NAL is IDR!
            isConfig = false,
            timestampUs = 500_000L
        )

        val packets = packetizer.packetize(frame)
        assertEquals(1, packets.size)
        assertTrue(packets[0].isKeyframe)
        assertEquals(0x17.toByte(), packets[0].payload[0]) // Keyframe byte

        // Non-IDR NAL type 1
        val nonIdrNal = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x41, 0x33, 0x44)
        val frameNonIdr = EncodedVideoFrame(
            nalData = nonIdrNal,
            isKeyframe = false,
            isConfig = false,
            timestampUs = 533_000L
        )
        val pNonIdr = packetizer.packetize(frameNonIdr)
        assertEquals(1, pNonIdr.size)
        assertFalse(pNonIdr[0].isKeyframe)
        assertEquals(0x27.toByte(), pNonIdr[0].payload[0]) // Inter-frame byte
    }

    @Test
    fun `8 Empty and invalid NAL handling does not crash or throw`() {
        val emptyAvcc = packetizer.annexBToAvcc(ByteArray(0))
        assertEquals(0, emptyAvcc.size)

        val emptyFrame = EncodedVideoFrame(
            nalData = ByteArray(0),
            isKeyframe = false,
            isConfig = false,
            timestampUs = 0L
        )
        val emptyPackets = packetizer.packetize(emptyFrame)
        assertTrue(emptyPackets.isEmpty())

        val rawNull = packetizer.packetize(ByteArray(0), false, 0L)
        assertNull(rawNull)

        // Invalid short NAL
        val garbageNal = byteArrayOf(0x00, 0x00, 0x00)
        val garbageAvcc = packetizer.annexBToAvcc(garbageNal)
        // Should handle gracefully without exception
        assertNotNull(garbageAvcc)
    }
}

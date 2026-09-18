package com.example.streaming.encoder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class H264NalParserTest {

    @Test
    fun testSplitAnnexBMultipleNals() {
        // SPS NAL (type 7) preceded by 4-byte start code
        // PPS NAL (type 8) preceded by 3-byte start code
        // IDR slice (type 5) preceded by 4-byte start code
        val spsHeader = 0x67.toByte() // 0x67 & 0x1F = 7 (SPS)
        val ppsHeader = 0x68.toByte() // 0x68 & 0x1F = 8 (PPS)
        val idrHeader = 0x65.toByte() // 0x65 & 0x1F = 5 (IDR)

        val stream = byteArrayOf(
            0, 0, 0, 1, spsHeader, 0x42, 0x00, 0x1E,
            0, 0, 1, ppsHeader, 0xCE.toByte(),
            0, 0, 0, 1, idrHeader, 0x88.toByte(), 0x12
        )

        val nals = H264NalParser.splitAnnexB(stream)
        assertEquals(3, nals.size)

        // Verify SPS
        assertEquals(spsHeader, nals[0][0])
        assertTrue(H264NalParser.isSps(nals[0]))
        assertEquals(H264NalParser.NAL_TYPE_SPS, H264NalParser.getNalType(nals[0]))

        // Verify PPS
        assertEquals(ppsHeader, nals[1][0])
        assertTrue(H264NalParser.isPps(nals[1]))
        assertEquals(H264NalParser.NAL_TYPE_PPS, H264NalParser.getNalType(nals[1]))

        // Verify IDR Keyframe
        assertEquals(idrHeader, nals[2][0])
        assertTrue(H264NalParser.isKeyframe(nals[2]))
        assertEquals(H264NalParser.NAL_TYPE_IDR, H264NalParser.getNalType(nals[2]))
    }

    @Test
    fun testStripStartCode() {
        val fourByteStart = byteArrayOf(0, 0, 0, 1, 0x67, 0x42)
        val stripped4 = H264NalParser.stripStartCode(fourByteStart)
        assertEquals(2, stripped4.size)
        assertEquals(0x67.toByte(), stripped4[0])

        val threeByteStart = byteArrayOf(0, 0, 1, 0x68, 0x42)
        val stripped3 = H264NalParser.stripStartCode(threeByteStart)
        assertEquals(2, stripped3.size)
        assertEquals(0x68.toByte(), stripped3[0])
    }

    @Test
    fun testTimestampMonotonicity() {
        val generator = VideoTimestampGenerator()
        generator.reset()

        val ts1 = generator.nextTimestampUs()
        assertEquals(0L, ts1)

        val ts2 = generator.nextTimestampUs()
        assertTrue(ts2 >= ts1)

        val ts3 = generator.nextTimestampUs()
        assertTrue(ts3 >= ts2)
    }
}

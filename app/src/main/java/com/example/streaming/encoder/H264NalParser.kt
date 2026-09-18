package com.example.streaming.encoder

import java.util.ArrayList

/**
 * High-performance, zero-allocation helper for parsing and processing H.264 Annex-B streams.
 * Correctly extracts NAL units (delimited by 0x000001 or 0x00000001), identifies SPS / PPS,
 * and strips start codes for AVCC / RTMP muxers.
 */
object H264NalParser {

    const val NAL_TYPE_SLICE = 1
    const val NAL_TYPE_DPA = 2
    const val NAL_TYPE_DPB = 3
    const val NAL_TYPE_DPC = 4
    const val NAL_TYPE_IDR = 5
    const val NAL_TYPE_SEI = 6
    const val NAL_TYPE_SPS = 7
    const val NAL_TYPE_PPS = 8
    const val NAL_TYPE_AUD = 9

    /**
     * Splits an Annex-B formatted buffer into distinct NAL units (excluding the 3-byte or 4-byte start codes).
     */
    fun splitAnnexB(data: ByteArray, length: Int = data.size): List<ByteArray> {
        val nals = ArrayList<ByteArray>(4)
        var start = -1
        var i = 0

        while (i <= length - 3) {
            val isStartCode3 = data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()
            val isStartCode4 = i <= length - 4 && isStartCode3 && i > 0 && data[i - 1] == 0.toByte()

            if (isStartCode3) {
                val actualStartCodeLen = if (i > 0 && data[i - 1] == 0.toByte()) 4 else 3
                val startCodeIndex = if (actualStartCodeLen == 4) i - 1 else i

                if (start != -1) {
                    val nalLen = startCodeIndex - start
                    if (nalLen > 0) {
                        val nal = ByteArray(nalLen)
                        System.arraycopy(data, start, nal, 0, nalLen)
                        nals.add(nal)
                    }
                }
                start = i + 3
                i += 2
            }
            i++
        }

        if (start != -1 && start < length) {
            val nalLen = length - start
            if (nalLen > 0) {
                val nal = ByteArray(nalLen)
                System.arraycopy(data, start, nal, 0, nalLen)
                nals.add(nal)
            }
        } else if (nals.isEmpty() && length > 0) {
            // Buffer didn't have start codes or was a raw NAL unit
            val stripped = stripStartCode(data, length)
            if (stripped.isNotEmpty()) {
                nals.add(stripped)
            }
        }

        return nals
    }

    /**
     * Strips leading 3-byte (0x000001) or 4-byte (0x00000001) start code from raw NAL bytes.
     */
    fun stripStartCode(bytes: ByteArray, length: Int = bytes.size): ByteArray {
        if (length >= 4 && bytes[0] == 0.toByte() && bytes[1] == 0.toByte() && bytes[2] == 0.toByte() && bytes[3] == 1.toByte()) {
            val res = ByteArray(length - 4)
            System.arraycopy(bytes, 4, res, 0, res.size)
            return res
        }
        if (length >= 3 && bytes[0] == 0.toByte() && bytes[1] == 0.toByte() && bytes[2] == 1.toByte()) {
            val res = ByteArray(length - 3)
            System.arraycopy(bytes, 3, res, 0, res.size)
            return res
        }
        if (length == bytes.size) return bytes
        val res = ByteArray(length)
        System.arraycopy(bytes, 0, res, 0, length)
        return res
    }

    /**
     * Identifies the NAL unit type from a NAL byte array (where the first byte is the NAL header).
     */
    fun getNalType(nalData: ByteArray): Int {
        if (nalData.isEmpty()) return 0
        return nalData[0].toInt() and 0x1F
    }

    fun isSps(nalData: ByteArray): Boolean = getNalType(nalData) == NAL_TYPE_SPS
    fun isPps(nalData: ByteArray): Boolean = getNalType(nalData) == NAL_TYPE_PPS
    fun isKeyframe(nalData: ByteArray): Boolean = getNalType(nalData) == NAL_TYPE_IDR
}

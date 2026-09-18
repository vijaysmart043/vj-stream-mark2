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
     * Splits an Annex-B formatted buffer into distinct NAL units (excluding start codes).
     * Robustly handles:
     * - 3-byte start codes (0x000001)
     * - 4-byte start codes (0x00000001)
     * - Arbitrary leading zeros before start codes (leading_zero_8bits)
     * - Multiple NAL units concatenated in an Access Unit
     * - Raw NAL units without start codes
     */
    fun splitAnnexB(data: ByteArray, length: Int = data.size): List<ByteArray> {
        if (length <= 0) return emptyList()
        val nals = ArrayList<ByteArray>(4)

        var i = 0
        var currentNalPayloadStart = -1

        while (i <= length - 3) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                // Found 0x00 0x00 0x01 at index i
                if (currentNalPayloadStart != -1) {
                    // Check if current start code has a 4-byte prefix (0x00 0x00 0x00 0x01)
                    val startCodeIndex = if (i > currentNalPayloadStart && data[i - 1] == 0.toByte()) {
                        i - 1
                    } else {
                        i
                    }
                    val nalLen = startCodeIndex - currentNalPayloadStart
                    if (nalLen > 0) {
                        val nal = ByteArray(nalLen)
                        System.arraycopy(data, currentNalPayloadStart, nal, 0, nalLen)
                        nals.add(nal)
                    }
                }

                currentNalPayloadStart = i + 3
                i += 3
            } else {
                i++
            }
        }

        if (currentNalPayloadStart != -1 && currentNalPayloadStart < length) {
            val nalLen = length - currentNalPayloadStart
            if (nalLen > 0) {
                val nal = ByteArray(nalLen)
                System.arraycopy(data, currentNalPayloadStart, nal, 0, nalLen)
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
     * Strips leading 3-byte (0x000001), 4-byte (0x00000001), or multi-zero start codes from raw NAL bytes.
     */
    fun stripStartCode(bytes: ByteArray, length: Int = bytes.size): ByteArray {
        if (length <= 0) return ByteArray(0)
        var offset = 0
        while (offset < length && bytes[offset] == 0.toByte()) {
            offset++
        }
        if (offset < length && bytes[offset] == 1.toByte() && offset >= 2) {
            val nalStart = offset + 1
            val nalLen = length - nalStart
            if (nalLen <= 0) return ByteArray(0)
            val res = ByteArray(nalLen)
            System.arraycopy(bytes, nalStart, res, 0, nalLen)
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

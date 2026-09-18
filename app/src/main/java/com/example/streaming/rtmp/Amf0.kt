package com.example.streaming.rtmp

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets

object Amf0 {
    const val TYPE_NUMBER: Byte = 0x00
    const val TYPE_BOOLEAN: Byte = 0x01
    const val TYPE_STRING: Byte = 0x02
    const val TYPE_OBJECT: Byte = 0x03
    const val TYPE_NULL: Byte = 0x05
    const val TYPE_ECMA_ARRAY: Byte = 0x08
    const val OBJECT_END: Byte = 0x09

    fun writeString(out: ByteArrayOutputStream, str: String) {
        out.write(TYPE_STRING.toInt())
        val bytes = str.toByteArray(StandardCharsets.UTF_8)
        out.write((bytes.size shr 8) and 0xFF)
        out.write(bytes.size and 0xFF)
        out.write(bytes)
    }

    fun writeNumber(out: ByteArrayOutputStream, num: Double) {
        out.write(TYPE_NUMBER.toInt())
        val bits = java.lang.Double.doubleToRawLongBits(num)
        for (i in 7 downTo 0) {
            out.write(((bits shr (i * 8)) and 0xFFL).toInt())
        }
    }

    fun writeBoolean(out: ByteArrayOutputStream, b: Boolean) {
        out.write(TYPE_BOOLEAN.toInt())
        out.write(if (b) 1 else 0)
    }

    fun writeNull(out: ByteArrayOutputStream) {
        out.write(TYPE_NULL.toInt())
    }

    fun writeProperty(out: ByteArrayOutputStream, key: String, value: Any?) {
        val keyBytes = key.toByteArray(StandardCharsets.UTF_8)
        out.write((keyBytes.size shr 8) and 0xFF)
        out.write(keyBytes.size and 0xFF)
        out.write(keyBytes)

        when (value) {
            is String -> writeString(out, value)
            is Number -> writeNumber(out, value.toDouble())
            is Boolean -> writeBoolean(out, value)
            null -> writeNull(out)
        }
    }

    fun writeObject(out: ByteArrayOutputStream, properties: Map<String, Any?>) {
        out.write(TYPE_OBJECT.toInt())
        for ((key, value) in properties) {
            writeProperty(out, key, value)
        }
        // Object end: 0x00, 0x00, 0x09
        out.write(0)
        out.write(0)
        out.write(OBJECT_END.toInt())
    }

    fun writeEcmaArray(out: ByteArrayOutputStream, properties: Map<String, Any?>) {
        out.write(TYPE_ECMA_ARRAY.toInt())
        val count = properties.size
        out.write((count shr 24) and 0xFF)
        out.write((count shr 16) and 0xFF)
        out.write((count shr 8) and 0xFF)
        out.write(count and 0xFF)

        for ((key, value) in properties) {
            writeProperty(out, key, value)
        }
        out.write(0)
        out.write(0)
        out.write(OBJECT_END.toInt())
    }

    // --- AMF0 Decoding ---

    fun readString(input: java.io.InputStream): String {
        val b1 = input.read()
        val b2 = input.read()
        if (b1 < 0 || b2 < 0) throw java.io.EOFException("EOF reading AMF string length")
        val len = (b1 shl 8) or b2
        val bytes = ByteArray(len)
        var readTotal = 0
        while (readTotal < len) {
            val r = input.read(bytes, readTotal, len - readTotal)
            if (r < 0) throw java.io.EOFException("EOF reading AMF string body")
            readTotal += r
        }
        return String(bytes, StandardCharsets.UTF_8)
    }

    fun readLongString(input: java.io.InputStream): String {
        val b1 = input.read()
        val b2 = input.read()
        val b3 = input.read()
        val b4 = input.read()
        if (b1 < 0 || b2 < 0 || b3 < 0 || b4 < 0) throw java.io.EOFException("EOF reading AMF long string length")
        val len = (b1 shl 24) or (b2 shl 16) or (b3 shl 8) or b4
        val bytes = ByteArray(len)
        var readTotal = 0
        while (readTotal < len) {
            val r = input.read(bytes, readTotal, len - readTotal)
            if (r < 0) throw java.io.EOFException("EOF reading AMF long string body")
            readTotal += r
        }
        return String(bytes, StandardCharsets.UTF_8)
    }

    fun readNumber(input: java.io.InputStream): Double {
        val bytes = ByteArray(8)
        var readTotal = 0
        while (readTotal < 8) {
            val r = input.read(bytes, readTotal, 8 - readTotal)
            if (r < 0) throw java.io.EOFException("EOF reading AMF number")
            readTotal += r
        }
        var bits = 0L
        for (i in 0 until 8) {
            bits = (bits shl 8) or (bytes[i].toLong() and 0xFFL)
        }
        return java.lang.Double.longBitsToDouble(bits)
    }

    fun readBoolean(input: java.io.InputStream): Boolean {
        val b = input.read()
        if (b < 0) throw java.io.EOFException("EOF reading AMF boolean")
        return b != 0
    }

    fun readObject(input: java.io.InputStream): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        while (true) {
            val b1 = input.read()
            val b2 = input.read()
            if (b1 < 0 || b2 < 0) break
            val keyLen = (b1 shl 8) or b2
            if (keyLen == 0) {
                val endMarker = input.read()
                if (endMarker.toByte() == OBJECT_END) {
                    break
                }
            }
            val keyBytes = ByteArray(keyLen)
            var rKey = 0
            while (rKey < keyLen) {
                val r = input.read(keyBytes, rKey, keyLen - rKey)
                if (r < 0) break
                rKey += r
            }
            val key = String(keyBytes, StandardCharsets.UTF_8)
            val value = readValue(input)
            map[key] = value
        }
        return map
    }

    fun readEcmaArray(input: java.io.InputStream): Map<String, Any?> {
        // 4 bytes count
        input.skip(4)
        return readObject(input)
    }

    fun readValue(input: java.io.InputStream): Any? {
        val marker = input.read()
        if (marker < 0) return null
        return when (marker.toByte()) {
            TYPE_NUMBER -> readNumber(input)
            TYPE_BOOLEAN -> readBoolean(input)
            TYPE_STRING -> readString(input)
            TYPE_OBJECT -> readObject(input)
            TYPE_NULL, 0x06.toByte() -> null
            TYPE_ECMA_ARRAY -> readEcmaArray(input)
            0x0C.toByte() -> readLongString(input)
            else -> null
        }
    }
}

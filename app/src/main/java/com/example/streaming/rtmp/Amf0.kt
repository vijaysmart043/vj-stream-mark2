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
    const val TYPE_UNDEFINED: Byte = 0x06
    const val TYPE_ECMA_ARRAY: Byte = 0x08
    const val OBJECT_END: Byte = 0x09
    const val TYPE_STRICT_ARRAY: Byte = 0x0A
    const val TYPE_DATE: Byte = 0x0B
    const val TYPE_LONG_STRING: Byte = 0x0C

    fun decode(bytes: ByteArray): List<Any?> {
        val stream = java.io.ByteArrayInputStream(bytes)
        val result = mutableListOf<Any?>()
        while (stream.available() > 0) {
            val item = readValue(stream) ?: break
            result.add(if (item === NullMarker) null else item)
        }
        return result
    }

    private object NullMarker

    fun readValue(stream: java.io.InputStream): Any? {
        val type = stream.read()
        if (type == -1) return null
        return when (type.toByte()) {
            TYPE_NUMBER -> readNumberValue(stream)
            TYPE_BOOLEAN -> stream.read() != 0
            TYPE_STRING -> readStringValue(stream)
            TYPE_OBJECT -> readObjectValue(stream)
            TYPE_NULL, TYPE_UNDEFINED -> NullMarker
            TYPE_ECMA_ARRAY -> readEcmaArrayValue(stream)
            TYPE_STRICT_ARRAY -> readStrictArrayValue(stream)
            TYPE_DATE -> {
                readNumberValue(stream) // 8-byte timestamp
                stream.read() // 2-byte timezone
                stream.read()
                NullMarker
            }
            TYPE_LONG_STRING -> readLongStringValue(stream)
            else -> null
        }
    }

    private fun readNumberValue(stream: java.io.InputStream): Double {
        var bits = 0L
        for (i in 0 until 8) {
            val b = stream.read()
            if (b == -1) break
            bits = (bits shl 8) or (b.toLong() and 0xFFL)
        }
        return Double.fromBits(bits)
    }

    private fun readStringValue(stream: java.io.InputStream): String {
        val lenHigh = stream.read()
        val lenLow = stream.read()
        if (lenHigh == -1 || lenLow == -1) return ""
        val len = (lenHigh shl 8) or lenLow
        val buf = ByteArray(len)
        var readTotal = 0
        while (readTotal < len) {
            val r = stream.read(buf, readTotal, len - readTotal)
            if (r <= 0) break
            readTotal += r
        }
        return String(buf, 0, readTotal, StandardCharsets.UTF_8)
    }

    private fun readLongStringValue(stream: java.io.InputStream): String {
        var len = 0
        for (i in 0 until 4) {
            val b = stream.read()
            if (b == -1) break
            len = (len shl 8) or b
        }
        val buf = ByteArray(len)
        var readTotal = 0
        while (readTotal < len) {
            val r = stream.read(buf, readTotal, len - readTotal)
            if (r <= 0) break
            readTotal += r
        }
        return String(buf, 0, readTotal, StandardCharsets.UTF_8)
    }

    private fun readObjectValue(stream: java.io.InputStream): Map<String, Any?> {
        val map = linkedMapOf<String, Any?>()
        while (true) {
            val lenHigh = stream.read()
            val lenLow = stream.read()
            if (lenHigh == -1 || lenLow == -1) break
            val keyLen = (lenHigh shl 8) or lenLow
            if (keyLen == 0) {
                // Object end marker: 0x09
                stream.read()
                break
            }
            val keyBuf = ByteArray(keyLen)
            var readTotal = 0
            while (readTotal < keyLen) {
                val r = stream.read(keyBuf, readTotal, keyLen - readTotal)
                if (r <= 0) break
                readTotal += r
            }
            val key = String(keyBuf, 0, readTotal, StandardCharsets.UTF_8)
            val value = readValue(stream)
            map[key] = if (value === NullMarker) null else value
        }
        return map
    }

    private fun readEcmaArrayValue(stream: java.io.InputStream): Map<String, Any?> {
        // Skip 4-byte array count
        for (i in 0 until 4) stream.read()
        return readObjectValue(stream)
    }

    private fun readStrictArrayValue(stream: java.io.InputStream): List<Any?> {
        var count = 0
        for (i in 0 until 4) {
            val b = stream.read()
            if (b == -1) break
            count = (count shl 8) or b
        }
        val list = mutableListOf<Any?>()
        for (i in 0 until count) {
            val v = readValue(stream)
            list.add(if (v === NullMarker) null else v)
        }
        return list
    }

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
}

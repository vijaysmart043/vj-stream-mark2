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
}

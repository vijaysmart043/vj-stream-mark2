package com.example.streaming.rtmp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class Amf0Test {

    @Test
    fun testEncodeDecodeRoundTrip() {
        val baos = ByteArrayOutputStream()
        Amf0.writeString(baos, "connect")
        Amf0.writeNumber(baos, 1.0)
        Amf0.writeObject(baos, mapOf("app" to "live2", "fpad" to false))

        val decoded = Amf0.decode(baos.toByteArray())
        assertEquals(3, decoded.size)
        assertEquals("connect", decoded[0])
        assertEquals(1.0, (decoded[1] as Number).toDouble(), 0.001)

        val obj = decoded[2] as Map<*, *>
        assertEquals("live2", obj["app"])
        assertEquals(false, obj["fpad"])
    }

    @Test
    fun testDecodeCreateStreamResponse() {
        val baos = ByteArrayOutputStream()
        Amf0.writeString(baos, "_result")
        Amf0.writeNumber(baos, 4.0)
        Amf0.writeNull(baos)
        Amf0.writeNumber(baos, 1.0) // streamId = 1.0

        val decoded = Amf0.decode(baos.toByteArray())
        assertEquals(4, decoded.size)
        assertEquals("_result", decoded[0])
        assertEquals(4.0, (decoded[1] as Number).toDouble(), 0.001)
        assertEquals(null, decoded[2])
        assertEquals(1, (decoded[3] as Number).toInt())
    }

    @Test
    fun testDecodeOnStatusResponse() {
        val baos = ByteArrayOutputStream()
        Amf0.writeString(baos, "onStatus")
        Amf0.writeNumber(baos, 0.0)
        Amf0.writeNull(baos)
        Amf0.writeObject(baos, mapOf(
            "level" to "status",
            "code" to "NetStream.Publish.Start",
            "description" to "Publishing live"
        ))

        val decoded = Amf0.decode(baos.toByteArray())
        assertEquals(4, decoded.size)
        assertEquals("onStatus", decoded[0])
        val info = decoded[3] as Map<*, *>
        assertEquals("status", info["level"])
        assertEquals("NetStream.Publish.Start", info["code"])
    }
}

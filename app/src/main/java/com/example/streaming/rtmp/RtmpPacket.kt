package com.example.streaming.rtmp

data class RtmpPacket(
    val messageType: Byte,
    val chunkStreamId: Int,
    val messageStreamId: Int,
    val timestamp: Long,
    val data: ByteArray,
    val isKeyframe: Boolean = false
) {
    companion object {
        const val TYPE_SET_CHUNK_SIZE: Byte = 0x01
        const val TYPE_AUDIO: Byte = 0x08
        const val TYPE_VIDEO: Byte = 0x09
        const val TYPE_DATA_AMF0: Byte = 0x12
        const val TYPE_COMMAND_AMF0: Byte = 0x14

        const val CSID_CONTROL = 2
        const val CSID_COMMAND = 3
        const val CSID_AUDIO = 4
        const val CSID_VIDEO = 6
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as RtmpPacket
        if (messageType != other.messageType) return false
        if (chunkStreamId != other.chunkStreamId) return false
        if (messageStreamId != other.messageStreamId) return false
        if (timestamp != other.timestamp) return false
        if (!data.contentEquals(other.data)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = messageType.toInt()
        result = 31 * result + chunkStreamId
        result = 31 * result + messageStreamId
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}

package com.btshare.bluetoothrelay.protocol

enum class PacketType(val wireValue: Int) {
    DATA(1),
    ACKNOWLEDGEMENT(2),
    ;

    companion object {
        fun fromWireValue(value: Int): PacketType =
            entries.firstOrNull { it.wireValue == value }
                ?: throw ProtocolException("Unknown packet type: $value")
    }
}

data class RelayPacket(
    val type: PacketType,
    val sequence: Long,
    val payload: ByteArray = byteArrayOf(),
) {
    init {
        require(sequence > 0) { "Sequence numbers must be positive" }
    }

    override fun equals(other: Any?): Boolean =
        other is RelayPacket &&
            type == other.type &&
            sequence == other.sequence &&
            payload.contentEquals(other.payload)

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + sequence.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

class ProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)

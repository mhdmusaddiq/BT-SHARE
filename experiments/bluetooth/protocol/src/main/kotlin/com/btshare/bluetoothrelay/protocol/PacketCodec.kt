package com.btshare.bluetoothrelay.protocol

import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PacketCodec(
    private val maximumFrameBodyBytes: Int = DEFAULT_MAXIMUM_FRAME_BODY_BYTES,
) {
    init {
        require(maximumFrameBodyBytes >= HEADER_BYTES) {
            "Maximum frame body must fit the protocol header"
        }
    }

    val maximumPayloadBytes: Int = maximumFrameBodyBytes - HEADER_BYTES

    @Throws(ProtocolException::class)
    fun read(input: InputStream): RelayPacket? {
        val firstLengthByte = input.read()
        if (firstLengthByte == -1) return null

        val lengthBytes = ByteArray(LENGTH_PREFIX_BYTES)
        lengthBytes[0] = firstLengthByte.toByte()
        readFully(input, lengthBytes, 1, LENGTH_PREFIX_BYTES - 1, "length prefix")

        val frameBodyLength = ByteBuffer.wrap(lengthBytes)
            .order(ByteOrder.BIG_ENDIAN)
            .int
        if (frameBodyLength !in HEADER_BYTES..maximumFrameBodyBytes) {
            throw ProtocolException(
                "Invalid frame body length $frameBodyLength; expected $HEADER_BYTES..$maximumFrameBodyBytes",
            )
        }

        val body = ByteArray(frameBodyLength)
        readFully(input, body, 0, body.size, "frame body")
        val buffer = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN)

        val version = buffer.get().toInt() and 0xFF
        if (version != PROTOCOL_VERSION) {
            throw ProtocolException("Unsupported protocol version: $version")
        }

        val type = PacketType.fromWireValue(buffer.get().toInt() and 0xFF)
        val sequence = buffer.long
        if (sequence <= 0) {
            throw ProtocolException("Invalid sequence number: $sequence")
        }

        val payload = ByteArray(buffer.remaining())
        buffer.get(payload)
        return RelayPacket(type, sequence, payload).also(AudioWireFormat::validatePacket)
    }

    @Throws(ProtocolException::class)
    fun write(output: OutputStream, packet: RelayPacket) {
        if (packet.payload.size > maximumPayloadBytes) {
            throw ProtocolException(
                "Payload is ${packet.payload.size} bytes; maximum is $maximumPayloadBytes",
            )
        }
        AudioWireFormat.validatePacket(packet)

        val bodyLength = HEADER_BYTES + packet.payload.size
        val dataOutput = DataOutputStream(output)
        dataOutput.writeInt(bodyLength)
        dataOutput.writeByte(PROTOCOL_VERSION)
        dataOutput.writeByte(packet.type.wireValue)
        dataOutput.writeLong(packet.sequence)
        dataOutput.write(packet.payload)
        dataOutput.flush()
    }

    private fun readFully(
        input: InputStream,
        destination: ByteArray,
        offset: Int,
        length: Int,
        fieldName: String,
    ) {
        var position = offset
        val end = offset + length
        while (position < end) {
            val count = input.read(destination, position, end - position)
            if (count == -1) {
                throw ProtocolException("Truncated $fieldName", EOFException())
            }
            if (count == 0) continue
            position += count
        }
    }

    companion object {
        const val PROTOCOL_VERSION = 1
        const val LENGTH_PREFIX_BYTES = Int.SIZE_BYTES
        const val HEADER_BYTES = 1 + 1 + Long.SIZE_BYTES
        const val DEFAULT_MAXIMUM_FRAME_BODY_BYTES = 64 * 1024
    }
}

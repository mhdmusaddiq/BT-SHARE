package com.btshare.bluetoothrelay.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PacketCodecTest {
    private val codec = PacketCodec()

    @Test
    fun `round trips a unicode data packet`() {
        val expected = RelayPacket(
            PacketType.DATA,
            42,
            "Hello, ලෝකය 🌍".toByteArray(),
        )
        val encoded = ByteArrayOutputStream()

        codec.write(encoded, expected)
        val actual = codec.read(ByteArrayInputStream(encoded.toByteArray()))

        assertEquals(expected.type, actual?.type)
        assertEquals(expected.sequence, actual?.sequence)
        assertArrayEquals(expected.payload, actual?.payload)
    }

    @Test
    fun `reads adjacent frames without consuming the next frame`() {
        val encoded = ByteArrayOutputStream()
        codec.write(encoded, RelayPacket(PacketType.DATA, 1, byteArrayOf(10)))
        codec.write(encoded, RelayPacket(PacketType.ACKNOWLEDGEMENT, 1))
        val input = ByteArrayInputStream(encoded.toByteArray())

        assertEquals(PacketType.DATA, codec.read(input)?.type)
        assertEquals(PacketType.ACKNOWLEDGEMENT, codec.read(input)?.type)
        assertNull(codec.read(input))
    }

    @Test
    fun `clean end of stream returns null`() {
        assertNull(codec.read(ByteArrayInputStream(byteArrayOf())))
    }

    @Test
    fun `rejects a frame shorter than the protocol header`() {
        val encoded = ByteArrayOutputStream()
        DataOutputStream(encoded).writeInt(PacketCodec.HEADER_BYTES - 1)

        assertThrows(ProtocolException::class.java) {
            codec.read(ByteArrayInputStream(encoded.toByteArray()))
        }
    }

    @Test
    fun `rejects an oversized frame before allocating its body`() {
        val encoded = ByteArrayOutputStream()
        DataOutputStream(encoded).writeInt(PacketCodec.DEFAULT_MAXIMUM_FRAME_BODY_BYTES + 1)

        assertThrows(ProtocolException::class.java) {
            codec.read(ByteArrayInputStream(encoded.toByteArray()))
        }
    }

    @Test
    fun `rejects a truncated frame body`() {
        val encoded = ByteArrayOutputStream()
        DataOutputStream(encoded).apply {
            writeInt(PacketCodec.HEADER_BYTES + 4)
            write(byteArrayOf(1, 1, 0))
        }

        assertThrows(ProtocolException::class.java) {
            codec.read(ByteArrayInputStream(encoded.toByteArray()))
        }
    }

    @Test
    fun `rejects acknowledgement payloads`() {
        assertThrows(ProtocolException::class.java) {
            codec.write(
                ByteArrayOutputStream(),
                RelayPacket(PacketType.ACKNOWLEDGEMENT, 1, byteArrayOf(1)),
            )
        }
    }
}

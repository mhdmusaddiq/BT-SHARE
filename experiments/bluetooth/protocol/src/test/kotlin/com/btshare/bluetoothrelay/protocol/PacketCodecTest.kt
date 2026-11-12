package com.btshare.bluetoothrelay.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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

    @Test
    fun `round trips all audio frame types`() {
        val metadata = AudioWireFormat.metadataFor(8, 4)
        val expected = listOf(
            RelayPacket(PacketType.AUDIO_START, 10, AudioWireFormat.encodeStart(metadata)),
            RelayPacket(
                PacketType.AUDIO_CHUNK,
                11,
                AudioWireFormat.encodeChunk(10, 0, byteArrayOf(1, 2, 3, 4)),
            ),
            RelayPacket(
                PacketType.AUDIO_END,
                12,
                AudioWireFormat.encodeEnd(AudioEndPayload(10, 1, 4, AudioEndReason.STOPPED)),
            ),
        )
        val encoded = ByteArrayOutputStream()
        expected.forEach { codec.write(encoded, it) }
        val input = ByteArrayInputStream(encoded.toByteArray())

        expected.forEach { packet ->
            assertEquals(packet, codec.read(input))
        }
        assertNull(codec.read(input))
    }

    @Test
    fun `rejects invalid audio metadata`() {
        val error = assertThrows(ProtocolException::class.java) {
            AudioWireFormat.encodeStart(
                AudioStreamMetadata(
                    sampleRateHz = 48_000,
                    channelCount = 1,
                    encoding = AudioEncoding.PCM_SIGNED_16_BIT_LITTLE_ENDIAN,
                    declaredPcmBytes = 1_600,
                    chunkSizeBytes = 1_024,
                ),
            )
        }

        assertTrue(error.message.orEmpty().contains("sample rate"))
    }

    @Test
    fun `rejects invalid audio packet lengths`() {
        assertThrows(ProtocolException::class.java) {
            codec.write(
                ByteArrayOutputStream(),
                RelayPacket(
                    PacketType.AUDIO_START,
                    1,
                    ByteArray(AudioWireFormat.START_PAYLOAD_BYTES - 1),
                ),
            )
        }
        assertThrows(ProtocolException::class.java) {
            codec.write(
                ByteArrayOutputStream(),
                RelayPacket(
                    PacketType.AUDIO_CHUNK,
                    2,
                    AudioWireFormat.encodeChunk(
                        streamId = 1,
                        chunkIndex = 0,
                        pcmBytes = ByteArray(AudioWireFormat.MAXIMUM_CHUNK_BYTES + 2),
                    ),
                ),
            )
        }
        assertThrows(ProtocolException::class.java) {
            codec.write(
                ByteArrayOutputStream(),
                RelayPacket(
                    PacketType.AUDIO_END,
                    3,
                    ByteArray(AudioWireFormat.END_PAYLOAD_BYTES + 1),
                ),
            )
        }
    }

    @Test
    fun `keeps B0 data and acknowledgement bytes compatible`() {
        val encoded = ByteArrayOutputStream()
        codec.write(encoded, RelayPacket(PacketType.DATA, 1, byteArrayOf(0x68, 0x69)))
        codec.write(encoded, RelayPacket(PacketType.ACKNOWLEDGEMENT, 1))

        assertArrayEquals(
            byteArrayOf(
                0, 0, 0, 12, 1, 1, 0, 0, 0, 0, 0, 0, 0, 1, 0x68, 0x69,
                0, 0, 0, 10, 1, 2, 0, 0, 0, 0, 0, 0, 0, 1,
            ),
            encoded.toByteArray(),
        )
    }
}

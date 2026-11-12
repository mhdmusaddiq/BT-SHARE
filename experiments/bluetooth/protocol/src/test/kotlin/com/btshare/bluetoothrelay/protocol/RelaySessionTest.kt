package com.btshare.bluetoothrelay.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test

class RelaySessionTest {
    private val codec = PacketCodec()

    @Test
    fun `received data is delivered and acknowledged with the same sequence`() {
        val incoming = ByteArrayOutputStream().also {
            codec.write(it, RelayPacket(PacketType.DATA, 12, "relay me".toByteArray()))
        }
        val outgoing = ByteArrayOutputStream()
        val listener = RecordingListener()
        val session = RelaySession(
            ByteArrayInputStream(incoming.toByteArray()),
            outgoing,
            listener,
        )

        session.readLoop()

        assertEquals(listOf(12L to "relay me"), listener.messages)
        val acknowledgement = codec.read(ByteArrayInputStream(outgoing.toByteArray()))
        assertEquals(PacketType.ACKNOWLEDGEMENT, acknowledgement?.type)
        assertEquals(12L, acknowledgement?.sequence)
    }

    @Test
    fun `sent data receives an RTT when its acknowledgement arrives`() {
        var nowNanos = 1_000L
        val outgoing = ByteArrayOutputStream()
        val listener = RecordingListener()
        val session = RelaySession(
            ByteArrayInputStream(byteArrayOf()),
            outgoing,
            listener,
            rttTracker = RttTracker { nowNanos },
        )

        val sequence = session.sendText("ping")
        nowNanos = 6_500L
        session.handleIncoming(RelayPacket(PacketType.ACKNOWLEDGEMENT, sequence))

        val sent = codec.read(ByteArrayInputStream(outgoing.toByteArray()))
        assertEquals(PacketType.DATA, sent?.type)
        assertEquals(sequence, sent?.sequence)
        assertEquals(listOf(sequence to 5_500L), listener.acknowledgements)
    }

    @Test
    fun `unexpected acknowledgement is reported without a fake RTT`() {
        val listener = RecordingListener()
        val session = RelaySession(
            ByteArrayInputStream(byteArrayOf()),
            ByteArrayOutputStream(),
            listener,
        )

        session.handleIncoming(RelayPacket(PacketType.ACKNOWLEDGEMENT, 88))

        assertEquals(listOf(88L), listener.unexpectedAcknowledgements)
        assertEquals(emptyList<Pair<Long, Long>>(), listener.acknowledgements)
    }

    @Test
    fun `malformed UTF-8 data is rejected`() {
        val session = RelaySession(
            ByteArrayInputStream(byteArrayOf()),
            ByteArrayOutputStream(),
            RecordingListener(),
        )

        assertThrows(ProtocolException::class.java) {
            session.handleIncoming(
                RelayPacket(PacketType.DATA, 1, byteArrayOf(0xC3.toByte(), 0x28)),
            )
        }
    }

    @Test
    fun `audio uses start ordered chunks and end without DATA decoding`() {
        val outgoing = ByteArrayOutputStream()
        val session = RelaySession(
            ByteArrayInputStream(byteArrayOf()),
            outgoing,
            RecordingListener(),
        )
        val pcm = ByteArray(10) { it.toByte() }

        val summary = session.sendAudio(pcm, chunkSizeBytes = 4)
        val input = ByteArrayInputStream(outgoing.toByteArray())
        val start = codec.read(input)
        val first = codec.read(input)
        val second = codec.read(input)
        val third = codec.read(input)
        val end = codec.read(input)

        assertEquals(PacketType.AUDIO_START, start?.type)
        assertEquals(summary.streamId, start?.sequence)
        assertEquals(4, AudioWireFormat.decodeStart(start!!.payload).chunkSizeBytes)
        assertEquals(0, AudioWireFormat.decodeChunk(first!!.payload).chunkIndex)
        assertEquals(1, AudioWireFormat.decodeChunk(second!!.payload).chunkIndex)
        assertEquals(2, AudioWireFormat.decodeChunk(third!!.payload).chunkIndex)
        assertEquals(PacketType.AUDIO_END, end?.type)
        assertEquals(AudioEndReason.COMPLETED, AudioWireFormat.decodeEnd(end!!.payload).reason)
        assertEquals(3, summary.chunkCount)
        assertEquals(10L, summary.pcmBytes)
    }

    @Test
    fun `audio discontinuities and duplicates are counted and stream is truncated`() {
        val listener = RecordingListener()
        val session = RelaySession(
            ByteArrayInputStream(byteArrayOf()),
            ByteArrayOutputStream(),
            listener,
        )
        val metadata = AudioWireFormat.metadataFor(12, 4)

        session.handleIncoming(
            RelayPacket(PacketType.AUDIO_START, 10, AudioWireFormat.encodeStart(metadata)),
        )
        session.handleIncoming(
            RelayPacket(
                PacketType.AUDIO_CHUNK,
                11,
                AudioWireFormat.encodeChunk(10, 0, byteArrayOf(1, 2, 3, 4)),
            ),
        )
        session.handleIncoming(
            RelayPacket(
                PacketType.AUDIO_CHUNK,
                12,
                AudioWireFormat.encodeChunk(10, 2, byteArrayOf(5, 6, 7, 8)),
            ),
        )
        session.handleIncoming(
            RelayPacket(
                PacketType.AUDIO_CHUNK,
                13,
                AudioWireFormat.encodeChunk(10, 0, byteArrayOf(1, 2, 3, 4)),
            ),
        )
        session.handleIncoming(
            RelayPacket(
                PacketType.AUDIO_END,
                14,
                AudioWireFormat.encodeEnd(AudioEndPayload(10, 3, 12, AudioEndReason.COMPLETED)),
            ),
        )

        val summary = listener.audioSummaries.single()
        assertEquals(AudioStreamResult.TRUNCATED, summary.result)
        assertEquals(1, summary.missingChunks)
        assertEquals(1, summary.duplicateChunks)
        assertEquals(2, summary.outOfOrderChunks)
        assertEquals(8L, summary.pcmBytes)
    }

    @Test
    fun `end of connection marks an active audio stream truncated`() {
        val incoming = ByteArrayOutputStream().also {
            codec.write(
                it,
                RelayPacket(
                    PacketType.AUDIO_START,
                    20,
                    AudioWireFormat.encodeStart(AudioWireFormat.metadataFor(4, 4)),
                ),
            )
        }
        val listener = RecordingListener()
        val session = RelaySession(
            ByteArrayInputStream(incoming.toByteArray()),
            ByteArrayOutputStream(),
            listener,
        )

        session.readLoop()

        val summary = listener.audioSummaries.singleOrNull()
        assertNotNull(summary)
        assertEquals(AudioStreamResult.TRUNCATED, summary?.result)
    }

    private class RecordingListener : RelaySession.Listener {
        val messages = mutableListOf<Pair<Long, String>>()
        val acknowledgements = mutableListOf<Pair<Long, Long>>()
        val unexpectedAcknowledgements = mutableListOf<Long>()
        val audioSummaries = mutableListOf<AudioReceiveSummary>()

        override fun onMessageReceived(sequence: Long, text: String) {
            messages += sequence to text
        }

        override fun onAcknowledgementReceived(sequence: Long, roundTripNanos: Long) {
            acknowledgements += sequence to roundTripNanos
        }

        override fun onUnexpectedAcknowledgement(sequence: Long) {
            unexpectedAcknowledgements += sequence
        }

        override fun onAudioStreamEnded(summary: AudioReceiveSummary) {
            audioSummaries += summary
        }
    }
}

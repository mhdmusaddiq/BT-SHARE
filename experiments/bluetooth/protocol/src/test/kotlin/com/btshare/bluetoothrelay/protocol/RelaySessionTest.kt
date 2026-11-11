package com.btshare.bluetoothrelay.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
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

    private class RecordingListener : RelaySession.Listener {
        val messages = mutableListOf<Pair<Long, String>>()
        val acknowledgements = mutableListOf<Pair<Long, Long>>()
        val unexpectedAcknowledgements = mutableListOf<Long>()

        override fun onMessageReceived(sequence: Long, text: String) {
            messages += sequence to text
        }

        override fun onAcknowledgementReceived(sequence: Long, roundTripNanos: Long) {
            acknowledgements += sequence to roundTripNanos
        }

        override fun onUnexpectedAcknowledgement(sequence: Long) {
            unexpectedAcknowledgements += sequence
        }
    }
}

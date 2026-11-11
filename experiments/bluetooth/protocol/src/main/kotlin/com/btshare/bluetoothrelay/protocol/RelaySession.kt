package com.btshare.bluetoothrelay.protocol

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicLong

class RelaySession(
    private val input: InputStream,
    private val output: OutputStream,
    private val listener: Listener,
    private val codec: PacketCodec = PacketCodec(),
    private val rttTracker: RttTracker = RttTracker(),
) : Closeable {
    interface Listener {
        fun onMessageReceived(sequence: Long, text: String)
        fun onAcknowledgementReceived(sequence: Long, roundTripNanos: Long)
        fun onUnexpectedAcknowledgement(sequence: Long)
    }

    private val nextSequence = AtomicLong(1L)
    private val writeLock = Any()

    @Throws(Exception::class)
    fun sendText(text: String): Long {
        val payload = text.toByteArray(StandardCharsets.UTF_8)
        if (payload.size > codec.maximumPayloadBytes) {
            throw ProtocolException(
                "UTF-8 message is ${payload.size} bytes; maximum is ${codec.maximumPayloadBytes}",
            )
        }

        val sequence = nextSequence.getAndIncrement()
        if (sequence <= 0) {
            throw IllegalStateException("Sequence number space exhausted")
        }

        rttTracker.markSent(sequence)
        try {
            write(RelayPacket(PacketType.DATA, sequence, payload))
        } catch (error: Exception) {
            rttTracker.forget(sequence)
            throw error
        }
        return sequence
    }

    @Throws(Exception::class)
    fun readLoop() {
        while (true) {
            val packet = codec.read(input) ?: return
            handleIncoming(packet)
        }
    }

    internal fun handleIncoming(packet: RelayPacket) {
        when (packet.type) {
            PacketType.DATA -> {
                val text = decodeUtf8(packet.payload)
                listener.onMessageReceived(packet.sequence, text)
                write(RelayPacket(PacketType.ACKNOWLEDGEMENT, packet.sequence))
            }

            PacketType.ACKNOWLEDGEMENT -> {
                val roundTripNanos = rttTracker.acknowledge(packet.sequence)
                if (roundTripNanos == null) {
                    listener.onUnexpectedAcknowledgement(packet.sequence)
                } else {
                    listener.onAcknowledgementReceived(packet.sequence, roundTripNanos)
                }
            }
        }
    }

    private fun write(packet: RelayPacket) {
        synchronized(writeLock) {
            codec.write(output, packet)
        }
    }

    private fun decodeUtf8(payload: ByteArray): String {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(payload))
                .toString()
        } catch (error: Exception) {
            throw ProtocolException("DATA payload is not valid UTF-8", error)
        }
    }

    override fun close() {
        rttTracker.clear()
        runCatching { input.close() }
        runCatching { output.close() }
    }
}

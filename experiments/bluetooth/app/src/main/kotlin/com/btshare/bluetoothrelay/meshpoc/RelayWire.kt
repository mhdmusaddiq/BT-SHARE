package com.btshare.bluetoothrelay.meshpoc

import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** Independent POC v2 framing; deliberately NOT compatible with existing B0 audio wire frames.
 * Open a POC v2 socket ONLY from the new Relay Demo activity on all three phones.
 */
enum class RelayFrameType(val id: Int) {
    TEXT(1), AUDIO_START(2), AUDIO_CHUNK(3), AUDIO_END(4), END_TO_END_ACK(5);
    companion object {
        fun parse(id: Int): RelayFrameType = entries.firstOrNull { it.id == id }
            ?: throw RelayWireException("Unknown frame type $id")
    }
}

class RelayWireException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class RelayFrame(
    val type: RelayFrameType,
    val sessionId: UUID,
    val originId: UUID,
    val streamId: Long,
    val sequence: Long,
    val ttl: Int,
    val hops: Int,
    val payload: ByteArray,
) {
    init {
        require(streamId > 0) { "streamId must be positive" }
        require(sequence > 0) { "sequence must be positive" }
        require(ttl in 0..RelayWire.MAX_TTL) { "Invalid TTL" }
        require(hops in 0..RelayWire.MAX_HOPS) { "Invalid hops" }
        require(payload.size <= RelayWire.MAX_PAYLOAD) { "Payload too large" }
    }
    fun forwarded(): RelayFrame = copy(ttl = ttl - 1, hops = hops + 1)
}

object RelayWire {
    const val MAGIC = 0x42545232 // BTR2
    const val VERSION = 2
    const val HEADER_BYTES = 60
    const val MAX_FRAME_BODY_BYTES = 8192
    const val MAX_PAYLOAD = MAX_FRAME_BODY_BYTES - HEADER_BYTES
    const val MAX_TTL = 8
    const val MAX_HOPS = 8

    fun encode(frame: RelayFrame): ByteArray {
        val length = HEADER_BYTES + frame.payload.size
        require(length <= MAX_FRAME_BODY_BYTES)
        val bb = ByteBuffer.allocate(4 + length).order(ByteOrder.BIG_ENDIAN)
        bb.putInt(length)
        bb.putInt(MAGIC)
        bb.put(VERSION.toByte())
        bb.put(frame.type.id.toByte())
        bb.put(frame.ttl.toByte())
        bb.put(frame.hops.toByte())
        putUuid(bb, frame.sessionId)
        putUuid(bb, frame.originId)
        bb.putLong(frame.streamId)
        bb.putLong(frame.sequence)
        bb.putInt(frame.payload.size)
        bb.put(frame.payload)
        return bb.array()
    }

    fun write(output: OutputStream, frame: RelayFrame) {
        val stream = DataOutputStream(output)
        stream.write(encode(frame))
        stream.flush()
    }

    /** Returns null on clean EOF; fails closed on partial, malformed, or oversize frames. */
    fun read(input: InputStream): RelayFrame? {
        val first = input.read()
        if (first < 0) return null
        val prefix = ByteArray(4)
        prefix[0] = first.toByte()
        readExactly(input, prefix, 1, 3)
        val length = ByteBuffer.wrap(prefix).order(ByteOrder.BIG_ENDIAN).int
        if (length !in HEADER_BYTES..MAX_FRAME_BODY_BYTES) {
            throw RelayWireException("Invalid relay frame body length: $length")
        }
        val body = ByteArray(length)
        readExactly(input, body, 0, length)
        val bb = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN)
        if (bb.int != MAGIC) throw RelayWireException("Bad wire magic")
        if ((bb.get().toInt() and 255) != VERSION) throw RelayWireException("Unsupported protocol version")
        val type = RelayFrameType.parse(bb.get().toInt() and 255)
        val ttl = bb.get().toInt() and 255
        val hops = bb.get().toInt() and 255
        if (ttl > MAX_TTL || hops > MAX_HOPS) throw RelayWireException("Invalid routing metadata")
        val sessionId = readUuid(bb)
        val originId = readUuid(bb)
        val streamId = bb.long
        val sequence = bb.long
        val payloadBytes = bb.int
        if (payloadBytes < 0 || payloadBytes != bb.remaining() || payloadBytes > MAX_PAYLOAD) {
            throw RelayWireException("Invalid payload length")
        }
        val payload = ByteArray(payloadBytes)
        bb.get(payload)
        if (streamId <= 0 || sequence <= 0) throw RelayWireException("Nonpositive stream/sequence")
        if (type == RelayFrameType.END_TO_END_ACK && payload.isNotEmpty()) {
            throw RelayWireException("ACK payload must be empty")
        }
        return RelayFrame(type, sessionId, originId, streamId, sequence, ttl, hops, payload)
    }

    private fun readExactly(input: InputStream, into: ByteArray, start: Int, size: Int) {
        var index = start
        val end = start + size
        while (index < end) {
            val count = input.read(into, index, end - index)
            if (count < 0) throw RelayWireException("Truncated Bluetooth stream", EOFException())
            if (count == 0) continue
            index += count
        }
    }

    private fun putUuid(bb: ByteBuffer, id: UUID) {
        bb.putLong(id.mostSignificantBits)
        bb.putLong(id.leastSignificantBits)
    }
    private fun readUuid(bb: ByteBuffer): UUID = UUID(bb.long, bb.long)
}

class RelayDuplicateGuard(private val capacity: Int = 4096) {
    init { require(capacity in 1..100_000) }
    private val seen = object : LinkedHashMap<String, Boolean>(capacity + 1, .75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > capacity
    }
    @Synchronized
    fun shouldProcess(frame: RelayFrame): Boolean {
        if (frame.ttl <= 0 || frame.hops >= RelayWire.MAX_HOPS) return false
        val key = "${frame.sessionId}|${frame.originId}|${frame.streamId}|${frame.sequence}|${frame.type.id}"
        if (seen.containsKey(key)) return false
        seen[key] = true
        return true
    }
    @Synchronized fun clear() { seen.clear() }
}

package com.btshare.bluetoothrelay.protocol

import java.util.concurrent.ConcurrentHashMap

class RttTracker(
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val sentAtNanos = ConcurrentHashMap<Long, Long>()

    fun markSent(sequence: Long) {
        require(sequence > 0) { "Sequence numbers must be positive" }
        check(sentAtNanos.putIfAbsent(sequence, nanoTime()) == null) {
            "Sequence $sequence is already awaiting acknowledgement"
        }
    }

    fun acknowledge(sequence: Long): Long? {
        val sentAt = sentAtNanos.remove(sequence) ?: return null
        return (nanoTime() - sentAt).coerceAtLeast(0L)
    }

    fun forget(sequence: Long) {
        sentAtNanos.remove(sequence)
    }

    fun clear() {
        sentAtNanos.clear()
    }

    fun pendingCount(): Int = sentAtNanos.size
}

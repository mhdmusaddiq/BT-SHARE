package com.btshare.bluetoothrelay.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class RttTrackerTest {
    private var nowNanos = 0L
    private val tracker = RttTracker { nowNanos }

    @Test
    fun `measures elapsed monotonic time for an acknowledgement`() {
        nowNanos = 1_000L
        tracker.markSent(7)
        nowNanos = 2_750L

        assertEquals(1_750L, tracker.acknowledge(7))
        assertEquals(0, tracker.pendingCount())
    }

    @Test
    fun `returns null for an unknown or duplicate acknowledgement`() {
        assertNull(tracker.acknowledge(99))
        tracker.markSent(1)
        assertEquals(0L, tracker.acknowledge(1))
        assertNull(tracker.acknowledge(1))
    }

    @Test
    fun `rejects duplicate pending sequence numbers`() {
        tracker.markSent(3)

        assertThrows(IllegalStateException::class.java) {
            tracker.markSent(3)
        }
    }

    @Test
    fun `clamps a backwards test clock to zero`() {
        nowNanos = 10L
        tracker.markSent(5)
        nowNanos = 2L

        assertEquals(0L, tracker.acknowledge(5))
    }
}

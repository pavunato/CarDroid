package com.cardroid.runner

import com.cardroid.lib.Iap2Frame
import com.cardroid.lib.Iap2ReceiveSequence
import com.cardroid.lib.Iap2SendWindow
import kotlin.test.*

class Iap2SendWindowTest {
    private class Harness(window: Int, retries: Int = 2, initial: Int = 0) {
        var seq = initial
        val sent = mutableListOf<Pair<Int, Boolean>>()
        val acked = mutableListOf<Int>()
        val link = Iap2SendWindow(window, 100, retries,
            makeFrame = { payload -> Iap2Frame(0x40, (seq++ and 255).toByte(), 0, 1, payload) },
            emit = { frame, retransmit -> sent += (frame.seq.toInt() and 255) to retransmit },
            acknowledged = { acked += it.seq.toInt() and 255 })
    }
    @Test fun twoFramesRemainTrackedAndCumulativeAckReleasesBoth() {
        val h = Harness(5)
        h.link.enqueue(byteArrayOf(1), 0); h.link.enqueue(byteArrayOf(2), 1)
        assertEquals(2, h.link.pendingCount)
        h.link.acknowledge(1, 2)
        assertEquals(listOf(0, 1), h.acked)
        assertEquals(0, h.link.pendingCount)
    }
    @Test fun windowOneQueuesUntilAckAndIgnoresStaleAck() {
        val h = Harness(1)
        h.link.enqueue(byteArrayOf(1), 0); h.link.enqueue(byteArrayOf(2), 0)
        h.link.acknowledge(99, 1)
        assertEquals(listOf(0 to false), h.sent)
        h.link.acknowledge(0, 2)
        assertEquals(listOf(0 to false, 1 to false), h.sent)
        h.link.acknowledge(0, 3)
        assertEquals(1, h.link.pendingCount)
    }
    @Test fun silenceRetriesAndExhaustionDropsWaitingFrames() {
        val h = Harness(1, retries = 1)
        h.link.enqueue(byteArrayOf(1), 0); h.link.enqueue(byteArrayOf(2), 0)
        assertTrue(h.link.tick(99)); assertEquals(1, h.sent.size)
        assertTrue(h.link.tick(100)); assertEquals(0 to true, h.sent.last())
        assertFalse(h.link.tick(200)); assertEquals(0, h.link.queuedCount)
        assertFalse(h.link.enqueue(byteArrayOf(3), 201))
    }
    @Test fun cumulativeAckAndReceiveOrderWorkAcrossWrap() {
        val h = Harness(5, initial = 255)
        h.link.enqueue(byteArrayOf(1), 0); h.link.enqueue(byteArrayOf(2), 0)
        h.link.acknowledge(0, 1)
        assertEquals(listOf(255, 0), h.acked)
        val receive = Iap2ReceiveSequence()
        assertEquals(Iap2ReceiveSequence.Result.ACCEPTED, receive.accept(255.toByte()))
        assertEquals(Iap2ReceiveSequence.Result.OUT_OF_ORDER, receive.accept(1))
        assertContentEquals(byteArrayOf(0), receive.missingBefore(1))
        assertEquals(Iap2ReceiveSequence.Result.DUPLICATE, receive.accept(255.toByte()))
        assertEquals(Iap2ReceiveSequence.Result.ACCEPTED, receive.accept(0))
    }
    @Test fun extendedAckRetransmitsOnlyRequestedOutstandingFrames() {
        val h = Harness(5, retries = 1)
        repeat(3) { h.link.enqueue(byteArrayOf(it.toByte()), 0) }
        assertTrue(h.link.retransmitRequested(byteArrayOf(1, 99), 10))
        assertEquals(listOf(0 to false, 1 to false, 2 to false, 1 to true), h.sent)
        assertFalse(h.link.retransmitRequested(byteArrayOf(1), 11))
        assertEquals(0, h.link.pendingCount)
    }
}

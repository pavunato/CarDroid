package com.cardroid.lib

/** Ordered iAP2 send window. The owner serializes calls and supplies a monotonic clock. */
internal class Iap2SendWindow(
    private val windowSize: Int,
    private val timeoutMs: Long,
    private val maxRetries: Int,
    private val makeFrame: (ByteArray) -> Iap2Frame,
    private val emit: (Iap2Frame, Boolean) -> Unit,
    private val acknowledged: (Iap2Frame) -> Unit = {},
) {
    private data class Pending(val frame: Iap2Frame, var sentAt: Long, var retries: Int = 0)
    private val waiting = ArrayDeque<ByteArray>()
    private val pending = ArrayDeque<Pending>()
    val pendingCount get() = pending.size
    val queuedCount get() = waiting.size
    private var failed = false

    init { require(windowSize in 1..127 && timeoutMs > 0 && maxRetries >= 0) }

    fun enqueue(payload: ByteArray, now: Long): Boolean {
        if (failed || waiting.size + pending.size >= MAX_BUFFERED) return false
        waiting.addLast(payload.copyOf())
        pump(now)
        return true
    }

    /** The ACK is cumulative only when it matches a currently outstanding sequence. */
    fun acknowledge(seq: Byte, now: Long) {
        val index = pending.indexOfFirst { it.frame.seq == seq }
        if (index < 0) return
        repeat(index + 1) { acknowledged(pending.removeFirst().frame) }
        pump(now)
    }

    /** An EAK payload lists missing sequence numbers the peer wants retransmitted. */
    fun retransmitRequested(sequences: ByteArray, now: Long): Boolean {
        if (failed) return false
        val requested = pending.filter { item -> sequences.any { it == item.frame.seq } }
        if (requested.any { it.retries >= maxRetries }) return fail()
        requested.forEach {
            it.retries++
            it.sentAt = now
            emit(it.frame, true)
        }
        return true
    }

    /** Returns false when retry exhaustion invalidates the link. */
    fun tick(now: Long): Boolean {
        if (failed) return false
        val expired = pending.filter { now - it.sentAt >= timeoutMs }
        if (expired.any { it.retries >= maxRetries }) {
            return fail()
        }
        expired.forEach {
            it.retries++
            it.sentAt = now
            emit(it.frame, true)
        }
        return true
    }

    private fun fail(): Boolean {
        failed = true
        waiting.clear()
        pending.clear()
        return false
    }

    private fun pump(now: Long) {
        while (pending.size < windowSize && waiting.isNotEmpty()) {
            val frame = makeFrame(waiting.removeFirst())
            pending.addLast(Pending(frame, now))
            emit(frame, false)
        }
    }

    companion object { const val MAX_BUFFERED = 128 }
}

/** Checks sequenced payloads without advancing on duplicates or gaps. */
internal class Iap2ReceiveSequence {
    private var accepted: Int? = null
    val lastAccepted: Byte? get() = accepted?.toByte()
    enum class Result { ACCEPTED, DUPLICATE, OUT_OF_ORDER }
    /** The peer's SYN is the initial sequenced packet, including across wraparound. */
    fun initialize(synSeq: Byte) { accepted = synSeq.toInt() and 0xff }
    fun missingBefore(seq: Byte): ByteArray {
        val last = accepted ?: return byteArrayOf()
        val distance = ((seq.toInt() and 0xff) - last) and 0xff
        if (distance !in 2..127) return byteArrayOf()
        return ByteArray(distance - 1) { ((last + it + 1) and 0xff).toByte() }
    }
    fun accept(seq: Byte): Result {
        val value = seq.toInt() and 0xff
        val last = accepted
        if (last == null || value == ((last + 1) and 0xff)) {
            accepted = value
            return Result.ACCEPTED
        }
        return if (value == last) Result.DUPLICATE else Result.OUT_OF_ORDER
    }
}

package com.cardroid.lib.airplay

/** RTP-to-NTP anchor packet sent to an AirPlay audio stream's control port. */
object AudioSyncPacket {
    const val SIZE = 20
    private const val PT_SYNC = 0xD4
    private const val RTP_VERSION = 0x80
    private const val FIRST_EXTENSION = 0x10

    fun build(
        sequence: Int,
        currentRtpTimestamp: Long,
        nextRtpTimestamp: Long,
        ntp: ByteArray,
        first: Boolean,
    ): ByteArray {
        require(ntp.size == 8) { "NTP timestamp must be 8 bytes" }
        val packet = ByteArray(SIZE)
        packet[0] = (RTP_VERSION or if (first) FIRST_EXTENSION else 0).toByte()
        packet[1] = PT_SYNC.toByte()
        writeBe16(packet, 2, sequence)
        writeBe32(packet, 4, currentRtpTimestamp)
        ntp.copyInto(packet, 8)
        writeBe32(packet, 16, nextRtpTimestamp)
        return packet
    }

    /** Seconds from the NTP epoch (1900-01-01) to the Unix epoch. */
    const val NTP_UNIX_OFFSET_SECONDS = 2_208_988_800L

    /**
     * Shifts `System.nanoTime()` into NTP-epoch wall-clock nanoseconds, captured once so the
     * sender clock stays monotonic. A real iPhone answers timing requests on the wall clock
     * (NTP64 ≈ 2^63.9); an uptime clock gives the receiver a 7-byte synced timestamp that
     * DiPlay 0.2.22 cannot plist-encode in its /feedback reply (HTTP 500, session end).
     */
    private val senderEpochOffsetNanos: Long = run {
        val wallMs = System.currentTimeMillis()
        (wallMs / 1000L + NTP_UNIX_OFFSET_SECONDS) * 1_000_000_000L + (wallMs % 1000L) * 1_000_000L -
            System.nanoTime()
    }

    /** A `System.nanoTime()` reading in the sender's NTP-epoch clock domain. */
    fun senderNanos(monotonicNanos: Long): Long = monotonicNanos + senderEpochOffsetNanos

    /** Now on the sender clock, as NTP64 bytes. */
    fun ntpNow(): ByteArray = ntpFromNanos(senderNanos(System.nanoTime()))

    /** Nanoseconds since the NTP epoch encoded as an NTP 64-bit seconds/fraction pair. */
    fun ntpFromNanos(nanos: Long): ByteArray {
        val seconds = Math.floorDiv(nanos, 1_000_000_000L)
        val fractionNanos = Math.floorMod(nanos, 1_000_000_000L)
        val fraction = (fractionNanos shl 32) / 1_000_000_000L
        val packed = (seconds shl 32) or (fraction and 0xffff_ffffL)
        return ByteArray(8) { i -> (packed ushr (8 * (7 - i))).toByte() }
    }

    private fun writeBe16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 8).toByte()
        target[offset + 1] = value.toByte()
    }

    private fun writeBe32(target: ByteArray, offset: Int, value: Long) {
        val v = value.toInt()
        target[offset] = (v ushr 24).toByte()
        target[offset + 1] = (v ushr 16).toByte()
        target[offset + 2] = (v ushr 8).toByte()
        target[offset + 3] = v.toByte()
    }
}

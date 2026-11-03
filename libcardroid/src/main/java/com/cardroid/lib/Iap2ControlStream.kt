package com.cardroid.lib

import java.io.IOException

/** Reassembles the ordered control byte stream across link frames. Owned by one session. */
internal class Iap2ControlStream {
    private val buffer = ByteArray(MAX_MESSAGE_SIZE)
    private var count = 0
    private var expected = 0

    /** A malformed header ends the link; never ACK and silently discard control bytes. */
    fun feed(bytes: ByteArray): List<ByteArray> {
        val messages = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < bytes.size) {
            val target = if (expected == 0) HEADER_SIZE else expected
            val amount = minOf(target - count, bytes.size - offset)
            bytes.copyInto(buffer, count, offset, offset + amount)
            count += amount
            offset += amount
            if (count >= 2 && (buffer[0] != Iap2ControlSession.SOM_0 || buffer[1] != Iap2ControlSession.SOM_1)) {
                throw IOException("Invalid iAP2 control message marker")
            }
            if (expected == 0 && count == HEADER_SIZE) {
                expected = ((buffer[2].toInt() and 0xff) shl 8) or (buffer[3].toInt() and 0xff)
                if (expected < HEADER_SIZE) throw IOException("Invalid iAP2 control message length")
            }
            if (expected != 0 && count == expected) {
                messages += buffer.copyOf(count)
                count = 0
                expected = 0
            }
        }
        return messages
    }

    companion object {
        private const val HEADER_SIZE = 6
        private const val MAX_MESSAGE_SIZE = 0xffff
    }
}

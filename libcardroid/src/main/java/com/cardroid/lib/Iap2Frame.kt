package com.cardroid.lib

import java.io.InputStream
import java.io.OutputStream

/**
 * iAP2 Link-layer frame.
 *
 * Verified against public reverse engineering at public iAP2 research.
 *
 * Wire layout:
 *
 *   off  bytes  field
 *   0    2      header             = FF 5A
 *   2    2      length             = total packet length (header 9 + payload N + payload-checksum 1)
 *                                    OR 9 if no payload
 *   4    1      control            = SYN(0x80)/ACK(0x40)/EAK(0x20)/RST(0x10) bitfield
 *   5    1      seq                = sequence number
 *   6    1      ack                = ack number
 *   7    1      session            = session-id
 *   8    1      header checksum    = (-sum(bytes[0..7])) & 0xFF
 *   9    N      payload            = upper-layer bytes (only if length > 9)
 *   9+N  1      payload checksum   = (-sum(payload[0..N-1])) & 0xFF (only if payload present)
 *
 * Control bit semantics (from reference reverse engineering):
 *   SYN = 0x80   link-sync exchange
 *   ACK = 0x40   acknowledging peer's packet
 *   EAK = 0x20   extended ack (selective ack list in payload)
 *   RST = 0x10   reset link
 */
data class Iap2Frame(
    val control: Byte,
    val seq: Byte,
    val ack: Byte,
    val session: Byte,
    val payload: ByteArray,
) {
    fun encode(): ByteArray {
        val hasPayload = payload.isNotEmpty()
        val length = if (hasPayload) 10 + payload.size else 9
        require(length <= MAX_FRAME_LENGTH) { "iAP2 frame exceeds 16-bit length" }
        val totalWireSize = if (hasPayload) 9 + payload.size + 1 else 9
        val out = ByteArray(totalWireSize)

        // Header (8 bytes + 1 checksum)
        out[0] = Iap2Constants.LINK_HEADER_0
        out[1] = Iap2Constants.LINK_HEADER_1
        out[2] = ((length ushr 8) and 0xFF).toByte()
        out[3] = (length and 0xFF).toByte()
        out[4] = control
        out[5] = seq
        out[6] = ack
        out[7] = session

        var headerSum = 0
        for (i in 0..7) headerSum += out[i].toInt() and 0xFF
        out[8] = ((-headerSum) and 0xFF).toByte()

        if (hasPayload) {
            System.arraycopy(payload, 0, out, 9, payload.size)
            var paySum = 0
            for (b in payload) paySum += b.toInt() and 0xFF
            out[9 + payload.size] = ((-paySum) and 0xFF).toByte()
        }
        return out
    }

    companion object {
        const val MAX_FRAME_LENGTH = 0xFFFF
        /** Read exactly one frame from [stream], blocking until complete or EOF. */
        fun readFrom(stream: InputStream): Iap2Frame? = readFrom(stream, onSkip = null, onRawWire = null)

        /**
         * Same as [readFrom] but reports every byte discarded during the FF5A
         * resync (useful for spotting DETECT markers) and the raw wire bytes
         * of the parsed frame (useful for verifying our parser layout).
         */
        fun readFrom(
            stream: InputStream,
            onSkip: ((Byte) -> Unit)?,
            onRawWire: ((ByteArray) -> Unit)? = null,
        ): Iap2Frame? {
            // Resync to FF 5A.
            var b0 = stream.read()
            while (b0 != -1) {
                if (b0 == 0xFF) {
                    val b1 = stream.read()
                    if (b1 == 0x5A) break
                    if (b1 == -1) return null
                    onSkip?.invoke(0xFF.toByte())
                    onSkip?.invoke((b1 and 0xFF).toByte())
                } else {
                    onSkip?.invoke((b0 and 0xFF).toByte())
                }
                b0 = stream.read()
            }
            if (b0 == -1) return null

            // Read remaining 7 bytes of header.
            val hdr = ByteArray(9)
            hdr[0] = 0xFF.toByte(); hdr[1] = 0x5A.toByte()
            var off = 2
            while (off < 9) {
                val n = stream.read(hdr, off, 9 - off)
                if (n <= 0) return null
                off += n
            }

            // Verify header checksum.
            var sum = 0
            for (i in 0..7) sum += hdr[i].toInt() and 0xFF
            val expectedHdrChk = ((-sum) and 0xFF).toByte()
            if (hdr[8] != expectedHdrChk) return null

            val length = ((hdr[2].toInt() and 0xFF) shl 8) or (hdr[3].toInt() and 0xFF)
            val control = hdr[4]
            val seq = hdr[5]
            val ack = hdr[6]
            val session = hdr[7]

            if (length < 9 || length > MAX_FRAME_LENGTH) {
                onRawWire?.invoke(hdr)
                return null
            }

            val payload = if (length > 9) {
                val payloadLen = length - 10 // -9 header -1 payload-checksum
                if (payloadLen < 0) {
                    onRawWire?.invoke(hdr)
                    return null
                }
                val payloadAndChk = ByteArray(payloadLen + 1)
                var p = 0
                while (p < payloadAndChk.size) {
                    val n = stream.read(payloadAndChk, p, payloadAndChk.size - p)
                    if (n <= 0) {
                        onRawWire?.invoke(hdr + payloadAndChk.copyOf(p))
                        return null
                    }
                    p += n
                }
                onRawWire?.invoke(hdr + payloadAndChk)
                // Verify payload checksum.
                var pSum = 0
                for (i in 0 until payloadLen) pSum += payloadAndChk[i].toInt() and 0xFF
                val expectedPayChk = ((-pSum) and 0xFF).toByte()
                if (payloadAndChk[payloadLen] != expectedPayChk) return null
                payloadAndChk.copyOfRange(0, payloadLen)
            } else {
                onRawWire?.invoke(hdr)
                ByteArray(0)
            }
            return Iap2Frame(control, seq, ack, session, payload)
        }
    }
}

fun OutputStream.writeFrame(frame: Iap2Frame) {
    write(frame.encode())
    flush()
}

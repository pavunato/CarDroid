package com.cardroid.lib.airplay

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.concurrent.atomic.AtomicLong

/**
 * AirPlay timing responder (NTP over RTCP) on the same clock as [AudioSyncPacket].
 *
 * The receiver is the timing client: it sends 32-byte 0xD2 requests to the
 * `timingPort` advertised in SETUP and syncs its media clock to our replies.
 * Buffered MainHigh (type 102) audio is scheduled on that clock, so the
 * replies must use the same time base as the audio sync anchors
 * (NTP-epoch wall clock via [AudioSyncPacket.senderNanos], like a real iPhone).  The native
 * OkCar responder's clock is not visible, which is why this exists.
 *
 * Wire format (big-endian): [0x80][pt][length=7][rtp_ts=0][originate 8][receive 8][transmit 8].
 */
class AirPlayTimingServer(private val log: (String) -> Unit = {}) {
    private var socket: DatagramSocket? = null
    @Volatile private var running = false
    private val requests = AtomicLong()
    private var lastLogMs = 0L

    val port: Int get() = socket?.localPort ?: 0
    val requestCount: Long get() = requests.get()

    fun start(): Int {
        val s = DatagramSocket()
        socket = s
        running = true
        Thread({ loop(s) }, "cardroid-timing").apply { isDaemon = true; start() }
        log("AirPlay: Java timing server on port ${s.localPort}")
        return s.localPort
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        socket = null
        log("AirPlay: Java timing server stopped after ${requests.get()} requests")
    }

    private fun loop(s: DatagramSocket) {
        val buffer = ByteArray(256)
        while (running) {
            val packet = DatagramPacket(buffer, buffer.size)
            try { s.receive(packet) } catch (_: Exception) { return }
            val received = AudioSyncPacket.senderNanos(System.nanoTime())
            val response = buildResponse(
                buffer.copyOf(packet.length), received, AudioSyncPacket.senderNanos(System.nanoTime()),
            )
            if (response == null) {
                log("AirPlay: timing server ignored ${packet.length}B non-timesync packet")
                continue
            }
            val n = requests.incrementAndGet()
            val now = System.currentTimeMillis()
            if (n <= 3 || now - lastLogMs >= 30_000L) {
                lastLogMs = now
                log("AirPlay: timing reply #$n -> ${packet.address.hostAddress}:${packet.port}")
            }
            runCatching { s.send(DatagramPacket(response, response.size, packet.address, packet.port)) }
        }
    }

    companion object {
        const val PT_REQUEST = 0xD2
        const val PT_RESPONSE = 0xD3
        const val SIZE = 32

        /** Reply to a 0xD2 request, or null for anything else. Originate echoes the request's transmit time. */
        fun buildResponse(request: ByteArray, receivedNanos: Long, transmitNanos: Long): ByteArray? {
            if (request.size < SIZE) return null
            if ((request[0].toInt() and 0xff) shr 6 != 2) return null
            if (request[1].toInt() and 0xff != PT_REQUEST) return null
            val length = ((request[2].toInt() and 0xff) shl 8) or (request[3].toInt() and 0xff)
            if (length != SIZE / 4 - 1) return null
            val response = ByteArray(SIZE)
            response[0] = 0x80.toByte()
            response[1] = PT_RESPONSE.toByte()
            response[3] = (SIZE / 4 - 1).toByte()
            request.copyInto(response, 8, 24, 32)
            AudioSyncPacket.ntpFromNanos(receivedNanos).copyInto(response, 16)
            AudioSyncPacket.ntpFromNanos(transmitNanos).copyInto(response, 24)
            return response
        }
    }
}

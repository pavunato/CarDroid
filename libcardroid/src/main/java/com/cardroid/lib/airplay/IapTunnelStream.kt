package com.cardroid.lib.airplay

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Send-only iAP2 tunnel (SETUP stream type 130) to the receiver's dataPort, car-touch-iap2-tunnel Stage 1.
 * Each [send] writes ONE APTransportPackage ('comm') through [CarPlayRecordOutput] keyed with
 * [CarPlayMediaWire.streamKey] (shared, seed). Never reads (receivers treat the tunnel as receive-only).
 * Failures close only the tunnel and are reported via [log]; they never throw into callers' media paths
 * except [send] which throws so the caller can decide.
 */
class IapTunnelStream(
    private val wire: OutputStream,
    key: ByteArray,
    val seed: Long,
    private val log: (String) -> Unit = {},
    private val closeable: Closeable? = null,
) : Closeable {
    private val out = CarPlayRecordOutput(BufferedOutputStream(wire, 32 * 1024), key)
    private val closed = AtomicBoolean(false)
    private var packages = 0L

    val isClosed: Boolean get() = closed.get()

    @Synchronized fun send(iap2: ByteArray) {
        check(!closed.get()) { "tunnel closed" }
        val pkg = IapTunnelPackage.wrap(iap2)
        try {
            out.write(pkg); out.flush()
        } catch (e: Exception) {
            log("[tunnel] write failed: ${e.javaClass.simpleName}: ${e.message}; closing tunnel only")
            close(); throw e
        }
        val k = ++packages
        if (k <= 5 || k % 30 == 0L) log("[tunnel] wrote ${pkg.size} bytes pkt#$k")
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { closeable?.close() ?: wire.close() }
    }

    companion object {
        const val CLIENT_TYPE_UUID = "E9459FD0-BCAD-4C45-820F-1E72447EF2F2"
        const val STREAM_TYPE = 130
        const val CONNECT_TIMEOUT_MS = 5000

        fun seedText(seed: Long): String = java.lang.Long.toUnsignedString(seed)

        /**
         * Random seed in [2^32, 2^63): a valid u64 that is non-negative as a signed Long, so every plist reader
         * (signed or unsigned 8-byte int) derives the same "DataStream-Salt<seed>" decimal, and the bplist encoder
         * never picks a narrow width for it.
         */
        fun newSeed(random: SecureRandom = SecureRandom()): Long {
            while (true) {
                val v = random.nextLong() and Long.MAX_VALUE
                if (v >= 1L shl 32) return v
            }
        }

        /**
         * Tunnel SETUP entry exactly as a real iPhone sends it (Receiver capture, iOS 26.7.1):
         * {controlType=2, channelID=<random MAC>-RCS-1, seed, clientUUID, type=130,
         *  wantsDedicatedSocket=true, sendMessageAsIs=true, clientTypeUUID}; no streamConnectionID.
         */
        fun streamEntry(
            seed: Long,
            clientUuid: String = java.util.UUID.randomUUID().toString().uppercase(),
            channelId: String = nextChannelId(),
        ): AirPlayBplist.Value = AirPlayBplist.dict(
            "controlType" to AirPlayBplist.int(2),
            "channelID" to AirPlayBplist.str(channelId),
            "seed" to AirPlayBplist.int(seed),
            "clientUUID" to AirPlayBplist.str(clientUuid),
            "type" to AirPlayBplist.int(STREAM_TYPE),
            "wantsDedicatedSocket" to AirPlayBplist.bool(true),
            "sendMessageAsIs" to AirPlayBplist.bool(true),
            "clientTypeUUID" to AirPlayBplist.str(CLIENT_TYPE_UUID),
        )

        /** "<locally-administered MAC>-RCS-<n>", the iPhone's channelID shape (e.g. 52:34:11:BE:8F:48-RCS-1). */
        fun randomChannelId(random: SecureRandom = SecureRandom()): String {
            val b = ByteArray(6).also { random.nextBytes(it) }
            b[0] = ((b[0].toInt() and 0xFC) or 0x02).toByte() // unicast, locally administered
            return b.joinToString(":") { "%02X".format(it) } + "-RCS-1"
        }

        /**
         * A real iPhone names the tunnel channel "<receiver Wi-Fi BSSID>-RCS-<n>" and counts its tunnel
         * sessions: RCS-1, RCS-2, RCS-3 across three DiPlay connections hours apart, each time with
         * DiPlay's P2P BSSID (the bssid of iAP2 0x4301). Without a BSSID, a stable random MAC is used.
         */
        private val fallbackChannelMac: String = randomChannelId().substringBefore("-RCS-")
        private val sessionChannelCount = java.util.concurrent.atomic.AtomicInteger()

        fun nextChannelId(receiverBssid: String? = null): String {
            val mac = receiverBssid?.uppercase()?.takeIf { Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}").matches(it) }
                ?: fallbackChannelMac
            return "$mac-RCS-${sessionChannelCount.incrementAndGet()}"
        }

        /** dataPort of the type-130 reply entry (our SETUP carried exactly one), or null if absent/invalid. */
        fun dataPort(replies: List<*>): Int? =
            replies.filterIsInstance<Map<*, *>>().firstOrNull { (it["type"] as? Number)?.toInt() == STREAM_TYPE }
                ?.let { (it["dataPort"] as? Number)?.toInt() }?.takeIf { it in 1..65535 }

        /** One-line description of every reply stream, e.g. `130/1/45678`: type/streamID/dataPort. */
        fun describeReplies(replies: List<*>): String = replies.filterIsInstance<Map<*, *>>().joinToString(",") {
            "${it["type"]}/${it["streamID"] ?: "-"}/${it["dataPort"] ?: "-"}"
        }

        /**
         * Connect to the receiver's dataPort with the caller's (network-bound) socket and wrap it.
         * [bind] is the orchestrator's addressBinder. Throws on failure; callers must catch and continue without tunnel.
         */
        fun open(
            socket: Socket, bind: ((Socket) -> Unit)?, host: InetAddress, port: Int,
            shared: ByteArray, seed: Long, log: (String) -> Unit,
        ): IapTunnelStream {
            try {
                bind?.invoke(socket); socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                log("[tunnel] connected dataPort=$port seed=${seedText(seed)}")
                return IapTunnelStream(socket.getOutputStream(), CarPlayMediaWire.streamKey(shared, seed), seed, log, socket)
            } catch (e: Exception) { runCatching { socket.close() }; throw e }
        }
    }
}

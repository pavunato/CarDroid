package com.cardroid.runner

import com.cardroid.lib.airplay.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.*

/** car-touch-iap2-tunnel Stage 1: package framing, type-130 SETUP entry, and TestReceiver-style receive round trip. */
class IapTunnelTest {
    private val shared = ByteArray(32) { (it * 7 + 1).toByte() }

    /** Test-local receiver mirroring TestReceiver iapTunnel.ts: [2B LE len][ct][16B tag], AAD=len, nonce counter from 0. */
    private fun decryptAll(wire: ByteArray, seed: Long): ByteArray {
        val key = CarPlayCrypto.hkdf(shared, "DataStream-Salt${java.lang.Long.toUnsignedString(seed)}", "DataStream-Output-Encryption-Key")
        val plain = ByteArrayOutputStream(); var pos = 0; var counter = 0L
        while (pos < wire.size) {
            val len = (wire[pos].toInt() and 255) or ((wire[pos + 1].toInt() and 255) shl 8)
            assertTrue(len <= 0x4000)
            val aad = wire.copyOfRange(pos, pos + 2)
            plain.write(CarPlayCrypto.open(key, CarPlayCrypto.nonce(counter++), wire.copyOfRange(pos + 2, pos + 2 + len + 16), aad))
            pos += 2 + len + 16
        }
        return plain.toByteArray()
    }

    private fun packages(plain: ByteArray): List<ByteArray> {
        val out = mutableListOf<ByteArray>(); var pos = 0
        while (pos < plain.size) {
            val size = IapTunnelPackage.sizeAt(plain, pos)!!
            out += IapTunnelPackage.unwrap(plain.copyOfRange(pos, pos + size))!!; pos += size
        }
        return out
    }

    @Test fun wrapHeaderLayout() {
        val body = byteArrayOf(0xFF.toByte(), 0x55, 2, 0, 0xEE.toByte(), 0x10)
        val p = IapTunnelPackage.wrap(body)
        assertEquals(38, p.size)
        val b = ByteBuffer.wrap(p).order(ByteOrder.BIG_ENDIAN)
        assertEquals(38, b.getInt(0)); assertEquals(0x636F6D6D, b.getInt(16))
        for (i in 0 until 32) if (i !in 0..3 && i !in 16..19) assertEquals(0, p[i].toInt(), "byte $i")
        assertContentEquals(body, p.copyOfRange(32, 38))
        assertContentEquals(body, IapTunnelPackage.unwrap(p))
    }

    @Test fun unwrapRejectsBadSizeAndType() {
        val ok = IapTunnelPackage.wrap(byteArrayOf(1, 2, 3))
        assertNull(IapTunnelPackage.unwrap(ok.copyOf(31)))
        fun mutate(f: (ByteBuffer) -> Unit) = ok.copyOf().also { f(ByteBuffer.wrap(it).order(ByteOrder.BIG_ENDIAN)) }
        assertNull(IapTunnelPackage.unwrap(mutate { it.putInt(0, 31) }))
        assertNull(IapTunnelPackage.unwrap(mutate { it.putInt(0, 4 * 1024 * 1024 + 1) }))
        assertNull(IapTunnelPackage.unwrap(mutate { it.putInt(0, ok.size + 1) }))
        assertNull(IapTunnelPackage.unwrap(mutate { it.putInt(16, 0x636D6E64) }))
        assertNull(IapTunnelPackage.sizeAt(mutate { it.putInt(0, 5) }))
        assertFails { IapTunnelPackage.wrap(ByteArray(0)) }
    }

    @Test fun seedAboveSignedRangePrintsUnsigned() {
        assertEquals("18446744073709551615", IapTunnelStream.seedText(-1L))
        assertEquals("9223372036854775808", IapTunnelStream.seedText(Long.MIN_VALUE))
        repeat(200) { assertTrue(IapTunnelStream.newSeed() >= 1L shl 32) }
    }

    @Test fun roundTripThroughTestReceiverStyleDecryptor() {
        val seed = Long.MIN_VALUE + 12345 // > 2^63 as unsigned: salt must use the unsigned decimal
        val wire = ByteArrayOutputStream()
        val tunnel = IapTunnelStream(wire, CarPlayMediaWire.streamKey(shared, seed), seed)
        val small = byteArrayOf(0xFF.toByte(), 0x55, 2, 0, 0xEE.toByte(), 0x10)
        val big = ByteArray(40000) { (it % 251).toByte() } // crosses the 16 KB record boundary
        val third = ByteArray(29) { (it + 3).toByte() }
        tunnel.send(small); tunnel.send(big); tunnel.send(third)
        val got = packages(decryptAll(wire.toByteArray(), seed))
        assertEquals(3, got.size)
        assertContentEquals(small, got[0]); assertContentEquals(big, got[1]); assertContentEquals(third, got[2])
        tunnel.close()
        assertTrue(tunnel.isClosed)
        assertFails { tunnel.send(small) }
    }

    @Test fun wrongSeedFailsToDecrypt() {
        val wire = ByteArrayOutputStream()
        IapTunnelStream(wire, CarPlayMediaWire.streamKey(shared, 77L), 77L).send(byteArrayOf(1))
        assertFails { decryptAll(wire.toByteArray(), 78L) }
    }

    @Test fun streamEntryAndReplyParsing() {
        val seed = Long.MAX_VALUE - 5
        val plist = ReceiverPlist.map(AirPlayBplist.encode(AirPlayBplist.dict("streams" to AirPlayBplist.array(IapTunnelStream.streamEntry(seed)))))
        val e = (plist["streams"] as List<*>)[0] as Map<*, *>
        assertEquals(130L, (e["type"] as Number).toLong())
        assertEquals("E9459FD0-BCAD-4C45-820F-1E72447EF2F2", e["clientTypeUUID"])
        assertEquals(seed, (e["seed"] as Number).toLong())
        assertNull(e["streamConnectionID"])
        // Real iPhone tunnel entry keys (Receiver capture, iOS 26.7.1).
        assertEquals(setOf("controlType", "channelID", "seed", "clientUUID", "type", "wantsDedicatedSocket", "sendMessageAsIs", "clientTypeUUID"),
            e.keys.map { it.toString() }.toSet())
        assertEquals(2L, (e["controlType"] as Number).toLong())
        assertEquals(true, e["wantsDedicatedSocket"]); assertEquals(true, e["sendMessageAsIs"])
        assertTrue(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}-RCS-[0-9]+").matches(e["channelID"] as String))
        // Like a real iPhone: same channel MAC every session, RCS counter increments.
        val a = IapTunnelStream.nextChannelId(); val b = IapTunnelStream.nextChannelId()
        assertEquals(a.substringBefore("-RCS-"), b.substringBefore("-RCS-"))
        assertEquals(a.substringAfter("-RCS-").toInt() + 1, b.substringAfter("-RCS-").toInt())
        val c = IapTunnelStream.nextChannelId("36:a7:1f:09:7c:ce")
        assertEquals("36:A7:1F:09:7C:CE", c.substringBefore("-RCS-"))
        val replies = listOf(mapOf("type" to 110L, "dataPort" to 1L), mapOf("type" to 130L, "streamID" to 1L, "dataPort" to 45678L))
        assertEquals(45678, IapTunnelStream.dataPort(replies))
        assertEquals("110/-/1,130/1/45678", IapTunnelStream.describeReplies(replies))
        assertNull(IapTunnelStream.dataPort(listOf(mapOf("type" to 130L))))
        assertNull(IapTunnelStream.dataPort(listOf(mapOf("type" to 130L, "dataPort" to 70000L))))
        assertNull(IapTunnelStream.dataPort(emptyList<Any>()))
    }

    @Test fun iphoneVideoEntryMatchesCapture() {
        // {streamConnectionID, latencyMs=75, type=110, uuid=<display uuid>}: no width/height/fps.
        val m = ReceiverPlist.map(AirPlayBplist.encode(AirPlayBplist.dict("v" to
            AirPlayHandshakeOrchestrator.iphoneVideoStream(42L, "e0ff8a27-6738-3d56-8a16-cc53ce1299b4"))))["v"] as Map<*, *>
        assertEquals(setOf("type", "streamConnectionID", "latencyMs", "uuid"), m.keys.map { it.toString() }.toSet())
        assertEquals(110L, m["type"]); assertEquals(75L, m["latencyMs"]); assertEquals("e0ff8a27-6738-3d56-8a16-cc53ce1299b4", m["uuid"])
    }
    @Test fun openClosesConnectedSocketWhenOutputStreamFails() {
        var closed = false
        val socket = object : java.net.Socket() {
            override fun setTcpNoDelay(on: Boolean) = Unit
            override fun connect(endpoint: java.net.SocketAddress?, timeout: Int) = Unit
            override fun getOutputStream(): java.io.OutputStream = throw java.io.IOException("output unavailable")
            override fun close() { closed = true }
        }
        assertFailsWith<java.io.IOException> {
            IapTunnelStream.open(socket, null, java.net.InetAddress.getLoopbackAddress(),
                12345, shared, 42L) {}
        }
        assertTrue(closed, "Connected tunnel socket must close if stream construction fails")
    }

}

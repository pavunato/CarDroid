package com.cardroid.runner

import com.cardroid.lib.airplay.AirPlayKeepAlive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals

class AirPlayKeepAliveTest {
    @Test fun sendsPeriodicDatagramsUntilClosed() {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { rx ->
            rx.soTimeout = 2000
            val ka = AirPlayKeepAlive(InetAddress.getLoopbackAddress(), rx.localPort, {}, intervalMs = 50)
            ka.start(CoroutineScope(Dispatchers.IO))
            val p = DatagramPacket(ByteArray(8), 8)
            rx.receive(p); rx.receive(p)
            assertEquals(1, p.length)
            ka.close()
        }
    }
}

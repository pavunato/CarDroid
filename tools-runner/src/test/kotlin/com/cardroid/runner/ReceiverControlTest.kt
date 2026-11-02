package com.cardroid.runner

import com.cardroid.lib.*
import com.cardroid.mfi.OkcarBypassProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class ReceiverControlTest {
    private class Transport : IapTransport {
        override val incomingControl = MutableSharedFlow<Pair<Int, ByteArray>>()
        override var onLinkUp: () -> Unit = {}
        val sent = mutableListOf<ByteArray>()
        override fun start(scope: CoroutineScope) {}
        override fun stop() {}
        override fun sendControl(controlMessageBytes: ByteArray) { sent += controlMessageBytes }
        override fun sendMfiCertResponse(hash: ByteArray) { sendControl(Iap2ControlSession.build(0xAA02, Iap2ControlSession.param(0, hash))) }
        override fun sendAuthStatusOk() { sendControl(Iap2ControlSession.build(0xAA05)) }
        override fun sendAuthenticationResponse(signature: ByteArray) {}
        override fun diagnostic(msg: String) {}
    }

    @Test fun receiverStyleHandshakeReachesWifiAndCarPlayAvailability() = runBlocking {
        val transport = Transport()
        val networks = mutableListOf<WifiHandoff.NetworkInfo>()
        val wifi = object : WifiHandoff {
            override fun connectToAccessory(network: WifiHandoff.NetworkInfo) { networks += network }
            override fun tearDown() {}
        }
        val session = CarPlaySession(transport, OkcarBypassProvider(), wifi,
            iphoneBehavior = true, bluetoothNotifications = true)
        session.onLinkUp()
        session.startControlFlow()
        session.onControlMessage(0x1D01, Iap2ControlSession.param(0, "Receiver\u0000".encodeToByteArray()))
        session.onControlMessage(0xAA01, Iap2ControlSession.param(0, byteArrayOf(1)))
        session.onControlMessage(0xAA03, Iap2ControlSession.param(0, byteArrayOf(1)))
        val beforeWifi = transport.sent.map { Iap2ControlSession.parse(it)!!.first }
        assertEquals(listOf(0x1D00, 0x1D02, 0xAA00, 0xAA02, 0xAA05), beforeWifi.take(5))
        assertEquals(0x5702, beforeWifi.last())
        // DiPlay's 5703 fields: SSID 1, passphrase 2, security 3, channel 4.
        val config = Iap2ControlSession.param(1, "Receiver\u0000".encodeToByteArray()) +
            Iap2ControlSession.param(2, "test-pass\u0000".encodeToByteArray()) +
            Iap2ControlSession.param(3, byteArrayOf(2)) + Iap2ControlSession.param(4, byteArrayOf(36))
        session.onControlMessage(0x5703, config)
        session.onControlMessage(0x5703, config)
        assertEquals(1, networks.size)
        assertEquals("Receiver", networks.single().ssid)
        assertEquals(36, networks.single().channel)
        assertEquals(0x4300, Iap2ControlSession.parse(transport.sent.last())!!.first)
    }

    @Test fun bluetoothPostAuthSendsNotificationsAndAnswersSubscriptions() = runBlocking {
        val transport = Transport()
        val wifi = object : WifiHandoff {
            override fun connectToAccessory(network: WifiHandoff.NetworkInfo) {}
            override fun tearDown() {}
        }
        val session = CarPlaySession(transport, OkcarBypassProvider(), wifi,
            iphoneBehavior = true, bluetoothNotifications = true,
            bluetoothIdentifier = { "AA:BB:CC:DD:EE:FF" })
        session.onControlMessage(0xAA03, Iap2ControlSession.param(0, byteArrayOf(1)))
        val ids = transport.sent.map { Iap2ControlSession.parse(it)!!.first }
        assertEquals(0xAA05, ids.first())
        assertTrue(0x4E0D in ids)
        assertTrue(0x4E0E in ids)
        assertEquals(0x5702, ids.last())
        session.onControlMessage(0x5000, byteArrayOf())
        session.onControlMessage(0x4154, byteArrayOf())
        session.onControlMessage(0x4157, byteArrayOf())
        assertEquals(listOf(0x5001, 0x4155, 0x4158), transport.sent.takeLast(3).map { Iap2ControlSession.parse(it)!!.first })
    }

    @Test fun transportNotificationsUseOnlyARealIdentifier() {
        assertNull(IphoneIap2.bluetoothTransportIdentifier(null))
        assertNull(IphoneIap2.bluetoothTransportIdentifier("02:00:00:00:00:00"))
        assertNull(IphoneIap2.bluetoothTransportIdentifier("invalid"))
        val (id, params) = Iap2ControlSession.parse(assertNotNull(IphoneIap2.bluetoothTransportIdentifier("aa:bb:cc:dd:ee:ff")))!!
        assertEquals(0x4E0E, id)
        assertEquals("AA:BB:CC:DD:EE:FF\u0000", Iap2ControlSession.firstParamValue(params).decodeToString())
        val availability = Iap2ControlSession.parse(IphoneIap2.wirelessAvailability())!!
        assertEquals(0x4E0D, availability.first)
        assertContentEquals(byteArrayOf(1), Iap2ControlSession.firstParamValue(availability.second))
    }

    @Test fun wifiConfigurationAnnouncesWirelessAvailabilityOnce() = runBlocking {
        val transport = Transport()
        var ssid = ""
        var joins = 0
        val wifi = object : WifiHandoff {
            override fun connectToAccessory(network: WifiHandoff.NetworkInfo) { ssid = network.ssid; joins++ }
            override fun tearDown() {}
        }
        val session = CarPlaySession(transport, OkcarBypassProvider(), wifi)
        val params = Iap2ControlSession.param(1, "Receiver\u0000".encodeToByteArray())
        session.onControlMessage(0x5703, params)
        session.onControlMessage(0x5703, params)
        assertEquals("Receiver", ssid)
        assertEquals(1, joins, "duplicate receiver Wi-Fi configurations must not restart a join")
        // Matches Receiver's group 1 / parameter 0 U8 wireless availability codec.
        assertEquals("4040000f4300000900010005000001",
            transport.sent.single().joinToString("") { "%02x".format(it) })
    }

    @Test fun parsesReceiverPublishedStartSessionVector() = runBlocking {
        val transport = Transport()
        val wifi = object : WifiHandoff {
            override fun connectToAccessory(network: WifiHandoff.NetworkInfo) {}
            override fun tearDown() {}
        }
        val session = CarPlaySession(transport, OkcarBypassProvider(), wifi)
        var endpoint: CarPlaySession.AirPlayEndpoint? = null
        session.onAirPlayStart = { endpoint = it }
        session.onControlMessage(0x1D01, Iap2ControlSession.param(0, "Receiver\u0000".encodeToByteArray()))
        // Receiver Iap2WirelessControlClientTest.carPlayStartSessionMatchesLiviWirelessVector.
        val hex = "40400060430100350001000900004c49564900000e000173656372657431323300000500022400100003" +
            "3139322e3136382e322e31000005000403000800020000c000000a00036465762d3100000b000461" +
            "61626263630000080005312e3000"
        val wire = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val (id, params) = assertNotNull(Iap2ControlSession.parse(wire))
        session.onControlMessage(id, params)
        val result = assertNotNull(endpoint)
        assertEquals("Receiver", result.receiverName)
        assertEquals("192.168.2.1", result.ipv6)
        assertEquals(49152, result.port)
        assertEquals("aabbcc", result.publicKey)
        assertEquals("1.0", result.firmware)
        assertNotEquals(result.publicKey, result.sessionUuid)
        java.util.UUID.fromString(result.sessionUuid)
        Unit
    }

    @Test fun linkUpReannouncesWirelessAvailability() = runBlocking {
        val transport = Transport()
        val wifi = object : WifiHandoff {
            override fun connectToAccessory(network: WifiHandoff.NetworkInfo) {}
            override fun tearDown() {}
        }
        val session = CarPlaySession(transport, OkcarBypassProvider(), wifi)
        val params = Iap2ControlSession.param(1, "Receiver\u0000".encodeToByteArray())
        fun availabilityCount() = transport.sent.count { it.size > 5 && it[4] == 0x43.toByte() && it[5] == 0x00.toByte() }
        session.onControlMessage(0x5703, params)
        session.onControlMessage(0x5703, params)
        assertEquals(1, availabilityCount())
        session.onLinkUp()
        session.onControlMessage(0x5703, params)
        assertEquals(2, availabilityCount())
    }
}

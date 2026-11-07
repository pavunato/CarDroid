package com.cardroid.runner

import com.cardroid.lib.airplay.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.test.*

class AudioStreamLifecycleTest {
    @Test fun startupSendsNoAudioSetup() {
        val calls = mutableListOf<String>()
        val lifecycle = AudioStreamLifecycle({ calls += "setup102" }, { calls += "teardown102" })
        assertFalse(lifecycle.active)
        assertTrue(calls.isEmpty())
        lifecycle.release() // silence before any playback: nothing to tear down
        assertTrue(calls.isEmpty())
    }

    @Test fun playbackStartSendsExactlyOneSetupEvenWhenSignalRepeats() {
        val calls = mutableListOf<String>()
        val lifecycle = AudioStreamLifecycle({ calls += "setup102" }, { calls += "teardown102" })
        repeat(5) { assertTrue(lifecycle.ensure()) }
        assertEquals(listOf("setup102"), calls)
    }

    @Test fun sustainedSilenceTearsDownThenNextPlaybackSetsUpAgain() {
        val calls = mutableListOf<String>()
        val lifecycle = AudioStreamLifecycle({ calls += "setup" }, { calls += "teardown" })
        lifecycle.ensure(); lifecycle.release(); lifecycle.release(); lifecycle.ensure()
        assertEquals(listOf("setup", "teardown", "setup"), calls)
    }

    @Test fun rejectedSetupIsNotMarkedActiveAndCanRetry() {
        var attempts = 0
        val lifecycle = AudioStreamLifecycle({ if (++attempts == 1) error("SETUP rejected") }, {})
        assertFalse(lifecycle.ensure())
        assertFalse(lifecycle.active)
        assertTrue(lifecycle.ensure())
        assertEquals(2, attempts)
    }

    @Test fun teardownBodyNamesOnlyTheAudioStream() {
        val streams = ReceiverPlist.map(AudioStreamLifecycle.teardownBody(102))["streams"] as List<*>
        assertEquals(listOf(mapOf("type" to 102L)), streams)
    }

    @Test fun transportSendsNoAudioUntilAttachedAndStopsAfterDetach() {
        val receiver = DatagramSocket(0, InetAddress.getLoopbackAddress()).apply { soTimeout = 300 }
        val server = java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val video = Socket(InetAddress.getLoopbackAddress(), server.localPort)
        val key = ByteArray(32) { 3 }
        val transport = CarPlayMediaTransport(video, null, null, key, ByteArray(0), {}, 640, 480, 640, 480,
            audioProfile = AudioProfile(streamType = 102, codec = AudioCodec.AAC_LC))
        val setups = mutableListOf<Int>()
        transport.audioLifecycle = AudioStreamLifecycle(
            setup = {
                setups += 1
                transport.attachAudio(DatagramSocket(), InetSocketAddress(InetAddress.getLoopbackAddress(), receiver.localPort), key, null)
            },
            teardown = { transport.detachAudio() },
        )
        try {
            transport.sendAudioEncoded(ByteArray(10)) // before playback: dropped, nothing on the wire
            assertFailsWith<java.net.SocketTimeoutException> { receiver.receive(DatagramPacket(ByteArray(4096), 4096)) }
            assertTrue(transport.ensureAudioStream())
            assertTrue(transport.ensureAudioStream())
            assertEquals(1, setups.size)
            transport.sendAudioEncoded(ByteArray(10))
            receiver.receive(DatagramPacket(ByteArray(4096), 4096))
            transport.releaseAudioStream()
            transport.sendAudioEncoded(ByteArray(10))
            assertFailsWith<java.net.SocketTimeoutException> { receiver.receive(DatagramPacket(ByteArray(4096), 4096)) }
        } finally { receiver.close(); transport.close(); server.close() }
    }
}

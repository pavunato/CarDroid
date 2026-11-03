package com.cardroid.runner

import android.bluetooth.*
import com.cardroid.lib.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlin.test.*

class Iap2BluetoothTransportTest {
    @Test fun realTransportRejectsInitialGapAndReassemblesControlStream() = runBlocking {
        val input = PipedInputStream(65536)
        val peer = PipedOutputStream(input)
        val output = ByteArrayOutputStream()
        val release = CountDownLatch(1)
        val socket = object : BluetoothSocket() {
            override val inputStream = input
            override val outputStream = output
            override val remoteDevice = BluetoothDevice()
            override fun close() { input.close(); release.countDown() }
        }
        val server = object : BluetoothServerSocket() {
            var accepted = false
            override fun accept(): BluetoothSocket {
                if (!accepted) { accepted = true; return socket }
                release.await(); throw IOException("closed")
            }
            override fun close() { release.countDown() }
        }
        val adapter = object : BluetoothAdapter() {
            override fun listenUsingRfcommWithServiceRecord(name: String, uuid: UUID) = server
        }
        val transport = Iap2Transport(adapter, LinkSyncParameters())
        val messages = Channel<Pair<Int, ByteArray>>(Channel.UNLIMITED)
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { transport.incomingControl.collect { messages.send(it) } }
        val link = CompletableDeferred<Unit>()
        transport.onLinkUp = { link.complete(Unit) }
        fun send(frame: Iap2Frame) { peer.write(frame.encode()); peer.flush() }
        try {
            transport.start(this)
            withTimeout(3000) { while (!transport.listening.value) delay(10) }
            val params = LinkSyncParameters(maxOutgoing = 4, retransmitTimeoutMs = 4000)
            send(Iap2LinkSync.buildSyncFrame(params, seq = 10, ack = 0))
            send(Iap2Frame(0x40, 10, 1, 0, byteArrayOf()))
            withTimeout(3000) { link.await() }
            val first = Iap2ControlSession.build(0x1d01, Iap2ControlSession.param(0, "test".encodeToByteArray()))
            val second = Iap2ControlSession.build(0xaa03)
            val third = Iap2ControlSession.build(0x5703)
            // Sequence 12 must not be accepted before 11, even as the first data frame.
            send(Iap2Frame(0x40, 12, 1, 1, first.copyOfRange(5, first.size)))
            send(Iap2Frame(0x40, 11, 1, 1, first.copyOfRange(0, 5)))
            send(Iap2Frame(0x40, 12, 1, 1, first.copyOfRange(5, first.size)))
            send(Iap2Frame(0x40, 13, 1, 1, second + third))
            assertEquals(listOf(0x1d01, 0xaa03, 0x5703), withTimeout(3000) { List(3) { messages.receive().first } })
            val large = Iap2ControlSession.build(0xaa01, ByteArray(9000) { 0x55 })
            send(Iap2Frame(0x40, 14, 1, 1, large))
            val received = withTimeout(3000) { messages.receive() }
            assertEquals(0xaa01, received.first)
            assertEquals(9000, received.second.size)
        } finally {
            transport.stop()
            peer.close()
            collector.cancelAndJoin()
        }
    }
}

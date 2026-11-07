package com.cardroid.lib.airplay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Low-rate UDP keep-alive to the receiver's `keepAlivePort` (returned in the SETUP
 * response because we send keepAliveLowPower=true). The payload format an iPhone
 * uses is unverified; the DiPlay receiver only drains the port, so a single zero
 * byte is sent every [intervalMs].
 */
class AirPlayKeepAlive(
    private val target: InetAddress,
    private val port: Int,
    private val log: (String) -> Unit,
    private val intervalMs: Long = 2000L,
) {
    private var socket: DatagramSocket? = null
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        val s = DatagramSocket().also { socket = it }
        log("AirPlay: keep-alive UDP -> $port every ${intervalMs}ms")
        job = scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val packet = DatagramPacket(ByteArray(1), 1, target, port)
            while (isActive) {
                runCatching { s.send(packet) }.onFailure { return@launch }
                delay(intervalMs)
            }
        }
    }

    fun close() {
        job?.cancel(); job = null
        runCatching { socket?.close() }; socket = null
    }
}

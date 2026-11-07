package com.cardroid.app

import com.cardroid.app.helper.HelperProtocol
import com.cardroid.lib.airplay.AudioCodec
import com.cardroid.lib.airplay.CarPlayMediaTransport
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/** Receives shell-captured PCM; reuses the negotiated CarPlay audio format. */
class HelperPlaybackAudio(
    private val version: Int,
    private val transport: CarPlayMediaTransport,
    private val log: (String) -> Unit,
    private val onFailure: () -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val socket = Socket()

    fun start() {
        Thread({
            var encoder: AacLcEncoder? = null
            try {
                socket.connect(InetSocketAddress("127.0.0.1", HelperProtocol.PORT), 1500)
                socket.tcpNoDelay = true
                socket.soTimeout = 5000
                val out = socket.getOutputStream()
                val input = socket.getInputStream()
                HelperProtocol.write(out, HelperProtocol.T_HELLO, HelperProtocol.hello(version, HelperProtocol.ROLE_AUDIO))
                val hello = HelperProtocol.read(input)
                check(hello.type == HelperProtocol.T_HELLO_ACK) { "Helper rejected audio connection" }
                check(HelperProtocol.parseHelloAck(hello.payload).protoVersion == HelperProtocol.PROTOCOL_VERSION)
                val profile = transport.audioProfile
                if (profile.codec == AudioCodec.AAC_LC) {
                    encoder = AacLcEncoder(profile.sampleRate, 2, log)
                    check(encoder.start()) { "AAC encoder unavailable" }
                }
                HelperProtocol.write(out, HelperProtocol.T_AUDIO_CONFIG,
                    ByteBuffer.allocate(8).putInt(profile.sampleRate).putInt(profile.samplesPerPacket).array())
                val ready = HelperProtocol.read(input)
                check(ready.type == HelperProtocol.T_AUDIO_READY) {
                    if (ready.type == HelperProtocol.T_ERROR) HelperProtocol.parseError(ready.payload).message else "No audio ready response"
                }
                log("HelperAudio: capturing device output without MediaProjection; phone speaker redirected")
                val signal = PlaybackSignalTracker(profile.sampleRate.toLong() * 2)
                var streamReady = false
                var lastSetupAttemptNanos = 0L
                while (!closed.get()) {
                    val frame = HelperProtocol.read(input)
                    check(frame.type == HelperProtocol.T_AUDIO_PCM && frame.payload.size == profile.samplesPerPacket * 4) {
                        if (frame.type == HelperProtocol.T_ERROR) HelperProtocol.parseError(frame.payload).message else "Invalid audio frame"
                    }
                    val audible = frame.payload.any { it != 0.toByte() }
                    signal.accept(audible, profile.samplesPerPacket)?.let {
                        log("HelperAudio: playback signal=$it capturedFrames=${signal.capturedFrames}")
                        // Real iPhone: type-102 SETUP when playback starts, TEARDOWN after sustained silence.
                        if (it == PlaybackSignalTracker.Transition.SILENT) { transport.releaseAudioStream(); streamReady = false }
                    }
                    if (audible && !streamReady) {
                        val now = System.nanoTime()
                        if (now - lastSetupAttemptNanos >= SETUP_RETRY_NANOS) {
                            lastSetupAttemptNanos = now
                            streamReady = transport.ensureAudioStream()
                        }
                    }
                    if (!streamReady) continue // no type-102 stream yet: drop silence/unsent frames
                    val aac = encoder
                    if (aac != null) aac.encode(frame.payload).forEach { transport.sendAudioEncoded(it, profile.samplesPerPacket) }
                    else transport.sendAudio(frame.payload)
                }
            } catch (e: Exception) {
                if (!closed.get()) {
                    log("HelperAudio: failed ${e.javaClass.simpleName}: ${e.message}; helper audio stopped")
                    // Release output redirection before starting the fallback.
                    close()
                    onFailure()
                }
            } finally {
                encoder?.close()
                close()
            }
        }, "helper-audio-client").start()
    }

    private companion object {
        const val SETUP_RETRY_NANOS = 5_000_000_000L
    }

    override fun close() {
        closed.set(true)
        runCatching { socket.close() }
    }
}

package com.cardroid.lib.airplay

import java.io.Closeable
import java.net.Socket
import java.net.DatagramSocket
import java.net.DatagramPacket
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

class CarPlayMediaOptions(
    val controllerId: String,
    val seed: ByteArray,
    val receiverPublicKey: ByteArray?,
    var width: Int = 1280,
    var height: Int = 720,
    var receiverWidth: Int = width,
    var receiverHeight: Int = height,
    var fps: Int = 30,
    /** The car display's /info uuid; named in the screen SETUP as a real iPhone does. */
    var displayUuid: String? = null,
    /** Long-side cap for the encoded canvas; [VideoCanvas.NATIVE_MAX_LONG_SIDE] streams the panel's own size. */
    var maxCanvasLongSide: Int = VideoCanvas.STANDARD_LONG_SIDE,
    /** App hook after an accepted (de-duplicated) requestUI: open the asked-for app on the car display. */
    var onRequestUI: ((RequestUi.Request) -> Unit)? = null,
    val audio: Boolean = true,
    /** Filled from the receiver's /info before media SETUP. */
    @Volatile var audioProfile: AudioProfile = AudioProfile.DEFAULT,
    val nowPlaying: () -> com.cardroid.lib.NowPlayingState = { com.cardroid.lib.NowPlayingState() },
    val onReady: (CarPlayMediaTransport) -> Unit,
)

/** Owns the negotiated encrypted media sockets; capture is supplied by Android. */
class CarPlayMediaTransport(
    video: Socket,
    private val audio: DatagramSocket?,
    private val audioTarget: InetSocketAddress?,
    videoKey: ByteArray,
    private val audioKey: ByteArray,
    private val log: (String) -> Unit,
    val width: Int, val height: Int, val receiverWidth: Int, val receiverHeight: Int,
    val fps: Int = 30,
    val audioProfile: AudioProfile = AudioProfile.DEFAULT,
    private val audioControlTarget: InetSocketAddress? = null,
) : Closeable {
    /** Fired once for a fatal video/event failure, after cleanup (never on local close()). */
    @Volatile var onPeerEnded: ((String) -> Unit)? = null
    @Volatile var onCommand: ((Map<String,Any?>) -> Unit)? = null
    /** Resource-manager callback for accessory changeModes requests. */
    @Volatile var onChangeModes: ((Map<String,Any?>) -> Unit)? = null
    /** Accessory asks the controller to show its UI (user tapped the CarPlay icon on the car). */
    @Volatile var onRequestUI: ((Map<String, Any?>) -> Unit)? = null
    /** car-touch-iap2-tunnel IAP_OUT: raw accessory->phone iAP2 bytes from event-channel iAPSendMessage (called on the event thread). */
    @Volatile var onIapData: ((ByteArray) -> Unit)? = null
    /** Session-scoped /info HID declarations; cleared with the media session. */
    @Volatile var hidRegistry: HidDeviceRegistry? = null
    private val closed = AtomicBoolean(false)
    private class VideoConnection(val socket: Socket, val key: ByteArray) {
        val output = socket.getOutputStream()
        var counter = 0L // sender thread only; fresh key/stream starts at zero
    }
    private val videoConnection = java.util.concurrent.atomic.AtomicReference<VideoConnection?>(VideoConnection(video, videoKey))
    private var videoGeneration = 0L // guarded by queueLock
    private var audioCounter = 0L
    private var sequence = 0
    private var sample = 0L
    private var firstAudioPacket = true
    private var frames = 0L
    private var packets = 0L
    private var eventSocket: Socket? = null
    private var eventThread: Thread? = null
    @Volatile private var eventWriter: EventCommandWriter? = null
    private sealed class VideoItem {
        class Config(val sps: ByteArray, val pps: ByteArray) : VideoItem()
        class Frame(val encoded: ByteArray, val timeUs: Long, val idr: Boolean, val enqueuedNanos: Long, val generation: Long) : VideoItem()
    }
    private val videoQueue = java.util.concurrent.LinkedBlockingDeque<VideoItem>()
    private val queueLock = Any()
    private val senderStarted = AtomicBoolean(false)
    @Volatile private var senderThread: Thread? = null
    @Volatile var onKeyFrameNeeded: (() -> Unit)? = null
    private var latestConfig: VideoItem.Config? = null // sender thread only
    private var waitingForIdr = false // guarded by queueLock
    private var lastKeyRequestMs = 0L // guarded by queueLock
    private val windowDropped = java.util.concurrent.atomic.AtomicInteger()
    private val windowQueueMax = java.util.concurrent.atomic.AtomicInteger()
    private fun ensureSender() {
        if (!senderStarted.compareAndSet(false,true)) return
        senderThread = Thread({ senderLoop() },"cardroid-video-tx").apply { isDaemon=true; start() }
    }
    fun sendVideoConfig(sps: ByteArray, pps: ByteArray) {
        check(!closed.get())
        videoQueue.addLast(VideoItem.Config(sps.copyOf(),pps.copyOf())); ensureSender()
    }
    fun sendVideoFrame(encoded: ByteArray, timeUs: Long) {
        check(!closed.get())
        val idr = CarPlayMediaWire.nalUnits(encoded).any { it.isNotEmpty() && (it[0].toInt() and 31) == 5 }
        val now = System.nanoTime()
        var requestKey = false
        synchronized(queueLock) {
            if (videoConnection.get() == null) return
            val oldest = videoQueue.firstOrNull { it is VideoItem.Frame } as VideoItem.Frame?
            val queued = videoQueue.count { it is VideoItem.Frame }
            val backlog = queued >= MAX_QUEUE || (oldest != null && (now - oldest.enqueuedNanos) / 1_000_000 > MAX_AGE_MS)
            if (backlog) {
                videoQueue.removeIf { it is VideoItem.Frame }
                windowDropped.addAndGet(queued); waitingForIdr = true
                log("Media: video backlog dropped=$queued (queue/age); requested keyframe")
                requestKey = keyRequestDue()
            }
            if (waitingForIdr && !idr) {
                windowDropped.incrementAndGet()
                if (!requestKey) requestKey = keyRequestDue()
            } else {
                waitingForIdr = false
                videoQueue.addLast(VideoItem.Frame(encoded,timeUs,idr,now,videoGeneration))
                val depth = videoQueue.size; var m = windowQueueMax.get()
                while (depth > m && !windowQueueMax.compareAndSet(m,depth)) m = windowQueueMax.get()
            }
        }
        if (requestKey) runCatching { onKeyFrameNeeded?.invoke() }
        ensureSender()
    }
    /** Discard predictive backlog and wait for a decodable fresh frame after screen return. */
    fun requestVideoRefresh() {
        if (closed.get()) return
        synchronized(queueLock) {
            videoQueue.removeIf { it is VideoItem.Frame }
            waitingForIdr = true
        }
        log("Media: video refresh requested; waiting for config + IDR")
        runCatching { onKeyFrameNeeded?.invoke() }
    }

    private fun keyRequestDue(): Boolean { // call under queueLock
        val t = System.nanoTime() / 1_000_000
        if (lastKeyRequestMs != 0L && t - lastKeyRequestMs < KEY_REQUEST_INTERVAL_MS) return false
        lastKeyRequestMs = t; return true
    }
    /** Stop only screen delivery; keep Android display, audio and events alive. */
    fun suspendVideo() {
        val old = synchronized(queueLock) {
            videoGeneration++
            videoQueue.removeIf { it is VideoItem.Frame }
            waitingForIdr = true
            videoConnection.getAndSet(null)
        }
        // Closing unblocks an in-flight write without waiting for the sender.
        runCatching { old?.socket?.close() }
        log("Media: screen suspended; audio/events preserved")
    }

    fun resumeVideo(socket: Socket, key: ByteArray) {
        val next = try { VideoConnection(socket, key.copyOf()) }
        catch (e: Exception) { runCatching { socket.close() }; throw e }
        val previous = synchronized(queueLock) {
            if (closed.get()) { socket.close(); return }
            videoGeneration++
            videoQueue.removeIf { it is VideoItem.Frame }
            waitingForIdr = true
            videoConnection.getAndSet(next)
        }
        runCatching { previous?.socket?.close() }
        log("Media: screen resumed with fresh connection/key; waiting for IDR")
        requestVideoRefresh()
    }

    internal fun failSession(reason: String) = endTransport(reason)

    private fun senderLoop() {
        try {
            while (!closed.get()) {
                val item = videoQueue.takeFirst()
                if (item is VideoItem.Config) latestConfig = item
                val connection = synchronized(queueLock) {
                    if (item is VideoItem.Frame && item.generation != videoGeneration) null
                    else videoConnection.get()
                } ?: continue
                try {
                    when (item) {
                        is VideoItem.Config -> writeConfig(item, connection)
                        is VideoItem.Frame -> writeFrame(item, connection)
                    }
                } catch (e: Exception) {
                    // A deliberate screen replacement closes the old socket.
                    // Only failure of the active connection ends the session.
                    endTransport("video send failed: ${e.message}", failedVideo = connection)
                }
            }
        } catch (_: InterruptedException) { }
    }
    private fun writeConfig(config: VideoItem.Config, connection: VideoConnection) {
        val body = CarPlayMediaWire.videoConfig(config.sps, config.pps)
        connection.output.write(CarPlayMediaWire.screenPacket(connection.key, 0, true, body, 0))
        connection.output.flush()
        log("Media: H264 config sent SPS=${config.sps.size} PPS=${config.pps.size}")
    }

    private fun writeFrame(f: VideoItem.Frame, connection: VideoConnection) {
        if (f.idr) latestConfig?.let { writeConfig(it, connection) }
        val body = CarPlayMediaWire.videoFrame(f.encoded)
        val t0 = System.nanoTime()
        connection.output.write(CarPlayMediaWire.screenPacket(connection.key,connection.counter++,false,body,f.timeUs))
        connection.output.flush()
        if (closed.get()) return
        val writeMs = (System.nanoTime() - t0) / 1_000_000
        frames++
        if (frames == 1L) log("Media: video first frame ${body.size}B")
        if (vStart == 0L) vStart = t0
        vFrames++; vBytes += body.size; if (body.size > vMaxBytes) vMaxBytes = body.size.toLong()
        if (writeMs > vMaxWrite) vMaxWrite = writeMs; if (f.idr) vIdr++
        if (System.nanoTime() - vStart >= 1_000_000_000L) {
            log("Media: video stats fps=$vFrames sent=$vFrames dropped=${windowDropped.getAndSet(0)} queueMax=${windowQueueMax.getAndSet(videoQueue.size)} avg=${vBytes / vFrames}B max=${vMaxBytes}B kbps=${vBytes * 8 / 1000} maxWrite=${vMaxWrite}ms idr=$vIdr")
            vStart = System.nanoTime(); vFrames = 0; vBytes = 0; vMaxBytes = 0; vMaxWrite = 0; vIdr = 0
        }
    }
    private var vStart = 0L; private var vFrames = 0L; private var vBytes = 0L; private var vMaxBytes = 0L; private var vMaxWrite = 0L; private var vIdr = 0L
    private var aStart = 0L; private var aPackets = 0L; private var aMaxSend = 0L
    @Volatile private var syncThread: Thread? = null
    private var syncSocket: DatagramSocket? = null
    private val syncStarted = AtomicBoolean(false)
    private var syncSequence = 0
    private var firstSync = true
    @Volatile private var lastAudioTimestamp = 0L

    fun sendAudio(pcm: ByteArray) {
        check(!closed.get())
        check(audioProfile.codec == AudioCodec.PCM) {
            "PCM audio supplied for ${audioProfile.codec} profile"
        }
        val frames = pcm.size / 4
        sendAudioPayload(pcm, frames, pcmLittleEndian = true)
    }

    /** Sends a raw AAC-LC access unit (without ADTS) for a type-102 stream. */
    fun sendAudioEncoded(aacAccessUnit: ByteArray, sampleFrames: Int = AudioProfile.AAC_FRAME_SAMPLES) {
        check(!closed.get())
        check(audioProfile.codec == AudioCodec.AAC_LC) {
            "AAC audio supplied for ${audioProfile.codec} profile"
        }
        sendAudioPayload(aacAccessUnit, sampleFrames, pcmLittleEndian = false)
    }

    private fun sendAudioPayload(payload: ByteArray, sampleFrames: Int, pcmLittleEndian: Boolean) {
        val socket = audio ?: return
        val target = audioTarget ?: return
        require(sampleFrames > 0)
        val timestamp = sample
        val packet = CarPlayMediaWire.audioPacket(
            audioKey,
            audioCounter++,
            sequence++,
            timestamp.toInt(),
            0,
            payload,
            payloadType = audioProfile.streamType,
            pcmLittleEndian = pcmLittleEndian,
            marker = firstAudioPacket,
        )
        firstAudioPacket = false
        sample += sampleFrames
        lastAudioTimestamp = sample
        val t0 = System.nanoTime()
        socket.send(DatagramPacket(packet,packet.size,target))
        // Start anchors only once the media stream has produced its first
        // packet (after RECORD), avoiding pre-RECORD sync traffic.
        startAudioSync()
        val sendMs = (System.nanoTime() - t0) / 1_000_000
        packets++
        if (packets == 1L) log("Media: audio first packet type=${audioProfile.streamType} codec=${audioProfile.codec} samples=$sample")
        if (aStart == 0L) aStart = t0
        aPackets++; if (sendMs > aMaxSend) aMaxSend = sendMs
        val elapsed = System.nanoTime() - aStart
        if (elapsed >= 5_000_000_000L) {
            log("Media: audio stats pps=${aPackets * 1_000_000_000L / elapsed} maxSend=${aMaxSend}ms")
            aStart = System.nanoTime(); aPackets = 0; aMaxSend = 0
        }
    }

    /** Send RTP-to-NTP anchors when the receiver supplied an audio controlPort. */
    private fun startAudioSync() {
        val target = audioControlTarget ?: return
        if (!syncStarted.compareAndSet(false, true)) return
        val socket = runCatching { DatagramSocket() }.getOrElse {
            log("Media: audio sync socket failed: ${it.message}")
            syncStarted.set(false)
            return
        }
        syncSocket = socket
        syncThread = Thread({
            try {
                while (!closed.get() && !socket.isClosed) {
                    val next = lastAudioTimestamp
                    val current = (next - audioProfile.samplesPerPacket).coerceAtLeast(0L)
                    val packet = AudioSyncPacket.build(
                        syncSequence++ and 0xffff,
                        current,
                        next,
                        AudioSyncPacket.ntpNow(),
                        firstSync,
                    )
                    socket.send(DatagramPacket(packet, packet.size, target))
                    if (firstSync) {
                        firstSync = false
                        log("Media: audio sync first packet controlPort=${target.port} ts=$current->$next")
                    }
                    Thread.sleep(1_000L)
                }
            } catch (e: InterruptedException) {
                // Normal close path.
            } catch (e: Exception) {
                if (!closed.get()) log("Media: audio sync stopped: ${e.message}")
            }
        }, "cardroid-audio-sync").apply { isDaemon = true; start() }
    }
    private var iapCount=0
    private var iapLast: ByteArray?=null
    private var iapKeysLogged=false
    // Accessory->phone iAP2 bytes arrive as bplist {type:iAPSendMessage, params:{data:<bytes>}}; log only, no reply yet.
    private fun logIap(command: Map<*,*>) {
        val data=(command["params"] as? Map<*,*>)?.get("data") as? ByteArray
        if (data==null) {
            if (!iapKeysLogged) { iapKeysLogged=true; log("Media: iAP from receiver without bytes; params keys=${(command["params"] as? Map<*,*>)?.keys}") }
            return
        }
        val n=++iapCount
        val changed=iapLast==null || !iapLast.contentEquals(data)
        // car-touch-iap2-tunnel Stage 0 diagnostics (log only)
        if (n==1) log("[car-diag] iap-first t=${CarDiagSummary.wall()} +${sinceEventsMs()}ms len=${data.size}${if (data.contentEquals(CarDiagSummary.IAP_MARKER)) " marker" else ""}")
        else if (changed) log("[car-diag] iap-change #$n t=${CarDiagSummary.wall()} +${sinceEventsMs()}ms len=${data.size}${if (data.contentEquals(CarDiagSummary.IAP_MARKER)) " marker" else ""}")
        iapLast=data
        onIapData?.let { cb -> runCatching { cb(data) }.onFailure { log("[iap-out] responder failed: ${it.javaClass.simpleName}: ${it.message}") } }
        if (n<=15 || changed) {
            val hex=data.take(256).joinToString("") { "%02X".format(it) } + if (data.size>256) "…" else ""
            log("Media: iAP from receiver #$n len=${data.size} hex=$hex${iapDecode(data)}")
        }
        if (n>15 && n%30==0) log("Media: iAP from receiver: $n messages so far (last len=${data.size})")
    }
    private fun iapDecode(d: ByteArray): String {
        fun u(i: Int)=d[i].toInt() and 0xFF
        if (d.size<9 || u(0)!=0xFF || u(1)!=0x5A) return ""
        val ctl=u(4)
        val flags=listOf(0x80 to "SYN",0x40 to "ACK",0x20 to "EAK",0x10 to "RST",0x08 to "SLP").filter { ctl and it.first!=0 }.joinToString("|") { it.second }
        var out=" [iAP2 len=${(u(2) shl 8) or u(3)} ctl=0x%02X($flags) seq=${u(5)} ack=${u(6)} sess=${u(7)}".format(ctl)
        if (d.size>=16 && u(9)==0x40 && u(10)==0x40) out+=" msgId=0x%04X".format((u(13) shl 8) or u(14))
        return "$out]"
    }
    // car-touch-iap2-tunnel Stage 0 diagnostics: event-channel command-type counts + hidSendReport timestamps (log only)
    private val cmdCounts = CarDiagSummary.CommandCounts()
    @Volatile private var eventsStartMs = 0L
    private val endCountsLogged = AtomicBoolean(false)
    private fun sinceEventsMs() = if (eventsStartMs == 0L) 0L else System.currentTimeMillis() - eventsStartMs
    private fun diagCommand(command: Map<String,Any?>?, type: Any?) {
        val countOfType = cmdCounts.record(type)
        if (type=="hidSendReport" && command!=null) {
            val report = command["hidReport"] as? ByteArray
            if (report != null) {
                hidRegistry?.let { registry ->
                    val received = registry.decodeDirect(
                        command["uuid"], report,
                        receiverWidth.coerceAtLeast(1), receiverHeight.coerceAtLeast(1),
                        receiverWidth.coerceAtLeast(1), receiverHeight.coerceAtLeast(1),
                    )
                    registry.note("received", received, report.size)
                }
            }
            if (CarDiagSummary.logHid(countOfType)) log(CarDiagSummary.hidLine(countOfType, sinceEventsMs(), command))
        }
    }
    private fun logEndCounts() { if (endCountsLogged.compareAndSet(false,true) && eventsStartMs!=0L) log(cmdCounts.summary("end")) }
    /** Enqueue a phone-to-car event-channel command without blocking the event reader. */
    fun sendEventCommand(command: AirPlayBplist.Value, label: String = "event command"): Boolean =
        eventWriter?.enqueueCommand(command, label) ?: false

    /**
     * Start the encrypted event reader and its single bounded writer.  When a
     * session registry is supplied, HID mode 0 is issued once for each exact
     * UUID after the event socket is ready.
     */
    fun startEvents(socket: Socket, shared: ByteArray, registry: HidDeviceRegistry? = null) {
        eventSocket = socket
        hidRegistry = registry
        eventsStartMs = System.currentTimeMillis()
        log("[car-diag] events-start t=${CarDiagSummary.wall(eventsStartMs)}")
        Thread({
            try {
                Thread.sleep(10_000); if (!closed.get()) log(cmdCounts.summary("+10s"))
                Thread.sleep(20_000); if (!closed.get()) log(cmdCounts.summary("+30s"))
            } catch (e: InterruptedException) {}
        },"cardroid-car-diag").apply { isDaemon=true; start() }
        val input = CarPlayRecordInput(socket.getInputStream(),CarPlayCrypto.hkdf(shared,"Events-Salt","Events-Write-Encryption-Key"))
        eventWriter = EventCommandWriter(
            socket.getOutputStream(),
            CarPlayCrypto.hkdf(shared,"Events-Salt","Events-Read-Encryption-Key"),
            log,
        )
        // The event session is now established and /info has already been
        // decoded by the orchestrator. Preserve each UUID's plist type/value.
        registry?.commandValues()?.forEachIndexed { index, command ->
            if (!sendEventCommand(command, "hidSetInputMode[$index]"))
                log("[hid] hidSetInputMode[$index] enqueue failed")
            else log("[hid] hidSetInputMode[$index] queued")
        }
        eventThread = Thread({
            try {
                while (!closed.get()) {
                    fun line(): String {
                        val out = java.io.ByteArrayOutputStream()
                        while (true) { val b = input.read(); check(b >= 0) { "Event EOF" }; if (b == 10) break; require(out.size() < 65536); if (b != 13) out.write(b) }
                        return out.toString("US-ASCII")
                    }
                    val request = line()
                    val headers = mutableMapOf<String,String>()
                    while (true) { val h = line(); if (h.isEmpty()) break; val i = h.indexOf(':'); if (i > 0) headers[h.substring(0,i).lowercase()] = h.substring(i+1).trim() }
                    val size = headers["content-length"]?.toIntOrNull() ?: 0
                    require(size in 0..1048576)
                    val body = readExact(input,size)
                    val command=runCatching { ReceiverPlist.map(body) }.getOrNull()
                    val type=command?.get("type")
                    val response = request.startsWith("HTTP/") || request.startsWith("RTSP/")
                    if (response) {
                        val status = request.split(' ').getOrNull(1) ?: "?"
                        log("Media: event response status=$status cseq=${headers["cseq"] ?: "-"} body=${body.size}B type=$type")
                    } else {
                        runCatching { diagCommand(command, type) }
                        if (command!=null) runCatching { onCommand?.invoke(command) }
                            .onFailure { log("Media: command handler failed: ${it.message}") }
                        if (type=="iAPSendMessage") logIap(command)
                        else if (type=="changeModes") {
                            log("Media: receiver changeModes observed; dispatching resource arbiter params=${command?.get("params").toString().take(1500)}")
                            runCatching { onChangeModes?.invoke(command) }
                                .onFailure { log("Media: changeModes handler failed: ${it.javaClass.simpleName}: ${it.message}") }
                        }
                        else if (type=="requestUI") {
                            log("Media: receiver requestUI observed params=${command?.get("params").toString().take(300)}")
                            runCatching { onRequestUI?.invoke(command) }
                                .onFailure { log("Media: requestUI handler failed: ${it.javaClass.simpleName}: ${it.message}") }
                        }
                        else log("Media: event $request type=$type")
                        // Requests receive an asynchronous ACK. Responses to
                        // our commands are logged above and never ACKed.
                        eventWriter?.enqueueAck(headers["cseq"])
                    }
                }
            } catch (e: Exception) { endTransport("event channel ended: ${e.message}") }
        },"cardroid-events").apply { isDaemon=true; start() }
    }
    override fun close() = endTransport(null)

    private fun endTransport(failure: String?, failedVideo: VideoConnection? = null) {
        synchronized(queueLock) {
            if (failedVideo != null && videoConnection.get() !== failedVideo) return
            if (!closed.compareAndSet(false, true)) return
        }
        try {
            senderThread?.let { if (it !== Thread.currentThread()) it.interrupt() }
            videoQueue.clear()
            val currentVideo = synchronized(queueLock) { videoConnection.getAndSet(null) }
            runCatching { currentVideo?.socket?.close() }
            runCatching { audio?.close() }
            runCatching { syncSocket?.close() }
            syncThread?.interrupt()
            runCatching { eventWriter?.close() }; eventWriter = null
            runCatching { eventSocket?.close() }
            eventThread?.let { if (it !== Thread.currentThread()) it.interrupt() }
            hidRegistry = null
            logEndCounts()
            log("Media: stopped videoFrames=$frames audioPackets=$packets")
        } finally {
            // The winner of the close race owns notification. The orchestrator
            // clears hasWifiSession and tears down stale helper/session state.
            if (failure != null) {
                log("Media: $failure")
                runCatching { onPeerEnded?.invoke(failure) }
            }
        }
    }
    private companion object {
        const val MAX_QUEUE = 8
        const val MAX_AGE_MS = 250L
        const val KEY_REQUEST_INTERVAL_MS = 500L
    }
}

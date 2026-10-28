package com.cardroid.lib.airplay

import com.cardroid.mfi.MfiAuthProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.atomic.AtomicLong

/** Native or socket-backed timing listener advertised in AirPlay SETUP. */
data class AirPlayTimingSession(
    val port: Int,
    val close: () -> Unit,
) {
    companion object {
        /** Wraps [stop] so it runs at most once, even if several threads race to close (native timing stop is not re-entrant). */
        fun onceOnly(port: Int, stop: () -> Unit): AirPlayTimingSession {
            val done = java.util.concurrent.atomic.AtomicBoolean(false)
            return AirPlayTimingSession(port) { if (done.compareAndSet(false, true)) stop() }
        }
    }
}

/**
 * Drives the AirPlay handshake sequence per OkcarOS reference (see
 * the protocol notes).
 *
 *  1. POST /auth-setup       (MFi-SAP client material)
 *  2. SETUP rtsp:.../uuid    (bplist with deviceID/eiv/ekey/timingPort)
 *  3. GET  /info             (server caps)
 *  4. RECORD rtsp:.../uuid
 *  5. POST /command modesChanged
 *  6. POST /feedback (keepalive every ~2s)
 *
 * Step 1 needs MFi-SAP. The Android app injects [KotlinMfiSap]; callers can
 * still inject [AirPlayMfiSap.Stub] for loose wiring tests.
 */
class AirPlayHandshakeOrchestrator(
    private val params: AirPlayParams,
    private val mfi: AirPlayMfiSap = AirPlayMfiSap.Stub,
    private val socketFactory: () -> Socket = { Socket() },
    private val udpSocketFactory: () -> DatagramSocket = { DatagramSocket() },
    private val log: (String) -> Unit = {},
    /** See [AirPlayRtspClient.addressResolver]. */
    private val addressResolver: (() -> InetSocketAddress)? = null,
    /** See [AirPlayRtspClient.addressBinder]. */
    private val addressBinder: ((Socket) -> Unit)? = null,
    private val addressCandidates: (() -> List<java.net.InetSocketAddress>)? = null,
    /**
     * Optional second-chance SETUP key source. OkcarOS sends hardcoded eiv/ekey
     * constants in SETUP even though it still performs /auth-setup first.
     */
    private val setupKeyFallback: (() -> Pair<ByteArray, ByteArray>)? = null,
    private val mediaOptions: CarPlayMediaOptions? = null,
    /** Persistent controller identity for non-Receiver pair-setup; random per run when null. */
    private val pairingIdentity: Pair<String, ByteArray>? = null,
    /** car-touch-iap2-tunnel Stage 0: receives (label, raw bplist body) of the car's /info and SETUP replies for offline decode. */
    private val diagSink: ((String, ByteArray) -> Unit)? = null,
    /** Existing iAP2 auth provider to reuse for the control-channel experiment. */
    private val iapOutMfi: MfiAuthProvider? = null,
    private val onDiagnosticStage: (com.cardroid.lib.ConnectionDiagnostics.Stage) -> Unit = {},
) {
    private val lifecycleLock = Any() // orders readiness publication with stop/peer EOF
    private val rtspLock = Any() // serializes control-channel requests (feedback loop + iAP out)
    /** Actor owns responder state; network worker keeps blocking RTSP off the event/timer path. */
    private var iapOutActor: java.util.concurrent.ExecutorService? = null
    private var iapOutNetwork: java.util.concurrent.ExecutorService? = null
    private var iapOutTicker: java.util.concurrent.ScheduledExecutorService? = null
    @Volatile private var postAuthModesFuture: ScheduledFuture<*>? = null
    private val iapOutGeneration = AtomicLong(0L)
    private var iapOutSent = 0
    /** Serialized RTSP worker for receiver-driven resource ownership changes. */
    @Volatile private var modesNetwork: java.util.concurrent.ExecutorService? = null
    private val resourceArbiter = CarPlayResourceArbiter(log)
    private val requestUiDebouncer = RequestUi.Debouncer()
    private var rtsp = newRtspClient()
    // pair-verify X25519 secret; kept for later event/stream key derivation.
    private var verifySecret: ByteArray? = null
    private var mediaTransport: CarPlayMediaTransport? = null
    /** SETUP event connection must exist before RECORD; transport takes it after media setup. */
    @Volatile private var pendingEventSocket: Socket? = null
    @Volatile var hasWifiSession: Boolean = false
        private set
    /** Peer event EOF ends Wi-Fi independently of Bluetooth, including during startup. */
    @Volatile var onPeerEnded: ((String) -> Unit)? = null
    private fun closePendingEvents() {
        pendingEventSocket?.let { runCatching { it.close() } }
        pendingEventSocket = null
    }

    private fun connectMediaSocket(port: Int): Socket {
        val target = rtsp.connectedAddress ?: error("RTSP is disconnected")
        return socketFactory().also { socket ->
            try {
                addressBinder?.invoke(socket)
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(target, port), 5000)
            } catch (e: Exception) { socket.close(); throw e }
        }
    }
    @Volatile private var iapTunnel: IapTunnelStream? = null // car-touch-iap2-tunnel
    val tunnel: IapTunnelStream? get() = iapTunnel // car-touch-iap2-tunnel
    private fun closeTunnel() { iapTunnel?.let { runCatching { it.close() } }; iapTunnel = null } // car-touch-iap2-tunnel
    private var feedbackJob: Job? = null
    private var timingSocket: DatagramSocket? = null
    private var timingSession: AirPlayTimingSession? = null
    private val timingLock = Any()

    /** Atomically detach the timing session so only one caller ever stops it. */
    private fun takeTimingSession(): AirPlayTimingSession? = synchronized(timingLock) { timingSession.also { timingSession = null } }

    suspend fun run(scope: CoroutineScope) = withContext(Dispatchers.IO) {
        try {
            if (!runAttempt(scope, keyOverride = null, allowFallback = true)) {
                val fallbackKeys = setupKeyFallback?.invoke()
                    ?: error("SETUP failed and no fallback keys are available")
                log("AirPlay: retrying handshake with Okcar hardcoded SETUP keys")
                runAttempt(scope, keyOverride = fallbackKeys, allowFallback = false)
            }
        } catch (e: Throwable) {
            log("AirPlay: orchestrator failed: ${e.javaClass.simpleName}: ${e.message}")
            stop()
            throw e
        }
    }

    private fun newRtspClient(): AirPlayRtspClient =
        AirPlayRtspClient(
            params,
            socketFactory,
            log,
            addressResolver = addressResolver,
            addressBinder = addressBinder,
            addressCandidates = addressCandidates,
        )

    private fun resetAttemptResources() {
        hasWifiSession = false
        stopIapOut()
        stopResourceModes()
        resourceArbiter.reset()
        closeTunnel() // car-touch-iap2-tunnel
        closePendingEvents()
        mediaTransport?.close(); mediaTransport = null
        takeTimingSession()?.let { ts -> runCatching { ts.close() } }
        runCatching { timingSocket?.close() }
        timingSocket = null
        runCatching { rtsp.close() }
        rtsp = newRtspClient()
    }

    private suspend fun runAttempt(
        scope: CoroutineScope,
        keyOverride: Pair<ByteArray, ByteArray>?,
        allowFallback: Boolean,
    ): Boolean {
            // A retry starts with the phone as the permanent owner of both
            // main resources.  Receiver requests can then move either one
            // independently and invalidate the delayed reassertion token.
            resourceArbiter.reset()
            log("AirPlay: open RTSP control connection")
            rtsp.connect()

            // 0. DIAGNOSTIC: GET /info as the VERY FIRST request, before
            // /auth-setup. In standard AirPlay 2 an unauthenticated /info is
            // allowed and returns the feature bitmask + HomeKit pk. If this
            // 401s too, the endpoint requires pairing from the very start.
            runCatching {
                val probe = rtsp.getInfo()
                log("AirPlay: [diag] first-request /info -> ${probe.statusCode} body=${probe.body.size}B")
                if (probe.body.isNotEmpty()) {
                    log("AirPlay: [diag] /info ascii=${probe.body.asciiPreview(probe.body.size.coerceAtMost(700))}")
                    log("AirPlay: [diag] /info bodyHex=${probe.body.previewHex(probe.body.size.coerceAtMost(700))}")
                }
            }.onFailure { log("AirPlay: [diag] first-request /info failed: ${it.message}") }

            // Skipped on the media path: CarPlayPairing sends its own pair-setup M1,
            // and a second M1 would disturb the receiver's pair-setup state.
            if (mediaOptions?.receiverPublicKey == null) {
                // 0b. Full HomeKit pair-setup M1-M6 + pair-verify, as real iPhones do (reference reference).
                // HKP 3, PIN 3939; receiver identity learned from M6. Control keys come from pair-verify.
                val (cid, seed) = mediaOptions?.let { it.controllerId to it.seed } ?: pairingIdentity ?: (java.util.UUID.randomUUID().toString() to CarPlayCrypto.random(32))
                runCatching { CarPlayPairing(cid, seed, null, mapOf("X-Apple-HKP" to "3"), "application/octet-stream", log).run(rtsp) }
                    .onSuccess {
                        verifySecret = it
                        log("AirPlay: pair-setup + pair-verify complete; control encryption enabled")
                        // 0c. DIAGNOSTIC: first encrypted request after pair-verify.
                        runCatching {
                            val info = rtsp.getInfo()
                            log("AirPlay: [diag] post-pair-verify encrypted /info -> ${info.statusCode} body=${info.body.size}B headers=${info.headers}")
                            if (info.body.isNotEmpty()) {
                                val plist = runCatching { ReceiverPlist.decode(info.body) }
                                plist.onSuccess { log("AirPlay: [diag] post-pair-verify encrypted /info plist=${plistText(it).take(3000)}") }
                                    .onFailure { log("AirPlay: [diag] post-pair-verify encrypted /info not a bplist (${it.message}); hex=${info.body.previewHex(info.body.size.coerceAtMost(400))}") }
                            }
                        }.onFailure { log("AirPlay: [diag] post-pair-verify encrypted /info failed: ${it.javaClass.simpleName}: ${it.message}") }
                    }
                    .onFailure {
                        verifySecret = null
                        log("AirPlay: pair-setup/verify failed: ${it.javaClass.simpleName}: ${it.message}; continuing unpaired")
                    }
            }

            // 1. /auth-setup
            log("AirPlay: POST /auth-setup")
            val authBody = mfi.initialAuthBody()
            log("AirPlay: MFi-SAP M1 body=${authBody.size}B prefix=${authBody.previewHex(1)}")
            val authResp = rtsp.authSetup(authBody)
            log("AirPlay: /auth-setup -> ${authResp.statusCode} (body=${authResp.body.size}B first=${authResp.body.previewHex()})")
            log("AirPlay: /auth-setup response preview96=${authResp.body.previewHex(96)}")
            authResp.requireSuccess("/auth-setup")
            val derivedKeys = mfi.finishExchange(authResp.body)
            val (eiv, ekey) = keyOverride ?: derivedKeys
            params.eiv = eiv
            params.ekey = ekey
            log("AirPlay: MFi-SAP ${if (keyOverride == null) "derived" else "overrode"} eiv=${eiv.size}B ekey=${ekey.size}B")

            val pinnedKey = mediaOptions?.receiverPublicKey
            val shared = if (mediaOptions != null && pinnedKey != null) {
                log("AirPlay: completing pair-setup and pair-verify for encrypted media")
                CarPlayPairing(mediaOptions.controllerId, mediaOptions.seed, pinnedKey, log = log).run(rtsp)
                    .also { log("AirPlay: pairing verified; control encryption enabled") }
            } else null
            val pairingSecret = shared ?: verifySecret

            // 1b. DIAGNOSTIC: GET /info BEFORE SETUP so we can read the car's
            // feature bitmask + HomeKit pk even when SETUP would 401. This tells
            // us whether the BMW requires HomeKit pair-setup/pair-verify
            // (SupportsUnifiedPairSetupAndMFi) vs the MFi-SAP-only path.
            runCatching {
                val pre = rtsp.getInfo()
                log("AirPlay: [diag] pre-SETUP /info -> ${pre.statusCode} body=${pre.body.size}B")
                log("AirPlay: [diag] /info bodyHex=${pre.body.previewHex(pre.body.size.coerceAtMost(700))}")
                log("AirPlay: [diag] /info ascii=${pre.body.asciiPreview(pre.body.size.coerceAtMost(700))}")
            }.onFailure { log("AirPlay: [diag] pre-SETUP /info failed: ${it.message}") }

            // 2. Start timing before SETUP: answer the receiver's NTP requests on the
            // same clock as the audio sync anchors, as the sender does.
            val timingServer = AirPlayTimingServer(log)
            val timingPort = timingServer.start()
            timingSession = AirPlayTimingSession.onceOnly(timingPort) { timingServer.stop() }
            params.timingPort = timingPort

            // 3. SETUP. OkcarOS sends this immediately after /auth-setup;
            // /info is triggered later once the event-session side is ready.
            log("AirPlay: SETUP rtsp:.../${params.rtspSessionId} setupSessionUUID=${params.setupSessionUuid}")
            val setupBody = AirPlayBplist.encode(buildSetupPlist(params))
            val setupResp = rtsp.setup(setupBody)
            log("AirPlay: SETUP -> ${setupResp.statusCode}")
            if (setupResp.statusCode == 401 && allowFallback && setupKeyFallback != null) {
                val fallbackKeys = setupKeyFallback.invoke()
                if (!params.eiv.contentEquals(fallbackKeys.first) || !params.ekey.contentEquals(fallbackKeys.second)) {
                    log("AirPlay: SETUP 401 with non-fallback keys; closing RTSP and preparing fallback")
                    resetAttemptResources()
                    return false
                }
                log("AirPlay: SETUP 401 with fallback keys already in use; not retrying duplicate SETUP")
            }
            setupResp.requireSuccess("SETUP")
            diagCapture("setup-resp", setupResp.body, CarDiagSummary.summarizeSetupResponse(setupResp.body)) // car-touch-iap2-tunnel

            // Real iPhone opens the session event socket immediately after SETUP,
            // before /info and RECORD. Some receivers wait for this connection
            // before acknowledging RECORD, even though stream SETUP comes later.
            if (mediaOptions != null && pairingSecret != null) {
                val eventPort = (ReceiverPlist.map(setupResp.body)["eventPort"] as? Number)?.toInt()
                    ?.also { require(it in 1..65535) } ?: error("Missing receiver event port")
                pendingEventSocket = connectMediaSocket(eventPort)
                hasWifiSession = true
                log("AirPlay: event socket connected before RECORD port=$eventPort")
            }

            // 4. /info
            log("AirPlay: GET /info")
            val info = rtsp.getInfo()
            log("AirPlay: /info -> ${info.statusCode}, headers=${info.headers.keys}")
            info.requireSuccess("/info")
            diagCapture("info", info.body, CarDiagSummary.summarizeInfo(info.body)) // car-touch-iap2-tunnel
            // Keep the receiver's HID declarations scoped to this media
            // session.  UUID values are preserved as String/Number so the
            // event-channel arm commands serialize exactly as advertised.
            val hidRegistry = runCatching { HidDeviceRegistry.fromInfo(info.body, log) }
                .onFailure { log("[hid] /info registry unavailable: ${it.javaClass.simpleName}: ${it.message}") }
                .getOrNull()

            // Audio must follow the receiver's per-stream declaration.  The
            // real car advertises media AAC-LC on type 102/48 kHz while its
            // type-100 media entry has no PCM stereo bits; sending the old
            // hard-coded type-100/44.1 kHz PCM stream is accepted by SETUP but
            // is never routed to the car's media output.
            val infoMap = runCatching { ReceiverPlist.map(info.body) }
                .onFailure { log("AirPlay: /info audio capabilities unavailable: ${it.message}") }
                .getOrNull()
            if (mediaOptions?.audio == true) {
                mediaOptions.audioProfile = AudioNegotiation.preferred(infoMap, log)
                log("AirPlay: selected audio profile=${mediaOptions.audioProfile.label}")
            }

            // 5. RECORD, before any stream SETUP (real-iPhone order)
            log("AirPlay: RECORD")
            val recResp = synchronized(rtspLock) { rtsp.record() }
            log("AirPlay: RECORD -> ${recResp.statusCode}")
            recResp.requireSuccess("RECORD")
            onDiagnosticStage(com.cardroid.lib.ConnectionDiagnostics.Stage.RECORD)
            if (mediaOptions != null && pairingSecret == null) log("AirPlay: media skipped (no pair-verify secret)")
            if (mediaOptions != null && pairingSecret != null) {
                val displays=runCatching { ReceiverPlist.map(info.body)["displays"] as? List<*> }
                    .onFailure { log("AirPlay: /info plist unreadable for display size (${it.message}); using ${mediaOptions.width}x${mediaOptions.height}") }.getOrNull()
                val display=displays?.filterIsInstance<Map<*,*>>()?.firstOrNull { (it["type"] as? Number)?.toInt()==110 }
                val w=(display?.get("widthPixels") as? Number)?.toInt() ?: mediaOptions.width
                val h=(display?.get("heightPixels") as? Number)?.toInt() ?: mediaOptions.height
                require(w in 16..8192 && h in 16..8192)
                mediaOptions.receiverWidth=w;mediaOptions.receiverHeight=h
                val canvas=VideoCanvas.size(w,h,mediaOptions.maxCanvasLongSide)
                mediaOptions.width=canvas.width
                mediaOptions.height=canvas.height
                val maxFps=(display?.get("maxFPS") as? Number)?.toInt()
                mediaOptions.fps=if (maxFps!=null && maxFps>=60) 60 else 30
                mediaOptions.displayUuid=display?.get("uuid") as? String
                log("AirPlay: receiver display=${w}x${h} maxFPS=$maxFps -> fps=${mediaOptions.fps}; video canvas=${mediaOptions.width}x${mediaOptions.height}")
                startMedia(pairingSecret, setupResp, mediaOptions, hidRegistry)
            }

            if (mediaOptions == null || pairingSecret == null) sendInitialModes()

            synchronized(lifecycleLock) {
                mediaTransport?.let {
                    check(hasWifiSession) { "Wi-Fi event session ended before media readiness" }
                    mediaOptions?.onReady?.invoke(it)
                    onDiagnosticStage(com.cardroid.lib.ConnectionDiagnostics.Stage.MEDIA)
                }
            }

            // 7. Periodic /feedback every 2s
            feedbackJob = scope.launch(Dispatchers.IO) {
                var feedbackCount = 0
                while (true) {
                    delay(2_000)
                    try {
                        val fb = synchronized(rtspLock) { rtsp.feedback() }
                        log("AirPlay: /feedback -> ${fb.statusCode}")
                        fb.requireSuccess("/feedback")
                        // The receiver reports each audio stream's render state here; sampleTime
                        // appears (and advances) only once its audio output is actually consuming.
                        val feedbackMap = if (fb.body.isNotEmpty()) runCatching { ReceiverPlist.map(fb.body) }.getOrNull() else null
                        if (feedbackCount++ % 5 == 0 && feedbackMap != null) {
                            log("AirPlay: /feedback streams=${feedbackMap["streams"].toString().take(400)}")
                        }
                    } catch (e: Throwable) {
                        log("AirPlay: /feedback failed: ${e.message}; ending loop")
                        break
                    }
                }
            }
            return true
    }

    private fun startMedia(shared: ByteArray, initial: AirPlayRtspClient.Response,
                           options: CarPlayMediaOptions, hidRegistry: HidDeviceRegistry?) {
        val videoId = java.security.SecureRandom().nextLong() and Long.MAX_VALUE
        val audioId = java.security.SecureRandom().nextLong() and Long.MAX_VALUE
        val audioProfile = options.audioProfile
        // Real iPhone video entry (Receiver capture): no width/height/fps; the car display is named by uuid.
        val videoStream = iphoneVideoStream(videoId, options.displayUuid)
        val tunnelSeed = IapTunnelStream.newSeed()
        fun setupStreams(list: List<AirPlayBplist.Value>) =
            rtsp.setup(AirPlayBplist.encode(AirPlayBplist.dict("streams" to AirPlayBplist.ArrayV(list))))
        // A rejected optional stream is recoverable; an RTSP I/O failure is not.
        val tunnelResponse = setupStreams(listOf(IapTunnelStream.streamEntry(tunnelSeed)))
        val tunnelPort = if (tunnelResponse.statusCode in 200..299) {
            diagCapture("tunnel-setup-resp", tunnelResponse.body,
                CarDiagSummary.summarizeMediaSetupResponse(tunnelResponse.body))
            runCatching {
                val entries = ReceiverPlist.map(tunnelResponse.body)["streams"] as? List<*> ?: emptyList<Any?>()
                IapTunnelStream.dataPort(entries)
            }.onFailure { log("[tunnel] invalid SETUP reply: ${it.message}") }.getOrNull()
        } else {
            log("[tunnel] SETUP rejected: ${tunnelResponse.statusLine}; using /command")
            null
        }
        if (tunnelPort != null) {
            val host = rtsp.connectedAddress ?: error("RTSP is disconnected")
            runCatching {
                val opened = IapTunnelStream.open(socketFactory(), addressBinder, host, tunnelPort, shared, tunnelSeed, log)
                // Publish before the first write so stop()/failure cleanup owns the socket.
                iapTunnel = opened
                opened.send(com.cardroid.lib.Iap2LinkSync.DETECT_MARKER)
                log("[tunnel] iAP2 marker sent")
            }.onFailure {
                closeTunnel()
                log("[tunnel] open failed: ${it.message}; using /command")
            }
        } else log("[tunnel] no usable dataPort; using /command")
        sendInitialModes()
        // 2. The screen (type 110) alone.
        val response = setupStreams(listOf(videoStream))
        response.requireSuccess("video SETUP")
        diagCapture("media-setup-resp", response.body, CarDiagSummary.summarizeMediaSetupResponse(response.body)) // car-touch-iap2-tunnel
        val videoReplies = ReceiverPlist.map(response.body)["streams"] as? List<*> ?: error("No video SETUP streams")
        // Real iPhone (Receiver capture, iOS 26.7.1): audio gets its own SETUP after the screen, carrying only
        // Preserve the negotiated audio latency rather than forcing the captured iPhone value.
        var earlyAudioSocket: java.net.DatagramSocket? = null
        var video: java.net.Socket? = null
        var audio: java.net.DatagramSocket? = null
        try {
            val replies = if (options.audio) {
                val result = resourceArbiter.controllerTake(CarPlayResourceArbiter.RESOURCE_AUDIO, "phone starts media")
                sendModes(result.snapshot, "audio take")
                val socket = udpSocketFactory().also { earlyAudioSocket = it }
                val audioStream = iphoneAudioStream(audioProfile, audioId, socket.localPort)
                val audioResponse = runCatching { setupStreams(listOf(audioStream)) }
                    .onFailure { socket.close() }.getOrThrow()
                audioResponse.requireSuccess("audio SETUP")
                log("AirPlay: iPhone-style audio SETUP accepted controlPort(ours)=${socket.localPort} latency=${audioProfile.latencyMs}ms")
                videoReplies + (ReceiverPlist.map(audioResponse.body)["streams"] as? List<*> ?: error("No audio SETUP streams"))
            } else videoReplies
            fun port(type: Int): Int {
                val stream = replies.filterIsInstance<Map<*,*>>().firstOrNull { (it["type"] as? Number)?.toInt() == type }
                    ?: error("Receiver rejected media type $type")
                return (stream["dataPort"] as? Number)?.toInt()?.also { require(it in 1..65535) } ?: error("Missing media data port")
            }
            val videoPort = port(110)
            val audioPort = if (options.audio) port(audioProfile.streamType) else 0
            val audioControlPort = if (options.audio) {
                replies.filterIsInstance<Map<*, *>>()
                    .firstOrNull { (it["type"] as? Number)?.toInt() == audioProfile.streamType }
                    ?.let { (it["controlPort"] as? Number)?.toInt() ?: 0 }
                    ?.takeIf { it in 1..65535 } ?: 0
            } else 0
            val connectedVideo = connectMediaSocket(videoPort).also { video = it }
            audio = if (options.audio) earlyAudioSocket ?: udpSocketFactory() else null
            val host = rtsp.connectedAddress ?: error("RTSP is disconnected")
            val transport = CarPlayMediaTransport(connectedVideo,audio,if (audioPort > 0) java.net.InetSocketAddress(host,audioPort) else null,
                CarPlayMediaWire.streamKey(shared,videoId),CarPlayMediaWire.streamKey(shared,audioId),log,
                options.width,options.height,options.receiverWidth,options.receiverHeight,options.fps,
                audioProfile,
                if (audioControlPort > 0) java.net.InetSocketAddress(host,audioControlPort) else null)
            mediaTransport = transport
            val eventPort = (ReceiverPlist.map(initial.body)["eventPort"] as? Number)?.toInt()
                ?.also { require(it in 1..65535) } ?: error("Missing receiver event port")
            transport.onPeerEnded = { reason ->
                synchronized(lifecycleLock) {
                    hasWifiSession = false
                    stop()
                    onPeerEnded?.invoke(reason)
                }
            }
            setupIapOut(transport)
            val screenLifecycle = ScreenStreamLifecycle(
                suspendVideo = { transport.suspendVideo() },
                teardownScreen = {
                    synchronized(rtspLock) { rtsp.teardown(ScreenStreamLifecycle.teardownBody()) }
                        .requireSuccess("screen-only TEARDOWN")
                    log("AirPlay: screen-only TEARDOWN accepted; audio/events/display retained")
                },
                resumeScreen = {
                    check(mediaTransport === transport) { "Media session ended before screen restart" }
                    val id = java.security.SecureRandom().nextLong() and Long.MAX_VALUE
                    val reply = synchronized(rtspLock) {
                        setupStreams(listOf(iphoneVideoStream(id, options.displayUuid)))
                    }
                    reply.requireSuccess("return screen SETUP")
                    val streams = ReceiverPlist.map(reply.body)["streams"] as? List<*> ?: error("Missing screen reply")
                    val screen = streams.filterIsInstance<Map<*, *>>().firstOrNull {
                        (it["type"] as? Number)?.toInt() == 110
                    } ?: error("Missing return screen stream")
                    val port = (screen["dataPort"] as? Number)?.toInt()?.takeIf { it in 1..65535 }
                        ?: error("Missing return screen port")
                    val socket = connectMediaSocket(port)
                    if (mediaTransport !== transport) {
                        socket.close()
                        error("Media session ended during screen restart")
                    }
                    transport.resumeVideo(socket, CarPlayMediaWire.streamKey(shared, id))
                    log("AirPlay: return screen SETUP accepted stream=$id port=$port; existing app retained")
                },
            )
            startResourceModes(transport, screenLifecycle)
            val events = pendingEventSocket ?: error("Event socket was not established before RECORD")
            // Ownership transfers only after startEvents succeeds; both paths close on failure.
            transport.startEvents(events, shared, hidRegistry)
            pendingEventSocket = null
            log("AirPlay: media SETUP accepted videoPort=$videoPort audioPort=$audioPort audioControlPort=$audioControlPort eventPort=$eventPort profile=${audioProfile.label}")
        } catch (e: Exception) { video?.close(); audio?.close(); earlyAudioSocket?.close(); throw e }
    }

    /**
     * Connects the event-channel changeModes callback to the protocol-only
     * arbiter.  The event reader must stay non-blocking: the response is
     * serialized on its own RTSP worker, alongside feedback and iAP traffic.
     */
    private fun startResourceModes(transport: CarPlayMediaTransport, screenLifecycle: ScreenStreamLifecycle) {
        stopResourceModes()
        val network = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "cardroid-resource-modes").apply { isDaemon = true }
        }
        modesNetwork = network
        transport.onChangeModes = { command ->
            cancelPostAuthModesReassert("receiver changeModes")
            val result = resourceArbiter.applyChangeModes(command)
            log("AirPlay: changeModes accepted=${result.accepted} ignored=${result.ignored} changed=${result.changed}")
            val token = resourceArbiter.screenReturnToken()
            enqueueModesChanged(result.snapshot, "receiver changeModes") {
                val latest = resourceArbiter.snapshot().resources.first { it.id == CarPlayResourceArbiter.RESOURCE_SCREEN }
                // Reconcile the latest ownership, not a queued stale snapshot.
                screenLifecycle.reconcile(latest)
                if (result.changed && resourceArbiter.isScreenReturnCurrent(token)) transport.requestVideoRefresh()
            }
        }
        transport.onRequestUI = { command ->
            val request = RequestUi.parse(command)
            if (!requestUiDebouncer.accept(request, restoringScreen = resourceArbiter.accessoryOwnsScreen())) {
                log("AirPlay: requestUI ${request.kind} url=${request.url} repeated within 1 s; ignored")
            } else {
                log("AirPlay: requestUI ${request.kind} url=${request.url}")
                cancelPostAuthModesReassert("receiver requestUI")
                val result = resourceArbiter.controllerTake(CarPlayResourceArbiter.RESOURCE_SCREEN, "requestUI ${request.kind}")
                // Always announce: the car may have left CarPlay without telling us, and the debouncer limits repeats.
                val token = resourceArbiter.screenReturnToken()
                enqueueModesChanged(result.snapshot, "receiver requestUI") {
                    if (resourceArbiter.isScreenReturnCurrent(token)) {
                        screenLifecycle.reconcile(resourceArbiter.snapshot().resources.first {
                            it.id == CarPlayResourceArbiter.RESOURCE_SCREEN
                        })
                        transport.requestVideoRefresh()
                    }
                }
                runCatching { mediaOptions?.onRequestUI?.invoke(request) }
                    .onFailure { log("AirPlay: requestUI app hook failed: ${it.javaClass.simpleName}: ${it.message}") }
            }
        }
    }

    private fun enqueueModesChanged(snapshot: CarPlayResourceArbiter.Snapshot, reason: String, onAccepted: (() -> Unit)? = null) {
        val network = modesNetwork ?: run {
            log("AirPlay: modesChanged dropped ($reason; resource worker unavailable)")
            return
        }
        runCatching {
            network.execute {
                try {
                    val body = AirPlayBplist.encode(resourceArbiter.modesChangedCommand(snapshot))
                    val response = synchronized(rtspLock) { rtsp.command(body) }
                    log("AirPlay: /command modesChanged $reason -> ${response.statusCode} resources=${snapshot.resources}")
                    if (response.statusCode in 200..299) {
                        try { onAccepted?.invoke() }
                        catch (e: Exception) {
                            log("AirPlay: screen lifecycle failed: ${e.message}")
                            mediaTransport?.failSession("screen lifecycle failed: ${e.message}")
                        }
                    }
                } catch (e: Throwable) {
                    log("AirPlay: modesChanged $reason failed: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }.onFailure {
            log("AirPlay: modesChanged $reason queue rejected: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    /** Ownership declarations bracket disableBluetooth before video/audio SETUP. */
    private fun sendInitialModes() {
        resourceArbiter.startAccessoryOwned(CarPlayResourceArbiter.RESOURCE_AUDIO)
        resourceArbiter.startAccessoryOwned(CarPlayResourceArbiter.RESOURCE_SCREEN)
        sendModes(resourceArbiter.snapshot(), "initial")
        sendDisableBluetooth()
        resourceArbiter.controllerTake(CarPlayResourceArbiter.RESOURCE_SCREEN, "initial CarPlay UI")
        sendModes(resourceArbiter.snapshot(), "before video")
    }

    private fun sendModes(snapshot: CarPlayResourceArbiter.Snapshot, reason: String) {
        val body = AirPlayBplist.encode(resourceArbiter.modesChangedCommand(snapshot))
        val response = synchronized(rtspLock) { rtsp.command(body) }
        log("AirPlay: /command modesChanged $reason -> ${response.statusCode}")
        response.requireSuccess("/command modesChanged $reason")
    }

    /**
     * Real iPhones send `disableBluetooth {deviceID}` once CarPlay runs (AirLib's
     * receiver handles it as onDisableBluetoothReq), telling the head unit to stop
     * treating this device as a Bluetooth phone/media source.
     */
    private fun sendDisableBluetooth() {
        runCatching {
            val body = AirPlayBplist.encode(disableBluetoothCommand(params.deviceId))
            val resp = synchronized(rtspLock) { rtsp.command(body) }
            log("AirPlay: /command disableBluetooth deviceID=${params.deviceId} -> ${resp.statusCode}")
        }.onFailure { log("AirPlay: disableBluetooth failed: ${it.javaClass.simpleName}: ${it.message}") }
    }

    private fun stopResourceModes() {
        mediaTransport?.onChangeModes = null
        mediaTransport?.onRequestUI = null
        modesNetwork?.shutdownNow()
        modesNetwork = null
    }

    // car-touch-iap2-tunnel Stage 3 IAP_OUT. Event callbacks only enqueue into
    // the bounded actor; responder state and retransmission are serialized there.
    // Blocking RTSP writes use a separate ordered worker so timer/feed work is
    // not delayed by a slow receiver. The responder's send sink is therefore
    // intentionally enqueue-only.
    private fun setupIapOut(transport: CarPlayMediaTransport) {
        stopIapOut()
        val sessionGeneration = iapOutGeneration.get()
        val sessionTunnel = iapTunnel
        val sessionRtsp = rtsp
        val actor = java.util.concurrent.ThreadPoolExecutor(
            1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
            java.util.concurrent.ArrayBlockingQueue<Runnable>(128),
            { r -> Thread(r, "cardroid-iap-out-actor").apply { isDaemon = true } },
            java.util.concurrent.ThreadPoolExecutor.AbortPolicy(),
        )
        val network = java.util.concurrent.ThreadPoolExecutor(
            1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
            java.util.concurrent.ArrayBlockingQueue<Runnable>(128),
            { r -> Thread(r, "cardroid-iap-out-net").apply { isDaemon = true } },
            java.util.concurrent.ThreadPoolExecutor.AbortPolicy(),
        )
        val ticker = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "cardroid-iap-out-timer").apply { isDaemon = true }
        }
        iapOutActor = actor
        iapOutNetwork = network
        iapOutTicker = ticker

        fun enqueueActor(task: () -> Unit) {
            runCatching { actor.execute(task) }
                .onFailure { log("[iap-out] actor queue rejected: ${it.javaClass.simpleName}: ${it.message}") }
        }

        val responder = IapOutResponder(
            send = { bytes ->
                // The actor has already recorded sequence/deadline state. The
                // network worker preserves wire order while this enqueue stays
                // non-blocking for event and timer callers.
                runCatching {
                    network.execute {
                        if (iapOutGeneration.get() != sessionGeneration) return@execute
                        val tunnel = sessionTunnel
                        if (tunnel != null && !tunnel.isClosed) {
                            try {
                                tunnel.send(bytes)
                                val n = ++iapOutSent
                                if (n <= 20 || n % 30 == 0) log("[iap-out] tunnel tx len=${bytes.size}")
                                return@execute
                            } catch (e: Throwable) {
                                log("[iap-out] tunnel tx failed: ${e.javaClass.simpleName}: ${e.message}; using /command")
                            }
                        }
                        try {
                            val body = AirPlayBplist.encode(AirPlayBplist.dict(
                                "type" to AirPlayBplist.str("iAPSendMessage"),
                                "params" to AirPlayBplist.dict("data" to AirPlayBplist.data(bytes))))
                            val resp = synchronized(rtspLock) {
                                if (iapOutGeneration.get() != sessionGeneration) return@execute
                                sessionRtsp.command(body)
                            }
                            val n = ++iapOutSent
                            if (n <= 20 || n % 30 == 0) log("[iap-out] POST /command iAPSendMessage len=${bytes.size} -> ${resp.statusCode}")
                        } catch (e: Throwable) {
                            log("[iap-out] POST /command failed: ${e.javaClass.simpleName}: ${e.message}")
                        }
                    }
                }.onFailure { log("[iap-out] network queue rejected: ${it.javaClass.simpleName}: ${it.message}") }
            },
            log = log,
            mfi = iapOutMfi,
            deviceName = params.name,
            nowPlaying = { mediaOptions?.nowPlaying?.invoke() ?: com.cardroid.lib.NowPlayingState() },
        )
        responder.onAuthenticationComplete = {
            schedulePostAuthModesReassert(sessionGeneration)
        }
        transport.onIapData = { data -> enqueueActor { responder.onData(data.copyOf()) } }
        ticker.scheduleAtFixedRate({ enqueueActor { responder.tick() } }, 100L, 100L, java.util.concurrent.TimeUnit.MILLISECONDS)
        log("[iap-out] enabled (control channel; identification/auth isolated from Wi-Fi/AirPlay)")
    }

    /**
     * Reassert resource ownership once, shortly after the real control iAP
     * authentication completes. The delayed RTSP request runs on the ordered
     * network worker and takes the same control lock as feedback/iAP traffic.
     */
    private fun schedulePostAuthModesReassert(generation: Long) {
        if (iapOutGeneration.get() != generation || postAuthModesFuture != null) return
        val ticker = iapOutTicker ?: return
        val network = iapOutNetwork ?: return
        val resourceToken = resourceArbiter.reassertToken()
        postAuthModesFuture = runCatching {
            ticker.schedule({
                if (iapOutGeneration.get() == generation && resourceArbiter.isCurrent(resourceToken)) {
                    runCatching {
                        network.execute {
                            if (iapOutGeneration.get() == generation && resourceArbiter.isCurrent(resourceToken)) {
                                try {
                                    val body = AirPlayBplist.encode(resourceArbiter.modesChangedCommand())
                                    val response = synchronized(rtspLock) {
                                        if (iapOutGeneration.get() == generation && resourceArbiter.isCurrent(resourceToken)) rtsp.command(body) else null
                                    }
                                    if (response != null && iapOutGeneration.get() == generation && resourceArbiter.isCurrent(resourceToken)) {
                                        log("AirPlay: /command modesChanged post-auth reassert -> ${response.statusCode}")
                                    }
                                } catch (e: Throwable) {
                                    if (iapOutGeneration.get() == generation && resourceArbiter.isCurrent(resourceToken)) {
                                        log("AirPlay: post-auth modesChanged failed: ${e.javaClass.simpleName}: ${e.message}")
                                    }
                                }
                            }
                        }
                    }.onFailure {
                        if (iapOutGeneration.get() == generation) {
                            log("AirPlay: post-auth modesChanged queue rejected: ${it.javaClass.simpleName}: ${it.message}")
                        }
                    }
                }
            }, 1_000L, java.util.concurrent.TimeUnit.MILLISECONDS)
        }.onFailure {
            log("AirPlay: post-auth modesChanged schedule failed: ${it.javaClass.simpleName}: ${it.message}")
        }.getOrNull()
        if (postAuthModesFuture != null) log("AirPlay: post-auth modesChanged reassert scheduled")
    }

    private fun cancelPostAuthModesReassert(reason: String) {
        if (postAuthModesFuture != null) {
            postAuthModesFuture?.cancel(false)
            postAuthModesFuture = null
            log("AirPlay: post-auth modesChanged reassert cancelled ($reason)")
        }
    }

    private fun stopIapOut() {
        iapOutGeneration.incrementAndGet()
        cancelPostAuthModesReassert("session stop")
        iapOutTicker?.shutdownNow(); iapOutTicker = null
        iapOutActor?.shutdownNow(); iapOutActor = null
        iapOutNetwork?.shutdownNow(); iapOutNetwork = null
    }

    fun stop() = synchronized(lifecycleLock) {
        hasWifiSession = false
        stopIapOut()
        stopResourceModes()
        closeTunnel() // car-touch-iap2-tunnel
        closePendingEvents()
        mediaTransport?.close(); mediaTransport = null
        feedbackJob?.cancel()
        feedbackJob = null
        takeTimingSession()?.let { ts -> runCatching { ts.close() } }
        runCatching { timingSocket?.close() }
        timingSocket = null
        runCatching { rtsp.close() }
    }

    /** Stage 0 diagnostics: log the summary line and hand the raw body to the sink; never throws into the handshake. */
    private fun diagCapture(label: String, body: ByteArray, summary: String) {
        runCatching { log(summary) }
        runCatching { diagSink?.invoke(label, body) }.onFailure { log("[car-diag] save $label failed: ${it.javaClass.simpleName}: ${it.message}") }
    }

    private fun buildSetupPlist(p: AirPlayParams): AirPlayBplist.Value {
        return AirPlayBplist.dict(*buildSetupPairs(p))
    }

    private fun buildSetupPairs(p: AirPlayParams): Array<Pair<String, AirPlayBplist.Value>> =
        arrayOf(
            "deviceID" to AirPlayBplist.str(p.deviceId),
            "eiv" to AirPlayBplist.data(p.eiv),
            "ekey" to AirPlayBplist.data(p.ekey),
            "et" to AirPlayBplist.int(1),
            "groupContainsGroupLeader" to AirPlayBplist.bool(false),
            "groupUUID" to AirPlayBplist.str(p.setupSessionUuid.uppercase()),
            "isMultiSelectAirPlay" to AirPlayBplist.bool(false),
            "macAddress" to AirPlayBplist.str(p.macAddress),
            "model" to AirPlayBplist.str(p.model),
            "name" to AirPlayBplist.str(p.name),
            "osBuildVersion" to AirPlayBplist.str(p.osBuildVersion),
            "osName" to AirPlayBplist.str("iPhone OS"),
            "osVersion" to AirPlayBplist.str("14.5"),
            "senderSupportsRelay" to AirPlayBplist.bool(false),
            "sessionUUID" to AirPlayBplist.str(p.setupSessionUuid),
            "sourceVersion" to AirPlayBplist.str(p.sourceVersion),
            "timingPort" to AirPlayBplist.int(p.timingPort),
        )

    private fun plistText(v: Any?): String = when (v) {
        is ByteArray -> "<${v.size}B>"
        is Map<*, *> -> v.entries.joinToString(", ", "{", "}") { "${it.key}=${plistText(it.value)}" }
        is List<*> -> v.joinToString(", ", "[", "]") { plistText(it) }
        else -> v.toString()
    }

    private fun ByteArray.previewHex(max: Int = 8): String =
        take(max).joinToString("") { b -> "%02x".format(b.toInt() and 0xff) }.ifEmpty { "empty" }

    private fun ByteArray.asciiPreview(max: Int = 8): String =
        take(max).joinToString("") { b ->
            val c = b.toInt() and 0xff
            if (c in 0x20..0x7e) c.toChar().toString() else "."
        }.ifEmpty { "empty" }

    private fun AirPlayRtspClient.Response.requireSuccess(stage: String) {
        if (statusCode !in 200..299) error("$stage failed: $statusLine")
    }

    companion object {
        /** Real iPhone MainHigh media latency (Receiver capture, iOS 26.7.1). */
        const val IPHONE_AUDIO_LATENCY_MS = 1000L

        /** Screen stream description exactly as a real iPhone sends it (latency 75 ms, car display uuid). */
        fun iphoneVideoStream(streamConnectionId: Long, displayUuid: String?): AirPlayBplist.Value {
            val pairs = mutableListOf<Pair<String, AirPlayBplist.Value>>(
                "type" to AirPlayBplist.int(110),
                "streamConnectionID" to AirPlayBplist.int(streamConnectionId),
                "latencyMs" to AirPlayBplist.int(IPHONE_VIDEO_LATENCY_MS),
            )
            if (displayUuid != null) pairs += "uuid" to AirPlayBplist.str(displayUuid)
            return AirPlayBplist.dict(*pairs.toTypedArray())
        }

        /** Real iPhone screen latency (Receiver capture, iOS 26.7.1). */
        const val IPHONE_VIDEO_LATENCY_MS = 75L

        /** Audio stream description exactly as a real iPhone sends it; [controlPort] is our RTCP/UDP port. */
        fun iphoneAudioStream(profile: AudioProfile, streamConnectionId: Long, controlPort: Int): AirPlayBplist.Value =
            AirPlayBplist.dict(
                "type" to AirPlayBplist.int(profile.streamType),
                "streamConnectionID" to AirPlayBplist.int(streamConnectionId),
                "audioType" to AirPlayBplist.str(profile.audioType),
                "audioFormat" to AirPlayBplist.int(profile.formatBit),
                "spf" to AirPlayBplist.int(profile.samplesPerPacket),
                "audioLatencyMs" to AirPlayBplist.int(profile.latencyMs),
                "controlPort" to AirPlayBplist.int(controlPort),
                "supportsHighAccuracyTimestamps" to AirPlayBplist.bool(true),
            )


        fun disableBluetoothCommand(deviceId: String): AirPlayBplist.Value = AirPlayBplist.dict(
            "type" to AirPlayBplist.str("disableBluetooth"),
            "params" to AirPlayBplist.dict("deviceID" to AirPlayBplist.str(deviceId)),
        )

        /** Public shape used for tests and diagnostics; no receiver-specific burst. */
        fun fullModesChangedCommand(): AirPlayBplist.Value = AirPlayBplist.dict(
            "type" to AirPlayBplist.str("modesChanged"),
            "params" to AirPlayBplist.dict(
                "appStates" to AirPlayBplist.array(
                    AirPlayBplist.dict(
                        "appStateID" to AirPlayBplist.int(2),
                        "entity" to AirPlayBplist.int(0),
                    ),
                    AirPlayBplist.dict(
                        "appStateID" to AirPlayBplist.int(3),
                        "entity" to AirPlayBplist.int(0),
                    ),
                    AirPlayBplist.dict(
                        "appStateID" to AirPlayBplist.int(1),
                        "entity" to AirPlayBplist.int(0),
                        "speechMode" to AirPlayBplist.int(-1),
                    ),
                ),
                "resources" to AirPlayBplist.array(
                    AirPlayBplist.dict(
                        "resourceID" to AirPlayBplist.int(1),
                        "entity" to AirPlayBplist.int(1),
                        "permanentEntity" to AirPlayBplist.int(1),
                    ),
                    AirPlayBplist.dict(
                        "resourceID" to AirPlayBplist.int(2),
                        "entity" to AirPlayBplist.int(1),
                        "permanentEntity" to AirPlayBplist.int(1),
                    ),
                ),
            ),
        )
    }
}

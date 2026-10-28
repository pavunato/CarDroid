package com.cardroid.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.net.Network
import com.cardroid.lib.IPhoneIdentity
import com.cardroid.lib.CarDroidPipeline
import com.cardroid.lib.airplay.AirPlayHandshakeOrchestrator
import com.cardroid.lib.airplay.AirPlayParams
import com.okcar.tool.encrypter.SR
import com.cardroid.mfi.OkcarBypassProvider
import com.cardroid.mfi.MfiAuthProvider
import com.cardroid.app.shizuku.ShizukuHelperStarter
import com.cardroid.app.shizuku.ShizukuDecision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Foreground service that runs the iAP2 link.
 *
 * Two modes (selectable via Intent extras):
 *   - Bluetooth (default): RFCOMM listener + BLE advertiser. Real car connects.
 *   - TCP test: connects to test receiver on a test host.
 */
class CarDroidService : Service() {

    private var scope: CoroutineScope? = null
    private var pipeline: CarDroidPipeline? = null
    private var stateJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    private var helperClient: com.cardroid.app.helper.HelperClient? = null
    private val helperScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var shizukuStarter: ShizukuHelperStarter? = null
    private var autoStartJob: Job? = null
    @Volatile private var autoStartAttempted = false
    @Volatile private var autoStartSuppressed = false

    private val bluetoothStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            val state = intent.getIntExtra(android.bluetooth.BluetoothAdapter.EXTRA_STATE, -1)
            if (state == android.bluetooth.BluetoothAdapter.STATE_OFF || state == android.bluetooth.BluetoothAdapter.STATE_TURNING_OFF) stop()
        }
    }

    override fun onCreate() {
        super.onCreate()
        androidx.core.content.ContextCompat.registerReceiver(this, bluetoothStateReceiver,
            android.content.IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED),
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
        CarDroidLog.attachFile(applicationContext)
        CarDroidSettings.initialize(applicationContext)
        shizukuStarter = ShizukuHelperStarter.get(applicationContext).also { it.initialize() }
        shizukuStarter?.let { starter ->
            // A manual Start/Stop from the phone must consume this service instance's
            // one-shot auto-start opportunity, even if it races the three-second settle timer.
            helperScope.launch {
                starter.manualActions.collect {
                    autoStartSuppressed = true
                    autoStartAttempted = true
                    autoStartJob?.cancel()
                }
            }
            scheduleHelperAutoStart(starter)
        }
        runCatching {
            @Suppress("DEPRECATION")
            val code = packageManager.getPackageInfo(packageName, 0).versionCode
            helperClient = com.cardroid.app.helper.HelperClient(code, CarDroidLog::log).also {
                com.cardroid.app.car.CarDisplayState.attach(it)
                helperScope.launch { com.cardroid.app.helper.HelperClient.status.collect { st -> com.cardroid.app.car.CarDisplayState.update(st) } }
                it.start(helperScope)
            }
        }.onFailure { CarDroidLog.log("Helper: client start failed ${it.javaClass.simpleName}: ${it.message}") }
    }

    /** One bounded auto-start attempt per service instance; manual actions always suppress it. */
    private fun scheduleHelperAutoStart(starter: ShizukuHelperStarter) {
        val generation = starter.manualActionGeneration
        autoStartJob = helperScope.launch {
            delay(AUTO_START_SETTLE_MS)
            combine(starter.state, com.cardroid.app.helper.HelperClient.status) { shizuku, helper -> shizuku to helper }
                .collect { (shizuku, helper) ->
                    if (autoStartAttempted || autoStartSuppressed || starter.manualActionGeneration != generation) {
                        autoStartJob?.cancel()
                    } else if (!ShizukuDecision.mayAutoStart(
                        settingEnabled = CarDroidSettings.autoStartHelperViaShizuku.value,
                        attemptConsumed = autoStartAttempted,
                        manualSuppressed = autoStartSuppressed || starter.manualActionGeneration != generation,
                        starterReady = shizuku is ShizukuHelperStarter.State.Ready,
                        helperNotRunning = helper is com.cardroid.app.helper.HelperClient.State.NotRunning,
                    )) {
                        // The setting is evaluated at the settle point. Enabling it later is
                        // an explicit user choice and does not create a retry loop.
                        if (!CarDroidSettings.autoStartHelperViaShizuku.value ||
                            autoStartAttempted || autoStartSuppressed || starter.manualActionGeneration != generation
                        ) autoStartJob?.cancel()
                    } else {
                        autoStartAttempted = true
                        starter.start(auto = true)
                        autoStartJob?.cancel()
                    }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_BT -> startBt()
            ACTION_RETRY -> { stop(stopService = false); startBt() }
            ACTION_STOP -> stop()
        }
        return START_STICKY
    }

    private val discoveredReceivers = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile private var helperAudio: HelperPlaybackAudio? = null
    private var mediaTransport: com.cardroid.lib.airplay.CarPlayMediaTransport? = null
    private val captureLock = Any()

    /** NONE until the shell helper supplies the car display. */
    private enum class Mode { NONE, HELPER }
    @Volatile private var mode = Mode.NONE

    // helper-mode input routing state (touched from the cardroid-events thread only, reset per helper display)
    private var touchRouted = false
    private var lastKnobBits = 0
    private var lastMedia = 0
    private var lastMediaMs = 0L

    /** Helper mode: receiver HID reports -> helper TOUCH_STATE / KEY (phone untouched). Mirror mode never reaches this. */
    private fun routeHelperInput(command: Map<String, Any?>, session: com.cardroid.lib.airplay.CarPlayMediaTransport,
                                 receiverW: Int, receiverH: Int, displayW: Int, displayH: Int) {
        if (command["type"] != "hidSendReport") return
        // An event callback can race teardown or a fast reconnect. Only the
        // transport that is still current may route a report into the helper.
        if (session !== mediaTransport || mode == Mode.NONE) return
        val hc = helperClient ?: return
        val report = command["hidReport"] as? ByteArray ?: return
        val registry = session.hidRegistry
        if (registry != null) {
            val result = registry.decodeDirect(command["uuid"], report, receiverW, receiverH, displayW, displayH)
            registry.note("decoded", result, report.size)
            when (result) {
                is com.cardroid.lib.airplay.HidDeviceRegistry.DecodeResult.Touch -> {
                    val contacts = result.contacts.map {
                        com.cardroid.app.helper.HelperProtocol.TouchContact(it.id, it.down, it.x.toInt(), it.y.toInt())
                    }
                    if (hc.sendTouchState(contacts)) {
                        registry.note("injected", result, report.size)
                        if (!touchRouted) { touchRouted = true; CarDroidLog.log("CarDisplay: touch -> helper") }
                    }
                }
                is com.cardroid.lib.airplay.HidDeviceRegistry.DecodeResult.Button -> {
                    for (k in com.cardroid.app.helper.input.InputRouting.knobKeys(lastKnobBits, result.bits))
                        if (k == com.cardroid.app.helper.input.InputRouting.KEYCODE_HOME) {
                            if (hc.goHome()) registry.note("injected", result, report.size)
                        } else if (hc.sendKey(k)) registry.note("injected", result, report.size)
                    lastKnobBits = result.bits
                }
                is com.cardroid.lib.airplay.HidDeviceRegistry.DecodeResult.Media -> {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (com.cardroid.app.helper.input.InputRouting.mediaShouldFire(result.index, lastMedia, lastMediaMs, now)) {
                        com.cardroid.app.helper.input.InputRouting.mediaKeycode(result.index)?.let {
                            if (hc.sendKey(it)) { lastMediaMs = now; registry.note("injected", result, report.size) }
                        }
                    }
                    lastMedia = result.index
                }
                else -> Unit // registry.note() records bounded diagnostics
            }
            return
        }
        // If /info was unreadable, retain the established TestReceiver aliases as a
        // narrow compatibility fallback. No receiver UUID is lowercased.
        val uuid = command["uuid"] as? String
        when {
            uuid?.equals("2a2a2a2a", ignoreCase = true) == true -> runCatching {
                com.cardroid.lib.airplay.RemoteTouch.decodeDirect(report, receiverW, receiverH, displayW, displayH)
            }.onSuccess { contacts ->
                if (hc.sendTouchState(contacts.map { com.cardroid.app.helper.HelperProtocol.TouchContact(it.id, it.down, it.x.toInt(), it.y.toInt()) }) && !touchRouted) {
                    touchRouted = true; CarDroidLog.log("CarDisplay: touch -> helper")
                }
            }.onFailure { CarDroidLog.log("Touch: invalid report: ${it.message}") }
            uuid?.equals("2a2a2a2b", ignoreCase = true) == true -> if (report.size >= 4) {
                val bits = report[0].toInt() and 0xFF
                for (k in com.cardroid.app.helper.input.InputRouting.knobKeys(lastKnobBits, bits))
                    if (k == com.cardroid.app.helper.input.InputRouting.KEYCODE_HOME) hc.goHome() else hc.sendKey(k)
                lastKnobBits = bits
            }
            uuid?.equals("2a2a2a2c", ignoreCase = true) == true -> if (report.isNotEmpty()) {
                val idx = report[0].toInt() and 0xFF
                val now = android.os.SystemClock.elapsedRealtime()
                if (com.cardroid.app.helper.input.InputRouting.mediaShouldFire(idx, lastMedia, lastMediaMs, now)) {
                    com.cardroid.app.helper.input.InputRouting.mediaKeycode(idx)?.let { hc.sendKey(it); lastMediaMs = now }
                }
                lastMedia = idx
            }
        }
    }

    private fun startHelperDisplay(transport: com.cardroid.lib.airplay.CarPlayMediaTransport): Boolean {
        val hc = helperClient ?: return false
        touchRouted = false; lastKnobBits = 0; lastMedia = 0; lastMediaMs = 0L
        hc.onVideoConfig = { sps, pps -> if (mediaTransport === transport) runCatching { transport.sendVideoConfig(sps, pps) } }
        hc.onVideoFrame = { data, ptsUs ->
            try { transport.sendVideoFrame(data, ptsUs) } catch (e: IllegalStateException) {
                // receiver transport closed: end the display, no mirror fallback for a dead session
                CarDroidLog.log("CarDisplay: transport closed; stopping helper display")
                if (mediaTransport === transport) mode = Mode.NONE
                hc.stopDisplay()
            }
        }
        hc.onDisplayStarted = { id -> CarDroidLog.log("CarDisplay: helper display id=$id ${transport.width}x${transport.height}@${transport.fps}") }
        hc.onDisplayLost = { reason -> onHelperDisplayLost(transport, reason) }
        transport.onKeyFrameNeeded = { hc.requestKeyFrame() }
        val dpi = com.cardroid.lib.airplay.VideoCanvas.dpi(transport.width, transport.height)
        val bitrate = com.cardroid.lib.airplay.VideoCanvas.bitrate(transport.width, transport.height)
        val ok = hc.startDisplay(com.cardroid.app.helper.HelperClient.DisplayParams(
            transport.width, transport.height, dpi, transport.fps, bitrate))
        if (!ok) transport.onKeyFrameNeeded = null
        return ok
    }

    /** A lost helper stops media instead of capturing the phone screen. */
    private fun onHelperDisplayLost(transport: com.cardroid.lib.airplay.CarPlayMediaTransport, reason: String) {
        if (mode != Mode.HELPER || mediaTransport !== transport) return
        mode = Mode.NONE
        synchronized(captureLock) { helperAudio?.close(); helperAudio = null }
        transport.onKeyFrameNeeded = null
        CarDroidLog.log("CarDisplay: helper lost ($reason); restart helper to reconnect")
        runCatching { transport.close() }
    }

    private fun startMediaForeground() {
        val notification = buildNotification("CarDroid (BT) running")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else startForeground(NOTIF_ID, notification)
    }

    @Volatile private var identityName: String? = null

    private fun pairingIdentity(): Pair<String, ByteArray> {
        val prefs = getSharedPreferences("pairing",MODE_PRIVATE)
        val controllerId = prefs.getString("controllerId",null) ?: java.util.UUID.randomUUID().toString().also {
            prefs.edit().putString("controllerId",it).apply()
        }
        val seed = prefs.getString("seed",null)?.let { android.util.Base64.decode(it,android.util.Base64.NO_WRAP) }
            ?: com.cardroid.lib.airplay.CarPlayCrypto.random(32).also {
                prefs.edit().putString("seed",android.util.Base64.encodeToString(it,android.util.Base64.NO_WRAP)).apply()
            }
        return controllerId to seed
    }
    private fun mediaOptions(ep: com.cardroid.lib.CarPlaySession.AirPlayEndpoint, diagnosticToken: Long): com.cardroid.lib.airplay.CarPlayMediaOptions? {
        val hex = ep.publicKey
        val pinned = hex.length == 64 && hex.all { it in "0123456789abcdefABCDEF" }
        CarDroidLog.log("AirPlay: media enabled for '${ep.receiverName}' (receiver key ${if (pinned) "pinned from 0x4301" else "learned from pair-setup M6"})")
        val (controllerId,seed) = pairingIdentity()
        val captureWidth = 1280
        val captureHeight = 720
        // The helper display streams the car panel's own size (as an iPhone does); mirror capture keeps the phone-sized stream.
        val nativeCanvas = helperClient?.isConnected == true
        return com.cardroid.lib.airplay.CarPlayMediaOptions(controllerId,seed,
            if (pinned) hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray() else null, width=captureWidth, height=captureHeight,
            nowPlaying = { helperClient?.nowPlaying ?: com.cardroid.lib.NowPlayingState() },
            onRequestUI = { request -> if (mode == Mode.HELPER) com.cardroid.app.car.RequestUiApps.open(applicationContext, request) },
            maxCanvasLongSide = if (nativeCanvas) com.cardroid.lib.airplay.VideoCanvas.NATIVE_MAX_LONG_SIDE else com.cardroid.lib.airplay.VideoCanvas.STANDARD_LONG_SIDE,
            onReady = { transport ->
                mediaTransport = transport
                RemoteControlService.setHidRegistry(transport.hidRegistry)
                val hc = helperClient
                mode = if (hc != null && hc.isConnected) Mode.HELPER else Mode.NONE
                transport.onCommand = { command ->
                    if (command["type"]=="forceKeyFrame") transport.requestVideoRefresh()
                    else if (mode == Mode.HELPER) routeHelperInput(command, transport, transport.receiverWidth, transport.receiverHeight, transport.width, transport.height)
                }
                if (mode == Mode.HELPER && !startHelperDisplay(transport)) mode = Mode.NONE
                CarDroidLog.log("CarDisplay: mode=${if (mode == Mode.HELPER) "helper" else "unavailable"}")
                if (mode == Mode.HELPER) {
                    helperAudio?.close()
                    val audio = HelperPlaybackAudio(packageManager.getPackageInfo(packageName, 0).versionCode,
                        transport, CarDroidLog::log) {
                        android.os.Handler(mainLooper).post {
                            if (mediaTransport === transport && mode == Mode.HELPER) {
                                helperAudio = null
                                CarDroidLog.log("HelperAudio: unavailable; restart helper to retry audio")
                            }
                        }
                    }
                    helperAudio = audio
                    audio.start()
                } else {
                    CarDroidLog.log("CarDisplay: helper required; screen sharing is disabled")
                    // Record why before closing: the receiver's later socket close must not hide the cause.
                    CarDroidLog.diagnostics.helperMissing(diagnosticToken)
                    transport.close()
                }
            })
    }

    // car-touch-iap2-tunnel Stage 0: raw car /info + SETUP reply bodies to files/car-diag/<label>_<yyyyMMdd-HHmmss>.bplist (size-capped, newest 12 kept)
    private fun saveCarDiag(label: String, body: ByteArray) {
        if (body.size > 2 * 1024 * 1024) { CarDroidLog.log("[car-diag] $label body ${body.size}B too large; not saved"); return }
        val dir = java.io.File(filesDir, "car-diag").apply { mkdirs() }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val f = java.io.File(dir, "${label}_$stamp.bplist")
        f.writeBytes(body)
        dir.listFiles { x -> x.name.endsWith(".bplist") }?.sortedByDescending { it.lastModified() }?.drop(12)?.forEach { it.delete() }
        CarDroidLog.log("[car-diag] saved $label ${body.size}B -> ${f.absolutePath}")
    }









    private var mdns: MdnsBrowser? = null
    private var ctrlInt: CarPlayCtrlIntServer? = null
    private var airPlay: AirPlayHandshakeOrchestrator? = null
    private var wifiHandoff: AndroidWifiHandoff? = null
    @Volatile private var joinedNetwork: Network? = null
    private val endpointLock = Any()
    @Volatile private var pendingAirPlayEndpoint: com.cardroid.lib.CarPlaySession.AirPlayEndpoint? = null
    @Volatile private var mdnsResolvedEndpoint: Pair<String, Int>? = null

    /** Real Bluetooth MAC for SETUP deviceID: adapter, then Settings.Secure, then files/bt_mac (seed via `adb shell run-as com.cardroid.app sh -c 'echo XX:XX:XX:XX:XX:XX > files/bt_mac'`). Null if unknown. */
    @SuppressLint("MissingPermission")
    private fun bluetoothMac(): String? {
        val macRe = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")
        val candidates = listOf<Pair<String, () -> String?>>(
            "adapter" to { (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter?.address },
            "secure-setting" to { android.provider.Settings.Secure.getString(contentResolver, "bluetooth_address") },
            "file" to { java.io.File(filesDir, "bt_mac").takeIf { it.isFile }?.readText()?.trim() },
        )
        for ((source, read) in candidates) {
            val mac = runCatching(read).getOrNull()?.trim()
            if (mac != null && macRe.matches(mac) && !mac.equals("02:00:00:00:00:00", ignoreCase = true)) {
                CarDroidLog.log("AirPlay: SETUP deviceID=${mac.uppercase()} (source=$source)")
                return mac.uppercase()
            }
        }
        CarDroidLog.log("AirPlay: real Bluetooth MAC unavailable; SETUP deviceID stays default (seed files/bt_mac via adb run-as)")
        return null
    }

    @SuppressLint("MissingPermission")

    private fun startBt() {
        // Auto-start may arrive via startForegroundService while already running; keep the foreground promise.
        if (pipeline != null) { startMediaForeground(); return }
        CarDroidLog.clear()
        val diagnosticToken = CarDroidLog.diagnostics.begin()
        startMediaForeground()

        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null) {
            CarDroidLog.diagnostics.ble(diagnosticToken, com.cardroid.lib.ConnectionDiagnostics.Status.UNAVAILABLE)
            CarDroidLog.diagnostics.listener(diagnosticToken, com.cardroid.lib.ConnectionDiagnostics.Status.UNAVAILABLE)
            return
        }
        val phoneName = CarDroidName.forPhone(this)
        val identity = IPhoneIdentity(name = phoneName, bluetoothName = phoneName)
        identityName = identity.name
        val wifi = AndroidWifiHandoff(applicationContext, CarDroidLog::log) { issue ->
            if (issue == "WIFI_LOST") CarDroidLog.diagnostics.wifiLost(diagnosticToken)
            else CarDroidLog.diagnostics.failure(diagnosticToken, issue)
        }
        wifiHandoff = wifi
        val mfi = OkcarBypassProvider()

        val sc = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = sc

        startCtrlIntServer(sc, identity.name, preferredNetwork = null, phase = "pre-WiFi")

        val p = CarDroidPipeline.bluetooth(adapter, identity, mfi, wifi, log = CarDroidLog::log,
            diagnostics = CarDroidLog.diagnostics, diagnosticToken = diagnosticToken)
        pipeline = p
        teardownGuard.rearm()
        p.onLinkUp = { teardownGuard.rearm() }
        p.retainWifiOnBluetoothClose = {
            pipeline === p && airPlay?.hasWifiSession == true
        }
        p.onSessionEnded = { reason -> teardownSession(p, reason) }
        p.start(sc)
        sc.launch {
            p.listening?.collect { listening ->
                if (pipeline === p) available.value = listening
            }
        }
        stateJob = sc.launch {
            p.state.collect { s ->
                if (pipeline === p) CarDroidLog.setState(s)
                // Phase 0+1 is trusted; once WiFi handoff arrives, quiet the
                // iAP2 wire chatter so the AirPlay phase we're debugging is
                // legible. CarPlayAirPlayStart implies WiFi already joined.
                if (s is com.cardroid.lib.CarPlaySession.State.WifiHandoffReceived ||
                    s is com.cardroid.lib.CarPlaySession.State.CarPlayAirPlayStart
                ) {
                    CarDroidLog.setQuietWire(true)
                }
            }
        }
        CarDroidLog.log("CarDroid (BT) started; identity name='${identity.name}' bt='${identity.bluetoothName}'")
        CarDroidLog.log("MFi provider: ${mfi.name}")
        CarDroidLog.setQuietWire(false)

        // Keep one network-ready hook: an early 0x4301 must not replace the
        // discovery/control-service setup. Receiver replies to 0x4300 before
        // Android may have finished joining the accessory network.
        wifi.onNetworkReady { net ->
            if (pipeline !== p) return@onNetworkReady
            CarDroidLog.diagnostics.reached(diagnosticToken, com.cardroid.lib.ConnectionDiagnostics.Stage.WIFI_JOINED)
            discoveredReceivers.clear()
            joinedNetwork = net as? Network
            CarDroidLog.log("WiFi bound (network=$joinedNetwork); starting mDNS browse + ctrl-int publish")

            // Browse for AirPlay/RAOP in case car DOES advertise (some do).
            val browser = MdnsBrowser(applicationContext, CarDroidLog::log)
            mdns?.stop()
            mdns = browser
            browser.onServiceResolved = { svc ->
                if (svc.type.contains("_airplay")) {
                    svc.attributes["pk"]?.toString(Charsets.US_ASCII)?.lowercase()?.let { key ->
                        if (key.length == 64) discoveredReceivers[svc.host] = key
                    }
                    mdnsResolvedEndpoint = svc.host to svc.port
                    CarDroidLog.log("mDNS-resolved AirPlay endpoint: ${svc.host}:${svc.port}")
                }
            }
            browser.start()

            // Receiver browses the phone's control service and probes
            // /ctrl-int/1/connect; its AirPlay listener is advertised separately.
            startCtrlIntServer(sc, identity.name, preferredNetwork = joinedNetwork, phase = "accessory WiFi")
            val pending = synchronized(endpointLock) {
                pendingAirPlayEndpoint.also { pendingAirPlayEndpoint = null }
            }
            pending?.let { runAirPlayHandshake(it, p, wifi, sc, mfi, diagnosticToken) }
        }

        // Use the iAP2 receiver endpoint; queue it if Wi-Fi is still joining.
        p.onAirPlayEndpoint = { ep ->
            val activeNetwork = synchronized(endpointLock) {
                wifi.getJoinedNetwork().also { network ->
                    if (network == null) pendingAirPlayEndpoint = ep
                }
            }
            if (activeNetwork == null) {
                wifi.offerReceiverAddresses(ep.addresses, ep.port)
                CarDroidLog.log("AirPlay: deferring 0x4301 -- accessory WiFi not active; will retry on join")
                // A failed handshake can drop the car Wi-Fi; a later 0x4301 must bring it back, not wait forever.
                wifi.rejoinLastIfLost("0x4301 while accessory WiFi is down")
            } else {
                joinedNetwork = activeNetwork
                runAirPlayHandshake(ep, p, wifi, sc, mfi, diagnosticToken)
            }
        }
    }

    private fun runAirPlayHandshake(
        ep: com.cardroid.lib.CarPlaySession.AirPlayEndpoint,
        p: com.cardroid.lib.CarDroidPipeline,
        wifi: AndroidWifiHandoff,
        sc: kotlinx.coroutines.CoroutineScope,
        iapAuthProvider: MfiAuthProvider,
        diagnosticToken: Long,
    ) {
        run {
            // The car's 0x4301 message is authoritative. mDNS often picks up
            // OTHER AirPlay receivers on the same network (e.g. a laptop on
            // the AP) which would silently redirect our handshake to the
            // wrong host. Only honor mDNS if it actually resolved the same
            // IPv6 host as the car announced.
            val mdns = mdnsResolvedEndpoint
            val (rawHost, port) = when {
                mdns == null -> ep.ipv6 to ep.port
                mdns.first.substringBefore('%').equals(ep.ipv6, ignoreCase = true) -> {
                    CarDroidLog.log("AirPlay: mDNS host matches 0x4301 endpoint; using mDNS port=${mdns.second}")
                    mdns
                }
                else -> {
                    CarDroidLog.log("AirPlay: ignoring mDNS endpoint ${mdns.first}:${mdns.second} -- not the car (0x4301 said ${ep.ipv6})")
                    ep.ipv6 to ep.port
                }
            }
            val network = wifi.getJoinedNetwork()
            if (network == null) {
                joinedNetwork = null
                CarDroidLog.log("AirPlay: skipping handshake -- accessory WiFi is no longer active")
                return
            }
            joinedNetwork = network
            // Figure out the actual interface backing the joined accessory AP.
            // Hardcoding %wlan0 is wrong on OEMs that use wlan1/swlan0, and
            // even on Pixels the sub-ms ECONNREFUSED is caused by the zone id
            // in the address not matching what the kernel routes via.
            // Try the callback-captured iface first; fall back to
            // ConnectivityManager.getLinkProperties(network) -- on some Android
            // builds onLinkPropertiesChanged never fires for accessory APs.
            // Final fallback: enumerate NetworkInterfaces and pick the one
            // carrying an IPv6 link-local in the fe80:: range.
            val ifaceFromCallback = wifi.getJoinedInterfaceName()
            val ifaceFromQuery = network?.let { net ->
                runCatching {
                    val cm = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                    cm.getLinkProperties(net)?.interfaceName
                }.getOrNull()
            }
            val ifaceFromEnum = runCatching {
                java.net.NetworkInterface.getNetworkInterfaces()?.toList()?.firstOrNull { ni ->
                    val n = ni.name.lowercase()
                    // Real WiFi interface only -- exclude dummy/loopback/virtual.
                    ni.isUp && !ni.isLoopback && !ni.isVirtual &&
                        (n.startsWith("wlan") || n.startsWith("swlan") || n.contains("wifi")) &&
                        ni.inetAddresses.toList().any { it.isLinkLocalAddress && it is java.net.Inet6Address }
                }?.name
            }.getOrNull()
            val ifaceName = ifaceFromCallback ?: ifaceFromQuery ?: ifaceFromEnum
            CarDroidLog.log("AirPlay: iface candidates cb=$ifaceFromCallback query=$ifaceFromQuery enum=$ifaceFromEnum -> using $ifaceName")
            val nif = ifaceName?.let {
                runCatching { java.net.NetworkInterface.getByName(it) }.getOrNull()
            }
            val hostNoZone = rawHost.substringBefore('%')
            // Display the resolved scope id for ops triage: this is the field
            // the kernel actually consults for IPv6 link-local routing.
            val displayHost = if (hostNoZone.contains(':') && ifaceName != null) "$hostNoZone%$ifaceName" else hostNoZone
            CarDroidLog.log("AirPlay: resolving target host=$hostNoZone iface=$ifaceName nifIndex=${nif?.index ?: -1}")

            val media = mediaOptions(ep, diagnosticToken)
            val finalParams = AirPlayParams(
                host = displayHost,
                airPlayPort = port,
                sessionUuid = ep.sessionUuid,
            )
            bluetoothMac()?.let { finalParams.deviceId = it }
            identityName?.takeIf { it.isNotBlank() }?.let { finalParams.name = it }
            CarDroidLog.log("AirPlay: SETUP name=${finalParams.name}")

            // Build the connect address using the *exact* NetworkInterface we
            // bound the WiFi handoff to. This is the only reliable way on
            // Android to produce a link-local InetSocketAddress whose
            // sin6_scope_id matches the routing table.
            fun target(host: String): java.net.InetSocketAddress {
                val unscoped = host.substringBefore('%')
                val address = java.net.InetAddress.getByName(unscoped)
                val scoped = if (address is java.net.Inet6Address && address.isLinkLocalAddress && nif != null) {
                    java.net.Inet6Address.getByAddress(unscoped, address.address, nif)
                } else address
                return java.net.InetSocketAddress(scoped, port)
            }
            val resolver: () -> java.net.InetSocketAddress = { target(rawHost) }
            val candidates: () -> List<java.net.InetSocketAddress> = {
                val verifiedMdns = discoveredReceivers.entries.filter { it.value.equals(ep.publicKey,ignoreCase=true) }.map { it.key }
                (ep.addresses + verifiedMdns).distinctBy { it.substringBefore('%') }
                    .sortedBy { if (':' in it) 1 else 0 }.map(::target)
            }

            // Use a plain Socket() (NOT network.socketFactory) so we control
            // ordering: create -> network.bindSocket(fd) -> connect. The
            // SocketFactory variant doesn't expose the fd before connect on
            // some Android builds, leaving the connect to race the bind.
            val sockFactory: () -> java.net.Socket = { java.net.Socket() }
            val binder: (java.net.Socket) -> Unit = { s ->
                runCatching { network.bindSocket(s) }
                    .onSuccess { CarDroidLog.log("AirPlay: network.bindSocket(socket) OK on $network") }
                    .onFailure { CarDroidLog.log("AirPlay: network.bindSocket failed: ${it.message}") }
            }
            val udpFactory: () -> java.net.DatagramSocket = {
                java.net.DatagramSocket().also { ds ->
                    runCatching { network.bindSocket(ds) }
                }
            }
            CarDroidLog.log("AirPlay: starting handshake to ${finalParams.host}:${finalParams.airPlayPort}")
            val mfiSap: com.cardroid.lib.airplay.AirPlayMfiSap =
                OkcarMfiSapImpl(applicationContext, CarDroidLog::log)
            CarDroidLog.diagnostics.reached(diagnosticToken, com.cardroid.lib.ConnectionDiagnostics.Stage.AIRPLAY)
            lateinit var orch: AirPlayHandshakeOrchestrator
            orch = AirPlayHandshakeOrchestrator(
                onDiagnosticStage = { stage ->
                    if (pipeline === p && airPlay === orch) CarDroidLog.diagnostics.reached(diagnosticToken, stage)
                },
                params = finalParams,
                mfi = mfiSap,
                socketFactory = sockFactory,
                udpSocketFactory = udpFactory,
                log = CarDroidLog::log,
                addressResolver = resolver,
                addressCandidates = candidates,
                addressBinder = binder,
                setupKeyFallback = {
                    CarDroidSettings.HARDCODED_EIV to CarDroidSettings.HARDCODED_EKEY
                },
                mediaOptions = media,
                pairingIdentity = pairingIdentity(),
                diagSink = ::saveCarDiag, // car-touch-iap2-tunnel
                iapOutMfi = iapAuthProvider, // reuse the existing Bluetooth iAP2 provider
            )
            airPlay?.stop()
            helperClient?.stopDisplay(); mode = Mode.NONE
            synchronized(captureLock) { helperAudio?.close(); helperAudio = null }
            mediaTransport = null
            airPlay = orch
            orch.onPeerEnded = { reason -> if (airPlay === orch) p.endSession(reason) }
            sc.launch {
                try {
                    orch.run(sc)
                } catch (t: Throwable) {
                    if (pipeline === p && airPlay === orch) CarDroidLog.diagnostics.failure(diagnosticToken, "AIRPLAY_FAILED")
                    CarDroidLog.log("AirPlay: handshake failed ${t.javaClass.simpleName}: ${t.message}")
                    runCatching { orch.stop() }
                    if (airPlay === orch) { airPlay = null; p.endSession("handshake failed: ${t.javaClass.simpleName}") }
                }
            }
        }
    }

    private fun startCtrlIntServer(
        scope: CoroutineScope,
        serviceName: String,
        preferredNetwork: Network?,
        phase: String,
    ) {
        try {
            CarDroidLog.log("CtrlInt: starting server ($phase)")
            val ctrl = CarPlayCtrlIntServer(applicationContext, CarDroidLog::log)
            ctrlInt?.stop()
            ctrlInt = ctrl
            ctrl.onConnectRequest = { peer ->
                CarDroidLog.log("CtrlInt: receiver callback completed (peer=$peer); AirPlay endpoint comes from iAP2 0x4301")
            }
            ctrl.start(scope, serviceName = serviceName, preferredNetwork = preferredNetwork)
        } catch (t: Throwable) {
            CarDroidLog.log("CtrlInt: start failed ${t.javaClass.simpleName}: ${t.message}; continuing without it")
        }
    }

    /** Car session ended (RFCOMM closed / event channel EOF / handshake failed): drop media state; the pipeline already reset the status. Reconnect is unaffected (RFCOMM relisten + new handshake rebuild everything). */
    private val teardownGuard = SessionTeardownGuard()

    private fun teardownSession(p: com.cardroid.lib.CarDroidPipeline, reason: String) {
        if (pipeline !== p) return // service stopping or restarted
        // Like an iPhone after disableBluetooth: the Wi-Fi CarPlay session outlives the Bluetooth link.
        if (reason == "RFCOMM closed" && airPlay?.hasWifiSession == true) {
            CarDroidLog.setQuietWire(false)
            CarDroidLog.log("Session: RFCOMM closed; keeping Wi-Fi media running")
            return
        }
        // Event-channel EOF and RFCOMM close both end the same session ~ms apart on different threads; run teardown once.
        if (!teardownGuard.tryEnter()) {
            CarDroidLog.log("Session: teardown already done ($reason)")
            return
        }
        val hadMedia = airPlay != null || mediaTransport != null
        runCatching { airPlay?.stop() }; airPlay = null
        val transport = mediaTransport
        helperClient?.stopDisplay(); mode = Mode.NONE
        transport?.onKeyFrameNeeded = null; transport?.onPeerEnded = null
        runCatching { transport?.close() }
        mediaTransport = null
        RemoteControlService.setHidRegistry(null)
        RemoteControlService.setSharing(false)
        synchronized(captureLock) { helperAudio?.close(); helperAudio = null }
        LandscapeSharing.restore(applicationContext)
        pendingAirPlayEndpoint = null; joinedNetwork = null
        CarDroidLog.setQuietWire(false)
        CarDroidLog.log("Session: media state cleared ($reason)")
        // After each car session the phone UI pops up a link to the GitHub repo.
        if (hadMedia) starPrompt.value = true
    }


    private fun stop(stopService: Boolean = true) {
        CarDroidLog.diagnostics.stop()
        available.value = false
        CarDroidLog.setState(com.cardroid.lib.CarPlaySession.State.Idle)
        airPlay?.stop(); airPlay = null
        identityName = null
        helperClient?.stopDisplay(); mode = Mode.NONE
        mediaTransport?.close()
        LandscapeSharing.restore(applicationContext)
        RemoteControlService.setSharing(false)
        synchronized(captureLock) { helperAudio?.close(); helperAudio = null }
        mediaTransport = null
        RemoteControlService.setHidRegistry(null)
        ctrlInt?.stop(); ctrlInt = null
        mdns?.stop(); mdns = null
        val oldPipeline = pipeline; pipeline = null
        runCatching { oldPipeline?.stop() }
        scope?.cancel(); scope = null
        runCatching { wifiHandoff?.tearDown() }; wifiHandoff = null
        joinedNetwork = null
        mdnsResolvedEndpoint = null
        discoveredReceivers.clear()
        pendingAirPlayEndpoint = null
        if (stopService) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        CarDroidLog.setQuietWire(false)
        CarDroidLog.log("CarDroid stopped")
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(bluetoothStateReceiver) }
        autoStartJob?.cancel(); autoStartJob = null
        runCatching { helperClient?.let { com.cardroid.app.car.CarDisplayState.detach(it) }; helperClient?.stop(); helperScope.cancel() }
        stop(); super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, "CarDroid", NotificationManager.IMPORTANCE_LOW))
        }
        // The session-end notification was replaced by an in-app popup.
        if (Build.VERSION.SDK_INT >= 26) nm.deleteNotificationChannel(LEGACY_SESSION_CHANNEL_ID)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cardroid_notification)
            .setColor(CarDroidPalette.MintArgb)
            .setContentTitle(CarDroidName.forPhone(this))
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    companion object {
        internal val available = kotlinx.coroutines.flow.MutableStateFlow(false)
        /** True after a car session with media ends, until the phone UI shows the GitHub star popup. */
        internal val starPrompt = kotlinx.coroutines.flow.MutableStateFlow(false)
        const val ACTION_START_BT = "com.cardroid.app.START_BT"
        const val ACTION_STOP = "com.cardroid.app.STOP"
        const val ACTION_RETRY = "com.cardroid.app.RETRY"
        // Back-compat with older intent name from earlier code.
        const val ACTION_START = ACTION_START_BT
        /** Car display density; revisit in Phase 4 (launcher). */
        private const val NOTIF_ID = 1
        private const val CHANNEL_ID = "cardroid.fg"
        private const val LEGACY_SESSION_CHANNEL_ID = "cardroid.session"
        const val GITHUB_URL = "https://github.com/pavunato/CarDroid"
        private const val AUTO_START_SETTLE_MS = 3_000L
    }
}

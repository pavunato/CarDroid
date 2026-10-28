package com.cardroid.lib

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Only typed, allowlisted evidence enters this report. Never export raw protocol logs. */
class ConnectionDiagnostics(
    /** Runs a delayed task; injectable so tests drive the Wi-Fi join watchdog without sleeping. */
    private val schedule: (Long, () -> Unit) -> Unit = ::defaultSchedule,
) {
    enum class WifiJoin { NONE, PENDING, JOINED, TIMEOUT, FAILED }
    enum class Status { STOPPED, STARTING, SUPPORTED, FULL, FALLBACK, FAILED, UNAVAILABLE }
    enum class Stage(val label: String) {
        WAITING("Waiting for receiver"), SOCKET("Receiver Bluetooth socket accepted"),
        DETECT("iAP2 detect received"), LINK("iAP2 link synchronized"),
        CERT("Receiver certificate received"), AUTHENTICATING("Authentication response / challenge received"), AUTH("Authentication response processed locally"),
        IDENTIFIED("iAP2 identification exchanged"), EA("iAP2 EA session opened"), WIFI_REQUEST("Wi-Fi configuration requested"),
        WIFI_CONFIG("Wi-Fi configuration received"), WIFI_JOINED("Accessory Wi-Fi joined"),
        ENDPOINT("AirPlay endpoint received"), AIRPLAY("AirPlay handshake starting"),
        RECORD("AirPlay RECORD accepted"), MEDIA("Media transport ready; car rendering unverified")
    }
    data class Snapshot(
        val running: Boolean = false,
        val ble: Status = Status.STOPPED,
        val listener: Status = Status.STOPPED,
        val receiverConnected: Boolean = false,
        val stage: Stage = Stage.WAITING,
        val evidence: Set<Stage> = emptySet(),
        val lastError: String? = null,
        val wifiJoin: WifiJoin = WifiJoin.NONE,
        val events: List<String> = emptyList(),
    ) {
        val title: String get() = if (!running) "Connection checks stopped" else stage.label
        val bleDetail: String get() = when (ble) {
            Status.FULL -> "Full BLE advertisement active (local callback); car recognition unverified"
            Status.FALLBACK -> "Reduced discovery: manufacturer-only BLE; iAP2 UUID absent from BLE. Classic SDP is separate."
            Status.STARTING -> "Starting BLE; waiting for Android callback"
            Status.SUPPORTED -> "BLE advertising supported; not yet active"
            Status.FAILED -> "BLE advertising failed"
            Status.UNAVAILABLE -> "BLE advertising unavailable (adapter off or unsupported)"
            Status.STOPPED -> "BLE stopped"
        }
        val nextStep: String get() = when {
            !running -> "Finish Setup, then make this phone available."
            lastError == "HELPER_NOT_RUNNING" -> "Video was closed because the display helper was not running. Start the display helper (Shizuku or ADB), wait for “Display helper ready”, then retry."
            lastError == "WIFI_JOIN_TIMEOUT" || lastError == "WIFI_JOIN_FAILED" -> WIFI_JOIN_HELP
            lastError == "WIFI_UNAVAILABLE" -> "This Android version cannot join the receiver Wi-Fi (Android 10 or newer is required)."
            ble == Status.FAILED || ble == Status.UNAVAILABLE -> "Check Bluetooth and Nearby devices permissions; stop and retry."
            ble == Status.FALLBACK -> "Open the car’s CarPlay / add-phone screen. Reduced BLE discovery may prevent recognition; share this report."
            !receiverConnected && stage == Stage.WAITING -> "Select this phone in the car’s CarPlay screen. Ordinary Bluetooth pairing alone is not CarPlay."
            stage == Stage.SOCKET || stage == Stage.DETECT -> "Waiting for the receiver’s iAP2 link handshake. Share this report if it stalls."
            stage == Stage.MEDIA -> "Check the car display. Local media readiness does not prove a visible picture."
            else -> "Keep the car’s CarPlay screen open; share this report if this stage does not advance."
        }
    }
    private var generation = 0L
    /** Invalidates a scheduled Wi-Fi join timeout on join, failure, a new socket or a new generation. */
    private var joinWatch = 0L
    private val mutable = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = mutable
    @Synchronized fun begin(): Long {
        generation++; joinWatch++
        mutable.value = Snapshot(running = true, ble = Status.STARTING, listener = Status.STARTING)
        return generation
    }
    @Synchronized fun stop() {
        if (!mutable.value.running) return
        generation++
        mutable.value = Snapshot(lastError = mutable.value.lastError, events = mutable.value.events + "Stopped")
    }
    private fun update(token: Long, event: String, change: (Snapshot) -> Snapshot) {
        if (token != generation || !mutable.value.running) return
        val next = change(mutable.value)
        // Two layers can report the same transition (e.g. LINK); keep one timeline line. A new SOCKET is a new attempt.
        val repeat = event != Stage.SOCKET.label && next.events.lastOrNull()?.substringAfter(": ") == event
        mutable.value = if (repeat) next else next.copy(events = (next.events + "${System.currentTimeMillis()}: $event").takeLast(60))
    }
    @Synchronized fun ble(token: Long, status: Status, reason: String? = null) = update(token, "BLE $status") {
        it.copy(ble = status, lastError = safeReason(reason) ?: it.lastError)
    }
    @Synchronized fun listener(token: Long, status: Status, reason: String? = null) = update(token, "Listener $status") {
        it.copy(listener = status, lastError = safeReason(reason) ?: it.lastError)
    }
    @Synchronized fun reached(token: Long, stage: Stage) = update(token, stage.label) {
        // A new RFCOMM socket is a new receiver attempt; never carry old session success forward.
        val base = if (stage == Stage.SOCKET) it.copy(evidence = emptySet(), lastError = null, wifiJoin = WifiJoin.NONE) else it
        if (stage == Stage.SOCKET || stage == Stage.WIFI_JOINED) joinWatch++
        base.copy(stage = stage, evidence = base.evidence + stage,
            wifiJoin = if (stage == Stage.WIFI_JOINED) WifiJoin.JOINED else base.wifiJoin,
            // A late join supersedes the timeout; it is no longer the current problem.
            lastError = if (stage == Stage.WIFI_JOINED && base.lastError == "WIFI_JOIN_TIMEOUT") null else base.lastError,
            receiverConnected = base.receiverConnected || stage == Stage.SOCKET)
    }
    @Synchronized fun wifiLost(token: Long) = update(token, "Accessory Wi-Fi lost") {
        it.copy(stage = Stage.WIFI_CONFIG, evidence = it.evidence - setOf(Stage.WIFI_JOINED, Stage.AIRPLAY, Stage.RECORD, Stage.MEDIA), lastError = "WIFI_LOST")
    }
    @Synchronized fun socketClosed(token: Long) = update(token, "Receiver Bluetooth socket closed") {
        it.copy(receiverConnected = false)
    }
    @Synchronized fun ended(token: Long, reason: String?) = update(token, "Receiver session ended") {
        it.copy(receiverConnected = false, stage = Stage.WAITING, evidence = emptySet(), lastError = it.lastError ?: endReason(reason))
    }
    /** Media became ready but no display helper was connected, so CarDroid closes the stream locally. */
    @Synchronized fun helperMissing(token: Long) = update(token, "Display helper not running; media closed locally") {
        it.copy(lastError = "HELPER_NOT_RUNNING")
    }
    @Synchronized fun failure(token: Long, reason: String?) {
        val code = safeReason(reason) ?: "CONNECTION_FAILED"
        update(token, "Issue: $code") { failed(it, code) }
    }
    private fun failed(it: Snapshot, code: String): Snapshot {
        val joinFailed = code == "WIFI_JOIN_FAILED" || code == "WIFI_UNAVAILABLE"
        if (joinFailed) joinWatch++
        return it.copy(lastError = code, wifiJoin = if (joinFailed && it.wifiJoin != WifiJoin.JOINED) WifiJoin.FAILED else it.wifiJoin)
    }
    /** Wi-Fi credentials arrived; the phone must join before the receiver abandons the session (~30 s observed). */
    @Synchronized fun wifiJoinPending(token: Long) {
        if (token != generation || !mutable.value.running) return
        val watch = ++joinWatch
        update(token, "WIFI_JOIN_PENDING") { it.copy(wifiJoin = WifiJoin.PENDING) }
        schedule(WIFI_JOIN_TIMEOUT_MS) { wifiJoinTimedOut(token, watch) }
    }
    @Synchronized private fun wifiJoinTimedOut(token: Long, watch: Long) {
        if (watch != joinWatch || mutable.value.wifiJoin != WifiJoin.PENDING) return
        update(token, "WIFI_JOIN_TIMEOUT") { it.copy(wifiJoin = WifiJoin.TIMEOUT, lastError = "WIFI_JOIN_TIMEOUT") }
    }
    fun session(token: Long, state: CarPlaySession.State) {
        val stage = when (state) {
            CarPlaySession.State.LinkUp -> Stage.LINK
            is CarPlaySession.State.MfiCertReceived -> Stage.CERT
            is CarPlaySession.State.Authenticating -> Stage.AUTHENTICATING
            CarPlaySession.State.AuthComplete -> Stage.AUTH
            CarPlaySession.State.Identified -> Stage.IDENTIFIED
            CarPlaySession.State.EaSessionOpen -> Stage.EA
            CarPlaySession.State.WifiHandoffRequested -> Stage.WIFI_REQUEST
            is CarPlaySession.State.WifiHandoffReceived -> Stage.WIFI_CONFIG
            is CarPlaySession.State.CarPlayAirPlayStart -> Stage.ENDPOINT
            is CarPlaySession.State.Failed -> {
                failure(token, when (state.reason) {
                    "Head unit reported MFi authentication failure" -> "AUTH_FAILED"
                    "Head unit rejected identification" -> "IDENTIFICATION_REJECTED"
                    else -> "CONNECTION_FAILED"
                }); null
            }
            else -> null
        }
        stage?.let { reached(token, it) }
        if (stage == Stage.WIFI_CONFIG) wifiJoinPending(token)
    }
    /** Keeps only who ended the session: RFCOMM EOF / media-channel EOF come from the receiver; a failed local handshake is ours. */
    private fun endReason(reason: String?): String = when {
        reason == null -> "SESSION_ENDED"
        reason == "RFCOMM closed" || reason.startsWith("event channel ended") -> "SESSION_ENDED_BY_RECEIVER"
        reason.startsWith("handshake failed") -> "SESSION_CLOSED_LOCALLY"
        reason.startsWith("video send failed") -> "VIDEO_SEND_FAILED"
        else -> safeReason(reason) ?: "SESSION_ENDED"
    }
    private fun safeReason(reason: String?): String? = reason?.let { text ->
        // Deliberately drop arbitrary exception text, addresses, credentials and payloads:
        // only a fixed code or a fixed exception class name may leave this function.
        CODES.firstOrNull { it == text }
            ?: EXCEPTIONS.firstOrNull { name -> Regex("""(^|[^A-Za-z0-9_])${name}($|[^A-Za-z0-9_])""").containsMatchIn(text) }
            ?: "OTHER_ERROR"
    }
    /** One plain-language line: what went wrong, or how far the attempt got. */
    fun summary(s: Snapshot = state.value): String = s.lastError?.let { "Stopped at “${s.stage.label}”: ${meaning(it)}." }
        ?: if (!s.running) "Connection checks stopped; no error recorded." else "Reached “${s.stage.label}”; no error recorded."

    fun report(): String = state.value.let { s -> buildString {
        appendLine("CarDroid connection diagnostics v1 (sanitized)")
        appendLine("Current: ${s.title}")
        appendLine("Bluetooth advertising: ${s.bleDetail}")
        appendLine("Classic SDP / RFCOMM listener: ${if (s.listener == Status.FULL) "LISTENING (local SDP registered)" else s.listener.name}")
        appendLine("Receiver socket connected: ${s.receiverConnected}")
        Stage.entries.filter { it != Stage.WAITING }.forEach { appendLine("${it.label}: ${if (it in s.evidence) "observed" else "not verified"}") }
        appendLine("Accessory Wi-Fi join: ${s.wifiJoin.name}")
        appendLine("Summary: ${summary(s)}")
        appendLine("Last error: ${s.lastError?.let { "$it (${meaning(it)})" } ?: "none recorded"}")
        appendLine("Next: ${s.nextStep}")
        appendLine("Setup readiness and advertising do not prove receiver recognition. No car-rendering acknowledgement is available.")
        appendLine("Typed event timeline (epoch ms):")
        s.events.forEach(::appendLine)
        appendLine("Raw logs, names, addresses, SSIDs, passwords, pairing/authentication keys and payloads are excluded.")
    } }
    companion object {
        /** Plain words for each typed code; codes never carry user data. */
        fun meaning(code: String): String = when (code) {
            "HELPER_NOT_RUNNING" -> "the display helper was not running, so CarDroid closed the car video"
            "WIFI_JOIN_TIMEOUT" -> "phone did not join the receiver Wi-Fi within ${WIFI_JOIN_TIMEOUT_MS / 1000} s"
            "WIFI_JOIN_FAILED" -> "phone did not join the receiver Wi-Fi (Android rejected or could not find the network)"
            "WIFI_UNAVAILABLE" -> "this Android version cannot join the receiver Wi-Fi"
            "WIFI_LOST" -> "the receiver Wi-Fi connection dropped"
            "SESSION_ENDED_BY_RECEIVER" -> "the receiver ended the session without giving a reason"
            "SESSION_CLOSED_LOCALLY" -> "CarDroid ended the session after a local failure"
            "SESSION_ENDED" -> "the session ended"
            "VIDEO_SEND_FAILED" -> "sending video to the receiver failed"
            "AIRPLAY_FAILED" -> "the AirPlay handshake with the receiver failed"
            "AUTH_FAILED" -> "the receiver rejected authentication"
            "IDENTIFICATION_REJECTED" -> "the receiver rejected identification"
            "CONNECTION_FAILED" -> "the receiver connection failed"
            "DATA_TOO_LARGE", "TOO_MANY_ADVERTISERS", "ALREADY_STARTED", "INTERNAL_ERROR", "FEATURE_UNSUPPORTED" -> "Bluetooth advertising failed"
            "OTHER_ERROR" -> "an unclassified error occurred (details withheld for privacy)"
            else -> if (code in EXCEPTIONS) "a $code occurred" else "an error occurred"
        }
        /** Typed codes CarDroid itself emits; anything else is reduced to an exception name or OTHER_ERROR. */
        val CODES = listOf("DATA_TOO_LARGE", "TOO_MANY_ADVERTISERS", "ALREADY_STARTED", "INTERNAL_ERROR", "FEATURE_UNSUPPORTED",
            "AUTH_FAILED", "IDENTIFICATION_REJECTED", "AIRPLAY_FAILED", "CONNECTION_FAILED", "WIFI_UNAVAILABLE",
            "WIFI_JOIN_FAILED", "WIFI_JOIN_TIMEOUT", "WIFI_LOST", "HELPER_NOT_RUNNING", "SESSION_ENDED_BY_RECEIVER",
            "SESSION_CLOSED_LOCALLY", "VIDEO_SEND_FAILED", "SESSION_ENDED", "OTHER_ERROR")
        /** Fixed exception class simple names; never derived from the input text. Most specific first. */
        val EXCEPTIONS = listOf("SocketTimeoutException", "ConnectException", "NoRouteToHostException", "SocketException",
            "EOFException", "UnknownHostException", "IOException", "SecurityException", "IllegalArgumentException",
            "IllegalStateException", "TimeoutCancellationException", "TimeoutException", "UnsupportedOperationException",
            "NullPointerException")
        const val WIFI_JOIN_TIMEOUT_MS = 20_000L
        const val WIFI_JOIN_HELP = "Phone did not join the receiver Wi-Fi; check Wi-Fi is on, network name/password match receiver settings, and approve any Android Wi-Fi connection prompt."
        private val timer by lazy {
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "cardroid-diagnostics").apply { isDaemon = true } }
        }
        private fun defaultSchedule(delayMs: Long, task: () -> Unit) {
            timer.schedule({ runCatching(task) }, delayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
    }
}

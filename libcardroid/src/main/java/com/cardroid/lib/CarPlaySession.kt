package com.cardroid.lib

import com.cardroid.lib.Iap2ControlSession.Msg
import com.cardroid.mfi.MfiAuthProvider
import com.cardroid.mfi.OkcarBypassProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Drives the iAP2 control-session sequence that a head unit expects from
 * an iPhone for Wireless CarPlay bring-up.
 *
 * reference-aligned wireless client-mode flow:
 *   1. Detect / LinkSync  (handled by [Iap2Transport])
 *   2. Request MFi cert (0xAA00), receive cert (0xAA01)
 *   3. Send OkCar-extracted hardcoded MFi auth payload (0xAA02)
 *   4. Car sends auth response (0xAA03)
 *   5. We send AuthenticationSucceeded (0xAA05, no params)
 *   6. StartIdentification (0x1D00), receive IdentificationInformation
 *      (0x1D01), accept (0x1D02)
 *   7. Request AccessoryWiFiConfigurationInformation (0x5702)
 */
class CarPlaySession(
    private val transport: IapTransport,
    private val mfi: MfiAuthProvider,
    private val wifi: WifiHandoff,
    /**
     * Send the real-iPhone post-auth burst and subscription replies on this link.
     * Only the windowed control-channel responder enables it; the Bluetooth
     * transport tracks a single outstanding frame (car window is 5).
     */
    private val iphoneBehavior: Boolean = false,
    private val nowPlaying: () -> NowPlayingState = { NowPlayingState() },
    private val deviceName: String = IPhoneIdentity.DEVICE_NAME,
    private val diagnostics: ConnectionDiagnostics? = null,
    private val diagnosticToken: Long = 0,
) {
    sealed interface State {
        data object Idle : State
        data object LinkUp : State
        data class MfiCertReceived(val algorithm: String?) : State
        data class Authenticating(val challengeSize: Int) : State
        data object AuthComplete : State
        data object Identified : State
        data object EaSessionOpen : State
        data object WifiHandoffRequested : State
        data class WifiHandoffReceived(val ssid: String) : State
        data class CarPlayAirPlayStart(val endpoint: AirPlayEndpoint) : State
        data class Failed(val reason: String) : State
    }

    /** Extracted from the car's 0x4301 message after cardroid joins its AP. */
    data class AirPlayEndpoint(
        val ipv6: String,           // IPv4 or IPv6; legacy field name. e.g. "fe80::3a2a:8bff:fe18:33f8"
        val port: Int,              // e.g. 7000
        val bssid: String,          // e.g. "38:2a:8b:18:33:f8"
        val sessionUuid: String,    // e.g. "99243d19-6a71-4121-9c70-11bfedf9a1f5"
        val firmware: String,       // e.g. "475.8.1"
        val publicKey: String = "", // Receiver identity from 0x4301 parameter 4.
        val addresses: List<String> = listOf(ipv6),
        val receiverName: String = "", // iAP2 IdentificationInformation name (param 0), e.g. "Receiver".
    )

    /** Hook so the app can spin up its AirPlay client when this fires. */
    @Volatile var onAirPlayStart: (AirPlayEndpoint) -> Unit = {}

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    private var sessionState: State
        get() = _state.value
        set(value) {
            _state.value = value
            diagnostics?.session(diagnosticToken, value)
        }

    private var requestedMfiCertificate = false
    private var availabilitySent = false
    private var accessoryName = ""
    private var nowPlayingSubscribed = false
    private var lastNowPlaying: NowPlayingState? = null

    /** Process a control-session message arriving from the head unit. */
    suspend fun onControlMessage(messageId: Int, params: ByteArray) {
        when (messageId) {
            Msg.MFI_AUTH_CERTIFICATE -> handleMfiCertificate(params)
            Msg.MFI_AUTH_RESPONSE -> handleMfiResponse(params)
            Msg.MFI_AUTH_FAILED ->
                sessionState = State.Failed("Head unit reported MFi authentication failure")
            Msg.WIRELESS_CARPLAY_AVAILABLE ->
                transport.diagnostic("Car supports wireless CarPlay")
            Msg.DEVICE_TRANSPORT_IDENTIFIER_NOTIFICATION ->
                transport.diagnostic("DeviceTransportIdentifierNotification received")
            Msg.IDENTIFICATION_INFORMATION -> handleIdentificationInformation(params)
            Msg.START_IDENTIFICATION ->
                transport.diagnostic("Peer sent StartIdentification; ignoring to keep iPhone role")
            Msg.IDENTIFICATION_ACCEPTED -> {
                sessionState = State.Identified
                transport.diagnostic("Peer sent IdentificationAccepted; no action in iPhone role")
            }
            Msg.IDENTIFICATION_REJECTED ->
                sessionState = State.Failed("Head unit rejected identification")
            Msg.START_EA_SESSION -> {
                sessionState = State.EaSessionOpen
                transport.diagnostic("EA session opened (CarPlay protocol active)")
            }
            Msg.REQUEST_WIFI_INFORMATION ->
                transport.diagnostic("Peer requested WiFi information; ignoring to keep iPhone role")
            Msg.WIFI_INFORMATION -> handleWifiInformation(params)
            Msg.CARPLAY_AIRPLAY_START -> handleAirPlayStart(params)
            IphoneIap2.START_NOW_PLAYING_UPDATES -> if (iphoneBehavior) {
                nowPlayingSubscribed = true
                lastNowPlaying = null
                refreshNowPlaying()
            }
            0x5002 -> { nowPlayingSubscribed = false; lastNowPlaying = null }
            else -> {
                val reply = if (iphoneBehavior) IphoneIap2.replyFor(messageId) else null
                if (reply != null) {
                    transport.sendControl(reply)
                    transport.diagnostic("iPhone reply to 0x%04X -> 0x%04X (%d B)".format(
                        messageId, Iap2ControlSession.parse(reply)?.first ?: -1, reply.size))
                } else {
                    transport.diagnostic("unhandled ctrl msg id=0x%04X len=%d".format(messageId, params.size))
                }
            }
        }
    }

    /** Called on the iAP actor; sends updates only while the receiver subscribes. */
    fun refreshNowPlaying() {
        if (!iphoneBehavior || !nowPlayingSubscribed) return
        val current = nowPlaying()
        if (current == lastNowPlaying) return
        lastNowPlaying = current
        transport.sendControl(current.message())
        transport.diagnostic("NowPlayingUpdate title=${current.title} status=${current.status}")
    }

    /**
     * Car responds with its MFi certificate (iAP2 message 0xAA01) after we
     * requested it with 0xAA00.
     *
     * Extracted Okcar flow: parse cert as X.509 just to read sig algorithm,
     * then send j2.j.t()'s hardcoded hash in 0xAA02. The real challenge bytes
     * are not used by the extracted mechanism.
     */
    private suspend fun handleMfiCertificate(params: ByteArray) {
        val certBytes = Iap2ControlSession.firstParamValue(params)
        transport.diagnostic("MFi cert received from car (${certBytes.size} bytes)")

        (mfi as? OkcarBypassProvider)?.acceptCarCertificate(certBytes)
        sessionState = State.MfiCertReceived((mfi as? OkcarBypassProvider)?.carCertAlgorithm)

        val okcarHash = mfi.sign(ByteArray(0))
            ?: run {
                sessionState = State.Failed("MFi auth failed: provider returned no Okcar hash")
                return
            }
        transport.sendMfiCertResponse(okcarHash)
        transport.diagnostic("Sent extracted Okcar MFi response hash (${okcarHash.size} bytes)")
    }

    /**
     * Car responds to 0xAA02 with iAP2 message 0xAA03. We keep the extracted
     * OkCar MFi bypass behavior here, but do not emit OkCar's wired-only
     * post-auth control messages.
     */
    private fun handleMfiResponse(params: ByteArray) {
        val responseBytes = Iap2ControlSession.firstParamValue(params)
        sessionState = State.Authenticating(responseBytes.size)
        transport.diagnostic("MFi auth response received from car (${responseBytes.size} bytes) - accepting via extracted Okcar flow")

        transport.sendAuthStatusOk()
        transport.diagnostic("Sent AuthenticationSucceeded (0xAA05)")
        if (iphoneBehavior) sendPostAuthBurst()

        sessionState = State.AuthComplete

        // Per reference flow: after AuthSucceeded, request WiFi configuration.
        transport.diagnostic("MFi auth complete; requesting accessory WiFi configuration")
        requestWifiInformation()
    }

    /** Real-iPhone status burst after 0xAA05 (session id, language, time, device name, location setup). */
    private fun sendPostAuthBurst() {
        val now = System.currentTimeMillis()
        val zone = java.util.TimeZone.getDefault()
        val burst = IphoneIap2.postAuthBurst(now, zone.getOffset(now) / 60_000, zone.inDaylightTime(java.util.Date(now)), deviceName = deviceName)
        burst.forEach { transport.sendControl(it) }
        transport.diagnostic("Sent iPhone post-auth burst: " +
            burst.joinToString { "0x%04X".format(Iap2ControlSession.parse(it)?.first ?: -1) })
    }

    /** Drive an authentication challenge from the old generic path (kept for non-bypass providers). */
    suspend fun onAuthenticationChallenge(challenge: ByteArray) {
        sessionState = State.Authenticating(challenge.size)
        val signed = mfi.sign(challenge)
            ?: run {
                sessionState = State.Failed(
                    "MFi auth failed: provider returned no signature. " +
                            "Use OkcarBypassProvider or a genuine Apple Authentication Coprocessor."
                )
                return
            }
        transport.sendAuthenticationResponse(signed)
    }

    /** Peer session is gone: show Idle ("waiting for receiver") instead of the last handshake state. */
    fun onSessionEnded() {
        requestedMfiCertificate = false
        availabilitySent = false
        accessoryName = ""
        nowPlayingSubscribed = false; lastNowPlaying = null
        sessionState = State.Idle
    }

    fun onLinkUp() {
        requestedMfiCertificate = false
        availabilitySent = false
        accessoryName = ""
        nowPlayingSubscribed = false; lastNowPlaying = null
        sessionState = State.LinkUp
    }

    fun startControlFlow() {
        // Real-car test: after LinkUp, car NEVER sends spontaneously. We must
        // drive. Per reference/reference reference: send StartIdentification first.
        transport.diagnostic("LinkUp -> sending StartIdentification on negotiated session")
        sendStartIdentification()
    }

    private fun sendStartIdentification() {
        transport.sendControl(Iap2ControlSession.build(Msg.START_IDENTIFICATION))
    }

    private fun handleIdentificationInformation(params: ByteArray) {
        val parsed = Iap2ControlSession.parseParams(params)
        val supportedByAccessory = parsed.firstOrNull { it.id == 6 }?.value
        val acceptedByAccessory = parsed.firstOrNull { it.id == 7 }?.value
        accessoryName = parsed.firstString(0).orEmpty()
        transport.diagnostic(
            "Accessory IdentificationInformation received: name=$accessoryName params=${parsed.size}, " +
                "sent=${supportedByAccessory?.size?.div(2) ?: 0}, " +
                "recv=${acceptedByAccessory?.size?.div(2) ?: 0}"
        )
        sessionState = State.Identified
        transport.sendControl(Iap2ControlSession.build(Msg.IDENTIFICATION_ACCEPTED))
        transport.diagnostic("Sent IdentificationAccepted (0x1D02)")
        // Now start MFi auth (per reference + reference client flow). After AuthSucceeded
        // we'll request WiFi info.
        sendAuthenticationCertificateRequest()
    }

    private fun sendAuthenticationCertificateRequest() {
        if (requestedMfiCertificate) return
        requestedMfiCertificate = true
        transport.sendControl(Iap2ControlSession.build(Msg.MFI_REQUEST_CERTIFICATE))
        transport.diagnostic("Sent RequestAuthenticationCertificate (0xAA00)")
    }

    private fun requestWifiInformation() {
        transport.sendControl(Iap2ControlSession.build(Msg.REQUEST_WIFI_INFORMATION))
        sessionState = State.WifiHandoffRequested
        transport.diagnostic("Sent RequestAccessoryWiFiConfigurationInformation (0x5702)")
    }

    private fun handleWifiInformation(params: ByteArray) {
        val parsed = Iap2ControlSession.parseParams(params)
        val ssid = parsed.firstString(0x0001)
        val passphrase = parsed.firstString(0x0002)
        val bssid = ByteArray(0)
        val channel = parsed.firstOrNull { it.id == 0x0004 }?.value?.firstOrNull()?.toInt()?.and(0xFF) ?: 0

        if (ssid.isNullOrEmpty()) {
            sessionState = State.Failed("WiFi information missing SSID")
            return
        }

        val network = WifiHandoff.NetworkInfo(
            ssid = ssid,
            passphrase = passphrase.orEmpty(),
            bssid = bssid,
            channel = channel,
        )
        sessionState = State.WifiHandoffReceived(ssid)
        transport.diagnostic("Accessory WiFi information received: ssid=$ssid channel=$channel")
        wifi.connectToAccessory(network)
        if (!availabilitySent) {
            // Receiver v0.1.0 Iap2WirelessControlClient responds to 0x4300 with
            // 0x4301. Wireless availability is nested group 1, U8 parameter 0.
            val wireless = Iap2ControlSession.param(0, byteArrayOf(1))
            transport.sendControl(Iap2ControlSession.build(
                Msg.CARPLAY_AVAILABILITY, Iap2ControlSession.param(1, wireless),
            ))
            availabilitySent = true
            transport.diagnostic("Sent CarPlayAvailability (0x4300): wireless available")
        }
    }

    private fun List<Iap2ControlSession.Parameter>.firstString(id: Int): String? =
        firstOrNull { it.id == id }
            ?.value
            ?.takeWhile { it != 0.toByte() }
            ?.toByteArray()
            ?.decodeToString()

    /**
     * Handle msg 0x4301 — the CarPlay AirPlay-start notification the car
     * sends in response to 0x4300. Extract its IP, port and public key, and
     * hand off to the app via [onAirPlayStart]. Wi-Fi may still be joining.
     */
    private fun handleAirPlayStart(params: ByteArray) {
        val parsed = Iap2ControlSession.parseParams(params)

        // Nested network info in param id=1.
        val nested = parsed.firstOrNull { it.id == 1 }?.value
            ?.let { Iap2ControlSession.parseParams(it) }
            .orEmpty()
        val addresses = nested.filter { it.id == 3 }.map { parameter ->
            parameter.value.takeWhile { it != 0.toByte() }.toByteArray().decodeToString()
        }.filter { it.isNotBlank() }.distinct().sortedBy { if (':' in it) 1 else 0 }
        val ipv6 = addresses.firstOrNull().orEmpty()

        val port = parsed.firstOrNull { it.id == 2 }?.value?.let { v ->
            if (v.size >= 4) {
                ((v[0].toInt() and 0xFF) shl 24) or
                    ((v[1].toInt() and 0xFF) shl 16) or
                    ((v[2].toInt() and 0xFF) shl 8) or
                    (v[3].toInt() and 0xFF)
            } else null
        } ?: 7000

        val bssid = parsed.firstString(3).orEmpty()
        val publicKey = parsed.firstString(4).orEmpty()
        val sessionUuid = java.util.UUID.randomUUID().toString()
        val firmware = parsed.firstString(5).orEmpty()

        if (ipv6.isEmpty()) {
            transport.diagnostic("0x4301 received but receiver IP missing; params=${parsed.map { it.id }}")
            return
        }

        val ep = AirPlayEndpoint(
            ipv6 = ipv6, port = port, bssid = bssid,
            sessionUuid = sessionUuid, firmware = firmware, publicKey = publicKey, addresses = addresses,
            receiverName = accessoryName,
        )
        transport.diagnostic(
            "AirPlay start: ipv6=$ipv6 port=$port bssid=$bssid uuid=$sessionUuid fw=$firmware name=$accessoryName"
        )
        sessionState = State.CarPlayAirPlayStart(ep)
        onAirPlayStart(ep)
    }
}

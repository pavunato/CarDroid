package com.cardroid.lib

import android.bluetooth.BluetoothAdapter
import com.cardroid.mfi.MfiAuthProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * One-call composition of the whole pretender stack.
 *
 * Two modes:
 *  - Bluetooth (production): pass an [adapter]; we listen on RFCOMM and a car
 *    connects to us.
 *  - TCP (test): pass [tcpHost]/[tcpPort]; we connect out to a test receiver
 *    Python server running on a test host and play the iAP2 client side.
 */
class CarDroidPipeline private constructor(
    private val transport: IapTransport,
    private val advertiser: IPhoneAdvertiser?,
    mfi: MfiAuthProvider,
    private val wifi: WifiHandoff,
    private val log: (String) -> Unit,
    private val diagnostics: ConnectionDiagnostics = ConnectionDiagnostics(),
    private val diagnosticToken: Long = 0,
) {
    private val carPlay = CarPlaySession(transport, mfi, wifi, diagnostics = diagnostics, diagnosticToken = diagnosticToken)
    private var airPlay: AirPlayClient? = null

    val listening: StateFlow<Boolean>? get() = (transport as? Iap2Transport)?.listening

    val state: StateFlow<CarPlaySession.State> get() = carPlay.state

    /** Hook the app can set to spin up AirPlay/MediaSession once 0x4301 fires. */
    @Volatile var onAirPlayEndpoint: (CarPlaySession.AirPlayEndpoint) -> Unit = {}

    /** App hook: the car session ended (reason); tear down media. Called after the status was reset. */
    @Volatile var onSessionEnded: (String) -> Unit = {}

    /** App hook: a new car link came up (new session); re-arm per-session teardown state. */
    @Volatile var onLinkUp: () -> Unit = {}

    /** Wi-Fi can outlive the bootstrap Bluetooth connection, including during media SETUP. */
    @Volatile var retainWifiOnBluetoothClose: () -> Boolean = { false }

    /** Reset status + notify the app unless Bluetooth handed off to a live Wi-Fi session. */
    fun endSession(reason: String) {
        if (reason == "RFCOMM closed" && retainWifiOnBluetoothClose()) {
            log("Session: RFCOMM closed; retaining established Wi-Fi session")
            return
        }
        diagnostics.ended(diagnosticToken, reason)
        carPlay.onSessionEnded()
        log("Session: ended ($reason); status reset")
        runCatching { onSessionEnded(reason) }
    }

    fun start(scope: CoroutineScope) {
        transport.onSessionEnded = { reason -> endSession(reason) }
        transport.onLinkUp = {
            runCatching { onLinkUp() }
            diagnostics.reached(diagnosticToken, ConnectionDiagnostics.Stage.LINK)
            carPlay.onLinkUp()
            carPlay.startControlFlow()
        }
        carPlay.onAirPlayStart = { ep ->
            log("Pipeline: AirPlay start for $ep")
            onAirPlayEndpoint(ep)
        }
        scope.launch {
            transport.incomingControl.collect { (id, params) ->
                carPlay.onControlMessage(id, params)
            }
        }
        advertiser?.start()
        transport.start(scope)
    }

    fun stop() {
        try { advertiser?.stop() } finally { transport.stop() }
    }

    companion object {
        /** Verified iAP2 defaults from reference/iap2 reverse engineering. */
        fun defaultLinkSyncParams(): LinkSyncParameters = LinkSyncParameters()

        /** Production: Bluetooth RFCOMM, with BLE advertiser. */
        fun bluetooth(
            adapter: BluetoothAdapter,
            identity: IPhoneIdentity,
            mfi: MfiAuthProvider,
            wifi: WifiHandoff,
            linkSyncParams: LinkSyncParameters = defaultLinkSyncParams(),
            log: (String) -> Unit = {},
            diagnostics: ConnectionDiagnostics = ConnectionDiagnostics(),
            diagnosticToken: Long = 0,
        ): CarDroidPipeline = CarDroidPipeline(
            transport = Iap2Transport(adapter, linkSyncParams, log, diagnostics, diagnosticToken),
            advertiser = IPhoneAdvertiser(adapter, identity, log, diagnostics, diagnosticToken),
            diagnostics = diagnostics,
            diagnosticToken = diagnosticToken,
            mfi = mfi,
            wifi = wifi,
            log = log,
        )

        /** Test: connect to a Python head-unit simulator over TCP. */
        fun tcp(
            host: String,
            port: Int,
            mfi: MfiAuthProvider,
            wifi: WifiHandoff,
            linkSyncParams: LinkSyncParameters = defaultLinkSyncParams(),
            log: (String) -> Unit = {},
        ): CarDroidPipeline = CarDroidPipeline(
            transport = TcpClientTransport(host, port, linkSyncParams, log),
            advertiser = null,
            mfi = mfi,
            wifi = wifi,
            log = log,
        )
    }
}

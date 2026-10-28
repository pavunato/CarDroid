package com.cardroid.lib

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

/**
 * Owns the iAP2 RFCOMM connection lifecycle and link-layer state machine.
 *
 * Public discovery surface (visible to a head unit):
 *   - SDP record carrying [Iap2Constants.SERVICE_UUID_IPHONE] (we are the iPhone)
 *   - Listening RFCOMM channel from BluetoothAdapter.listenUsingRfcommWithServiceRecord
 *
 * After a head unit connects, the transport:
 *   1. Sends Link Sync as soon as the socket is up
 *   2. Acks the head unit's reciprocal Link Sync
 *   3. Forwards control-session payloads to [CarPlaySession]
 */
class Iap2Transport(
    private val adapter: BluetoothAdapter,
    private val linkSyncParams: LinkSyncParameters,
    private val onLog: (String) -> Unit = {},
    private val diagnostics: ConnectionDiagnostics = ConnectionDiagnostics(),
    private val diagnosticToken: Long = 0,
) : IapTransport {
    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening

    private val _incomingControl = MutableSharedFlow<Pair<Int, ByteArray>>(extraBufferCapacity = 16)
    override val incomingControl: SharedFlow<Pair<Int, ByteArray>> = _incomingControl

    private val lifecycleLock = Any()
    @Volatile private var stopped = true
    @Volatile private var serverSocket: BluetoothServerSocket? = null
    @Volatile private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var loopJob: Job? = null

    // Start PSN at 0, then advance for every sequenced frame we send.
    private var outSeq: Byte = 0
    private var lastAck: Byte = 0
    private var waitingForPeerAckAfterSyn = false
    private var linkNormal = false
    private var pendingControlFrame: Iap2Frame? = null
    private var pendingControlRetransmits = 0
    private var sentAnySequencedFrame = false
    private var sentDetectEcho = false
    /** Seq of the last car data frame (payload, non-SYN); a repeat means the car retransmitted. */
    private var lastRxDataSeq: Int = -1
    /** LSP adopted from the peer's SYN. We echo this back in our SYN|ACK. */
    private var adoptedPeerLsp: LinkSyncParameters? = null

    private fun containsDetectMarker(buf: List<Byte>): Boolean {
        val m = Iap2LinkSync.DETECT_MARKER
        if (buf.size < m.size) return false
        for (start in 0..(buf.size - m.size)) {
            var match = true
            for (i in m.indices) {
                if (buf[start + i] != m[i]) { match = false; break }
            }
            if (match) return true
        }
        return false
    }
    /**
     * Active session ID for control messages, negotiated from the peer's LSP
     * during SYN exchange. Defaults to reference's control id and is updated from car.
     */
    private var negotiatedControlSession: Byte = Iap2Constants.CONTROL_SESSION_ID

    @Volatile override var onLinkUp: () -> Unit = {}
    @Volatile override var onSessionEnded: (String) -> Unit = {}

    @SuppressLint("MissingPermission")
    override fun start(scope: CoroutineScope) = synchronized(lifecycleLock) {
        if (!stopped) return@synchronized
        stopped = false
        loopJob = scope.launch(Dispatchers.IO) { runAcceptLoop() }
    }

    override fun stop(): Unit = synchronized(lifecycleLock) {
        stopped = true
        _listening.value = false
        // Cancel before closing sockets so expected shutdown never triggers recovery.
        loopJob?.cancel()
        runCatching { socket?.close() }
        runCatching { serverSocket?.close() }
    }

    @SuppressLint("MissingPermission")
    private suspend fun runAcceptLoop() = withContext(Dispatchers.IO) {
        try {
            while (isActive && !stopped) {
                var server: BluetoothServerSocket? = null
                var phase = "listen"
                try {
                    // Recreating the listener also registers a fresh SDP record.
                    server = adapter.listenUsingRfcommWithServiceRecord(
                        "Wireless iAP", Iap2Constants.SERVICE_UUID_IPHONE,
                    )
                    synchronized(lifecycleLock) {
                        if (stopped || !isActive) {
                            server.close()
                            return@withContext
                        }
                        serverSocket = server
                        _listening.value = true
                        diagnostics.listener(diagnosticToken, ConnectionDiagnostics.Status.FULL)
                    }
                    onLog("RFCOMM listening on UUID ${Iap2Constants.SERVICE_UUID_IPHONE}")
                    while (isActive && !stopped) {
                        phase = "accept"
                        val s = server.accept()
                        try {
                            synchronized(lifecycleLock) {
                                if (stopped || !isActive) return@withContext
                                socket = s
                                diagnostics.reached(diagnosticToken, ConnectionDiagnostics.Stage.SOCKET)
                            }
                            phase = "session"
                            input = s.inputStream
                            output = s.outputStream
                            onLog("Head unit connected: ${s.remoteDevice.address}")
                            handleSession()
                        } finally {
                            runCatching { s.close() }
                            socket = null
                            diagnostics.socketClosed(diagnosticToken)
                            input = null
                            output = null
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (isActive && !stopped) {
                        diagnostics.listener(diagnosticToken, ConnectionDiagnostics.Status.FAILED, e.javaClass.simpleName)
                        onLog("RFCOMM $phase failed: ${e.javaClass.simpleName}: ${e.message}; recreating listener in 1000ms")
                    }
                } finally {
                    runCatching { server?.close() }
                    synchronized(lifecycleLock) {
                        if (serverSocket === server) {
                            serverSocket = null
                            _listening.value = false
                        }
                    }
                }
                if (isActive && !stopped) delay(1000)
            }
        } finally {
            onLog("RFCOMM listener stopped")
        }
    }

    private suspend fun handleSession() = withContext(Dispatchers.IO) {
        val out = output ?: return@withContext
        val inp = input ?: return@withContext

        try {
            // Per reference/reference client (link_layer_client.py): _sent_psn = 1.
            // First frame (SYN|ACK) goes at seq=1, first data at seq=2. Starting
            // at 0 may make peers treat the frames as part of the SYN handshake
            // and silently drop our subsequent data.
            outSeq = 1
            lastAck = 0
            negotiatedControlSession = Iap2Constants.CONTROL_SESSION_ID
            waitingForPeerAckAfterSyn = false
            linkNormal = false
            pendingControlFrame = null
            pendingControlRetransmits = 0
            sentAnySequencedFrame = false
            sentDetectEcho = false
            lastRxDataSeq = -1
            adoptedPeerLsp = null

            // iAP2 over Bluetooth: the car (IVI / accessory) drives phase 0.
            // Verified via raw-byte logging on a 38:2A:8B:.. head unit:
            //   car  -> phone  FF 55 02 00 EE 10   (DETECT marker, iAP2 support)
            //   phone -> car   FF 55 02 00 EE 10   (echo)
            //   car  -> phone  FF 5A .. SYN [LSP]
            //   phone -> car   FF 5A .. SYN|ACK [LSP]
            //   car  -> phone  bare ACK            (link up)
            // Sending DETECT/SYN proactively confuses the car -- it then expects
            // us to ECHO its DETECT and respond to its SYN with SYN|ACK. So we
            // stay silent until we see what the car sends first.
            onLog("waiting for car DETECT marker / SYN (reactive mode)")

            // 2. Process incoming frames.
            val skippedBuffer = mutableListOf<Byte>()
            val flushSkipped = {
                if (skippedBuffer.isNotEmpty()) {
                    val hex = skippedBuffer.joinToString("") { "%02X".format(it) }
                    onLog("rx pre-frame bytes: $hex")
                    // If we just saw a DETECT marker FF 55 02 00 EE 10 and we
                    // haven't echoed one yet, echo it now so the car proceeds
                    // to SYN.
                    if (!sentDetectEcho && containsDetectMarker(skippedBuffer)) {
                        out.write(Iap2LinkSync.DETECT_MARKER)
                        out.flush()
                        sentDetectEcho = true
                        diagnostics.reached(diagnosticToken, ConnectionDiagnostics.Stage.DETECT)
                        onLog("  -> echoed DETECT_MARKER back to car")
                    }
                    skippedBuffer.clear()
                }
            }
            while (true) {
                val frame = try {
                    Iap2Frame.readFrom(
                        inp,
                        onSkip = { b ->
                            skippedBuffer.add(b)
                            if (!sentDetectEcho && containsDetectMarker(skippedBuffer)) {
                                out.write(Iap2LinkSync.DETECT_MARKER)
                                out.flush()
                                sentDetectEcho = true
                                diagnostics.reached(diagnosticToken, ConnectionDiagnostics.Stage.DETECT)
                                onLog("rx DETECT_MARKER from car; echoed back")
                                skippedBuffer.clear()
                            }
                        },
                        onRawWire = { wire ->
                            onLog("rx wire ${wire.size}B: " + wire.joinToString("") { "%02X".format(it) })
                        },
                    ) ?: break
                } catch (e: java.io.IOException) {
                    onLog("RFCOMM closed by peer: ${e.message}")
                    break
                }
                flushSkipped()
                lastAck = frame.seq
                val ctrl = frame.control.toInt() and 0xFF
                val flags = buildString {
                    if (ctrl and 0x80 != 0) append("SYN ")
                    if (ctrl and 0x40 != 0) append("ACK ")
                    if (ctrl and 0x20 != 0) append("EAK ")
                    if (ctrl and 0x10 != 0) append("RST ")
                }.trim()
                onLog("rx ctrl=0x%02X(%s) seq=0x%02X ack=0x%02X sid=0x%02X len=%d".format(
                    frame.control, flags, frame.seq, frame.ack, frame.session, frame.payload.size))
                if (frame.payload.isNotEmpty()) {
                    onLog("  payload hex: " + frame.payload.take(64).joinToString("") { "%02X".format(it) } +
                            if (frame.payload.size > 64) "..." else "")
                }
                if (ctrl and 0x40 != 0) {
                    handlePeerAck(frame.ack)
                }
                logBtRx(frame, ctrl)

                // Handle SYN from peer. In reactive mode we adopt the car's
                // LSP and reply with SYN|ACK carrying our own LSP. Then we wait
                // for the car's bare ACK to confirm before declaring link up.
                if (ctrl and 0x80 != 0) {
                    val peerLsp = Iap2LinkSync.parsePayload(frame.payload)
                    if (peerLsp != null) {
                        onLog("  car LSP: maxOut=${peerLsp.maxOutgoing.toInt() and 0xFF}, " +
                                "maxLen=${peerLsp.maxRxPacketLen}, sessions=${peerLsp.sessions}")
                        peerLsp.sessions.firstOrNull { it.type == 0x00.toByte() }?.let {
                            negotiatedControlSession = it.id
                            onLog("  -> peer advertised type=0 session id=0x%02X".format(it.id))
                        }
                        // Per reference _handle_syn: ADOPT the peer's LSP. The SYN|ACK
                        // we send echoes the peer's LSP bytes verbatim, not ours.
                        // Sending our OWN LSP back appears to put real cars into a
                        // state where they refuse to advance past the SYN exchange.
                        adoptedPeerLsp = peerLsp
                    }
                    lastAck = frame.seq

                    if (waitingForPeerAckAfterSyn && ctrl and 0x40 != 0 && frame.ack == outSeq) {
                        linkNormal = true
                        waitingForPeerAckAfterSyn = false
                        sendBareAck(frame.seq)
                        onLog("  -> peer SYN|ACK acknowledged our SYN|ACK (link up)")
                        onLinkUp()
                        continue
                    }

                    sendLinkSyncAck(frame.seq)
                    waitingForPeerAckAfterSyn = true
                    linkNormal = false
                    onLog("  -> SYN|ACK + (echoed peer) LSP sent; waiting for peer ACK")
                    continue
                }

                if (!linkNormal && waitingForPeerAckAfterSyn && (ctrl and 0x40 != 0)) {
                    linkNormal = true
                    waitingForPeerAckAfterSyn = false
                    onLog("  -> peer ACK received (link up)")
                    onLinkUp()
                    if (frame.payload.isEmpty()) continue
                }

                // Bare-ACK only frames that carry an upper-layer payload.
                // Bare ACKs from the peer don't need our own ACK back (would
                // cause an infinite ping-pong since both sides increment on
                // every ACK-flagged frame).
                // iAP2 link layer ACKs every sequenced data frame, whatever its
                // session; an un-ACKed frame is retransmitted until the peer
                // gives up on the link. Only control-session payloads are dispatched.
                if (frame.payload.isNotEmpty()) {
                    if (frame.session == Iap2Constants.CONTROL_SESSION_ID ||
                        frame.session == negotiatedControlSession
                    ) {
                        handleControlPayload(frame.payload)
                    }
                    sendBareAck(frame.seq)
                }
            }
        } catch (t: Throwable) {
            onLog("handleSession exception (non-fatal): ${t.javaClass.simpleName}: ${t.message}")
        }
        onLog("RFCOMM session ended")
        runCatching { onSessionEnded("RFCOMM closed") }
    }

    /**
     * One unfiltered line per received frame ("[bt-rx]" is not wire noise), so
     * Bluetooth traffic after the Wi-Fi handoff stays visible while quietWire is on.
     */
    private fun logBtRx(frame: Iap2Frame, ctrl: Int) {
        val seq = frame.seq.toInt() and 0xFF
        val sid = frame.session.toInt() and 0xFF
        val hasData = frame.payload.isNotEmpty() && ctrl and 0x80 == 0
        val msg = if (hasData) Iap2ControlSession.parse(frame.payload)?.first?.let { " msg=0x%04X".format(it) }.orEmpty() else ""
        val kind = when {
            ctrl and 0x10 != 0 -> "RST"
            ctrl and 0x80 != 0 -> "SYN"
            ctrl and 0x20 != 0 -> "EAK"
            hasData -> "data"
            else -> "ack"
        }
        val retransmit = if (hasData && seq == lastRxDataSeq) " RETRANSMIT" else ""
        if (hasData) lastRxDataSeq = seq
        val control = sid == (Iap2Constants.CONTROL_SESSION_ID.toInt() and 0xFF) ||
            sid == (negotiatedControlSession.toInt() and 0xFF)
        val handling = if (!hasData) "" else if (control) " dispatched+acked" else " acked(non-control session)"
        onLog("[bt-rx] $kind seq=0x%02X ack=0x%02X sid=0x%02X len=%d$msg$handling$retransmit".format(
            seq, frame.ack.toInt() and 0xFF, sid, frame.payload.size))
    }

    /** Send a bare link-layer ACK (no payload) acknowledging peer's [peerSeq]. */
    private fun sendBareAck(peerSeq: Byte) {
        val out = output ?: return
        val frame = Iap2Frame(
            control = 0x40.toByte(),  // ACK only
            seq = outSeq,
            ack = peerSeq,
            // reference maps both link control and control/auth payloads to session 0.
            session = Iap2Constants.LINK_SESSION_ID,
            payload = ByteArray(0),
        )
        out.writeFrame(frame)
        val wire = frame.encode()
        onLog("  ACK wire ${wire.size}B: " + wire.joinToString("") { "%02X".format(it) })
    }

    /**
     * Send Link Sync with SYN|ACK after receiving the peer's SYN.
     * Per reference client: ECHO the peer's LSP bytes verbatim (we adopt the
     * peer's parameters). Sending our own LSP instead causes real cars to
     * silently refuse to advance past the SYN exchange.
     */
    private fun sendLinkSyncAck(peerSeq: Byte) {
        val out = output ?: return
        advanceOutgoingSeq()
        val lspToEcho = adoptedPeerLsp ?: linkSyncParams
        val frame = Iap2LinkSync.buildSyncFrame(
            lspToEcho.copy(controlByte = 0xC0.toByte()),
            seq = outSeq,
            ack = peerSeq,
        )
        out.writeFrame(frame)
        val wire = frame.encode()
        onLog(
            "  SYN|ACK (echoed) wire ${wire.size}B seq=0x%02X ack=0x%02X: %s".format(
                outSeq,
                peerSeq,
                wire.joinToString("") { "%02X".format(it) },
            )
        )
    }

    private fun handlePeerAck(peerAck: Byte) {
        val pending = pendingControlFrame ?: return
        if (peerAck == pending.seq) {
            onLog("  -> control seq=0x%02X acknowledged".format(pending.seq))
            pendingControlFrame = null
            pendingControlRetransmits = 0
            return
        }

        if (pendingControlRetransmits >= 3) {
            if (pendingControlRetransmits == 3) {
                pendingControlRetransmits += 1 // log exhaustion once
                onLog("[bt-tx] control seq=0x%02X still unacked after 3 retransmits (peer ack=0x%02X)".format(pending.seq, peerAck))
            }
            return
        }
        val out = output ?: return
        pendingControlRetransmits += 1
        out.writeFrame(pending)
        onLog(
            "  -> retransmit control seq=0x%02X after peer ack=0x%02X attempt=%d".format(
                pending.seq,
                peerAck,
                pendingControlRetransmits,
            )
        )
    }

    private suspend fun handleControlPayload(payload: ByteArray) {
        val parsed = Iap2ControlSession.parse(payload) ?: return
        val (id, params) = parsed
        // Route all control-session messages through CarPlaySession. Only MFi
        // auth payload construction uses the extracted OkCar behavior.
        _incomingControl.emit(id to params)
    }

    override fun sendControl(controlMessageBytes: ByteArray) {
        val out = output ?: return
        advanceOutgoingSeq()
        // Verified against reference/iap2 client wire trace: data goes on the
        // negotiated control session id (1 per car's LSP), NOT session 0. SYN
        // and bare ACK frames stay on session 0. reference's "session 0 for
        // control/auth" appears to refer to the link-layer catalog entry, not
        // the wire field.
        val frame = Iap2Frame(
            control = 0x40.toByte(), // ACK bit (data with ACK), per reference._send_data
            seq = outSeq,
            ack = lastAck,
            session = negotiatedControlSession,
            payload = controlMessageBytes,
        )
        out.writeFrame(frame)
        pendingControlFrame = frame
        pendingControlRetransmits = 0

        val messageId = Iap2ControlSession.parse(controlMessageBytes)?.first
        val idText = messageId?.let { " msg=0x%04X".format(it) }.orEmpty()
        val wire = frame.encode()
        onLog(
            "tx ctrl=0x40 seq=0x%02X ack=0x%02X sid=0x%02X$idText len=%d wire=%s".format(
                outSeq,
                lastAck,
                negotiatedControlSession,
                controlMessageBytes.size,
                wire.joinToString("") { "%02X".format(it) },
            )
        )
    }

    private fun advanceOutgoingSeq() {
        if (sentAnySequencedFrame) {
            outSeq = ((outSeq.toInt() + 1) and 0xFF).toByte()
        } else {
            sentAnySequencedFrame = true
        }
    }

    /**
     * Send MFi certificate response (0xAA02).
     * Called after receiving car's cert (0xAA01). OkcarOS sends the hardcoded
     * hash from j2.j.t() here instead of a random nonce.
     */
    override fun sendMfiCertResponse(hash: ByteArray) {
        val msg = Iap2ControlSession.build(
            Iap2ControlSession.Msg.MFI_AUTH_CHALLENGE,
            Iap2ControlSession.param(0x0000, hash),
        )
        sendControl(msg)
    }

    /**
     * Send AuthenticationSucceeded (0xAA05).
     * OkcarOS j2.j.H(43525) sends this as a no-parameter control message.
     */
    override fun sendAuthStatusOk() =
        sendControl(Iap2ControlSession.build(Iap2ControlSession.Msg.MFI_AUTH_SUCCEEDED))

    /**
     * Legacy auth response for non-bypass providers (ExternalChipProvider etc.).
     */
    override fun sendAuthenticationResponse(signature: ByteArray) {
        val msg = Iap2ControlSession.build(
            Iap2ControlSession.Msg.MFI_AUTH_CHALLENGE,
            Iap2ControlSession.param(0x0000, signature),
        )
        sendControl(msg)
    }

    override fun diagnostic(msg: String) = onLog(msg)
}

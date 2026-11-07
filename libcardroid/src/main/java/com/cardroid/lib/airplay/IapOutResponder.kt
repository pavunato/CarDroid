package com.cardroid.lib.airplay

import com.cardroid.lib.CarPlaySession
import com.cardroid.lib.Iap2Constants
import com.cardroid.lib.Iap2ControlSession
import com.cardroid.lib.Iap2Frame
import com.cardroid.lib.Iap2LinkSync
import com.cardroid.lib.IapTransport
import com.cardroid.lib.WifiHandoff
import com.cardroid.mfi.MfiAuthProvider
import com.cardroid.mfi.NullProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import java.util.LinkedHashMap

/**
 * Phone-role iAP2 responder for the car's encrypted event-channel
 * iAPSendMessage path.
 *
 * This class intentionally has no socket or RTSP knowledge. The owner feeds it
 * complete (or partial) iAP2 bytes and supplies a non-blocking [send] sink. The
 * event-channel owner serializes calls to [onData] and [tick] on one actor;
 * keeping that rule here makes link state, retransmission, and control-session
 * sequencing deterministic without ever blocking the event HTTP acknowledgement.
 *
 * Once the link is up, the existing [CarPlaySession] is used for the phone-role
 * identification and MFi flow. Like an iPhone it sends 0x5702/0x4300 and
 * swallows the car's 0x5703/0x4301 replies through inert Wi-Fi and AirPlay-start
 * callbacks, so a tunnel replay cannot rejoin Wi-Fi or start a second session.
 */
class IapOutResponder(
    private val send: (ByteArray) -> Unit,
    private val log: (String) -> Unit,
    mfi: MfiAuthProvider? = null,
    /** Monotonic time keeps retransmission deadlines valid across wall-clock changes. */
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val nowPlaying: () -> com.cardroid.lib.NowPlayingState = { com.cardroid.lib.NowPlayingState() },
    private val deviceName: String = com.cardroid.lib.IPhoneIdentity.DEVICE_NAME,
) {
    enum class State { IDLE, MARKER_ECHOED, SYNACK_SENT, LINK_UP }

    var state = State.IDLE
        private set

    /**
     * Fired once after the real MFi response has been accepted by the
     * existing control-session state machine. The owner may schedule a
     * post-auth action; this responder never performs network I/O itself.
     */
    @Volatile var onAuthenticationComplete: () -> Unit = {}

    private val authProvider: MfiAuthProvider = mfi ?: NullProvider()
    private var nextSeq = 1
    private var lastSentSeq: Byte = 0
    /** Last peer sequence accepted into the upper-layer stream. */
    private var lastAcceptedPeerSeq: Byte = 0
    private var haveAcceptedPeerSeq = false
    /** Next peer sequence required for a new, in-order data frame. */
    private var expectedPeerSeq: Int? = null
    private var markerEchoAt = Long.MIN_VALUE
    private var rxLogged = 0
    private var linkUpNotified = false
    private var authenticationCompleteNotified = false

    /** The peer may retransmit SYN until it sees our ACK. Keep one SYN|ACK. */
    private var peerSynSeq: Byte? = null
    private var synAckFrame: Iap2Frame? = null

    private var peerMaxOut = 1
    private var peerMaxLen = DEFAULT_MAX_FRAME
    private var peerRetransmitMs = DEFAULT_RETRANSMIT_MS
    private var peerMaxRetransmits = MAX_RETRANSMITS
    private var negotiatedControlSession: Byte = Iap2Constants.CONTROL_SESSION_ID

    private val buf = ByteArrayOutputStream()
    private val queuedControl = ArrayDeque<ByteArray>()
    private val pending = LinkedHashMap<Int, PendingFrame>()

    private data class PendingFrame(
        val frame: Iap2Frame,
        val label: String,
        var attempts: Int,
        var deadlineMs: Long,
    )

    private val controlTransport: IapTransport
    private val carPlay: CarPlaySession

    init {
        controlTransport = object : IapTransport {
            override val incomingControl: SharedFlow<Pair<Int, ByteArray>> =
                MutableSharedFlow(extraBufferCapacity = 1)
            override var onLinkUp: () -> Unit = {}
            override var onSessionEnded: (String) -> Unit = {}

            override fun start(scope: CoroutineScope) = Unit
            override fun stop() = Unit

            override fun sendControl(controlMessageBytes: ByteArray) {
                val id = Iap2ControlSession.parse(controlMessageBytes)?.first
                if (id != null && id in OUTBOUND_BLOCKED) {
                    log("[iap-out] ignored side effect tx 0x%04X".format(id))
                    return
                }
                queueControl(controlMessageBytes, id)
            }

            override fun sendMfiCertResponse(hash: ByteArray) {
                sendControl(
                    Iap2ControlSession.build(
                        Iap2ControlSession.Msg.MFI_AUTH_CHALLENGE,
                        Iap2ControlSession.param(0x0000, hash),
                    ),
                )
            }

            override fun sendAuthStatusOk() {
                sendControl(Iap2ControlSession.build(Iap2ControlSession.Msg.MFI_AUTH_SUCCEEDED))
            }

            override fun sendAuthenticationResponse(signature: ByteArray) {
                sendMfiCertResponse(signature)
            }

            override fun diagnostic(msg: String) {
                log("[iap-out] $msg")
            }
        }
        carPlay = CarPlaySession(
            transport = controlTransport,
            mfi = authProvider,
            // Windowed queueing here makes the multi-frame iPhone burst safe.
            iphoneBehavior = true,
            nowPlaying = nowPlaying,
            deviceName = deviceName,
            wifi = object : WifiHandoff {
                override fun connectToAccessory(network: WifiHandoff.NetworkInfo) {
                    log("[iap-out] ignored side effect wifi.connectToAccessory ssid=${network.ssid}")
                }

                override fun tearDown() {
                    log("[iap-out] ignored side effect wifi.tearDown")
                }
            },
        ).also { session ->
            session.onAirPlayStart = { endpoint ->
                log("[iap-out] ignored side effect AirPlayStart host=${endpoint.ipv6} port=${endpoint.port}")
            }
        }
    }

    /**
     * Feed an event-channel payload. Call this from one serialized actor; the
     * method itself never performs network I/O.
     */
    fun onData(chunk: ByteArray) {
        if (chunk.isEmpty()) return
        tick(clockMs())
        buf.write(chunk)
        if (buf.size() > MAX_BUFFER) {
            buf.reset()
            log("[iap-out] rx buffer overflow; dropped")
            return
        }

        val data = buf.toByteArray()
        var pos = 0
        while (pos < data.size) {
            val rem = data.size - pos
            val marker = Iap2LinkSync.DETECT_MARKER
            if (startsWith(data, pos, marker)) {
                rx(data.copyOfRange(pos, pos + marker.size), "marker")
                onMarker(clockMs())
                pos += marker.size
                continue
            }

            if (data[pos] == 0xFF.toByte() && rem >= 2 && data[pos + 1] == 0x5A.toByte()) {
                if (rem < 4) break
                val len = ((data[pos + 2].toInt() and 0xFF) shl 8) or
                    (data[pos + 3].toInt() and 0xFF)
                if (len < 9 || len > MAX_WIRE_FRAME) {
                    log("[iap-out] rx invalid frame length=$len; resyncing")
                    pos += 2
                    continue
                }
                if (rem < len) break
                val wire = data.copyOfRange(pos, pos + len)
                pos += len
                val frame = parse(wire)
                if (frame == null) {
                    rx(wire, "bad-checksum")
                } else {
                    onFrame(frame, wire)
                }
                continue
            }

            // Preserve a possible partial marker or frame prefix for the next
            // event POST. Other bytes are harmless noise before a frame.
            if (data[pos] == 0xFF.toByte() && rem == 1) break
            if (data[pos] == 0xFF.toByte() && rem < marker.size && startsWith(marker, 0, data.copyOfRange(pos, data.size))) break
            pos++
        }

        buf.reset()
        if (pos < data.size) buf.write(data, pos, data.size - pos)
    }

    /** Drive bounded retransmission. The owner should call this about every 100 ms. */
    fun tick(nowMs: Long = clockMs()) {
        if (state == State.LINK_UP) {
            carPlay.refreshNowPlaying()
            carPlay.refreshPower()
        }
        if (pending.isEmpty()) return
        val expired = pending.entries.toList().filter { nowMs >= it.value.deadlineMs }
        // A missing ACK invalidates the whole link sequence. Removing only the
        // expired gap and pumping later frames would let the peer observe a
        // sequence hole. Drop the link's pending/queued data as one unit and
        // leave the surrounding transport/media session alive.
        if (expired.any { it.value.attempts >= peerMaxRetransmits }) {
            val failed = expired.first { it.value.attempts >= peerMaxRetransmits }
            log(
                "[iap-out] retransmit exhaustion seq=0x%02X label=%s; " +
                    "resetting iAP link and dropping %d queued frame(s)".format(
                        failed.key,
                        failed.value.label,
                        pending.size + queuedControl.size,
                    ),
            )
            resetLinkState("retransmit exhaustion", notifySession = true)
            return
        }
        for ((key, p) in expired) {
            p.attempts += 1
            p.deadlineMs = nowMs + peerRetransmitMs
            emit(p.frame, p.label, retransmit = true)
        }
        pumpControl(nowMs)
    }

    private fun setState(next: State) {
        if (next != state) {
            log("[iap-out] link state -> $next")
            state = next
        }
    }

    private fun startsWith(data: ByteArray, off: Int, prefix: ByteArray): Boolean {
        if (off < 0 || off + prefix.size > data.size) return false
        for (i in prefix.indices) if (data[off + i] != prefix[i]) return false
        return true
    }

    private fun onMarker(nowMs: Long) {
        if (state == State.LINK_UP) return
        if (markerEchoAt != Long.MIN_VALUE && nowMs - markerEchoAt < MARKER_ECHO_WINDOW_MS) return
        markerEchoAt = nowMs
        emitRaw(Iap2LinkSync.DETECT_MARKER, "marker")
        if (state == State.IDLE) setState(State.MARKER_ECHOED)
    }

    private fun onFrame(frame: Iap2Frame, wire: ByteArray) {
        val ctrl = frame.control.toInt() and 0xFF
        val flags = buildString {
            if (ctrl and 0x80 != 0) append("SYN|")
            if (ctrl and 0x40 != 0) append("ACK|")
            if (ctrl and 0x20 != 0) append("EAK|")
            if (ctrl and 0x10 != 0) append("RST|")
        }.trimEnd('|')
        rx(wire, flags.ifBlank { "frame" })

        if (ctrl and 0x40 != 0) acknowledge(frame.ack, clockMs())
        if (ctrl and 0x10 != 0) {
            log("[iap-out] car reset received")
            resetLinkState("peer reset", notifySession = true)
            return
        }

        if (ctrl and 0x80 != 0) {
            handleSyn(frame)
            return
        }

        // A bare ACK after our SYN|ACK confirms the link. It is deliberately
        // not answered: an ACK-of-ACK loop would only confuse the car.
        if (state == State.SYNACK_SENT && ctrl and 0x40 != 0 && frame.payload.isEmpty()) {
            setState(State.LINK_UP)
            if (!linkUpNotified) {
                linkUpNotified = true
                runCatching {
                    carPlay.onLinkUp()
                    carPlay.startControlFlow()
                }.onFailure { log("[iap-out] control start failed: ${it.javaClass.simpleName}: ${it.message}") }
            }
            return
        }

        if (frame.payload.isNotEmpty()) {
            // ACK every data frame, but only dispatch the next contiguous
            // sequence. A duplicate/out-of-order payload is acknowledged with
            // the last contiguous sequence and never re-runs auth or side
            // effects.
            // Decode/log before sequence filtering so duplicate and rejected
            // session messages remain visible in the diagnostic stream.
            val parsedPayload = logControlPayload(frame.session, frame.payload)
            val sequence = acceptPeerPayload(frame.seq)
            sendBareAck(if (haveAcceptedPeerSeq) lastAcceptedPeerSeq else frame.seq)
            if (!sequence.accepted) {
                log(
                    "[iap-out] dropped ${sequence.reason} peer payload seq=0x%02X " +
                        "last=0x%02X expected=0x%02X".format(
                            frame.seq,
                            lastAcceptedPeerSeq,
                            expectedPeerSeq ?: -1,
                        ),
                )
                pumpControl(clockMs())
                return
            }
            if (state != State.LINK_UP) {
                log("[iap-out] ignored peer payload before link-up seq=0x%02X".format(frame.seq))
                pumpControl(clockMs())
                return
            }
            if (parsedPayload != null) handleControlPayload(frame.session, parsedPayload)
        }
        pumpControl(clockMs())
    }

    private fun handleSyn(frame: Iap2Frame) {
        val previousSyn = peerSynSeq
        if (previousSyn != null && previousSyn == frame.seq && synAckFrame != null) {
            // SYN is idempotent. Re-emitting the original frame lets a car
            // recover from a lost SYN|ACK without allocating a new sequence.
            val existing = synAckFrame!!
            if (pending[existing.seq.toInt() and 0xFF] == null) {
                sendTracked(existing, "SYN|ACK", clockMs())
            } else {
                pending[existing.seq.toInt() and 0xFF]?.deadlineMs = clockMs() + peerRetransmitMs
                emit(existing, "SYN|ACK", retransmit = true)
            }
            if (state == State.IDLE || state == State.MARKER_ECHOED) setState(State.SYNACK_SENT)
            return
        }
        if (previousSyn != null && previousSyn != frame.seq) {
            // A different SYN starts a new iAP link sequence. Reset our local
            // sequence/session state before adopting its LSP.
            resetLinkState("new peer SYN", notifySession = state == State.LINK_UP)
        }

        val peer = Iap2LinkSync.parsePayload(frame.payload)
        if (peer != null) {
            peerMaxOut = (peer.maxOutgoing.toInt() and 0xFF).coerceIn(1, 64)
            peerMaxLen = peer.maxRxPacketLen.coerceIn(9, MAX_WIRE_FRAME)
            peerRetransmitMs = peer.retransmitTimeoutMs.coerceIn(250, 5_000)
            peerMaxRetransmits = (peer.maxRetransmits.toInt() and 0xFF).coerceIn(1, MAX_RETRANSMITS)
            peer.sessions.firstOrNull { it.type == 0x00.toByte() }?.let {
                negotiatedControlSession = it.id
            }
            log(
                "[iap-out] peer LSP maxOut=$peerMaxOut maxLen=$peerMaxLen " +
                    "retransmitMs=$peerRetransmitMs maxRetransmits=$peerMaxRetransmits " +
                    "controlSession=${negotiatedControlSession.toInt() and 0xFF} sessions=${peer.sessions}",
            )
        } else {
            log("[iap-out] malformed peer LSP len=${frame.payload.size}; using conservative defaults")
        }

        peerSynSeq = frame.seq
        lastAcceptedPeerSeq = frame.seq
        haveAcceptedPeerSeq = true
        expectedPeerSeq = ((frame.seq.toInt() and 0xFF) + 1) and 0xFF

        // The peer's LSP is echoed verbatim, matching the working Bluetooth
        // phone-role flow. Link SYN/SYN|ACK frames stay on session 0.
        val synAck = Iap2Frame(
            control = 0xC0.toByte(),
            seq = allocateSeq(),
            ack = frame.seq,
            session = Iap2Constants.LINK_SESSION_ID,
            payload = frame.payload,
        )
        synAckFrame = synAck
        sendTracked(synAck, "SYN|ACK", clockMs())
        setState(State.SYNACK_SENT)
    }

    private data class PeerSequenceResult(val accepted: Boolean, val reason: String = "")

    private fun acceptPeerPayload(seq: Byte): PeerSequenceResult {
        val value = seq.toInt() and 0xFF
        val expected = expectedPeerSeq
        if (!haveAcceptedPeerSeq || expected == null) {
            lastAcceptedPeerSeq = seq
            haveAcceptedPeerSeq = true
            expectedPeerSeq = (value + 1) and 0xFF
            return PeerSequenceResult(accepted = true)
        }
        if (value == expected) {
            lastAcceptedPeerSeq = seq
            expectedPeerSeq = (value + 1) and 0xFF
            return PeerSequenceResult(accepted = true)
        }
        return PeerSequenceResult(
            accepted = false,
            reason = if (seq == lastAcceptedPeerSeq) "duplicate" else "out-of-order",
        )
    }

    private fun sendBareAck(peerSeq: Byte) {
        val ack = Iap2Frame(
            control = 0x40.toByte(),
            seq = lastSentSeq,
            ack = peerSeq,
            session = Iap2Constants.LINK_SESSION_ID,
            payload = ByteArray(0),
        )
        emit(ack, "ACK", retransmit = false)
    }

    private fun acknowledge(ack: Byte, nowMs: Long) {
        val target = ack.toInt() and 0xFF
        if (pending.isEmpty()) return
        val keys = pending.keys.toList()
        val index = keys.indexOf(target)
        if (index < 0) return
        repeat(index + 1) { pending.remove(keys[it]) }
        log("[iap-out] cumulative ACK through seq=0x%02X pending=${pending.size}".format(target))
        pumpControl(nowMs)
    }

    private fun queueControl(payload: ByteArray, id: Int?) {
        if (state != State.LINK_UP) {
            log("[iap-out] tx msg ${id?.let { "0x%04X".format(it) } ?: "<unknown>"} dropped before link-up")
            return
        }
        val wireSize = 10 + payload.size
        if (wireSize > peerMaxLen) {
            log("[iap-out] tx msg ${id?.let { "0x%04X".format(it) } ?: "<unknown>"} dropped len=$wireSize > peer maxLen=$peerMaxLen")
            return
        }
        queuedControl.addLast(payload.copyOf())
        pumpControl(clockMs())
    }

    private fun pumpControl(nowMs: Long) {
        while (queuedControl.isNotEmpty() && pending.size < peerMaxOut) {
            val payload = queuedControl.removeFirst()
            val id = Iap2ControlSession.parse(payload)?.first
            val frame = Iap2Frame(
                control = 0x40.toByte(),
                seq = allocateSeq(),
                ack = if (haveAcceptedPeerSeq) lastAcceptedPeerSeq else 0,
                session = negotiatedControlSession,
                payload = payload,
            )
            sendTracked(frame, "msg ${id?.let { "0x%04X".format(it) } ?: "<unknown>"}", nowMs)
        }
    }

    private fun allocateSeq(): Byte {
        val seq = nextSeq.toByte()
        lastSentSeq = seq
        nextSeq = (nextSeq + 1) and 0xFF
        return seq
    }

    private fun sendTracked(frame: Iap2Frame, label: String, nowMs: Long) {
        pending[frame.seq.toInt() and 0xFF] = PendingFrame(
            frame = frame,
            label = label,
            attempts = 0,
            deadlineMs = nowMs + peerRetransmitMs,
        )
        emit(frame, label, retransmit = false)
    }

    private fun emit(frame: Iap2Frame, label: String, retransmit: Boolean) {
        val id = Iap2ControlSession.parse(frame.payload)?.first
        if (id != null) {
            log(
                "[iap-out] tx msg 0x%04X session=%d seq=0x%02X ack=0x%02X len=%d%s".format(
                    id,
                    frame.session.toInt() and 0xFF,
                    frame.seq,
                    frame.ack,
                    frame.payload.size,
                    if (retransmit) " retransmit" else "",
                ),
            )
        } else if (label != "ACK") {
            log(
                "[iap-out] tx frame $label seq=0x%02X ack=0x%02X session=%d%s".format(
                    frame.seq,
                    frame.ack,
                    frame.session.toInt() and 0xFF,
                    if (retransmit) " retransmit" else "",
                ),
            )
        }
        runCatching { send(frame.encode()) }
            .onFailure { log("[iap-out] tx $label failed: ${it.javaClass.simpleName}: ${it.message}") }
    }

    private fun emitRaw(bytes: ByteArray, label: String) {
        log("[iap-out] tx $label len=${bytes.size}")
        runCatching { send(bytes.copyOf()) }
            .onFailure { log("[iap-out] tx $label failed: ${it.javaClass.simpleName}: ${it.message}") }
    }

    private fun logControlPayload(session: Byte, bytes: ByteArray): Pair<Int, ByteArray>? {
        val parsed = Iap2ControlSession.parse(bytes)
        if (parsed == null) {
            log("[iap-out] car msg malformed session=${session.toInt() and 0xFF} len=${bytes.size} bytes=${hex(bytes, 100)}")
            return null
        }
        val (id, params) = parsed
        val descriptors = Iap2ControlSession.parseParams(params)
            .joinToString(",") { "${it.id}:${it.value.size}" }
            .ifEmpty { "none" }
        log(
            "[iap-out] car msg 0x%04X session=%d params=[%s] bytes=%s".format(
                id,
                session.toInt() and 0xFF,
                descriptors,
                hex(bytes, 100),
            ),
        )
        return parsed
    }

    private fun handleControlPayload(session: Byte, parsed: Pair<Int, ByteArray>) {
        val (id, params) = parsed

        if ((session.toInt() and 0xFF) != (negotiatedControlSession.toInt() and 0xFF)) {
            log(
                "[iap-out] ignored wrong session 0x%02X for control message 0x%04X " +
                    "(negotiated=0x%02X)".format(
                        session.toInt() and 0xFF,
                        id,
                        negotiatedControlSession.toInt() and 0xFF,
                    ),
            )
            return
        }

        if (id in INBOUND_BLOCKED) {
            log("[iap-out] ignored side effect 0x%04X session=%d".format(id, session.toInt() and 0xFF))
            return
        }

        val handled = runCatching {
            // CarPlaySession's auth provider is suspend-capable. We are already
            // on the responder actor (the event thread only enqueues), so this
            // cannot delay the event-channel HTTP acknowledgement.
            runBlocking { carPlay.onControlMessage(id, params) }
        }.onFailure {
            log("[iap-out] control msg 0x%04X failed: %s: %s".format(id, it.javaClass.simpleName, it.message))
        }.isSuccess

        // CarPlaySession briefly enters AuthComplete and then requests Wi-Fi,
        // so accept either state as proof that AuthenticationSucceeded was
        // sent. Duplicate/out-of-order frames never reach this point, and the
        // guard keeps retransmitted valid responses from re-triggering the
        // owner's post-auth action.
        if (handled && id == Iap2ControlSession.Msg.MFI_AUTH_RESPONSE &&
            !authenticationCompleteNotified &&
            (carPlay.state.value is CarPlaySession.State.AuthComplete ||
                carPlay.state.value is CarPlaySession.State.WifiHandoffRequested)
        ) {
            authenticationCompleteNotified = true
            runCatching { onAuthenticationComplete() }
                .onFailure { log("[iap-out] post-auth callback failed: ${it.javaClass.simpleName}: ${it.message}") }
        }

        when (id) {
            Iap2ControlSession.Msg.IDENTIFICATION_INFORMATION ->
                log("[iap-out] identification outcome=accepted (0x1D02 queued if valid)")
            Iap2ControlSession.Msg.IDENTIFICATION_REJECTED ->
                log("[iap-out] identification outcome=rejected")
            Iap2ControlSession.Msg.MFI_AUTH_CERTIFICATE -> {
                val certState = carPlay.state.value
                log(
                    "[iap-out] auth cert outcome=${if (certState is CarPlaySession.State.MfiCertReceived) "challenge queued" else "failed"} " +
                        "provider=${authProvider.name}",
                )
            }
            Iap2ControlSession.Msg.MFI_AUTH_RESPONSE ->
                log("[iap-out] auth outcome=${if (carPlay.state.value is CarPlaySession.State.AuthComplete || carPlay.state.value is CarPlaySession.State.WifiHandoffRequested) "accepted" else "pending/failed"}")
            Iap2ControlSession.Msg.MFI_AUTH_FAILED ->
                log("[iap-out] auth outcome=rejected")
        }
    }

    /** Reset only this iAP link; the enclosing RTSP/event media remains alive. */
    private fun resetLinkState(reason: String, notifySession: Boolean) {
        if (notifySession) runCatching { carPlay.onSessionEnded() }
            .onFailure { log("[iap-out] session reset callback failed: ${it.message}") }
        pending.clear()
        queuedControl.clear()
        nextSeq = 1
        lastSentSeq = 0
        lastAcceptedPeerSeq = 0
        haveAcceptedPeerSeq = false
        expectedPeerSeq = null
        peerSynSeq = null
        synAckFrame = null
        markerEchoAt = Long.MIN_VALUE
        peerMaxOut = 1
        peerMaxLen = DEFAULT_MAX_FRAME
        peerRetransmitMs = DEFAULT_RETRANSMIT_MS
        peerMaxRetransmits = MAX_RETRANSMITS
        negotiatedControlSession = Iap2Constants.CONTROL_SESSION_ID
        linkUpNotified = false
        authenticationCompleteNotified = false
        log("[iap-out] iAP link reset reason=$reason")
        setState(State.IDLE)
    }

    private fun parse(wire: ByteArray): Iap2Frame? {
        if (wire.size < 9) return null
        var sum = 0
        for (i in 0..7) sum += wire[i].toInt() and 0xFF
        if (wire[8] != ((-sum) and 0xFF).toByte()) return null
        val len = ((wire[2].toInt() and 0xFF) shl 8) or (wire[3].toInt() and 0xFF)
        if (len != wire.size || len < 9) return null
        val payload = if (len > 9) {
            if (len < 10) return null
            val n = len - 10
            if (n < 0) return null
            var pSum = 0
            for (i in 0 until n) pSum += wire[9 + i].toInt() and 0xFF
            if (wire[9 + n] != ((-pSum) and 0xFF).toByte()) return null
            wire.copyOfRange(9, 9 + n)
        } else ByteArray(0)
        return Iap2Frame(wire[4], wire[5], wire[6], wire[7], payload)
    }

    private fun rx(bytes: ByteArray, kind: String) {
        rxLogged += 1
        // Every decoded control message is logged separately below; this raw
        // wire preview is capped only to keep link diagnostics readable.
        if (rxLogged <= 30) log("[iap-out] rx ${hex(bytes, 100)} ($kind)")
    }

    private fun hex(bytes: ByteArray, max: Int): String =
        bytes.take(max).joinToString("") { "%02X".format(it) } + if (bytes.size > max) "…" else ""

    private companion object {
        const val MAX_BUFFER = 65_536
        const val MAX_WIRE_FRAME = 65_535
        const val DEFAULT_MAX_FRAME = 8_192
        const val DEFAULT_RETRANSMIT_MS = 1_500
        const val MAX_RETRANSMITS = 3
        const val MARKER_ECHO_WINDOW_MS = 1_000L
        /**
         * Like an iPhone, we send 0x5702 and 0x4300 over the tunnel; the car answers
         * 0x5703 and 0x4301. The car's answers are accepted by the session, whose Wi-Fi
         * and AirPlay-start callbacks are inert here, so nothing is joined or started.
         * Only messages that make no sense in this direction stay blocked.
         */
        val OUTBOUND_BLOCKED = setOf(
            Iap2ControlSession.Msg.WIFI_INFORMATION,
            Iap2ControlSession.Msg.CARPLAY_AIRPLAY_START,
        )
        val INBOUND_BLOCKED = setOf(
            Iap2ControlSession.Msg.REQUEST_WIFI_INFORMATION,
            Iap2ControlSession.Msg.CARPLAY_AVAILABILITY,
        )
    }
}

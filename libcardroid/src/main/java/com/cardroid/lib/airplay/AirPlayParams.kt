package com.cardroid.lib.airplay

import java.util.UUID
import java.security.SecureRandom

/**
 * Per-session AirPlay state. Mirrors OkcarOS `sources/a2/d.java`.
 *
 * Populated incrementally: host/airPlayPort/sessionUuid from the iAP2 0x4301
 * message; RTSP URL/session identifiers from OkcarOS defaults; eiv/ekey from
 * /auth-setup response; eventPort/timingPort from SETUP response.
 */
data class AirPlayParams(
    /** Car's IPv6 (with %iface zone, e.g. "fe80::3a2a:8bff:fe18:33f8%wlan0"). */
    var host: String,
    /** TCP port for RTSP control connection (from 0x4301 or mDNS SRV). */
    var airPlayPort: Int,
    /** UUID received in the iAP2 AirPlay start message (0x4301). */
    var sessionUuid: String,
    /** OkcarOS `f61m`: path segment used in RTSP session URLs. */
    var rtspSessionId: String = "7267369879794370004",
    /** OkcarOS `f53e`: random UUID sent in SETUP plist as `sessionUUID` (uppercase, as a real iPhone). */
    var setupSessionUuid: String = UUID.randomUUID().toString().uppercase(),
    /** AirPlay DACP sender id used by authorized RTSP requests. */
    var dacpId: String = randomHex64(),
    /** AirPlay remote-control token used by authorized RTSP requests. */
    var activeRemote: String = randomActiveRemote(),
    /** AES IV negotiated via /auth-setup. */
    var eiv: ByteArray = ByteArray(0),
    /** AES key negotiated via /auth-setup. */
    var ekey: ByteArray = ByteArray(0),
    /** Local UDP port we listen on for NTP timing. */
    var timingPort: Int = 0,
    /** TCP port the car asks us to open for event session (from SETUP response). */
    var eventPort: Int = 0,
    /** RTP video port (from SETUP response). */
    var videoPort: Int = 0,
    /** Our (spoofed) Bluetooth MAC, used as deviceID in SETUP. */
    var deviceId: String = "8e:0c:64:4f:bb:f7",
    /** OkcarOS SETUP `macAddress` default from PcConnectService. */
    var macAddress: String = "00:11:22:33:44:55",
    /** Marketing model string used in SETUP. */
    var model: String = "iPhone18,4",
    /** Device name used in SETUP. OkcarOS sends `OKCAR's box`. */
    var name: String = com.cardroid.lib.IPhoneIdentity.DEVICE_NAME,
    /** Pretend OS name used in SETUP. */
    var osName: String = "iPhone OS",
    /** Pretend iOS version used in SETUP (consistent with [osBuildVersion] and [sourceVersion]). */
    var osVersion: String = "26.7.1",
    /** Fresh per-session correlation UUID sent in SETUP as `sessionCorrelationUUID`. */
    var sessionCorrelationUuid: String = UUID.randomUUID().toString().uppercase(),
    /** Pretend iOS build version used in SETUP. */
    var osBuildVersion: String = "23H30",
    /** AirPlay source version sent in SETUP / RTSP headers. */
    var sourceVersion: String = "960.13.1",
)

private val airPlayRandom = SecureRandom()

private fun randomHex64(): String {
    val bytes = ByteArray(8)
    airPlayRandom.nextBytes(bytes)
    return bytes.joinToString("") { b -> "%02X".format(b.toInt() and 0xff) }
}

private fun randomActiveRemote(): String = airPlayRandom.nextInt(Int.MAX_VALUE).toString()

package com.cardroid.lib

import java.io.ByteArrayOutputStream

/**
 * Control session: 16-bit Type-Length-Value messages, sent as iAP2 frame payloads
 * on session id [Iap2Constants.CONTROL_SESSION_ID].
 *
 * Public TLV wire format:
 *
 *   off  bytes  field
 *   0    2      start-of-message marker = 0x40 0x40
 *   2    2      length, big-endian, total bytes including the marker
 *   4    2      message id, big-endian
 *   6    N      parameters (each: param-len(2) | param-id(2) | bytes)
 *
 * Message-id catalog (from reference/iap2 control session foundation):
 *   0x1D00  StartIdentification              (iPhone -> accessory)
 *   0x1D01  IdentificationInformation        (accessory -> iPhone)
 *   0x1D02  IdentificationAccepted           (iPhone -> accessory)
 *   0x1D03  IdentificationRejected           (iPhone -> accessory)
 *   0xEA00  StartExternalAccessoryProtocol   (car -> phone, opens EA session)
 *   0xEA01  StopExternalAccessoryProtocol
 *   0xEA03  ExternalAccessoryProtocolStatus
 *   0xAA00  RequestAuthenticationCertificate (phone -> car)
 *   0xAA01  MFiAuthCertificate               (car -> phone, car's MFi cert)
 *   0xAA02  Authentication challenge         (iPhone -> accessory; Okcar payload)
 *   0xAA03  Authentication response          (accessory -> iPhone)
 *   0xAA05  AuthenticationSucceeded          (iPhone -> accessory, no params)
 *   0x4E0D  WirelessCarPlayUpdate            (phone -> car)
 *   0x4E0E  DeviceTransportIdentifierNotification
 *   0x5702  RequestAccessoryWiFiConfigurationInformation
 *   0x5703  AccessoryWiFiConfigurationInformation
 */
object Iap2ControlSession {
    const val SOM_0: Byte = 0x40
    const val SOM_1: Byte = 0x40

    object Msg {
        // Identification (reference/iAP2 device-side flow)
        const val START_IDENTIFICATION = 0x1D00
        const val IDENTIFICATION_INFORMATION = 0x1D01
        const val IDENTIFICATION_ACCEPTED = 0x1D02
        const val IDENTIFICATION_REJECTED = 0x1D03

        // External Accessory Protocol messages.
        const val START_EA_SESSION = 0xEA00
        const val STOP_EA_SESSION = 0xEA01
        const val EA_SESSION_STATUS = 0xEA03

        // MFi authentication. Message order follows reference's device verifier
        // flow; only the 0xAA02 payload is supplied by the extracted OkCar code.
        // 0xAA00 = RequestAccessoryAuthenticationCertificate (phone -> car)
        //          phone asks car to send its MFi cert
        // 0xAA01 = AccessoryAuthenticationCertificate (car -> phone)
        //          car responds with its X.509 cert
        // 0xAA02 = RequestAccessoryAuthenticationChallenge (phone -> car)
        //          phone sends a 20-byte challenge for car to sign
        // 0xAA03 = AccessoryAuthenticationResponse (car -> phone)
        //          car returns SHA1/SHA256 signature of challenge
        // 0xAA04 = AuthenticationFailed (phone -> car)
        // 0xAA05 = AuthenticationSucceeded (phone -> car)
        const val MFI_REQUEST_CERTIFICATE = 0xAA00    // Phone requests car's cert
        const val MFI_AUTH_CERTIFICATE = 0xAA01       // Car sends its MFi cert
        const val MFI_AUTH_CHALLENGE = 0xAA02         // Phone sends Okcar hash/challenge
        const val MFI_AUTH_RESPONSE = 0xAA03          // Car returns auth response
        const val MFI_AUTH_FAILED = 0xAA04
        const val MFI_AUTH_SUCCEEDED = 0xAA05         // Phone tells car: auth OK

        // Backward-compat aliases (kept until call sites are migrated)
        const val MFI_REQUEST_CHALLENGE = MFI_AUTH_CHALLENGE
        const val MFI_AUTH_CERT_RESPONSE = MFI_AUTH_CHALLENGE
        const val MFI_AUTH_STATUS = 0xAA05            // alias for MFI_AUTH_SUCCEEDED

        // Wireless CarPlay status/transport notifications.
        const val WIRELESS_CARPLAY_AVAILABLE = 0x4E0D
        const val DEVICE_TRANSPORT_IDENTIFIER_NOTIFICATION = 0x4E0E

        // WiFi handoff
        const val REQUEST_WIFI_INFORMATION = 0x5702
        const val WIFI_INFORMATION = 0x5703

        // Phone announces availability; receiver answers with CarPlayStartSession.
        const val CARPLAY_AVAILABILITY = 0x4300

        // CarPlay AirPlay-start: response to CarPlayAvailability.
        // Observed params (BMW 38:2A:8B:18:33:F7):
        //   id=1 (nested group): ssid 0, passphrase 1, channel 2,
        //                        repeated IP strings 3, security 4
        //   id=2 (u32 BE):     AirPlay TCP port (0x1B58 = 7000)
        //   id=3 (string):     device identifier (not a BSSID)
        //   id=4 (string):     receiver public key (hex)
        //   id=5 (string):     source version
        const val CARPLAY_AIRPLAY_START = 0x4301
    }

    /** Build a TLV control message with the given message id and parameter blob. */
    fun build(messageId: Int, params: ByteArray = ByteArray(0)): ByteArray {
        val total = 6 + params.size
        val out = ByteArray(total)
        out[0] = SOM_0
        out[1] = SOM_1
        out[2] = ((total ushr 8) and 0xFF).toByte()
        out[3] = (total and 0xFF).toByte()
        out[4] = ((messageId ushr 8) and 0xFF).toByte()
        out[5] = (messageId and 0xFF).toByte()
        System.arraycopy(params, 0, out, 6, params.size)
        return out
    }

    /**
     * Build a single parameter blob (length, id, raw value).
     * Verified against reference/iap2 control_session_message/__init__.py:
     *   CSM_PARAM_STRUCT = Struct(">HH")  -> length first, param_id second
     */
    fun param(id: Int, value: ByteArray): ByteArray {
        val total = 4 + value.size
        val out = ByteArray(total)
        out[0] = ((total ushr 8) and 0xFF).toByte()  // length high
        out[1] = (total and 0xFF).toByte()           // length low
        out[2] = ((id ushr 8) and 0xFF).toByte()     // id high
        out[3] = (id and 0xFF).toByte()              // id low
        System.arraycopy(value, 0, out, 4, value.size)
        return out
    }

    /** Concatenate parameter blobs. */
    fun params(vararg blobs: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (b in blobs) out.write(b)
        return out.toByteArray()
    }

    data class Parameter(val id: Int, val value: ByteArray)

    fun parseParams(bytes: ByteArray): List<Parameter> {
        val out = mutableListOf<Parameter>()
        var offset = 0
        while (offset < bytes.size) {
            if (offset + 4 > bytes.size) return out
            val len = ((bytes[offset].toInt() and 0xFF) shl 8) or
                (bytes[offset + 1].toInt() and 0xFF)
            val id = ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
            if (len < 4 || offset + len > bytes.size) return out
            out += Parameter(id, bytes.copyOfRange(offset + 4, offset + len))
            offset += len
        }
        return out
    }

    fun firstParamValue(bytes: ByteArray): ByteArray =
        parseParams(bytes).firstOrNull()?.value ?: bytes

    /** Parse a control message into (messageId, raw-params-blob). null if malformed. */
    fun parse(bytes: ByteArray): Pair<Int, ByteArray>? {
        if (bytes.size < 6) return null
        if (bytes[0] != SOM_0 || bytes[1] != SOM_1) return null
        val len = ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
        if (len != bytes.size) return null
        val id = ((bytes[4].toInt() and 0xFF) shl 8) or (bytes[5].toInt() and 0xFF)
        return id to bytes.copyOfRange(6, bytes.size)
    }
}

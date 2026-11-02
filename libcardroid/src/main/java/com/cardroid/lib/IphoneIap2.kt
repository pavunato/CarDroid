package com.cardroid.lib

import java.util.UUID

/**
 * iPhone-side iAP2 behaviour a real phone shows after authentication: a
 * post-auth status burst and replies to the accessory's subscription requests.
 *
 * Without these the head unit only ever sees its requests ACKed.  Formats and
 * the raw constants come from CarDeck's Frida captures of a real iPhone talking
 * to an Audi MMI (VirtualIap2Link MIB3 path); the raw constants are kept byte
 * for byte because their field layouts are only known from those captures.
 */
object IphoneIap2 {
    fun wirelessAvailability(): ByteArray = Iap2ControlSession.build(0x4E0D, Iap2ControlSession.param(0, byteArrayOf(1)))

    /** Omit the notification when Android cannot provide the actual transport identifier. */
    fun bluetoothTransportIdentifier(address: String?): ByteArray? {
        val valid = address?.takeIf { Regex("(?i)^(?:[0-9a-f]{2}:){5}[0-9a-f]{2}$").matches(it) }
            ?.takeUnless { it.equals("02:00:00:00:00:00", ignoreCase = true) } ?: return null
        return Iap2ControlSession.build(0x4E0E, Iap2ControlSession.param(0, (valid.uppercase() + "\u0000").toByteArray()))
    }
    const val WIRELESS_SESSION_ID = 0x4E0C
    const val DEVICE_TIME = 0x4E0B
    const val DEVICE_LANGUAGE = 0x4E0A
    const val DEVICE_INFORMATION_UPDATE = 0x4E09
    const val AUX_STATUS_NOTIFICATION = 0xA100
    const val START_NOW_PLAYING_UPDATES = 0x5000
    const val START_CALL_STATE_UPDATES = 0x4154
    const val CALL_STATE_UPDATE = 0x4155
    const val START_COMMUNICATIONS_UPDATES = 0x4157
    const val START_LIST_UPDATES = 0x4170
    const val LIST_UPDATE = 0x4171

    /** 0xFFFA StartLocationInformation (captured). */
    val START_LOCATION_INFORMATION: ByteArray =
        hex("40400032fffa00080000000000140004000100088001000000fa0004000200088002000000fa000400040008800400000014")

    /** 0xFFF0 GPRMCDataStatusValuesNotification (captured). */
    val GPRMC_DATA_STATUS: ByteArray = hex("4040000efff00004000000040001")

    /** 0x5001 NowPlayingUpdate, idle state (captured). */
    val NOW_PLAYING_UPDATE_IDLE: ByteArray = hex(
        "404000555001002a00000005000100000800040000000000050006000005000c0000" +
            "0500100000050012000005001a8000250001000500000000080001000000000005" +
            "000500000500060000050007000005001000",
    )

    /** 0x4158 CommunicationsUpdate, idle state (captured). */
    val COMMUNICATIONS_UPDATE_IDLE: ByteArray = hex(
        "404000644158000500000100050001060005000200000e0004e697a053494de58da1" +
            "00000500050100050006010005000700000500080000050009000005000a000005" +
            "000b000005000c010005000d000005000e000005000f0000050010000005001100",
    )

    /** Messages a real iPhone sends right after AuthenticationSucceeded (0xAA05). */
    fun postAuthBurst(
        nowMillis: Long,
        tzOffsetMinutes: Int,
        daylight: Boolean,
        deviceName: String = IPhoneIdentity.DEVICE_NAME,
        language: String = "en",
        sessionId: String = UUID.randomUUID().toString().uppercase(),
    ): List<ByteArray> = listOf(
        Iap2ControlSession.build(WIRELESS_SESSION_ID, Iap2ControlSession.param(0, sessionId.toByteArray(Charsets.UTF_8))),
        Iap2ControlSession.build(AUX_STATUS_NOTIFICATION, Iap2ControlSession.param(0x0006, ByteArray(0))),
        Iap2ControlSession.build(DEVICE_LANGUAGE, Iap2ControlSession.param(0, language.toByteArray(Charsets.UTF_8))),
        Iap2ControlSession.build(DEVICE_TIME, deviceTimeParams(nowMillis, tzOffsetMinutes, daylight)),
        Iap2ControlSession.build(DEVICE_INFORMATION_UPDATE, Iap2ControlSession.param(0, (deviceName.substringBefore('\u0000') + "\u0000").toByteArray(Charsets.UTF_8))),
        START_LOCATION_INFORMATION.copyOf(),
        GPRMC_DATA_STATUS.copyOf(),
    )

    /** Empty-state reply to an accessory subscription, or null when we have none. */
    fun replyFor(messageId: Int): ByteArray? = when (messageId) {
        START_NOW_PLAYING_UPDATES -> NOW_PLAYING_UPDATE_IDLE.copyOf()
        START_COMMUNICATIONS_UPDATES -> COMMUNICATIONS_UPDATE_IDLE.copyOf()
        START_CALL_STATE_UPDATES -> Iap2ControlSession.build(CALL_STATE_UPDATE, Iap2ControlSession.param(0x0002, byteArrayOf(0)))
        START_LIST_UPDATES -> Iap2ControlSession.build(
            LIST_UPDATE,
            Iap2ControlSession.param(0x0000, byteArrayOf(0)) +
                Iap2ControlSession.param(0x0002, byteArrayOf(0, 0)) +
                Iap2ControlSession.param(0x0005, byteArrayOf(0)) +
                Iap2ControlSession.param(0x0007, byteArrayOf(0, 0)),
        )
        else -> null
    }

    /** 0x4E0B: u64 Unix seconds, s16 timezone offset (minutes), u8 daylight saving; all big-endian. */
    fun deviceTimeParams(nowMillis: Long, tzOffsetMinutes: Int, daylight: Boolean): ByteArray {
        val seconds = nowMillis / 1000L
        val secs = ByteArray(8) { i -> (seconds ushr ((7 - i) * 8)).toByte() }
        val tz = byteArrayOf((tzOffsetMinutes shr 8).toByte(), tzOffsetMinutes.toByte())
        return Iap2ControlSession.param(0, secs) + Iap2ControlSession.param(1, tz) +
            Iap2ControlSession.param(2, byteArrayOf(if (daylight) 1 else 0))
    }

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}

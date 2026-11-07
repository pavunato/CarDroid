package com.cardroid.lib.airplay

/**
 * Initial AirPlay session SETUP plist, copied from a real iPhone18,4 / iOS 26.7.1 / AirPlay
 * 960.13.1 (raw DiPlay trace, 07-11-26): same keys, value types, values and key order.
 *
 * No eiv/ekey/et: media keys come from the pair-verify shared secret via HKDF
 * (CarPlayMediaWire / CarPlayPairing), never from the SETUP plist.
 */
object AirPlaySessionSetup {
    /** The iPhone's `features` list, in its order. */
    val FEATURES: List<String> = listOf(
        "uiContext", "viewAreas", "cornerMasks", "focusTransfer", "h.264Level5.1", "mainBuffered",
        "altScreen", "enhancedSiri", "hevc", "fileTransfer", "uiSync", "vehicleStateProtocol",
        "sessionManagement", "videoPlayback", "logTransfer", "iAPChannel",
    )

    fun build(p: AirPlayParams): AirPlayBplist.Value = AirPlayBplist.dict(
        "statsCollectionEnabled" to AirPlayBplist.bool(true),
        "updateSessionRequest" to AirPlayBplist.bool(false),
        "features" to AirPlayBplist.array(*FEATURES.map { AirPlayBplist.str(it) }.toTypedArray()),
        "osName" to AirPlayBplist.str(p.osName),
        "sessionUUID" to AirPlayBplist.str(p.setupSessionUuid),
        "keepAliveLowPower" to AirPlayBplist.bool(true),
        "osBuildVersion" to AirPlayBplist.str(p.osBuildVersion),
        "timingPort" to AirPlayBplist.int(p.timingPort),
        "sourceVersion" to AirPlayBplist.str(p.sourceVersion),
        "osVersion" to AirPlayBplist.str(p.osVersion),
        "sessionCorrelationUUID" to AirPlayBplist.str(p.sessionCorrelationUuid),
        "deviceID" to AirPlayBplist.str(p.deviceId),
        "model" to AirPlayBplist.str(p.model),
        "name" to AirPlayBplist.str(p.name),
        "macAddress" to AirPlayBplist.str(p.macAddress),
    )
}

package com.cardroid.lib.airplay

/**
 * Initial AirPlay session SETUP plist, matching the key set of a real iPhone
 * (benchmark capture: deviceID, features, keepAliveLowPower, macAddress, model,
 * name, osBuildVersion, osName, osVersion, sessionCorrelationUUID, sessionUUID,
 * sourceVersion, statsCollectionEnabled, timingPort, updateSessionRequest).
 *
 * No eiv/ekey/et: media keys come from the pair-verify shared secret via HKDF
 * (CarPlayMediaWire / CarPlayPairing), never from the SETUP plist.
 */
object AirPlaySessionSetup {
    /**
     * `features` is an array of feature-name strings the sender supports. Only
     * "iAPChannel" is claimed: CarDroid opens the send-only type-130 iAP2 tunnel
     * (IapTunnelStream). Deliberately omitted: "cornerMasks" (no corner-mask
     * rendering), "videoPlayback" (no in-car video playback), "mainBuffered"
     * (no buffered type-102 main audio) and "viewAreas" (no view-area handling).
     */
    val FEATURES: List<String> = listOf("iAPChannel")

    fun build(p: AirPlayParams): AirPlayBplist.Value = AirPlayBplist.dict(
        "deviceID" to AirPlayBplist.str(p.deviceId),
        "features" to AirPlayBplist.array(*FEATURES.map { AirPlayBplist.str(it) }.toTypedArray()),
        "keepAliveLowPower" to AirPlayBplist.bool(true),
        "macAddress" to AirPlayBplist.str(p.macAddress),
        "model" to AirPlayBplist.str(p.model),
        "name" to AirPlayBplist.str(p.name),
        "osBuildVersion" to AirPlayBplist.str(p.osBuildVersion),
        "osName" to AirPlayBplist.str(p.osName),
        "osVersion" to AirPlayBplist.str(p.osVersion),
        "sessionCorrelationUUID" to AirPlayBplist.str(p.sessionCorrelationUuid),
        "sessionUUID" to AirPlayBplist.str(p.setupSessionUuid),
        "sourceVersion" to AirPlayBplist.str(p.sourceVersion),
        "statsCollectionEnabled" to AirPlayBplist.bool(false),
        "timingPort" to AirPlayBplist.int(p.timingPort),
        "updateSessionRequest" to AirPlayBplist.dict(),
    )
}

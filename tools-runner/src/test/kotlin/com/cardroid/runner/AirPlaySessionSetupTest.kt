package com.cardroid.runner

import com.cardroid.lib.airplay.AirPlayBplist
import com.cardroid.lib.airplay.AirPlayParams
import com.cardroid.lib.airplay.AirPlaySessionSetup
import com.cardroid.lib.airplay.ReceiverPlist
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AirPlaySessionSetupTest {
    private fun decode(p: AirPlayParams) = ReceiverPlist.map(AirPlayBplist.encode(AirPlaySessionSetup.build(p)))
    private fun params() = AirPlayParams("h", 7000, "s").also { it.timingPort = 54321 }

    @Test fun keySetMatchesIphone() {
        val m = decode(params())
        assertEquals(
            setOf("deviceID", "features", "keepAliveLowPower", "macAddress", "model", "name", "osBuildVersion", "osName",
                "osVersion", "sessionCorrelationUUID", "sessionUUID", "sourceVersion", "statsCollectionEnabled",
                "timingPort", "updateSessionRequest"),
            m.keys,
        )
        listOf("eiv", "ekey", "et", "groupContainsGroupLeader", "groupUUID", "isMultiSelectAirPlay", "senderSupportsRelay")
            .forEach { assertFalse(it in m.keys, it) }
    }

    @Test fun valueTypes() {
        val m = decode(params())
        listOf("deviceID", "macAddress", "model", "name", "osBuildVersion", "osName", "osVersion",
            "sessionCorrelationUUID", "sessionUUID", "sourceVersion").forEach { assertTrue(m[it] is String, it) }
        assertEquals(AirPlaySessionSetup.FEATURES, m["features"])
        assertEquals(16, (m["features"] as List<*>).size)
        assertEquals(54321, (m["timingPort"] as Number).toInt())
        assertEquals(true, m["keepAliveLowPower"])
        assertEquals(true, m["statsCollectionEnabled"])
        assertEquals(false, m["updateSessionRequest"])
        assertEquals("23H30", m["osBuildVersion"])
        val uuid = m["sessionUUID"] as String
        assertEquals(uuid.uppercase(), uuid)
    }

    @Test fun identityIsConsistentModernAndCorrelationIsFresh() {
        val a = params(); val b = params()
        assertEquals("iPhone18,4", a.model)
        assertTrue(a.osVersion.startsWith("26."))
        assertTrue(a.sourceVersion.startsWith("960."))
        assertNotEquals(a.sessionCorrelationUuid, b.sessionCorrelationUuid)
        assertNotEquals(a.sessionCorrelationUuid, a.setupSessionUuid)
    }
}

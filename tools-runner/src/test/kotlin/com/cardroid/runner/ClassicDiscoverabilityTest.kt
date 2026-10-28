package com.cardroid.runner

import com.cardroid.lib.ClassicDiscoverability
import kotlin.test.*

class ClassicDiscoverabilityTest {
    @Test fun consentDoesNotProveDiscoverability() {
        val d = ClassicDiscoverability()
        d.requestResult(120, 1000)
        assertEquals(ClassicDiscoverability.Mode.UNKNOWN, d.state.value.mode)
        d.observed(ClassicDiscoverability.Mode.DISCOVERABLE)
        assertEquals(119, d.state.value.remainingSeconds(2000))
        assertEquals(ClassicDiscoverability.Mode.DISCOVERABLE, d.state.value.mode)
        assertEquals(0, d.state.value.remainingSeconds(999999))
        assertEquals(ClassicDiscoverability.Mode.DISCOVERABLE, d.state.value.mode)
        d.observed(ClassicDiscoverability.Mode.PAIRED_ONLY)
        assertNull(d.state.value.remainingSeconds(3000))
    }
    @Test fun cancelAndPermissionFailureAreNotProtocolFailures() {
        val d = ClassicDiscoverability()
        d.requestResult(0, 0)
        assertEquals("Discoverability request canceled or denied", d.state.value.note)
        d.unavailable()
        assertEquals(ClassicDiscoverability.Mode.UNKNOWN, d.state.value.mode)
        assertEquals("Check Bluetooth / Nearby devices permissions", d.state.value.note)
    }
}

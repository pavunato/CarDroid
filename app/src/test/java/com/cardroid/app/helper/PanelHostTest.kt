package com.cardroid.app.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelHostTest {
    @Test fun registryTracksOwnershipPerDisplay() {
        val r = PanelRegistry<String>()
        r.put(61, "com.google.android.youtube", "yt")
        r.put(62, "com.google.android.apps.maps", "maps")
        assertEquals(mapOf(61 to "com.google.android.youtube", 62 to "com.google.android.apps.maps"), r.packages())
        assertEquals("yt", r.remove(61))
        assertNull(r.get(61))
        assertEquals(mapOf(62 to "com.google.android.apps.maps"), r.packages())
    }
    @Test fun clearReturnsEveryHandleOnce() {
        val r = PanelRegistry<String>()
        r.put(61, "a.b", "x"); r.put(62, "c.d", "y")
        assertEquals(listOf("x", "y"), r.clear())
        assertTrue(r.clear().isEmpty())
        assertTrue(r.packages().isEmpty())
    }
    @Test fun panelNameMatchesTheAppOwnedConvention() {
        assertEquals("CarDroid Panel: com.google.android.youtube", PanelHostProtocol.panelName("com.google.android.youtube"))
    }
    @Test fun acceptsOnlyPlainPackageNames() {
        assertTrue(PanelHostProtocol.isPackageName("com.google.android.youtube"))
        assertFalse(PanelHostProtocol.isPackageName(""))
        assertFalse(PanelHostProtocol.isPackageName("youtube"))
        assertFalse(PanelHostProtocol.isPackageName("com.evil\", displayId 1"))
        assertFalse(PanelHostProtocol.isPackageName("com..x"))
    }
}

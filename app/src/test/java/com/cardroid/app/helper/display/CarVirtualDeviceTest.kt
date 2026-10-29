package com.cardroid.app.helper.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarVirtualDeviceTest {
    private val list = """
        Max ID: 2
        Association ID | Package Name | Mac Address
        1 | com.google.android.projection.gearhead | null
        2 | com.android.shell | 02:ca:4d:00:00:01
    """.trimIndent()

    @Test fun findsShellAssociationByMac() {
        assertEquals(2, CarVirtualDevice.associationId(list, "com.android.shell", CarVirtualDevice.ASSOCIATION_MAC))
    }
    @Test fun matchesMacCaseInsensitively() {
        assertEquals(2, CarVirtualDevice.associationId(list, "com.android.shell", "02:CA:4D:00:00:01"))
    }
    @Test fun ignoresOtherPackagesAndMacs() {
        assertNull(CarVirtualDevice.associationId(list, "com.cardroid.app", CarVirtualDevice.ASSOCIATION_MAC))
        assertNull(CarVirtualDevice.associationId(list, "com.android.shell", "02:ca:4d:00:00:02"))
    }
    @Test fun headerAndEmptyListHaveNoAssociation() {
        assertNull(CarVirtualDevice.associationId("Max ID: 0\nAssociation ID | Package Name | Mac Address\n", "com.android.shell", CarVirtualDevice.ASSOCIATION_MAC))
    }
}

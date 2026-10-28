package com.cardroid.app.car

import com.cardroid.app.helper.HelperClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class EmbeddedAppStateTest {
    @After fun reset() { CarDisplayState.update(HelperClient.State.NotRunning) }

    @Test fun missingHelperDoesNotSelectAnApp() {
        CarDisplayState.update(HelperClient.State.NotRunning)
        assertFalse(CarDisplayState.openApp("maps/Main"))
        assertNull(CarDisplayState.appTarget.value)
    }

    @Test fun homeClearsSelectionButRetainsRecentAppHistory() {
        CarDisplayState.update(HelperClient.State.DisplayActive(103, 42))
        assertTrue(CarDisplayState.openApp("music/Main"))
        CarDisplayState.showDashboard()
        assertNull(CarDisplayState.appTarget.value)
        assertEquals("music/Main", CarDisplayState.recentAppTargets.value.first())
    }

    @Test fun repeatedSelectionsAreUniqueAndMostRecentFirst() {
        CarDisplayState.update(HelperClient.State.DisplayActive(104, 42))
        CarDisplayState.openApp("music/Main")
        CarDisplayState.openApp("maps/Main")
        CarDisplayState.openApp("music/Main")
        val history = CarDisplayState.recentAppTargets.value
        assertEquals(listOf("music/Main", "maps/Main"), history.take(2))
        assertEquals(1, history.count { it == "music/Main" })
    }

    @Test fun replacementDisplayStartsAtDashboardInsteadOfReopeningStaleContent() {
        CarDisplayState.update(HelperClient.State.DisplayActive(101, 42))
        assertTrue(CarDisplayState.openApp("maps/Main"))
        assertEquals("maps/Main", CarDisplayState.appTarget.value)
        CarDisplayState.update(HelperClient.State.DisplayActive(102, 42))
        assertNull(CarDisplayState.appTarget.value)
    }
}

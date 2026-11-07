package com.cardroid.runner

import com.cardroid.lib.CarWakeLoop
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CarWakeLoopTest {
    private val car = "AA:BB:CC:DD:6A:B7"

    @Test
    fun wakesRememberedCarOnlyWhileIdle() {
        var idle = true
        val poked = mutableListOf<String>()
        val loop = CarWakeLoop(target = { car }, idle = { idle }, poke = { poked += it; true })
        assertEquals(car, loop.tick())
        idle = false
        assertNull(loop.tick())
        assertEquals(listOf(car), poked)
    }

    @Test
    fun skipsWithoutRememberedCar() {
        var calls = 0
        val loop = CarWakeLoop(target = { null }, idle = { true }, poke = { calls++; true })
        assertNull(loop.tick())
        assertEquals(0, calls)
    }

    @Test
    fun failedOrThrowingPokeIsContainedAndLoggedOnce() {
        val logs = mutableListOf<String>()
        val loop = CarWakeLoop(target = { car }, idle = { true }, poke = { error("radio off") }, log = { logs += it })
        assertNull(loop.tick())
        assertNull(loop.tick())
        assertEquals(1, logs.size)
        assertTrue(logs.single().contains("failed"))
        assertTrue(!logs.single().contains("AA:BB"), "full address must not be logged")
    }
}

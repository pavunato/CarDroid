package com.cardroid.runner

import com.cardroid.lib.airplay.AirPlayBplist
import com.cardroid.lib.airplay.CarPlayResourceArbiter
import com.cardroid.lib.airplay.ReceiverPlist
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CarPlayResourceArbiterTest {
    private fun changeModes(
        resources: List<Map<String, Any?>> = emptyList(),
        appStates: List<Map<String, Any?>> = emptyList(),
    ): Map<String, Any?> = mapOf(
        "type" to "changeModes",
        "params" to mapOf(
            "resources" to resources,
            "appStates" to appStates,
        ),
    )

    @Test
    fun nativeHomeTakeOnlyMovesScreen() {
        val arbiter = CarPlayResourceArbiter()
        val result = arbiter.applyChangeModes(
            changeModes(
                resources = listOf(
                    mapOf(
                        "resourceID" to 1,
                        "transferType" to CarPlayResourceArbiter.TRANSFER_TAKE,
                        "transferPriority" to 500,
                        "takeConstraint" to 500,
                        "borrowConstraint" to 100,
                    ),
                ),
            ),
        )

        assertTrue(result.changed)
        assertEquals(0, result.ignored)
        assertEquals(2, result.snapshot.resources.first { it.id == 1 }.entity)
        assertEquals(2, result.snapshot.resources.first { it.id == 1 }.permanentEntity)
        assertEquals(1, result.snapshot.resources.first { it.id == 2 }.entity)
        assertEquals(1, result.snapshot.resources.first { it.id == 2 }.permanentEntity)
    }

    @Test
    fun transferTypesMatchAppleAirPlayCommon() {
        // kAirPlayTransferType_Take/Untake/Borrow/Unborrow in AirPlayCommon.h.
        assertEquals(1, CarPlayResourceArbiter.TRANSFER_TAKE)
        assertEquals(2, CarPlayResourceArbiter.TRANSFER_UNTAKE)
        assertEquals(3, CarPlayResourceArbiter.TRANSFER_BORROW)
        assertEquals(4, CarPlayResourceArbiter.TRANSFER_UNBORROW)
    }

    @Test
    fun takeThenUntakeReturnsScreenToPhone() {
        val arbiter = CarPlayResourceArbiter()
        arbiter.applyChangeModes(
            changeModes(resources = listOf(mapOf("resourceID" to 1, "transferType" to 1, "transferPriority" to 500))),
        )
        assertEquals(2, arbiter.snapshot().resources.first { it.id == 1 }.entity)

        // AirPlayReceiverSessionUntakeScreen sends no priority or constraints.
        val result = arbiter.applyChangeModes(
            changeModes(resources = listOf(mapOf("resourceID" to 1, "transferType" to 2))),
        )
        assertEquals(0, result.ignored)
        val screen = result.snapshot.resources.first { it.id == 1 }
        assertEquals(1, screen.entity)
        assertEquals(1, screen.permanentEntity)
    }

    @Test
    fun borrowThenUnborrowRestoresPermanentOwner() {
        val arbiter = CarPlayResourceArbiter()
        // AirPlayReceiverSessionBorrowScreen: priority + unborrowConstraint, no borrowID.
        val borrow = arbiter.applyChangeModes(
            changeModes(
                resources = listOf(
                    mapOf("resourceID" to 1, "transferType" to 3, "transferPriority" to 500, "unborrowConstraint" to 100),
                ),
            ),
        )
        assertEquals(0, borrow.ignored)
        val borrowed = borrow.snapshot.resources.first { it.id == 1 }
        assertEquals(2, borrowed.entity)
        assertEquals(1, borrowed.permanentEntity)

        val returned = arbiter.applyChangeModes(
            changeModes(resources = listOf(mapOf("resourceID" to 1, "transferType" to 4))),
        )
        assertEquals(0, returned.ignored)
        val restored = returned.snapshot.resources.first { it.id == 1 }
        assertEquals(1, restored.entity)
        assertEquals(1, restored.permanentEntity)
        assertFalse(restored.borrowed)
    }

    @Test
    fun homeThenRequestUIReturnsScreenToPhone() {
        val arbiter = CarPlayResourceArbiter()
        // Real LYNK Home press (08:51:25): take screen, priority 500, takeConstraint 500, borrowConstraint 100.
        arbiter.applyChangeModes(
            changeModes(
                resources = listOf(
                    mapOf("resourceID" to 1, "transferType" to 1, "transferPriority" to 500, "takeConstraint" to 500, "borrowConstraint" to 100),
                ),
            ),
        )
        assertEquals(2, arbiter.snapshot().resources.first { it.id == 1 }.entity)
        val before = arbiter.reassertToken()

        // Tapping the CarPlay icon sends requestUI; the phone takes the screen back.
        val result = arbiter.controllerTake(CarPlayResourceArbiter.RESOURCE_SCREEN, "requestUI")
        assertTrue(result.changed)
        val screen = result.snapshot.resources.first { it.id == 1 }
        assertEquals(1, screen.entity)
        assertEquals(1, screen.permanentEntity)
        assertEquals(1, result.snapshot.resources.first { it.id == 2 }.entity) // audio untouched
        assertFalse(arbiter.isCurrent(before))
        val map = ReceiverPlist.map(AirPlayBplist.encode(arbiter.modesChangedCommand()))
        val resources = (map["params"] as Map<*, *>)["resources"] as List<*>
        assertEquals(1L, (resources.first { (it as Map<*, *>)["resourceID"] == 1L } as Map<*, *>)["entity"])
    }

    @Test
    fun invalidPriorityIsIgnored() {
        val arbiter = CarPlayResourceArbiter()
        val result = arbiter.applyChangeModes(
            changeModes(resources = listOf(mapOf("resourceID" to 1, "transferType" to 1, "transferPriority" to 7))),
        )
        assertEquals(1, result.ignored)
        assertEquals(1, result.snapshot.resources.first { it.id == 1 }.entity)
    }

    @Test
    fun appStateRequestIsReflectedAsAccessoryOwner() {
        val arbiter = CarPlayResourceArbiter()
        arbiter.applyChangeModes(
            changeModes(appStates = listOf(mapOf("appStateID" to 3, "state" to true))),
        )

        val map = ReceiverPlist.map(AirPlayBplist.encode(arbiter.modesChangedCommand()))
        val params = map["params"] as Map<*, *>
        val states = params["appStates"] as List<*>
        assertEquals(listOf(2L, 1L, 3L), states.map { (it as Map<*, *>) ["appStateID"] })
        assertEquals(2L, (states[2] as Map<*, *>) ["entity"])
        assertEquals(-1L, (states[1] as Map<*, *>) ["speechMode"])
    }

    @Test
    fun unknownItemsAreIgnoredAndLogged() {
        val logs = mutableListOf<String>()
        val arbiter = CarPlayResourceArbiter(logs::add)
        val result = arbiter.applyChangeModes(
            changeModes(
                resources = listOf(mapOf("resourceID" to 99, "transferType" to 1)),
                appStates = listOf(mapOf("appStateID" to 99, "state" to true)),
            ),
        )

        assertFalse(result.changed)
        assertEquals(2, result.ignored)
        assertTrue(logs.any { it.contains("unsupported appStateID") })
        assertTrue(logs.any { it.contains("unsupported resourceID") })
        assertEquals(1, result.snapshot.resources.first { it.id == 1 }.entity)
    }

    @Test
    fun receiverRequestInvalidatesDelayedPhoneReassertion() {
        val arbiter = CarPlayResourceArbiter()
        val before = arbiter.reassertToken()
        arbiter.applyChangeModes(
            changeModes(
                resources = listOf(
                    mapOf(
                        "resourceID" to 1,
                        "transferType" to CarPlayResourceArbiter.TRANSFER_TAKE,
                        "transferPriority" to 500,
                        "takeConstraint" to 500,
                        "borrowConstraint" to 100,
                    ),
                ),
            ),
        )
        assertFalse(arbiter.isCurrent(before))
        assertEquals(2, arbiter.snapshot().resources.first { it.id == 1 }.entity)
    }

    @Test
    fun disableBluetoothMatchesRealIphoneShape() {
        // AirLib (receiver for real iPhones) reads type=disableBluetooth, params.deviceID (string).
        val map = ReceiverPlist.map(AirPlayBplist.encode(
            com.cardroid.lib.airplay.AirPlayHandshakeOrchestrator.disableBluetoothCommand("64:7B:CE:A5:6A:B7")))
        assertEquals("disableBluetooth", map["type"])
        assertEquals("64:7B:CE:A5:6A:B7", (map["params"] as Map<*, *>)["deviceID"])
    }

    @Test
    fun audioHandoverStartsCarOwnedThenPhoneTakes() {
        // LYNK /info initialPermanentEntity: mainAudio owned by the accessory; iPhone takes it when music starts.
        val arbiter = CarPlayResourceArbiter()
        arbiter.startAccessoryOwned(CarPlayResourceArbiter.RESOURCE_AUDIO)
        val start = arbiter.snapshot()
        assertEquals(2, start.resources.first { it.id == 2 }.entity)
        assertEquals(1, start.resources.first { it.id == 1 }.entity) // screen still phone
        val token = arbiter.reassertToken()
        val take = arbiter.controllerTake(CarPlayResourceArbiter.RESOURCE_AUDIO, "phone starts media")
        assertTrue(take.changed)
        assertEquals(1, take.snapshot.resources.first { it.id == 2 }.entity)
        assertEquals(1, take.snapshot.resources.first { it.id == 2 }.permanentEntity)
        assertFalse(arbiter.isCurrent(token)) // stale post-auth reassert cannot undo the take
    }
    @Test fun screenRefreshSurvivesUnrelatedModeEventsButNotScreenHandover() {
        val arbiter = CarPlayResourceArbiter()
        arbiter.controllerTake(1, "requestUI")
        val token = arbiter.screenReturnToken()
        arbiter.applyChangeModes(changeModes(resources = listOf(mapOf("resourceID" to 2, "transferType" to 1))))
        arbiter.applyChangeModes(changeModes(appStates = listOf(mapOf("appStateID" to 3, "state" to true))))
        arbiter.applyChangeModes(mapOf("type" to "changeModes")) // malformed
        arbiter.applyChangeModes(changeModes(resources = listOf(mapOf("resourceID" to 1, "transferType" to 2)))) // no-op
        assertTrue(arbiter.isScreenReturnCurrent(token))
        arbiter.applyChangeModes(changeModes(resources = listOf(mapOf("resourceID" to 1, "transferType" to 1))))
        assertFalse(arbiter.isScreenReturnCurrent(token))
        arbiter.controllerTake(1, "later return")
        assertFalse(arbiter.isScreenReturnCurrent(token)) // old return must remain stale
        assertTrue(arbiter.isScreenReturnCurrent(arbiter.screenReturnToken()))
        arbiter.reset()
        assertFalse(arbiter.isScreenReturnCurrent(token))
    }

    @Test
    fun modesChangedIsByteIdenticalToRealIphone() {
        // Raw DiPlay trace (log.tag.DiPlayTrace VERBOSE) of a real iPhone18,4 / iOS 26.7.1, /command cseq 14:
        // modesChanged with both resources phone-owned and no app states, 287 B.
        val iphone = ("62706c6973743030d201020304547479706556706172616d735c6d6f6465734368616e676564d2050607155961707053" +
            "7461746573597265736f7572636573a3080d12d2090a0b0c56656e746974795a6170705374617465494410001002d309" +
            "0e0f0b10115a7370656563684d6f64655a6170705374617465494413ffffffffffffffff1001d209130b145a61707053" +
            "7461746549441003a21619d31709180c11115a7265736f7572636549445f100f7065726d616e656e74456e74697479d3" +
            "1a091b1111115a7265736f7572636549445f100f7065726d616e656e74456e74697479080d1219262b353f43484f5a5c" +
            "5e65707b84868b96989ba2adbfc6d10000000000000101000000000000001c000000000000000000000000000000e3").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val arbiter = CarPlayResourceArbiter()
        arbiter.controllerTake(CarPlayResourceArbiter.RESOURCE_SCREEN, "test")
        arbiter.controllerTake(CarPlayResourceArbiter.RESOURCE_AUDIO, "test")
        val ours = AirPlayBplist.encode(arbiter.modesChangedCommand())
        assertEquals(iphone.toList(), ours.toList())

        val bt = AirPlayBplist.encode(
            com.cardroid.lib.airplay.AirPlayHandshakeOrchestrator.disableBluetoothCommand("64:7B:CE:A5:6A:B7"))
        assertEquals(setOf("deviceID"), (ReceiverPlist.map(bt)["params"] as Map<*, *>).keys)
        assertEquals(115, bt.size)
    }
}

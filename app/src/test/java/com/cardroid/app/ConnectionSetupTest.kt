package com.cardroid.app

import com.cardroid.app.helper.HelperClient
import com.cardroid.app.shizuku.ShizukuHelperStarter
import com.cardroid.lib.CarPlaySession
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionSetupTest {
    private fun step(
        shizuku: ShizukuHelperStarter.State = ShizukuHelperStarter.State.Ready,
        helper: HelperClient.State = HelperClient.State.NotRunning,
        session: CarPlaySession.State = CarPlaySession.State.Idle,
        missing: List<String> = emptyList(),
        bluetooth: Boolean = true,
    ) = nextStep(PermissionSnapshot(missing), bluetooth, session, shizuku, helper).kind

    @Test fun runtimeApprovalComesBeforeShizuku() {
        assertEquals(NextStep.Kind.Permissions, step(ShizukuHelperStarter.State.Unavailable, missing = listOf("permission")))
    }
    @Test fun bluetoothMustBeEnabledBeforeConnecting() {
        assertEquals(NextStep.Kind.Bluetooth, step(bluetooth = false))
    }
    @Test fun missingOrDeniedShizukuOffersSetupInsteadOfPermissionLoop() {
        listOf(ShizukuHelperStarter.State.Unavailable, ShizukuHelperStarter.State.Unsupported,
            ShizukuHelperStarter.State.PermissionDenied).forEach {
            assertEquals(NextStep.Kind.ShizukuOpen, step(it))
        }
    }
    @Test fun availableShizukuRequestsApproval() {
        assertEquals(NextStep.Kind.ShizukuAllow, step(ShizukuHelperStarter.State.NeedsPermission))
    }
    @Test fun adbHelperDoesNotRequireShizuku() {
        assertEquals(NextStep.Kind.Connect, step(ShizukuHelperStarter.State.Unavailable, HelperClient.State.Connected(123, 35)))
    }
    @Test fun shizukuErrorDoesNotBlockAnAlreadyConnectedHelper() {
        assertEquals(NextStep.Kind.Connect, step(ShizukuHelperStarter.State.Error("Unavailable"), HelperClient.State.Connected(123, 35)))
    }
    @Test fun outdatedHelperOffersRecovery() {
        assertEquals(NextStep.Kind.Helper, step(helper = HelperClient.State.Outdated))
    }
    @Test fun helperCanBePreparedDuringReceiverHandshake() {
        assertEquals(NextStep.Kind.Helper, step(session = CarPlaySession.State.LinkUp))
    }
    @Test fun startingHelperCannotBeStartedAgain() {
        assertEquals(NextStep.Kind.Preparing, step(ShizukuHelperStarter.State.Starting))
    }
    @Test fun preparedConnectionKeepsCancelAvailable() {
        assertEquals(NextStep.Kind.Connecting, step(helper = HelperClient.State.Connected(123, 35), session = CarPlaySession.State.LinkUp))
    }
    @Test fun listenerShowsWaitingInsteadOfOfferingConnectAgain() {
        val next = nextStep(PermissionSnapshot(emptyList()), true, CarPlaySession.State.Idle,
            ShizukuHelperStarter.State.Ready, HelperClient.State.Connected(123, 35), listening = true)
        assertEquals(NextStep.Kind.Available, next.kind)
        assertEquals("Waiting for car…", next.action)
    }
    @Test fun stoppedListenerOffersMakeAvailable() {
        val next = nextStep(PermissionSnapshot(emptyList()), true, CarPlaySession.State.Idle,
            ShizukuHelperStarter.State.Ready, HelperClient.State.NotRunning, listening = false)
        assertEquals(NextStep.Kind.Connect, next.kind)
        assertEquals("Make available", next.action)
    }
    @Test fun missingHelperWarnsThatVideoWillBeClosed() {
        for (h in listOf(HelperClient.State.NotRunning, HelperClient.State.Connecting, HelperClient.State.Outdated)) {
            val warning = helperWarning(h)
            org.junit.Assert.assertNotNull(warning)
            org.junit.Assert.assertTrue(warning!!.contains("video", ignoreCase = true))
        }
        org.junit.Assert.assertNull(helperWarning(HelperClient.State.Connected(1, 34)))
        org.junit.Assert.assertNull(helperWarning(HelperClient.State.DisplayActive(2, 1)))
    }
    @Test fun helperWarningIsShownNearConnect() {
        val ui = java.io.File("src/main/java/com/cardroid/app/MainActivity.kt").readText()
        val card = ui.indexOf("StepCard(\n")
        val warn = ui.indexOf("helperWarning(helper)")
        org.junit.Assert.assertTrue(card > 0 && warn > card && warn - card < 600)
    }
}

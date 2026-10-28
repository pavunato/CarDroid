package com.cardroid.runner

import com.cardroid.lib.ConnectionDiagnostics
import kotlin.test.*

class ConnectionDiagnosticsTest {
    private class FakeScheduler {
        val tasks = mutableListOf<Pair<Long, () -> Unit>>()
        val schedule: (Long, () -> Unit) -> Unit = { ms, task -> tasks += ms to task }
        fun runAll() { val t = tasks.toList(); tasks.clear(); t.forEach { it.second() } }
    }
    private val wifiConfig = com.cardroid.lib.CarPlaySession.State.WifiHandoffReceived("privateSSID")
    @Test fun wifiConfigStartsJoinWatchAndTimesOutBeforeReceiverGivesUp() {
        val clock = FakeScheduler(); val d = ConnectionDiagnostics(clock.schedule); val token = d.begin()
        d.reached(token, ConnectionDiagnostics.Stage.SOCKET)
        d.session(token, wifiConfig)
        assertEquals(ConnectionDiagnostics.WifiJoin.PENDING, d.state.value.wifiJoin)
        assertTrue(d.state.value.events.any { it.endsWith("WIFI_JOIN_PENDING") })
        assertEquals(1, clock.tasks.size)
        assertTrue(clock.tasks.single().first in 10_000L..20_000L, "must fire before the receiver's ~30 s give-up")
        d.reached(token, ConnectionDiagnostics.Stage.ENDPOINT)
        clock.runAll()
        assertEquals("WIFI_JOIN_TIMEOUT", d.state.value.lastError)
        assertEquals(ConnectionDiagnostics.WifiJoin.TIMEOUT, d.state.value.wifiJoin)
        assertTrue(d.state.value.nextStep.contains("did not join the receiver Wi-Fi"), d.state.value.nextStep)
        assertTrue(d.state.value.nextStep.contains("approve"))
        d.ended(token, "RFCOMM closed")
        assertEquals("WIFI_JOIN_TIMEOUT", d.state.value.lastError)
        assertFalse(d.report().contains("privateSSID"))
    }
    @Test fun wifiJoinWatchIsIgnoredAfterJoinOrNewAttempt() {
        val clock = FakeScheduler(); val d = ConnectionDiagnostics(clock.schedule); val token = d.begin()
        d.session(token, wifiConfig)
        d.reached(token, ConnectionDiagnostics.Stage.WIFI_JOINED)
        clock.runAll()
        assertNull(d.state.value.lastError)
        assertEquals(ConnectionDiagnostics.WifiJoin.JOINED, d.state.value.wifiJoin)
        d.session(token, wifiConfig)
        d.reached(token, ConnectionDiagnostics.Stage.SOCKET) // new receiver attempt
        clock.runAll()
        assertNull(d.state.value.lastError)
        d.session(token, wifiConfig)
        val next = d.begin() // new service generation
        clock.runAll()
        assertNull(d.state.value.lastError)
        assertTrue(next > token)
    }
    @Test fun wifiJoinFailureIsTypedAndStopsTheWatch() {
        val clock = FakeScheduler(); val d = ConnectionDiagnostics(clock.schedule); val token = d.begin()
        d.session(token, wifiConfig)
        d.failure(token, "WIFI_JOIN_FAILED")
        assertEquals("WIFI_JOIN_FAILED", d.state.value.lastError)
        assertEquals(ConnectionDiagnostics.WifiJoin.FAILED, d.state.value.wifiJoin)
        assertTrue(d.state.value.nextStep.contains("did not join the receiver Wi-Fi"))
        clock.runAll()
        assertEquals("WIFI_JOIN_FAILED", d.state.value.lastError)
    }
    @Test fun androidWifiFailureCallbacksReportJoinFailure() {
        val wifi = java.io.File("../app/src/main/java/com/cardroid/app/AndroidWifiHandoff.kt").readText()
        val unavailable = wifi.substring(wifi.indexOf("override fun onUnavailable()"))
        assertTrue(unavailable.substringBefore("override fun onLost").contains("onDiagnosticIssue(\"WIFI_JOIN_FAILED\")"))
        assertTrue(Regex("""runCatching \{ requestViaSpecifier\(""").containsMatchIn(wifi) ||
            wifi.contains("requestNetwork(request, cb) }.onFailure"), "requestNetwork/specifier errors must be reported, not crash")
        assertTrue(wifi.contains("onDiagnosticIssue(\"WIFI_JOIN_FAILED\")"))
    }
    @Test fun reportSummarizesLastErrorInPlainWords() {
        val clock = FakeScheduler(); val d = ConnectionDiagnostics(clock.schedule); val token = d.begin()
        d.reached(token, ConnectionDiagnostics.Stage.SOCKET)
        d.session(token, wifiConfig)
        clock.runAll()
        val summary = d.report().lines().single { it.startsWith("Summary: ") }
        assertTrue(summary.contains("did not join the receiver Wi-Fi"), summary)
        assertTrue(d.report().lines().any { it.startsWith("Last error: WIFI_JOIN_TIMEOUT (") })
        d.reached(token, ConnectionDiagnostics.Stage.SOCKET)
        d.reached(token, ConnectionDiagnostics.Stage.MEDIA)
        d.helperMissing(token)
        assertTrue(d.report().lines().single { it.startsWith("Summary: ") }.contains("display helper was not running"))
        d.failure(token, "AIRPLAY_FAILED"); d.failure(token, "WIFI_JOIN_FAILED")
        assertTrue(d.state.value.events.any { it.endsWith("Issue: AIRPLAY_FAILED") })
        assertTrue(d.state.value.events.any { it.endsWith("Issue: WIFI_JOIN_FAILED") })
        val ok = ConnectionDiagnostics(); val t = ok.begin()
        ok.reached(t, ConnectionDiagnostics.Stage.MEDIA)
        assertTrue(ok.report().lines().single { it.startsWith("Summary: ") }.contains("no error recorded"))
    }
    @Test fun consecutiveIdenticalStageIsRecordedOnce() {
        val d = ConnectionDiagnostics(); val token = d.begin()
        d.reached(token, ConnectionDiagnostics.Stage.SOCKET)
        // Pipeline onLinkUp and CarPlaySession.State.LinkUp both report the same link.
        d.reached(token, ConnectionDiagnostics.Stage.LINK)
        d.session(token, com.cardroid.lib.CarPlaySession.State.LinkUp)
        assertEquals(1, d.state.value.events.count { it.endsWith(ConnectionDiagnostics.Stage.LINK.label) })
        d.socketClosed(token); d.socketClosed(token)
        assertEquals(1, d.state.value.events.count { it.endsWith("Receiver Bluetooth socket closed") })
        // A second socket is a new attempt and must still be recorded.
        d.reached(token, ConnectionDiagnostics.Stage.SOCKET)
        d.reached(token, ConnectionDiagnostics.Stage.SOCKET)
        assertEquals(3, d.state.value.events.count { it.endsWith(ConnectionDiagnostics.Stage.SOCKET.label) })
    }
    @Test fun unknownReasonsBecomeTypedCodesWithoutLeakingText() {
        val d = ConnectionDiagnostics(); val token = d.begin()
        d.failure(token, "java.net.SocketTimeoutException: connect to 192.168.4.1 timed out")
        assertEquals("SocketTimeoutException", d.state.value.lastError)
        d.failure(token, "password=secret SSID=private pairingKey=abcdef")
        assertEquals("OTHER_ERROR", d.state.value.lastError)
        d.ble(token, ConnectionDiagnostics.Status.FAILED, "IllegalStateException")
        assertEquals("IllegalStateException", d.state.value.lastError)
        d.failure(token, "MyPrivateNetworkNameException")
        assertEquals("OTHER_ERROR", d.state.value.lastError)
        val report = d.report()
        assertFalse(report.contains("DETAILS_OMITTED_FOR_PRIVACY"))
        assertFalse(report.contains("192.168"))
        assertFalse(report.contains("secret"))
        assertFalse(report.contains("MyPrivate"))
        val e = ConnectionDiagnostics(); val t = e.begin()
        e.reached(t, ConnectionDiagnostics.Stage.SOCKET)
        e.ended(t, "video send failed: Broken pipe (192.168.4.1)")
        assertEquals("VIDEO_SEND_FAILED", e.state.value.lastError)
    }
    @Test fun sessionEndWithoutPriorReasonNamesTheReceiver() {
        for (reason in listOf("RFCOMM closed", "event channel ended: Broken pipe")) {
            val d = ConnectionDiagnostics(); val token = d.begin()
            d.reached(token, ConnectionDiagnostics.Stage.SOCKET)
            d.ended(token, reason)
            assertEquals("SESSION_ENDED_BY_RECEIVER", d.state.value.lastError, reason)
        }
        val d = ConnectionDiagnostics(); val token = d.begin()
        d.reached(token, ConnectionDiagnostics.Stage.SOCKET)
        d.ended(token, "handshake failed: SocketTimeoutException")
        assertEquals("SESSION_CLOSED_LOCALLY", d.state.value.lastError)
    }
    @Test fun sessionEndKeepsMostSpecificPriorReason() {
        val d = ConnectionDiagnostics(); val token = d.begin()
        d.reached(token, ConnectionDiagnostics.Stage.SOCKET)
        d.wifiLost(token)
        d.ended(token, "RFCOMM closed")
        assertEquals("WIFI_LOST", d.state.value.lastError)
    }
    @Test fun missingHelperIsRecordedExplicitlyAndSurvivesReceiverClose() {
        val d = ConnectionDiagnostics(); val token = d.begin()
        d.reached(token, ConnectionDiagnostics.Stage.SOCKET)
        d.reached(token, ConnectionDiagnostics.Stage.MEDIA)
        d.helperMissing(token)
        assertEquals("HELPER_NOT_RUNNING", d.state.value.lastError)
        assertTrue(d.state.value.nextStep.contains("display helper"), d.state.value.nextStep)
        assertTrue(d.state.value.nextStep.contains("Shizuku"))
        d.socketClosed(token)
        d.ended(token, "RFCOMM closed")
        assertEquals("HELPER_NOT_RUNNING", d.state.value.lastError)
    }
    @Test fun serviceRecordsHelperMissingBeforeClosingMedia() {
        val service = java.io.File("../app/src/main/java/com/cardroid/app/CarDroidService.kt").readText()
        val record = service.indexOf("diagnostics.helperMissing(")
        assertTrue(record > 0, "onReady must record HELPER_NOT_RUNNING")
        val close = service.indexOf("transport.close()", record)
        assertTrue(close > record && close - record < 400, "helper-missing reason must be recorded right before closing media")
    }
    @Test fun sessionEvidenceResetsAndSecretsNeverEnterReport() {
        val d = ConnectionDiagnostics(); val token = d.begin()
        d.reached(token, ConnectionDiagnostics.Stage.SOCKET)
        d.reached(token, ConnectionDiagnostics.Stage.MEDIA)
        d.failure(token, "password=secret SSID=private pairingKey=abcdef")
        assertFalse(d.report().contains("secret"))
        d.ended(token, "peer private address")
        assertFalse(d.state.value.receiverConnected)
        assertTrue(d.state.value.evidence.isEmpty())
        d.reached(token, ConnectionDiagnostics.Stage.SOCKET)
        assertFalse(d.report().contains("Media transport ready; car rendering unverified: observed"))
    }
    @Test fun bluetoothPipelinePublishesTypedCallbacksNotLogParsing() {
        val root = java.io.File("../libcardroid/src/main/java/com/cardroid/lib")
        val advertiser = java.io.File(root, "IPhoneAdvertiser.kt").readText()
        assertTrue(advertiser.contains("diagnostics.ble"), "advertising callbacks must publish actual outcomes")
        val transport = java.io.File(root, "Iap2Transport.kt").readText()
        assertTrue(transport.contains("Stage.SOCKET"))
        assertTrue(transport.contains("Stage.DETECT"))
        assertTrue(transport.contains("diagnostics.listener"))
    }

    @Test fun endpointIsNotMediaAndAuthIsOnlyLocalEvidence() {
        val d = ConnectionDiagnostics(); val token = d.begin()
        d.session(token, com.cardroid.lib.CarPlaySession.State.AuthComplete)
        assertEquals(ConnectionDiagnostics.Stage.AUTH, d.state.value.stage)
        d.session(token, com.cardroid.lib.CarPlaySession.State.WifiHandoffReceived("privateSSID"))
        assertFalse(d.report().contains("privateSSID"))
        assertFalse(ConnectionDiagnostics.Stage.WIFI_JOINED in d.state.value.evidence)
        assertFalse(ConnectionDiagnostics.Stage.MEDIA in d.state.value.evidence)
    }
    @Test fun uiSeparatesPrerequisitesAndSharesOnlySanitizedReport() {
        val root = java.io.File("../app/src/main/java/com/cardroid/app")
        val ui = java.io.File(root, "ConnectionDiagnosticsCard.kt")
        assertTrue(ui.exists(), "Live diagnostics card missing")
        val text = ui.readText()
        assertTrue(text.contains("diagnostics.report()"))
        assertTrue(text.contains("ACTION_SEND"))
        assertFalse(text.contains("CarDroidLog.lines"))
        assertTrue(java.io.File(root, "MainActivity.kt").readText().contains("ConnectionDiagnosticsCard("))
    }
    @Test fun stopIsIdempotentAndBluetoothCloseDoesNotInventWifiFailure() {
        val d = ConnectionDiagnostics(); val token = d.begin()
        d.reached(token, ConnectionDiagnostics.Stage.SOCKET)
        d.reached(token, ConnectionDiagnostics.Stage.MEDIA)
        d.socketClosed(token)
        assertFalse(d.state.value.receiverConnected)
        assertEquals(ConnectionDiagnostics.Stage.MEDIA, d.state.value.stage)
        d.stop(); val first = d.report(); d.stop()
        assertEquals(first, d.report())
    }
    @Test fun statusesStayDistinctAndFailureNeverMeansConnected() {
        for (status in listOf(ConnectionDiagnostics.Status.SUPPORTED, ConnectionDiagnostics.Status.STARTING, ConnectionDiagnostics.Status.FULL,
            ConnectionDiagnostics.Status.FALLBACK, ConnectionDiagnostics.Status.FAILED, ConnectionDiagnostics.Status.UNAVAILABLE)) {
            val d = ConnectionDiagnostics(); val token = d.begin(); d.ble(token, status)
            assertEquals(status, d.state.value.ble)
            assertFalse(d.state.value.receiverConnected)
            assertFalse(ConnectionDiagnostics.Stage.MEDIA in d.state.value.evidence)
        }
    }
    @Test fun wifiLossClearsLiveMediaProof() {
        val d = ConnectionDiagnostics(); val token = d.begin()
        d.reached(token, ConnectionDiagnostics.Stage.MEDIA)
        d.wifiLost(token)
        assertFalse(ConnectionDiagnostics.Stage.MEDIA in d.state.value.evidence)
        assertEquals("WIFI_LOST", d.state.value.lastError)
    }
    @Test fun explicitRetryTearsDownBeforeNewGenerationWithoutStoppingService() {
        val service = java.io.File("../app/src/main/java/com/cardroid/app/CarDroidService.kt").readText()
        assertTrue(service.contains("ACTION_RETRY -> { stop(stopService = false); startBt() }"))
    }
    @Test fun advertisingRequiresActualCallbackAndRejectsStaleSuccess() {
        val d = ConnectionDiagnostics()
        val old = d.begin()
        assertEquals(ConnectionDiagnostics.Status.STARTING, d.state.value.ble)
        d.stop()
        d.ble(old, ConnectionDiagnostics.Status.FULL)
        assertEquals(ConnectionDiagnostics.Status.STOPPED, d.state.value.ble)
        val current = d.begin()
        d.ble(old, ConnectionDiagnostics.Status.FULL)
        assertEquals(ConnectionDiagnostics.Status.STARTING, d.state.value.ble)
        d.ble(current, ConnectionDiagnostics.Status.FALLBACK, "DATA_TOO_LARGE")
        assertTrue(d.report().contains("iAP2 UUID absent"))
        assertFalse(d.state.value.receiverConnected)
    }
}

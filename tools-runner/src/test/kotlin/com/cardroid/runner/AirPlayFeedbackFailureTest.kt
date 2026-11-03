package com.cardroid.runner

import com.cardroid.lib.airplay.AirPlayHandshakeOrchestrator
import com.cardroid.lib.airplay.AirPlayParams
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class AirPlayFeedbackFailureTest {
    private fun orchestrator() = AirPlayHandshakeOrchestrator(AirPlayParams("127.0.0.1", 7000, "test"))

    @Test fun failedFeedbackEndsAndNotifiesOnlyOnce() = runBlocking {
        val session = orchestrator()
        val ended = mutableListOf<String>()
        session.onPeerEnded = { ended += it }
        session.runFeedbackLoop(intervalMs = 0) { throw IOException("closed") }
        assertEquals(listOf("AirPlay feedback failed"), ended)
        assertFalse(session.hasWifiSession)
        session.runFeedbackLoop(intervalMs = 0) { throw IOException("closed again") }
        assertEquals(1, ended.size)
    }

    @Test fun feedbackCancellationDoesNotReportFailure() = runBlocking {
        val session = orchestrator()
        val ended = mutableListOf<String>()
        session.onPeerEnded = { ended += it }
        assertFailsWith<CancellationException> {
            session.runFeedbackLoop(intervalMs = 0) { throw CancellationException("stopped") }
        }
        assertTrue(ended.isEmpty())
        session.stop()
        Unit
    }
}

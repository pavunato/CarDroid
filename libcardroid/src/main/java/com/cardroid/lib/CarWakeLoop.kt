package com.cardroid.lib

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Phone-initiated reconnect, as a real iPhone does with a car it already knows.
 *
 * When the phone comes back in range, the iPhone opens a Bluetooth link to the remembered head unit.
 * The head unit sees the ACL connection and dials the phone's iAP2 service (DiPlay's
 * "connect when iPhone Bluetooth connects"). This loop reproduces that by briefly opening a
 * baseband link (an SDP query) to the last car that ran an iAP2 session with us. It does this
 * only while nothing is connected. The car still opens iAP2; the phone never dials it.
 */
class CarWakeLoop(
    private val target: () -> String?,
    private val idle: () -> Boolean,
    private val poke: (String) -> Boolean,
    private val log: (String) -> Unit = {},
    private val intervalMs: Long = INTERVAL_MS,
) {
    private var lastResult: String? = null

    /** One wake attempt; returns the address poked, or null when skipped. */
    fun tick(): String? {
        val address = target() ?: return null.also { note("no remembered car") }
        if (!idle()) return null
        val ok = runCatching { poke(address) }.getOrDefault(false)
        note(if (ok) "woke ${redact(address)}" else "wake ${redact(address)} failed")
        return address.takeIf { ok }
    }

    fun start(scope: CoroutineScope): Job = scope.launch(Dispatchers.IO) {
        delay(FIRST_DELAY_MS)
        while (isActive) {
            tick()
            delay(intervalMs)
        }
    }

    /** Log only changes, so a parked phone does not flood the log every interval. */
    private fun note(result: String) {
        if (result == lastResult) return
        lastResult = result
        log("CarWake: $result")
    }

    companion object {
        const val INTERVAL_MS = 12_000L
        const val FIRST_DELAY_MS = 2_000L

        fun redact(address: String): String = "…" + address.takeLast(5)
    }
}

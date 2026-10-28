package com.cardroid.lib

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Classic visibility is independent of BLE, SDP and receiver connection proof. */
class ClassicDiscoverability {
    enum class Mode(val label: String) {
        UNKNOWN("Unknown"), OFF("Bluetooth off"), PAIRED_ONLY("Visible to paired devices only"), DISCOVERABLE("Discoverable to nearby devices")
    }
    data class Snapshot(val mode: Mode = Mode.UNKNOWN, val deadlineMs: Long? = null, val note: String? = null) {
        fun remainingSeconds(nowMs: Long): Int? = if (mode == Mode.DISCOVERABLE) deadlineMs?.let { ((it - nowMs).coerceAtLeast(0) / 1000).toInt() } else null
    }
    private val mutable = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = mutable
    @Synchronized fun observed(mode: Mode) {
        mutable.value = mutable.value.copy(mode = mode, deadlineMs = if (mode == Mode.DISCOVERABLE) mutable.value.deadlineMs else null)
    }
    @Synchronized fun requestResult(seconds: Int, nowMs: Long) {
        mutable.value = mutable.value.copy(deadlineMs = if (seconds > 0) nowMs + seconds * 1000L else null,
            note = if (seconds > 0) "Temporary discoverability approved by Android" else "Discoverability request canceled or denied")
    }
    @Synchronized fun unavailable() {
        mutable.value = Snapshot(note = "Check Bluetooth / Nearby devices permissions")
    }
    fun report(): String = state.value.let { "Classic visibility: ${it.mode.label}\nDiscoverability request: ${it.note ?: "none recorded"}\nVisibility expiry is normal, not a protocol failure.\n" }
}

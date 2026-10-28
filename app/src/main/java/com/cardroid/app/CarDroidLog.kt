package com.cardroid.app

import android.util.Log
import com.cardroid.lib.CarPlaySession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Tiny in-memory log fanned out to the UI, to logcat, and to a rotating file under the app's files dir. */
object CarDroidLog {
    val diagnostics = com.cardroid.lib.ConnectionDiagnostics()
    val classicDiscovery = com.cardroid.lib.ClassicDiscoverability()
    private const val TAG = "CarDroid"
    /** Per file; one previous file is kept as `.1`, so car runs survive logcat's small ring buffer. */
    private const val FILE_MAX_BYTES = 8L * 1024 * 1024
    private val fileTs = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    @Volatile private var logFile: java.io.File? = null
    private val fileLock = Any()

    /** Starts mirroring to files/cardroid.log; pull with `adb exec-out run-as com.cardroid.app cat files/cardroid.log`. */
    fun attachFile(context: android.content.Context) {
        if (logFile == null) logFile = java.io.File(context.applicationContext.filesDir, "cardroid.log")
    }

    private fun appendToFile(line: String) {
        val file = logFile ?: return
        synchronized(fileLock) {
            runCatching {
                if (file.length() > FILE_MAX_BYTES) file.renameTo(java.io.File(file.path + ".1"))
                file.appendText("${fileTs.format(Date())} $line\n")
            }
        }
    }

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    private val _state = MutableStateFlow<CarPlaySession.State>(CarPlaySession.State.Idle)
    val state: StateFlow<CarPlaySession.State> = _state

    private val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private const val MAX = 500

    /**
     * Per-frame iAP2/wire and network capability lines are always noise:
     * Phase 0/1 is trusted and the AirPlay phase is what we actually debug.
     * The toggle still exists as an escape hatch for future regressions.
     */
    @Volatile private var quietWire: Boolean = true

    fun setQuietWire(quiet: Boolean) {
        if (quietWire != quiet) {
            quietWire = quiet
            Log.i(TAG, "log: quietWire=$quiet")
        }
    }

    private fun isWireNoise(line: String): Boolean {
        // Trim leading spaces so indented continuation lines also match.
        val s = line.trimStart()
        return s.startsWith("rx ctrl=") ||
            s.startsWith("tx ctrl=") ||
            s.startsWith("rx wire ") ||
            s.startsWith("rx pre-frame bytes:") ||
            s.startsWith("payload hex:") ||
            s.startsWith("ACK wire ") ||
            s.startsWith("SYN|ACK (echoed) wire ") ||
            s.startsWith("car LSP:") ||
            s.startsWith("-> peer ") ||
            s.startsWith("-> control seq=") ||
            s.startsWith("-> SYN|ACK") ||
            s.startsWith("-> echoed DETECT_MARKER") ||
            s.startsWith("-> retransmit control seq=") ||
            s.startsWith("waiting for car DETECT") ||
            s.startsWith("Accessory WiFi capabilities:") ||
            s.startsWith("Accessory WiFi link:") ||
            s.startsWith("Requested accessory WiFi ")
    }

    fun log(line: String) {
        if (quietWire && isWireNoise(line)) return
        val stamped = "${ts.format(Date())}  $line"
        _lines.value = (_lines.value + stamped).takeLast(MAX)
        Log.i(TAG, line)
        appendToFile(line)
    }

    /** Wipe the in-UI log buffer (logcat history is untouched). */
    fun clear() { _lines.value = emptyList() }

    fun setState(s: CarPlaySession.State) {
        _state.value = s
        Log.i(TAG, "state -> $s")
    }
}

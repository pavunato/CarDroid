package com.cardroid.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cardroid.lib.ConnectionDiagnostics

@Composable
internal fun ConnectionDiagnosticsCard(onDiscoverable: () -> Unit, onStop: () -> Unit, onRetry: () -> Unit) {
    val diagnostics = CarDroidLog.diagnostics
    val snapshot by diagnostics.state.collectAsState()
    val visibility by CarDroidLog.classicDiscovery.state.collectAsState()
    var now by remember { mutableStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) { while (true) { now = android.os.SystemClock.elapsedRealtime(); kotlinx.coroutines.delay(1000) } }
    val context = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    var confirmSend by remember { mutableStateOf(false) }
    val version = remember(context) { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
    fun report() = "CarDroid $version; Android ${android.os.Build.VERSION.SDK_INT}\n" + CarDroidLog.classicDiscovery.report() + diagnostics.report()
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Car connection · live checks", style = MaterialTheme.typography.titleLarge)
            Text(snapshot.title, style = MaterialTheme.typography.titleMedium)
            Text(snapshot.nextStep, style = MaterialTheme.typography.bodyMedium)
            if (snapshot.ble == ConnectionDiagnostics.Status.FALLBACK) {
                Text("Reduced Bluetooth discovery", color = Color(0xFFFFC66D))
            }
            snapshot.lastError?.let { code ->
                Text("Last issue: ${ConnectionDiagnostics.meaning(code)} ($code)", color = MaterialTheme.colorScheme.error)
            }
            Text("Phone visibility: ${visibility.mode.label}", style = MaterialTheme.typography.bodySmall)
            visibility.remainingSeconds(now)?.let { Text("About $it seconds remaining (Android controls expiry)", style = MaterialTheme.typography.bodySmall) }
            visibility.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = onDiscoverable, enabled = visibility.mode != com.cardroid.lib.ClassicDiscoverability.Mode.DISCOVERABLE) {
                Text("Make phone discoverable")
            }
            Text("Android asks permission for temporary Classic Bluetooth visibility. This is separate from BLE advertising and does not verify CarPlay.", style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton(onClick = {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("CarDroid diagnostics", report()))
                    Toast.makeText(context, "Sanitized report copied", Toast.LENGTH_SHORT).show()
                }) { Text("Copy report") }
                TextButton(onClick = {
                    runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND)
                        .setType("text/plain").putExtra(Intent.EXTRA_TEXT, report())
                        .putExtra(Intent.EXTRA_SUBJECT, "CarDroid connection diagnostics"), "Share sanitized report")) }
                        .onFailure { Toast.makeText(context, "No sharing app available; use Copy report", Toast.LENGTH_LONG).show() }
                }) { Text("Share") }
            }
            TextButton(onClick = { confirmSend = true }) { Text("Send to developer") }
            if (confirmSend) AlertDialog(
                onDismissRequest = { confirmSend = false },
                title = { Text("Send report to developer?") },
                text = { Text("Uploads this sanitized report (same as Copy report) to the developer through Firebase Crashlytics (Google). It never includes raw logs, network names, addresses or keys.") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmSend = false
                        val id = sendReportToDeveloper(context, report())
                        Toast.makeText(context, if (id != null) "Report $id sent. Mention $id on Telegram or GitHub." else "Sending is unavailable in this build; use Share instead", Toast.LENGTH_LONG).show()
                    }) { Text("Send") }
                },
                dismissButton = { TextButton(onClick = { confirmSend = false }) { Text("Cancel") } },
            )
            if (snapshot.running) Row {
                TextButton(onClick = onRetry) { Text("Retry connection checks") }
                TextButton(onClick = onStop) { Text("Stop checks") }
            }
            Text("Report excludes raw logs, network names, addresses and keys.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide connection details" else "Show connection details") }
            if (expanded) {
                Text(snapshot.bleDetail, color = if (snapshot.ble == ConnectionDiagnostics.Status.FALLBACK) Color(0xFFFFC66D) else MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Classic iAP2 listener: ${when (snapshot.listener) {
                    ConnectionDiagnostics.Status.FULL -> "SDP registered / RFCOMM listening (local only)"
                    else -> snapshot.listener.name.lowercase()
                }}")
                Text("Receiver Bluetooth socket: ${if (snapshot.receiverConnected) "connected" else "not connected"}")
                ConnectionDiagnostics.Stage.entries.filter { it != ConnectionDiagnostics.Stage.WAITING }.forEach { stage ->
                    Text("${if (stage in snapshot.evidence) "• Observed" else "○ Not verified"} · ${stage.label}", style = MaterialTheme.typography.bodySmall)
                }
                Text("Full BLE, a Classic listener or all Setup checks passing cannot prove the car recognized CarPlay. Media readiness is local; there is no car-rendering acknowledgement.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

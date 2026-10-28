package com.cardroid.app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.cardroid.app.helper.HelperClient
import com.cardroid.app.shizuku.ShizukuHelperStarter
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import android.net.Uri
import android.provider.Settings
import com.cardroid.app.car.CarDisplayState
import com.cardroid.lib.CarPlaySession
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private val permissionRevision = MutableStateFlow(0)
    private val discoverableLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        CarDroidLog.classicDiscovery.requestResult(result.resultCode.coerceAtLeast(0), android.os.SystemClock.elapsedRealtime())
        refreshClassicVisibility()
    }
    private val scanModeReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) { refreshClassicVisibility() }
    }
    private fun refreshClassicVisibility() {
        val mode = runCatching {
            val adapter = btAdapter()
            when {
                adapter == null || !adapter.isEnabled -> com.cardroid.lib.ClassicDiscoverability.Mode.OFF
                adapter.scanMode == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE -> com.cardroid.lib.ClassicDiscoverability.Mode.DISCOVERABLE
                adapter.scanMode == BluetoothAdapter.SCAN_MODE_CONNECTABLE -> com.cardroid.lib.ClassicDiscoverability.Mode.PAIRED_ONLY
                else -> com.cardroid.lib.ClassicDiscoverability.Mode.UNKNOWN
            }
        }.getOrElse { CarDroidLog.classicDiscovery.unavailable(); return }
        CarDroidLog.classicDiscovery.observed(mode)
    }
    override fun onDestroy() {
        runCatching { unregisterReceiver(scanModeReceiver) }
        super.onDestroy()
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // Keep the Compose permission state in sync with the system dialog result.
        permissionRevision.value += 1
    }

    override fun onResume() {
        super.onResume()
        // Refresh runtime permissions when the Activity returns from system settings.
        permissionRevision.value += 1
        refreshClassicVisibility()
        ShizukuHelperStarter.get(applicationContext).initialize()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ContextCompat.registerReceiver(this, scanModeReceiver, android.content.IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_SCAN_MODE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }, ContextCompat.RECEIVER_EXPORTED)
        refreshClassicVisibility()
        CarDroidSettings.initialize(applicationContext)
        CarDroidLog.attachFile(applicationContext)
        com.cardroid.app.update.UpdateChecks.checkAutomatically(this)
        AutoStartReceiver.startBluetoothIfAllowed(this, "app opened")
        // Keep the platform bars aligned with the Compose cockpit palette even though
        // the legacy manifest theme still supplies the Activity shell.
        window.statusBarColor = android.graphics.Color.rgb(11, 16, 22)
        window.navigationBarColor = android.graphics.Color.rgb(11, 16, 22)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        setContent {
            val revision by permissionRevision.collectAsState()
            val permissions = remember(revision) { PermissionSnapshot.from(this@MainActivity, requiredPermissions()) }

            CarDroidTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    CarDroidScreen(
                        permissionSnapshot = permissions,
                        onRequestPerms = {
                            val missing = PermissionSnapshot.from(this@MainActivity, requiredPermissions()).missing
                            if (missing.isNotEmpty()) permLauncher.launch(missing.toTypedArray())
                        },
                        onAppSettings = {
                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                        },
                        onStartBt = {
                            startService(Intent(this, CarDroidService::class.java).setAction(CarDroidService.ACTION_START_BT))
                        },
                        onStop = {
                            startService(Intent(this, CarDroidService::class.java).setAction(CarDroidService.ACTION_STOP))
                        },
                        onEnableBluetooth = {
                            runCatching {
                                startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                            }
                        },
                        bluetoothEnabled = { bluetoothEnabledSafely() },
                        onDiscoverable = {
                            refreshClassicVisibility()
                            if (!PermissionSnapshot.from(this@MainActivity, requiredPermissions()).bluetoothReady) {
                                val missing = PermissionSnapshot.from(this@MainActivity, requiredPermissions()).missing
                                permLauncher.launch(missing.toTypedArray())
                            } else if (CarDroidLog.classicDiscovery.state.value.mode != com.cardroid.lib.ClassicDiscoverability.Mode.DISCOVERABLE) {
                                runCatching { discoverableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                                    .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)) }
                                    .onFailure { CarDroidLog.classicDiscovery.unavailable() }
                            }
                        },
                    )
                }
            }
        }
    }

    private fun btAdapter(): BluetoothAdapter? =
        (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun bluetoothEnabledSafely(): Boolean {
        // isEnabled throws on Android 12+ until BLUETOOTH_CONNECT is granted.
        if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return false
        return runCatching { btAdapter()?.isEnabled == true }.getOrDefault(false)
    }

    private fun requiredPermissions(): Array<String> {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            list += Manifest.permission.BLUETOOTH_CONNECT
            list += Manifest.permission.BLUETOOTH_ADVERTISE
            list += Manifest.permission.BLUETOOTH_SCAN
        }
        list += Manifest.permission.ACCESS_FINE_LOCATION
        if (Build.VERSION.SDK_INT >= 33) {
            list += Manifest.permission.POST_NOTIFICATIONS
            list += Manifest.permission.NEARBY_WIFI_DEVICES
        }
        return list.toTypedArray()
    }
}

internal data class PermissionSnapshot(val missing: List<String>) {
    val allGranted: Boolean get() = missing.isEmpty()
    val bluetoothReady: Boolean get() = missing.none {
        it == Manifest.permission.BLUETOOTH_CONNECT ||
            it == Manifest.permission.BLUETOOTH_ADVERTISE ||
            it == Manifest.permission.BLUETOOTH_SCAN ||
            (Build.VERSION.SDK_INT < 31 && it == Manifest.permission.ACCESS_FINE_LOCATION)
    }

    companion object {
        fun from(activity: ComponentActivity, permissions: Array<String>): PermissionSnapshot = PermissionSnapshot(
            permissions.distinct().filter {
                ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
            },
        )
    }
}

private data class ConnectionStatus(val title: String, val detail: String)

private fun describeConnection(state: CarPlaySession.State): ConnectionStatus = when (state) {
    CarPlaySession.State.Idle -> ConnectionStatus("Connect to your car", "Open CarPlay on the car first")
    CarPlaySession.State.LinkUp -> ConnectionStatus("Connecting", "Link established")
    is CarPlaySession.State.MfiCertReceived -> ConnectionStatus("Connecting", "Checking the receiver")
    is CarPlaySession.State.Authenticating -> ConnectionStatus("Connecting", "Verifying the receiver")
    CarPlaySession.State.AuthComplete -> ConnectionStatus("Connecting", "Authentication response processed locally")
    CarPlaySession.State.Identified -> ConnectionStatus("Connecting", "Opening CarPlay")
    CarPlaySession.State.EaSessionOpen -> ConnectionStatus("Connecting", "CarPlay session open")
    CarPlaySession.State.WifiHandoffRequested -> ConnectionStatus("Connecting", "Waiting for Wi-Fi")
    is CarPlaySession.State.WifiHandoffReceived -> ConnectionStatus("Connecting", "Joining ${state.ssid}")
    is CarPlaySession.State.CarPlayAirPlayStart -> ConnectionStatus(
        "AirPlay endpoint received",
        "Wi-Fi / media readiness is checked separately below",
    )
    is CarPlaySession.State.Failed -> ConnectionStatus(
        "Couldn't connect",
        state.reason.ifBlank { "The receiver stopped the handshake" }.take(80),
    )
}

/** The single action a first-time user should take next. */
internal data class NextStep(
    val title: String,
    val detail: String,
    val action: String,
    val kind: Kind,
) {
    enum class Kind { Available, Permissions, Bluetooth, ShizukuOpen, ShizukuAllow, Helper, Preparing, Connect, Connecting, Retry, Ready }
}

internal fun nextStep(
    permissions: PermissionSnapshot,
    bluetoothOn: Boolean,
    state: CarPlaySession.State,
    shizuku: ShizukuHelperStarter.State,
    helper: HelperClient.State,
    listening: Boolean = false,
): NextStep = when {
    !permissions.allGranted -> NextStep(
        "Allow permissions",
        if (permissions.missing.size == 1) "1 permission needed" else "${permissions.missing.size} permissions needed",
        "Allow",
        NextStep.Kind.Permissions,
    )
    !bluetoothOn -> NextStep("Turn on Bluetooth", "Required to reach the car", "Turn on", NextStep.Kind.Bluetooth)
    helper !is HelperClient.State.Connected && helper !is HelperClient.State.DisplayActive &&
        (shizuku is ShizukuHelperStarter.State.Unavailable || shizuku is ShizukuHelperStarter.State.Unsupported ||
            shizuku is ShizukuHelperStarter.State.PermissionDenied) -> NextStep(
        "Set up Shizuku", if (shizuku is ShizukuHelperStarter.State.PermissionDenied)
            "Allow CarDroid in Shizuku, then return here" else "Install or start Shizuku, then return here",
        "Open Shizuku", NextStep.Kind.ShizukuOpen)
    helper !is HelperClient.State.Connected && helper !is HelperClient.State.DisplayActive &&
        shizuku is ShizukuHelperStarter.State.NeedsPermission -> NextStep(
        "Approve Shizuku access", "Allows CarDroid to run apps on the car display", "Approve access", NextStep.Kind.ShizukuAllow)
    helper !is HelperClient.State.Connected && helper !is HelperClient.State.DisplayActive &&
        shizuku is ShizukuHelperStarter.State.Starting -> NextStep(
        "Preparing car display", "Waiting for Shizuku to start the display helper", "Preparing…", NextStep.Kind.Preparing)
    helper is HelperClient.State.Outdated ||
        (helper !is HelperClient.State.Connected && helper !is HelperClient.State.DisplayActive && shizuku is ShizukuHelperStarter.State.Error) -> NextStep(
        "Prepare car display", if (shizuku is ShizukuHelperStarter.State.Error) shizuku.message.take(100)
            else "The display helper needs an update", "Retry setup", NextStep.Kind.Helper)
    helper is HelperClient.State.NotRunning && state != CarPlaySession.State.Idle &&
        state !is CarPlaySession.State.Failed -> NextStep(
        "Prepare car display", "Finish display setup while the receiver connects", "Prepare display", NextStep.Kind.Helper)
    state is CarPlaySession.State.Failed ->
        NextStep("Couldn't connect", state.reason.ifBlank { "The receiver stopped the handshake" }.take(80), "Try again", NextStep.Kind.Retry)
    state is CarPlaySession.State.CarPlayAirPlayStart ->
        NextStep("Setting up car media", "Endpoint received; see live checks below for media readiness", "Waiting for media…", NextStep.Kind.Ready)
    state == CarPlaySession.State.Idle && listening ->
        NextStep("Waiting for your car", "Select this phone in the car’s CarPlay screen", "Waiting for car…", NextStep.Kind.Available)
    state == CarPlaySession.State.Idle ->
        NextStep("Make your phone available", "Then select this phone in the car’s CarPlay screen", "Make available", NextStep.Kind.Connect)
    else -> describeConnection(state).let { NextStep(it.title, it.detail, "Cancel", NextStep.Kind.Connecting) }
}

/** Shown near Connect: without a running helper, CarDroid closes the receiver's video stream right after media setup. */
internal fun helperWarning(helper: HelperClient.State): String? = when (helper) {
    is HelperClient.State.Connected, is HelperClient.State.DisplayActive -> null
    else -> "Display helper not running: Bluetooth can still connect, but CarDroid will close the car video until the helper is started (Shizuku or ADB)."
}

private enum class Sheet { Advanced }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CarDroidScreen(
    permissionSnapshot: PermissionSnapshot,
    onRequestPerms: () -> Unit,
    onAppSettings: () -> Unit,
    onStartBt: () -> Unit,
    onStop: () -> Unit,
    onEnableBluetooth: () -> Unit,
    bluetoothEnabled: () -> Boolean,
    onDiscoverable: () -> Unit,
) {
    val log by CarDroidLog.lines.collectAsState()
    val state by CarDroidLog.state.collectAsState()
    val useHardcodedKeys by CarDroidSettings.useHardcodedMfiKeys.collectAsState()
    val autoStartBluetooth by CarDroidSettings.autoStartBluetooth.collectAsState()
    val carDisplayId by CarDisplayState.displayId.collectAsState()
    val context = LocalContext.current
    val starter = remember(context) { ShizukuHelperStarter.get(context).also { it.initialize() } }
    val shizuku by starter.state.collectAsState()
    val helper by HelperClient.status.collectAsState()
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    var bluetoothOn by remember { mutableStateOf(bluetoothEnabled()) }
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                starter.initialize()
                bluetoothOn = bluetoothEnabled()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(permissionSnapshot, lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while (true) {
                bluetoothOn = bluetoothEnabled()
                kotlinx.coroutines.delay(1000)
            }
        }
    }
    val listening by CarDroidService.available.collectAsState()
    val starPrompt by CarDroidService.starPrompt.collectAsState()
    if (starPrompt) androidx.compose.material3.AlertDialog(
        onDismissRequest = { CarDroidService.starPrompt.value = false },
        title = { Text("Thanks for driving with CarDroid") },
        text = { Text("Enjoying it? Give CarDroid a star on GitHub.\n${CarDroidService.GITHUB_URL}") },
        confirmButton = {
            TextButton(onClick = {
                CarDroidService.starPrompt.value = false
                runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(CarDroidService.GITHUB_URL))) }
            }) { Text("Star on GitHub") }
        },
        dismissButton = { TextButton(onClick = { CarDroidService.starPrompt.value = false }) { Text("Not now") } },
    )
    val step = nextStep(permissionSnapshot, bluetoothOn, state, shizuku, helper, listening)
    val sessionActive = listening || (state != CarPlaySession.State.Idle && state !is CarPlaySession.State.Failed)
    val connect = {
        CarDroidSettings.setAutoStartHelperViaShizuku(true)
        onStartBt()
        if (helper !is HelperClient.State.Connected && helper !is HelperClient.State.DisplayActive &&
            (shizuku is ShizukuHelperStarter.State.Ready || shizuku is ShizukuHelperStarter.State.Started)) {
            starter.start(auto = true)
        }
    }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val retry: () -> Unit = {
        when {
            !permissionSnapshot.bluetoothReady -> onRequestPerms()
            !bluetoothOn -> onEnableBluetooth()
            else -> { context.startService(Intent(context, CarDroidService::class.java).setAction(CarDroidService.ACTION_RETRY)); Unit }
        }
    }
    val onAction: () -> Unit = when (step.kind) {
        NextStep.Kind.Permissions -> onRequestPerms
        NextStep.Kind.Bluetooth -> onEnableBluetooth
        NextStep.Kind.Connect -> connect
        NextStep.Kind.Retry -> retry
        NextStep.Kind.ShizukuOpen -> { { starter.openShizuku(context) } }
        NextStep.Kind.ShizukuAllow -> { { starter.requestPermission() } }
        NextStep.Kind.Helper -> { { onStartBt(); starter.restart() } }
        NextStep.Kind.Preparing, NextStep.Kind.Available -> { {} }
        NextStep.Kind.Connecting -> onStop
        NextStep.Kind.Ready -> connect
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.foundation.Image(
                            painter = androidx.compose.ui.res.painterResource(R.drawable.ic_cardroid_foreground),
                            contentDescription = null,
                            modifier = Modifier.size(40.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).background(CarDroidPalette.IconBackground),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("CarDroid", style = MaterialTheme.typography.headlineSmall)
                            Text(CarDroidName.forPhone(androidx.compose.ui.platform.LocalContext.current),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.width(10.dp))
                        PreviewPill()
                    }
                },
                actions = {
                    TextButton(onClick = { sheet = Sheet.Advanced }) { Text("Advanced") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth().align(Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StepCard(
                    step = step,
                    connecting = step.kind == NextStep.Kind.Connecting,
                    onAction = onAction,
                    onDisconnect = onStop,
                    canDisconnect = sessionActive,
                )
                helperWarning(helper)?.let { warning ->
                    Text(warning, modifier = Modifier.padding(horizontal = 8.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(when (helper) {
                        is HelperClient.State.Connected, is HelperClient.State.DisplayActive -> "Display helper ready"
                        HelperClient.State.Connecting -> "Display helper connecting"
                        HelperClient.State.Outdated -> "Display helper needs restart"
                        HelperClient.State.NotRunning -> "Display helper idle"
                    },
                        modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (carDisplayId >= 0) "Car display active" else "Car display waiting",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ConnectionDiagnosticsCard(onDiscoverable, onStop, onRetry = retry)
                ConnectionChecklist(
                    permissions = permissionSnapshot,
                    bluetoothOn = bluetoothOn,
                    shizuku = shizuku,
                    helper = helper,
                    onPermissions = onRequestPerms,
                    onAppSettings = onAppSettings,
                    onBluetooth = onEnableBluetooth,
                    onShizuku = { if (shizuku is ShizukuHelperStarter.State.NeedsPermission) starter.requestPermission() else starter.openShizuku(context) },
                )
            }
        }
    }

    if (sheet != null) {
        ModalBottomSheet(
            onDismissRequest = { sheet = null },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                when (sheet) {
                    Sheet.Advanced -> AdvancedSheet(
                        log = log,
                        useHardcodedKeys = useHardcodedKeys,
                        onHardcodedKeys = { CarDroidSettings.setUseHardcodedMfiKeys(it) },
                        autoStartBluetooth = autoStartBluetooth,
                        onAutoStartBluetooth = { CarDroidSettings.setAutoStartBluetooth(it) },
                        onClearLog = { CarDroidLog.clear() },
                    )
                    null -> Unit
                }
                TextButton(onClick = { context.startActivity(Intent(context, com.cardroid.app.update.UpdateActivity::class.java)) }) {
                    Text("Check for updates")
                }
            }
        }
    }
}

@Composable
private fun StepCard(
    step: NextStep,
    connecting: Boolean,
    onAction: () -> Unit,
    onDisconnect: () -> Unit,
    canDisconnect: Boolean,
) {
    val dot = when (step.kind) {
        NextStep.Kind.Retry, NextStep.Kind.Permissions -> MaterialTheme.colorScheme.error
        NextStep.Kind.Ready, NextStep.Kind.Connecting -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.primary
    }
    ProductCard {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            StatusDot(dot)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(step.title, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    step.detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
        if (connecting) {
            Spacer(Modifier.height(14.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(18.dp))
        Button(onClick = onAction, enabled = step.kind !in setOf(NextStep.Kind.Preparing, NextStep.Kind.Available, NextStep.Kind.Ready), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text(step.action, style = MaterialTheme.typography.titleSmall)
        }
        if (canDisconnect && step.kind != NextStep.Kind.Connecting) {
            TextButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) { Text(if (step.kind == NextStep.Kind.Available) "Stop waiting" else "Disconnect") }
        }
    }
}

@Composable
private fun ConnectionChecklist(
    permissions: PermissionSnapshot,
    bluetoothOn: Boolean,
    shizuku: ShizukuHelperStarter.State,
    helper: HelperClient.State,
    onPermissions: () -> Unit,
    onAppSettings: () -> Unit,
    onBluetooth: () -> Unit,
    onShizuku: () -> Unit,
) {
    val helperReady = helper is HelperClient.State.Connected || helper is HelperClient.State.DisplayActive
    val shizukuRunning = shizuku !is ShizukuHelperStarter.State.Unavailable &&
        shizuku !is ShizukuHelperStarter.State.Unsupported && shizuku !is ShizukuHelperStarter.State.Error
    val shizukuApproved = shizuku is ShizukuHelperStarter.State.Ready ||
        shizuku is ShizukuHelperStarter.State.Starting || shizuku is ShizukuHelperStarter.State.Started
    val permissionGroups = mutableListOf(
        Triple("Bluetooth access", "Lets your car find this phone and open the first connection. CarDroid then uses Wi-Fi for video and audio.", if (Build.VERSION.SDK_INT >= 31)
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_SCAN)
            else emptyList()),
        Triple("Location", "Android uses this permission to reveal Wi-Fi network names. CarDroid checks the network name to see whether your phone is already on the car’s Wi-Fi.", listOf(Manifest.permission.ACCESS_FINE_LOCATION)),
    )
    if (Build.VERSION.SDK_INT >= 33) {
        permissionGroups += Triple("Nearby Wi-Fi", "Lets CarDroid join the Wi-Fi network shared by your car, which carries video and audio after Bluetooth setup.", listOf(Manifest.permission.NEARBY_WIFI_DEVICES))
        permissionGroups += Triple("Notifications", "Keeps the CarDroid connection notification visible when you switch to another app.", listOf(Manifest.permission.POST_NOTIFICATIONS))
    }
    val runtimeGroups = permissionGroups.filter { it.third.isNotEmpty() }
    val runtimeDone = runtimeGroups.count { (_, _, required) -> required.none { it in permissions.missing } }
    val helperAccess = shizukuApproved || helperReady
    ProductCard {
        Text("Setup checklist", style = MaterialTheme.typography.titleLarge)
        Text("Prerequisites only — these checks do not verify a CarPlay connection.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        ChecklistGroup("Phone setup", runtimeDone + if (bluetoothOn) 1 else 0, runtimeGroups.size + 1) {
            runtimeGroups.forEach { (title, detail, required) ->
                val complete = required.none { it in permissions.missing }
                ChecklistRow(title, "Needs access", detail, complete,
                    if (complete) null else "Allow", onPermissions)
            }
            ChecklistRow("Bluetooth", "Off", "Bluetooth handles discovery and the first connection with the car. Keep it on while setting up CarPlay; video and audio use Wi-Fi afterward.",
                bluetoothOn, if (bluetoothOn) null else "Turn on", onBluetooth)
            TextButton(onClick = onAppSettings) { Text("Manage permissions") }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
        ChecklistGroup("Display setup", listOf(shizukuRunning, helperAccess).count { it }, 2) {
            ChecklistRow("Shizuku service", if (shizuku is ShizukuHelperStarter.State.Unsupported) "Update required"
                else if (shizuku is ShizukuHelperStarter.State.Error) "Could not check" else "Not running",
                "Shizuku lets CarDroid start the helper that runs apps on a separate display. Open Shizuku and start its service; after a phone restart, you may need to start it again.", shizukuRunning,
                if (shizukuRunning) null else "Open", onShizuku)
            ChecklistRow("Shizuku access", "Needs access", "In Shizuku, allow CarDroid to use its service. This lets CarDroid start the display helper without a computer each time.",
                helperAccess, if (helperAccess) null else "Set up", onShizuku)
            Text("The display helper handles touch, Home and Back. Android Accessibility and root are not needed. An ADB-started helper also works without Shizuku.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

    }
}

@Composable
private fun ChecklistGroup(title: String, complete: Int, total: Int, content: @Composable ColumnScope.() -> Unit) {
    var expanded by androidx.compose.runtime.saveable.rememberSaveable(title) { mutableStateOf(false) }
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(
                role = androidx.compose.ui.semantics.Role.Button,
                onClickLabel = if (expanded) "Collapse $title" else "Expand $title",
            ) { expanded = !expanded }.semantics {
                stateDescription = if (expanded) "Expanded" else "Collapsed"
            }.padding(vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (complete == total) "✓" else "○", modifier = Modifier.width(30.dp),
                color = if (complete == total) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                style = MaterialTheme.typography.titleLarge)
            Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Text("$complete/$total", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(14.dp))
            Text(if (expanded) "⌃" else "⌄", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        androidx.compose.animation.AnimatedVisibility(visible = expanded) {
            Column(Modifier.padding(start = 4.dp, bottom = 8.dp), content = content)
        }
    }
}

@Composable
private fun ChecklistRow(title: String, status: String, detail: String, complete: Boolean,
    action: String? = null, onAction: () -> Unit = {}) {
    var showExplanation by androidx.compose.runtime.saveable.rememberSaveable(title) { mutableStateOf(false) }
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (complete) "✓" else "○", color = if (complete) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                style = MaterialTheme.typography.titleLarge, modifier = Modifier.width(30.dp).semantics {
                    contentDescription = if (complete) "Complete" else "Pending"
                })
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (!complete) Text(status, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            androidx.compose.material3.IconButton(onClick = { showExplanation = !showExplanation },
                modifier = Modifier.semantics {
                    contentDescription = if (showExplanation) "Hide help for $title" else "About $title"
                    stateDescription = if (showExplanation) "Expanded" else "Collapsed"
                }) {
                Text("ⓘ", style = MaterialTheme.typography.titleMedium,
                    color = if (showExplanation) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!complete && action != null) TextButton(onClick = onAction) { Text(action) }
        }
        androidx.compose.animation.AnimatedVisibility(visible = showExplanation) {
            Text(detail, modifier = Modifier.padding(start = 30.dp, end = 12.dp, bottom = 12.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AdvancedSheet(
    log: List<String>,
    useHardcodedKeys: Boolean,
    onHardcodedKeys: (Boolean) -> Unit,
    autoStartBluetooth: Boolean,
    onAutoStartBluetooth: (Boolean) -> Unit,
    onClearLog: () -> Unit,
) {
    Text("Advanced", style = MaterialTheme.typography.headlineSmall)
    SwitchRow("Extracted MFi keys", useHardcodedKeys, onHardcodedKeys)
    SwitchRow("Start Bluetooth on open", autoStartBluetooth, onAutoStartBluetooth)
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Diagnostics", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onClearLog, enabled = log.isNotEmpty()) { Text("Clear") }
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().height(170.dp),
    ) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
            if (log.isEmpty()) {
                Text(
                    "No events yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                log.forEach { line ->
                    Text(
                        line,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun PreviewPill() {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.extraLarge) {
        Text(
            "PREVIEW",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun StatusDot(color: Color) {
    Surface(
        color = color,
        shape = CircleShape,
        modifier = Modifier.size(10.dp).semantics { contentDescription = "Status" },
    ) {}
}

@Composable
private fun ProductCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f)),
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
        content = { Column(modifier = Modifier.padding(18.dp), content = content) },
    )
}

private fun permissionLabel(permission: String): String = when (permission) {
    Manifest.permission.BLUETOOTH_CONNECT -> "Bluetooth"
    Manifest.permission.BLUETOOTH_ADVERTISE -> "Bluetooth advertising"
    Manifest.permission.BLUETOOTH_SCAN -> "Bluetooth scan"
    Manifest.permission.ACCESS_FINE_LOCATION -> "Location"
    Manifest.permission.NEARBY_WIFI_DEVICES -> "Nearby Wi‑Fi"
    Manifest.permission.POST_NOTIFICATIONS -> "Notifications"
    else -> permission.substringAfterLast('.')
}

@Suppress("unused")
private fun requireBluetoothPerm(activity: ComponentActivity): Boolean =
    Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

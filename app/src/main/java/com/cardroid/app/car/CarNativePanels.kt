package com.cardroid.app.car

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.graphics.SurfaceTexture
import android.os.IBinder
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.cardroid.app.CarDroidLog
import com.cardroid.app.helper.HelperBridge
import com.cardroid.app.helper.HelperProtocol
import kotlinx.coroutines.*

@Composable
internal fun DashboardHome(apps: List<CarApp>, prefs: CarLauncherPrefs, visible: Boolean = true) {
    val selected by prefs.panelApps.collectAsState()
    val recent by prefs.panelRecent.collectAsState()
    val panelApps = selected.mapIndexed { index, target ->
        apps.firstOrNull { it.target == target } ?: apps.firstOrNull {
            it.packageName == if (index == 0) "com.google.android.youtube" else "com.google.android.apps.maps"
        }
    }
    var choosing by remember { mutableIntStateOf(-1) }
    val revisions = remember { mutableStateListOf(0, 0) }
    Row(Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        repeat(2) { index ->
            key(index, panelApps[index]?.target, revisions[index]) {
                NativeAppPanel(panelApps[index], Modifier.weight(1f).fillMaxHeight(), visible) { choosing = index }
            }
        }
    }
    // Capture the cell: closing sets choosing = -1 while the list content may still re-run.
    val cell = choosing
    if (cell >= 0) AlertDialog(onDismissRequest = { choosing = -1 },
        title = { Text("Choose ${if (cell == 0) "left" else "right"} app") },
        text = {
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                // Reload recreates the panel display (same path as picking an app) and relaunches the current app.
                panelApps[cell]?.let { current ->
                    item(key = "reload") {
                        Row(Modifier.fillMaxWidth().clickable { revisions[cell]++; choosing = -1 }.padding(vertical = 16.dp)) {
                            Text("Reload ${current.label}", color = Car.Accent, fontSize = 18.sp)
                        }
                    }
                }
                items(CarLauncherLogic.recentFirst(apps, { it.target }, recent), key = { it.target }) { app ->
                    val inOtherCell = app.packageName == panelApps[1 - cell]?.packageName
                    Row(Modifier.fillMaxWidth().clickable(enabled = !inOtherCell) {
                        prefs.setPanelApp(cell, app.target); revisions[cell]++; choosing = -1
                    }.padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text(app.label, color = if (inOtherCell) Car.TextDim else Car.Text, fontSize = 18.sp)
                            if (inOtherCell) Text("In the other cell", color = Car.TextDim, fontSize = 13.sp)
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { choosing = -1 }) { Text("Cancel") } })
}

@Composable
internal fun NativeAppPanel(app: CarApp?, modifier: Modifier, visible: Boolean,
    showChooser: Boolean = true, onDisplayChanged: (Int) -> Unit = {}, onChoose: () -> Unit) {
    var error by remember(app?.target) { mutableStateOf<String?>(null) }
    var panel by remember { mutableStateOf<NativePanelView?>(null) }
    val helper by com.cardroid.app.helper.HelperClient.status.collectAsState()
    val displayChanged by rememberUpdatedState(onDisplayChanged)
    LaunchedEffect(helper) {
        if (helper is com.cardroid.app.helper.HelperClient.State.Connected || helper is com.cardroid.app.helper.HelperClient.State.DisplayActive) panel?.launchApp()
        else panel?.helperDisconnected()
    }
    Column(modifier) {
        // A subtle, full-width hit target opens the chooser; transparent so no strip shows above the app.
        if (showChooser) Box(Modifier.fillMaxWidth().height(32.dp).clickable(enabled = visible, onClickLabel = "Change app", onClick = onChoose), contentAlignment = Alignment.Center) {
            Box(Modifier.width(40.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Car.TextDim))
        }
        Column(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(22.dp)).background(Car.Surface)) {
            if (app == null) Text("Tap the indicator to choose an app.", color = Car.TextDim, modifier = Modifier.padding(20.dp))
            else key(app.target) {
                error?.let { Text(it, color = Car.Accent, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
                // Keep the TextureView attached off-page to retain the display and native task.
                AndroidView(modifier = Modifier.fillMaxWidth().weight(1f), factory = { context ->
                    NativePanelView(context, app, { displayChanged(it) }) { error = it }.also { panel = it }
                }, update = { it.inputEnabled = visible && (helper is com.cardroid.app.helper.HelperClient.State.Connected || helper is com.cardroid.app.helper.HelperClient.State.DisplayActive) },
                    onRelease = { it.releasePanel(); panel = null })
            }
        }
    }
}

/**
 * Prefers a helper-owned display on the car session's virtual device (keeps running while the phone is locked); falls back
 * to an app-owned, untrusted display, which avoids interfering with the phone's keyguard/IME focus but sleeps with the phone.
 */
private class NativePanelView(context: Context, private val app: CarApp,
    private val onDisplayChanged: (Int) -> Unit,
    private val reportError: (String?) -> Unit) : TextureView(context), TextureView.SurfaceTextureListener {
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var helperPanelId = -1
    private val panelDisplayId: Int? get() = helperPanelId.takeIf { it > 0 } ?: virtualDisplay?.display?.displayId
    private var output: Surface? = null
    private var appLaunched = false
    var inputEnabled = false
        set(value) {
            if (field && !value) panelDisplayId?.let { CarDisplayState.panelTouch(it, emptyList()) }
            field = value
        }
    init { surfaceTextureListener = this; isOpaque = true }
    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        try {
            texture.setDefaultBufferSize(width, height)
            output = Surface(texture)
            HelperBridge.createPanel(output!!, width, height, 160, app.packageName)?.let { id ->
                helperPanelId = id
                onDisplayChanged(id)
                launchApp()
                return
            }
            val manager = context.getSystemService(android.hardware.display.DisplayManager::class.java)
            // Private cells are excluded from SystemUI secondary-display keyguard presentations.
            // The shell helper launches the user-selected app and routes input on the owner's behalf.
            val flags = android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or (1 shl 8) or (1 shl 6)
            virtualDisplay = manager.createVirtualDisplay("CarDroid Panel: ${app.packageName}", width, height, 160, output, flags)
                ?: error("Could not create app display")
            onDisplayChanged(virtualDisplay!!.display.displayId)
            launchApp()
        } catch (t: Throwable) { fail(t); releasePanel() }
    }
    fun launchApp() {
        if (appLaunched) return
        val id = panelDisplayId ?: return
        if (CarDisplayState.panelLaunch(id, app.target)) { appLaunched = true; reportError(null) }
        else reportError("Start the display helper, then reselect the app.")
    }
    fun helperDisconnected() { appLaunched = false; inputEnabled = false }
    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        if (width > 0 && height > 0) runCatching {
            texture.setDefaultBufferSize(width, height)
            if (helperPanelId > 0) HelperBridge.resizePanel(helperPanelId, width, height, 160) else virtualDisplay?.resize(width, height, 160)
        }.onFailure(::fail)
    }
    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean { releasePanel(); return true }
    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val id = panelDisplayId ?: return true
        if (!inputEnabled) return true
        val contacts = if (event.actionMasked == MotionEvent.ACTION_CANCEL || event.actionMasked == MotionEvent.ACTION_UP) emptyList()
        else (0 until event.pointerCount.coerceAtMost(HelperProtocol.MAX_CONTACTS)).filter {
            !(event.actionMasked == MotionEvent.ACTION_POINTER_UP && it == event.actionIndex)
        }.map { i -> HelperProtocol.TouchContact(event.getPointerId(i), true,
            event.getX(i).toInt().coerceIn(0, (width - 1).coerceAtLeast(0)),
            event.getY(i).toInt().coerceIn(0, (height - 1).coerceAtLeast(0))) }
        CarDisplayState.panelTouch(id, contacts)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) parent?.requestDisallowInterceptTouchEvent(false)
        return true
    }
    fun releasePanel() {
        appLaunched = false
        onDisplayChanged(-1)
        panelDisplayId?.let { CarDisplayState.panelTouch(it, emptyList()) }
        if (helperPanelId > 0) HelperBridge.releasePanel(helperPanelId)
        helperPanelId = -1
        runCatching { virtualDisplay?.release() }; virtualDisplay = null
        output?.release(); output = null
    }
    private fun fail(t: Throwable) {
        val message = "${app.label}: ${t.message ?: t.javaClass.simpleName}"
        CarDroidLog.log("NativePanel $message"); reportError(message)
    }
}

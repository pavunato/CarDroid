package com.cardroid.app.car

import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.cardroid.app.CarDroidLog

/**
 * CarDroid car launcher, running ON the helper's "CarDroid Car" display (manifest: own taskAffinity, singleTask, excludeFromRecents).
 * UI: [LauncherContent] -> CarLauncherScreen (CarLauncherUi.kt). Behaviour:
 *  - BACK is consumed (a finishing launcher root would expose the Pixel SecondaryDisplayLauncher),
 *  - finishes itself when it is not on the car display (display 0 / wrong name) or when its display is removed,
 *  - launches select a NativeAppPanel beside the persistent rail.
 */
class CarLauncherActivity : ComponentActivity() {
    private val displayManager by lazy { getSystemService(DisplayManager::class.java) }
    private var ownDisplayId = Display.DEFAULT_DISPLAY
    private val prefs by lazy { CarLauncherPrefs(this) }
    /** Bumped on every re-launch (GO_HOME / knob HOME): the UI closes overlays and returns to the first page. */
    private var homeSignal by mutableIntStateOf(0)
    private var installedApps by mutableStateOf<List<CarApp>>(emptyList())

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {
            if (displayId == ownDisplayId) { CarDroidLog.log("CarLauncher: display $displayId removed; finishing"); finishAndRemoveTask() }
        }
    }

    @Suppress("DEPRECATION")
    private fun currentDisplay(): Display = if (Build.VERSION.SDK_INT >= 30) display ?: windowManager.defaultDisplay else windowManager.defaultDisplay

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = currentDisplay()
        ownDisplayId = d.displayId
        CarDroidLog.log("CarLauncher: onCreate display=${d.displayId} name='${d.name}'")
        if (!CarDisplayState.isCarDisplay(d)) {
            CarDroidLog.log("CarLauncher: not on the car display; finishing"); finishAndRemoveTask(); return
        }
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.navigationBarColor = android.graphics.Color.TRANSPARENT; window.statusBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() { /* consumed: launcher is the car root */ } })
        displayManager.registerDisplayListener(displayListener, null)
        installedApps = CarApps.load(this)
        setContent { LauncherContent(installedApps) }
    }

    override fun onNewIntent(intent: android.content.Intent) { super.onNewIntent(intent); CarDisplayState.showDashboard(); homeSignal++; CarDroidLog.log("CarLauncher: onNewIntent (brought to front)") }

    override fun onResume() {
        super.onResume()
        installedApps = CarApps.load(this)
        CarDisplayState.setLauncherOnTop(true)
        if (ownDisplayId != Display.DEFAULT_DISPLAY && displayManager.getDisplay(ownDisplayId) == null) finishAndRemoveTask()
    }

    override fun onPause() { CarDisplayState.setLauncherOnTop(false); super.onPause() }

    override fun onDestroy() {
        CarDisplayState.setLauncherOnTop(false)
        runCatching { displayManager.unregisterDisplayListener(displayListener) }
        CarDroidLog.log("CarLauncher: onDestroy display=$ownDisplayId")
        super.onDestroy()
    }

    /** Designed launcher UI (Part B): dock + paged icon grid, favorites/hide via [CarLauncherPrefs]; see CarLauncherUi.kt. */
    @Composable
    private fun LauncherContent(apps: List<CarApp>) {
        com.cardroid.app.CarDroidTheme {
            CarLauncherScreen(apps, prefs, homeSignal) { app -> CarApps.launch(app) }
        }
    }
}

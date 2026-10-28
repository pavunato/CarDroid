package com.cardroid.app.car

import android.content.ComponentName
import android.content.Context
import android.content.Intent

/** A launchable app entry: LAUNCHER activity [component] of [packageName]. */
data class CarApp(val label: String, val packageName: String, val className: String) {
    val component: ComponentName get() = ComponentName(packageName, className)
    /** Helper LAUNCH target ("pkg/cls"). */
    val target: String get() = "$packageName/$className"
}

object CarApps {
    /** All MAIN/LAUNCHER activities except CarDroid itself, sorted by label (needs the manifest <queries> entry on targetSdk 34). */
    fun load(context: Context): List<CarApp> {
        val pm = context.packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(q, 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .map { CarApp(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.activityInfo.name) }
            .sortedBy { it.label.lowercase() }
    }

    /** Select launcher-owned content; never put another task above the rail. */
    fun launch(app: CarApp): Boolean = CarDisplayState.openApp(app.target)
}

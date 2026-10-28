package com.cardroid.app.helper.activity

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.IInterface
import com.cardroid.app.helper.HelperProtocol
import com.cardroid.app.helper.display.FakeContext
import java.lang.reflect.Method

/**
 * Starts activities on the car display as com.android.shell. Port of scrcpy (Apache-2.0, Genymobile/scrcpy): wrappers/ActivityManager
 * (reflective IActivityManager.startActivityAsUser with a null caller and USER_CURRENT) and device/Device.startApp/getLaunchIntent
 * (ActivityOptions.setLaunchDisplayId + FLAG_ACTIVITY_NEW_TASK). All failures throw; the caller reports ERROR(LAUNCH_FAILED) and keeps running.
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi", "SoonBlockedPrivateApi")
class ActivityLauncher(private val log: (String) -> Unit) {
    companion object {
        const val LAUNCHER_PACKAGE = "com.cardroid.app"
        const val LAUNCHER_CLASS = "com.cardroid.app.car.CarLauncherActivity"
        private const val USER_CURRENT = -2
    }

    private val manager: IInterface by lazy {
        val getDefault = Class.forName("android.app.ActivityManagerNative").getDeclaredMethod("getDefault")
        getDefault.invoke(null) as IInterface
    }
    private val startMethod: Method by lazy {
        val app = Class.forName("android.app.IApplicationThread")
        val profiler = Class.forName("android.app.ProfilerInfo")
        manager.javaClass.getMethod("startActivityAsUser", app, String::class.java, Intent::class.java, String::class.java, IBinder::class.java,
            String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, profiler, Bundle::class.java, Int::class.javaPrimitiveType)
    }

    /** Launches [target] ("pkg/cls" or "pkg") on [displayId]. Returns the ActivityManager start result (>= 0 is success). */
    fun launch(displayId: Int, target: String, flags: Int = 0, separateTask: Boolean = false): Int {
        val intent = buildIntent(target)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Standard activities get a separate task per panel instead of stealing a dashboard task.
        // Apps declaring singleTask/singleInstance may still enforce their own global instance.
        if (separateTask) intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        if (flags and HelperProtocol.LAUNCH_CLEAR_TASK != 0) intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return start(displayId, intent)
    }

    /** Brings CarLauncherActivity to the front of the car display (singleTask in its own affinity). */
    fun goHome(displayId: Int): Int {
        val intent = Intent().setComponent(ComponentName(LAUNCHER_PACKAGE, LAUNCHER_CLASS)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return start(displayId, intent)
    }

    private fun start(displayId: Int, intent: Intent): Int {
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
        val r = startMethod.invoke(manager, null, FakeContext.PACKAGE_NAME, intent, null, null, null, 0, 0, null, options, USER_CURRENT) as Int
        log("Activity: start ${intent.component?.flattenToShortString()} on display $displayId -> result $r")
        if (r < 0) error("startActivityAsUser result $r")
        return r
    }

    private fun buildIntent(target: String): Intent {
        val slash = target.indexOf('/')
        if (slash > 0) {
            val pkg = target.substring(0, slash)
            var cls = target.substring(slash + 1)
            if (cls.startsWith(".")) cls = pkg + cls
            if (cls.isEmpty()) error("bad component '$target'")
            return Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(ComponentName(pkg, cls))
        }
        val pm = FakeContext.get(log).packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(target)
        val ri = pm.queryIntentActivities(query, 0).firstOrNull()
            ?: error("no launcher activity for package '$target'")
        return Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(ComponentName(ri.activityInfo.packageName, ri.activityInfo.name))
    }
}

package com.cardroid.app.helper.display

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.display.VirtualDisplay
import android.hardware.display.VirtualDisplayConfig
import android.os.Build
import android.os.IBinder
import android.view.Surface
import java.util.concurrent.Executor

/**
 * Shell-owned companion Virtual Device for one car session. Its displays form their own always-unlocked power group, so car
 * apps keep running while the phone sleeps or shows the keyguard. Plain virtual displays cannot: separate-timeouts builds
 * (e.g. HyperOS on Android 16) put every TYPE_VIRTUAL display into the phone's group, ignoring OWN_DISPLAY_GROUP.
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
class CarVirtualDevice private constructor(private val device: Any, private val log: (String) -> Unit) {

    /** [flags] must not contain OWN_DISPLAY_GROUP: it would take the display out of the device group. */
    fun createDisplay(name: String, width: Int, height: Int, dpi: Int, surface: Surface, flags: Int): VirtualDisplay {
        val config = VirtualDisplayConfig.Builder(name, width, height, dpi).setSurface(surface).setFlags(flags).build()
        return device.javaClass.getMethod("createVirtualDisplay", VirtualDisplayConfig::class.java, Executor::class.java, VirtualDisplay.Callback::class.java)
            .invoke(device, config, null, null) as? VirtualDisplay ?: error("createVirtualDisplay returned null")
    }

    fun close() {
        runCatching { device.javaClass.getMethod("close").invoke(device) }
            .onSuccess { log("Virtual device closed") }
            .onFailure { log("Virtual device close failed (${it.javaClass.simpleName}: ${it.message})") }
    }

    companion object {
        /** Locally administered MAC naming the shell's CarDroid association; it never refers to real hardware. */
        const val ASSOCIATION_MAC = "02:ca:4d:00:00:01"
        private const val PACKAGE = FakeContext.PACKAGE_NAME
        private const val PROFILE = "android.app.role.COMPANION_DEVICE_APP_STREAMING"
        private const val LOCK_STATE_ALWAYS_UNLOCKED = 1

        /** Null when unsupported or refused; the caller falls back to a plain virtual display. */
        fun open(log: (String) -> Unit): CarVirtualDevice? {
            if (Build.VERSION.SDK_INT < 34) { log("Virtual device unavailable: sdk ${Build.VERSION.SDK_INT} < 34"); return null }
            return try {
                val association = ensureAssociation(log)
                val ctx = FakeContext.get(log)
                val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "virtualdevice") as? IBinder
                    ?: error("virtualdevice service unavailable")
                val iface = Class.forName("android.companion.virtual.IVirtualDeviceManager")
                val service = Class.forName("android.companion.virtual.IVirtualDeviceManager\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)
                // The system-service instance carries no Context, so build the manager around FakeContext (shell attribution).
                val managerCls = Class.forName("android.companion.virtual.VirtualDeviceManager")
                val manager = managerCls.getDeclaredConstructor(iface, Context::class.java).apply { isAccessible = true }.newInstance(service, ctx)
                val builderCls = Class.forName("android.companion.virtual.VirtualDeviceParams\$Builder")
                val builder = builderCls.getConstructor().newInstance()
                builderCls.getMethod("setLockState", Int::class.javaPrimitiveType).invoke(builder, LOCK_STATE_ALWAYS_UNLOCKED)
                runCatching { builderCls.getMethod("setName", String::class.java).invoke(builder, "CarDroid") }
                val params = builderCls.getMethod("build").invoke(builder)
                val device = managerCls.getMethod("createVirtualDevice", Int::class.javaPrimitiveType, Class.forName("android.companion.virtual.VirtualDeviceParams"))
                    .invoke(manager, association, params) ?: error("createVirtualDevice returned null")
                log("Virtual device created (association $association)")
                CarVirtualDevice(device, log)
            } catch (t: Throwable) {
                val c = (t as? java.lang.reflect.InvocationTargetException)?.cause ?: t
                log("Virtual device unavailable: ${c.javaClass.simpleName}: ${c.message}")
                null
            }
        }

        private fun ensureAssociation(log: (String) -> Unit): Int {
            associationId(companionCmd("list", "0"), PACKAGE, ASSOCIATION_MAC)?.let { return it }
            log("Creating companion association $PACKAGE $ASSOCIATION_MAC")
            companionCmd("associate", "0", PACKAGE, ASSOCIATION_MAC, PROFILE)
            return associationId(companionCmd("list", "0"), PACKAGE, ASSOCIATION_MAC) ?: error("companion association was not created")
        }

        private fun companionCmd(vararg args: String): String {
            val process = ProcessBuilder("/system/bin/cmd", "companiondevice", *args).redirectErrorStream(true).start()
            return try { process.inputStream.bufferedReader().use { it.readText() } } finally { process.destroy() }
        }

        /** Parses `cmd companiondevice list` rows: `<id> | <package> | <mac>`. */
        internal fun associationId(list: String, pkg: String, mac: String): Int? = list.lineSequence().mapNotNull { line ->
            val cols = line.split('|').map { it.trim() }
            if (cols.size < 3 || cols[1] != pkg || !cols[2].equals(mac, ignoreCase = true)) null else cols[0].toIntOrNull()
        }.firstOrNull()
    }
}

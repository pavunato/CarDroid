package com.cardroid.app.helper.display

import android.annotation.SuppressLint
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.view.Surface

/**
 * Creates the car virtual display (scrcpy NewDisplayCapture flag set, Apache-2.0 Genymobile/scrcpy) on an encoder surface
 * as com.android.shell, keeps it awake, and tears everything down. Every failure throws with a readable message; the caller reports ERROR.
 * The display lives on a [CarVirtualDevice] when available (own power group, survives the phone sleeping/locking), else falls back to a plain one.
 * DESTROY_CONTENT_ON_REMOVAL is deliberately not set: apps move back to the phone when the car display ends.
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi", "SoonBlockedPrivateApi")
class HelperDisplay(
    private val width: Int, private val height: Int, private val dpi: Int, private val fps: Int, private val bitrate: Int,
    private val log: (String) -> Unit, private val sink: DisplayEncoder.Sink,
) {
    private var encoder: DisplayEncoder? = null
    private var display: VirtualDisplay? = null
    @Volatile private var virtualDevice: CarVirtualDevice? = null
    private var surface: Surface? = null
    private var relay: FrameRelay? = null
    private var keepActive: Thread? = null
    @Volatile private var released = false
    var displayId = -1; private set
    val activeFps: Int get() = encoder?.activeFps ?: fps

    fun start(): Int {
        val ctx = FakeContext.get(log)
        val enc = DisplayEncoder(width, height, fps, bitrate, log, sink)
        encoder = enc
        try {
            val input = enc.prepare(); surface = input
            val ctor = DisplayManager::class.java.getDeclaredConstructor(android.content.Context::class.java).apply { isAccessible = true }
            val dm = ctor.newInstance(ctx)
            // Relay keeps the encoder fed while the display is static (GraphicBufferSource repeat gives up after ~10 repeats).
            // Do not silently fall back to the raw encoder surface: that path is the exact car-session
            // failure mode, and would leave a helper display that freezes instead of triggering the
            // app's tested mirror fallback when EGL cannot provide the relay.
            val target = FrameRelay(width, height, fps, input, log) { sink.onFailure(it) }
                .also { relay = it }
                .start()
                .also { log("Frame relay active (GL re-draw every ${FrameRelay.IDLE_MS} ms when idle)") }
            val vd = createOnVirtualDevice(target)
                ?: dm.createVirtualDisplay("CarDroid Car", width, height, dpi, target, flags())
                ?: error("createVirtualDisplay returned null")
            display = vd
            displayId = vd.display.displayId
            log("Display created id=$displayId ${width}x$height/$dpi flags=0x${Integer.toHexString(vd.display.flags)} virtualDevice=${virtualDevice != null}")
            lockCarOrientation(displayId)
            setImePolicyLocal(displayId)
            enc.startDraining()
            startKeepActive(displayId)
            return displayId
        } catch (t: Throwable) { release(); throw t }
    }

    fun requestKeyFrame() { encoder?.requestKeyFrame() }

    /** App panel display on this session's virtual device; null without one (the app then keeps its own panel display). */
    fun createPanel(name: String, width: Int, height: Int, dpi: Int, target: Surface): VirtualDisplay? =
        virtualDevice?.takeIf { !released }?.createDisplay(name, width, height, dpi, target, PANEL_FLAGS)

    /** Keeps the car display awake and unlocked while the phone sleeps; null means use the plain virtual display. */
    private fun createOnVirtualDevice(target: Surface): VirtualDisplay? {
        val device = CarVirtualDevice.open(log) ?: return null
        return try {
            device.createDisplay("CarDroid Car", width, height, dpi, target, flags() and (OWN_DISPLAY_GROUP or DEVICE_DISPLAY_GROUP).inv())
                .also { virtualDevice = device }
        } catch (t: Throwable) {
            val c = (t as? java.lang.reflect.InvocationTargetException)?.cause ?: t
            log("Virtual device display failed, using a plain virtual display (${c.javaClass.simpleName}: ${c.message})")
            device.close(); null
        }
    }

    private fun flags(): Int {
        // Private, undecorated display: keep SystemUI's shade and secondary-keyguard
        // presentations off this canvas (some ROMs otherwise move shade focus off the phone).
        var f = OWN_CONTENT_ONLY or SUPPORTS_TOUCH or ROTATES_WITH_CONTENT
        if (Build.VERSION.SDK_INT >= 33) f = f or TRUSTED or OWN_DISPLAY_GROUP or ALWAYS_UNLOCKED or TOUCH_FEEDBACK_DISABLED
        // Touches on panels arrive through the launcher on this display first; taking top focus here
        // would blur the panel app (e.g. Chrome closes its omnibox suggestions before the tap reaches them).
        if (Build.VERSION.SDK_INT >= 34) f = f or OWN_FOCUS or DEVICE_DISPLAY_GROUP or STEAL_TOP_FOCUS_DISABLED
        return f
    }

    private fun service(name: String, type: String): Any {
        val binder = Class.forName("android.os.ServiceManager").getDeclaredMethod("getService", String::class.java).invoke(null, name) as? IBinder
            ?: error("service $name unavailable")
        return Class.forName("$type\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
    }

    /** Rotation zero is the native orientation of this virtual display's negotiated canvas. */
    private fun lockCarOrientation(id: Int) {
        check(id != android.view.Display.DEFAULT_DISPLAY) { "Refusing to lock phone orientation" }
        try {
            val wm = service("window", "android.view.IWindowManager")
            val intType = Int::class.javaPrimitiveType
            wm.javaClass.getMethod("setIgnoreOrientationRequest", intType, Boolean::class.javaPrimitiveType)
                .invoke(wm, id, true)
            // FIXED_TO_USER_ROTATION_ENABLED: app sensor/portrait requests cannot rotate the canvas.
            wm.javaClass.getMethod("setFixedToUserRotation", intType, intType).invoke(wm, id, 2)
            // Android versions differ by the optional caller attribution argument.
            val freeze = wm.javaClass.methods.firstOrNull {
                it.name == "freezeDisplayRotation" && it.parameterTypes.contentEquals(
                    arrayOf(intType, intType, String::class.java))
            }
            if (freeze != null) freeze.invoke(wm, id, Surface.ROTATION_0, "CarDroid")
            else wm.javaClass.getMethod("freezeDisplayRotation", intType, intType)
                .invoke(wm, id, Surface.ROTATION_0)
            log("Orientation locked display=$id canvas=${width}x$height rotation=0 ignoreAppRequests=true")
        } catch (t: Throwable) {
            log("Orientation lock failed display=$id (${t.javaClass.simpleName}: ${t.cause?.message ?: t.message})")
        }
    }

    private fun setImePolicyLocal(id: Int) {
        try {
            val wm = service("window", "android.view.IWindowManager")
            wm.javaClass.getMethod("setDisplayImePolicy", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(wm, id, 0)
            log("IME policy LOCAL set for display $id")
        } catch (t: Throwable) { log("setDisplayImePolicy failed (${t.javaClass.simpleName}: ${t.message})") }
    }

    private fun startKeepActive(id: Int) {
        val pm = try { service("power", "android.os.IPowerManager") } catch (t: Throwable) { log("keep-active unavailable: ${t.message}"); return }
        val m = try {
            pm.javaClass.getMethod("userActivity", Int::class.javaPrimitiveType, Long::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        } catch (t: Throwable) { log("keep-active unavailable: ${t.message}"); return }
        keepActive = Thread({
            var failures = 0
            while (!released) {
                try { m.invoke(pm, id, SystemClock.uptimeMillis(), 0, 0); failures = 0 }
                catch (t: Throwable) { if (failures++ == 0) log("userActivity failed (${t.javaClass.simpleName}: ${t.message})") }
                try { Thread.sleep(KEEP_ACTIVE_MS) } catch (_: InterruptedException) { return@Thread }
            }
        }, "helper-keepactive").apply { isDaemon = true; start() }
    }

    @Synchronized fun release() {
        if (released) return
        released = true
        keepActive?.interrupt(); keepActive = null
        runCatching { display?.release() }; display = null
        virtualDevice?.close(); virtualDevice = null
        relay?.release(); relay = null
        encoder?.release(); encoder = null
        runCatching { surface?.release() }; surface = null
        log("Display released id=$displayId")
    }

    companion object {
        const val KEEP_ACTIVE_MS = 4_000L
        private const val PUBLIC = 1 shl 0
        private const val PRESENTATION = 1 shl 1
        private const val OWN_CONTENT_ONLY = 1 shl 3
        private const val SUPPORTS_TOUCH = 1 shl 6
        private const val ROTATES_WITH_CONTENT = 1 shl 7
        private const val SHOULD_SHOW_SYSTEM_DECORATIONS = 1 shl 9
        private const val TRUSTED = 1 shl 10
        private const val OWN_DISPLAY_GROUP = 1 shl 11
        private const val ALWAYS_UNLOCKED = 1 shl 12
        private const val TOUCH_FEEDBACK_DISABLED = 1 shl 13
        private const val OWN_FOCUS = 1 shl 14
        private const val DEVICE_DISPLAY_GROUP = 1 shl 15
        private const val STEAL_TOP_FOCUS_DISABLED = 1 shl 16
        private const val DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
        /** Same private cell as the app-owned panels, plus own focus so panel input never steals the phone's focus. */
        private const val PANEL_FLAGS = OWN_CONTENT_ONLY or SUPPORTS_TOUCH or DESTROY_CONTENT_ON_REMOVAL or TRUSTED or
            TOUCH_FEEDBACK_DISABLED or OWN_FOCUS
    }
}

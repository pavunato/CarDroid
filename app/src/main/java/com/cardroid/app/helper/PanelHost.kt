package com.cardroid.app.helper

import android.annotation.SuppressLint
import android.hardware.display.VirtualDisplay
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.view.Surface

/**
 * Helper-side panel displays: created on the car session's virtual device for the app's TextureView surfaces
 * (see [PanelHostProtocol]). Only the CarDroid app UID may transact; its death releases its panels.
 */
class PanelHost(private val appUid: Int, private val log: (String) -> Unit,
    private val create: (name: String, width: Int, height: Int, dpi: Int, surface: Surface) -> VirtualDisplay?) : Binder() {
    private val registry = PanelRegistry<VirtualDisplay>()

    /** Display id -> package for helper-owned panels (ownership check for PANEL_* commands). */
    fun packages(): Map<Int, String> = registry.packages()

    fun releaseAll(reason: String) {
        val released = registry.clear()
        released.forEach { runCatching { it.release() } }
        if (released.isNotEmpty()) log("Panels: released ${released.size} ($reason)")
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code !in PanelHostProtocol.TX_CREATE_PANEL..PanelHostProtocol.TX_RELEASE_PANEL) return super.onTransact(code, data, reply, flags)
        if (getCallingUid() != appUid) { log("Panels: rejected uid ${getCallingUid()}"); throw SecurityException("not the CarDroid app") }
        data.enforceInterface(PanelHostProtocol.DESCRIPTOR)
        when (code) {
            PanelHostProtocol.TX_CREATE_PANEL -> {
                val surface = Surface.CREATOR.createFromParcel(data)
                val width = data.readInt(); val height = data.readInt(); val dpi = data.readInt(); val pkg = data.readString().orEmpty()
                require(width > 0 && height > 0 && dpi > 0 && PanelHostProtocol.isPackageName(pkg)) { "bad panel request" }
                val display = try { create(PanelHostProtocol.panelName(pkg), width, height, dpi, surface) } catch (t: Throwable) {
                    val c = (t as? java.lang.reflect.InvocationTargetException)?.cause ?: t
                    log("Panels: create $pkg failed ${c.javaClass.simpleName}: ${c.message}"); null
                }
                val id = display?.display?.displayId ?: -1
                if (display != null) { registry.put(id, pkg, display); log("Panels: created $id for $pkg ${width}x$height/$dpi") }
                reply?.writeNoException(); reply?.writeInt(id)
            }
            PanelHostProtocol.TX_RESIZE_PANEL -> {
                val id = data.readInt(); val width = data.readInt(); val height = data.readInt(); val dpi = data.readInt()
                if (width > 0 && height > 0 && dpi > 0) registry.get(id)?.resize(width, height, dpi)
                reply?.writeNoException()
            }
            PanelHostProtocol.TX_RELEASE_PANEL -> {
                registry.remove(data.readInt())?.let { runCatching { it.release() } }
                reply?.writeNoException()
            }
        }
        return true
    }

    /**
     * Hands this binder to the app's bridge provider (shell has no other route to the app); the returned app token's
     * death releases the app's panels. Returns false when the app (or this Android version) does not support it.
     */
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    fun attachToApp(): Boolean = try {
        val am = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)!!
        val token = Binder()
        val holder = am.javaClass.getMethod("getContentProviderExternal", String::class.java, Int::class.javaPrimitiveType, IBinder::class.java, String::class.java)
            .invoke(am, PanelHostProtocol.AUTHORITY, 0, token, "cardroid-helper") ?: error("bridge provider not found")
        try {
            val provider = holder.javaClass.getField("provider").get(holder)!!
            val source = android.content.AttributionSource.Builder(Process.myUid()).setPackageName(com.cardroid.app.helper.display.FakeContext.PACKAGE_NAME).build()
            val call = provider.javaClass.methods.first { it.name == "call" && it.parameterTypes.size == 5 && it.parameterTypes[0] == android.content.AttributionSource::class.java }
            val result = call.invoke(provider, source, PanelHostProtocol.AUTHORITY, PanelHostProtocol.METHOD_ATTACH, null,
                Bundle().apply { putBinder(PanelHostProtocol.KEY_BINDER, this@PanelHost) }) as? Bundle
            val appToken = result?.getBinder(PanelHostProtocol.KEY_BINDER) ?: error("bridge returned no token")
            appToken.linkToDeath({ releaseAll("app died") }, 0)
            log("Panels: attached to app bridge")
            true
        } finally {
            runCatching { am.javaClass.getMethod("removeContentProviderExternalAsUser", String::class.java, IBinder::class.java, Int::class.javaPrimitiveType).invoke(am, PanelHostProtocol.AUTHORITY, token, 0) }
        }
    } catch (t: Throwable) {
        val c = (t as? java.lang.reflect.InvocationTargetException)?.cause ?: t
        log("Panels: app bridge unavailable (${c.javaClass.simpleName}: ${c.message}); app keeps its own panel displays"); false
    }
}

/** Thread-safe id -> (package, handle) map; binder threads write, the CONTROL thread reads. */
class PanelRegistry<T : Any> {
    private val panels = LinkedHashMap<Int, Pair<String, T>>()
    @Synchronized fun put(id: Int, pkg: String, handle: T) { panels[id] = pkg to handle }
    @Synchronized fun get(id: Int): T? = panels[id]?.second
    @Synchronized fun remove(id: Int): T? = panels.remove(id)?.second
    @Synchronized fun packages(): Map<Int, String> = panels.mapValues { it.value.first }
    @Synchronized fun clear(): List<T> = panels.values.map { it.second }.also { panels.clear() }
}

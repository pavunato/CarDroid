package com.cardroid.app.helper

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.view.Surface
import com.cardroid.app.CarDroidLog

/** App side of [PanelHostProtocol]: asks the helper for panel displays on its virtual device. */
object HelperBridge {
    @Volatile private var host: IBinder? = null
    /** Kept alive with the app process; the helper releases our panels when it dies. */
    private val token = Binder()

    internal fun attach(binder: IBinder): Bundle {
        host = binder
        runCatching { binder.linkToDeath({ if (host === binder) host = null }, 0) }.onFailure { host = null }
        CarDroidLog.log("HelperBridge: helper attached")
        return Bundle().apply { putBinder(PanelHostProtocol.KEY_BINDER, token) }
    }

    /** Helper-owned panel display id, or null when the helper is absent or has no virtual device (use an app display). */
    fun createPanel(surface: Surface, width: Int, height: Int, dpi: Int, packageName: String): Int? = transact(PanelHostProtocol.TX_CREATE_PANEL, {
        surface.writeToParcel(it, 0); it.writeInt(width); it.writeInt(height); it.writeInt(dpi); it.writeString(packageName)
    }) { it.readInt() }?.takeIf { it > 0 }

    fun resizePanel(id: Int, width: Int, height: Int, dpi: Int) {
        transact(PanelHostProtocol.TX_RESIZE_PANEL, { it.writeInt(id); it.writeInt(width); it.writeInt(height); it.writeInt(dpi) }) {}
    }

    fun releasePanel(id: Int) { transact(PanelHostProtocol.TX_RELEASE_PANEL, { it.writeInt(id) }) {} }

    private fun <T> transact(code: Int, write: (Parcel) -> Unit, read: (Parcel) -> T): T? {
        val binder = host ?: return null
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(PanelHostProtocol.DESCRIPTOR); write(data)
            binder.transact(code, data, reply, 0)
            reply.readException(); read(reply)
        } catch (t: Exception) { CarDroidLog.log("HelperBridge: call $code failed ${t.javaClass.simpleName}: ${t.message}"); null
        } finally { data.recycle(); reply.recycle() }
    }
}

/** Exported only for the shell helper: it is the helper's one way to hand its binder to the app. */
class HelperBridgeProvider : ContentProvider() {
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (Binder.getCallingUid() != PanelHostProtocol.SHELL_UID) throw SecurityException("helper bridge is for the shell helper only")
        if (method != PanelHostProtocol.METHOD_ATTACH) return null
        val binder = extras?.getBinder(PanelHostProtocol.KEY_BINDER) ?: return null
        return HelperBridge.attach(binder)
    }
    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}

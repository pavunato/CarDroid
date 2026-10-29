package com.cardroid.app.helper

import android.os.IBinder

/**
 * Binder contract between the app's car panels and the helper's [com.cardroid.app.helper.PanelHost]. The helper hands its
 * binder to the app through [AUTHORITY] (`call(METHOD_ATTACH)`, shell UID only) and receives an app token whose death
 * releases the app's panels. Panels then live on the helper's virtual device, so they stay awake when the phone locks.
 */
object PanelHostProtocol {
    const val AUTHORITY = "com.cardroid.app.helperbridge"
    const val METHOD_ATTACH = "attach"
    const val KEY_BINDER = "binder"
    const val DESCRIPTOR = "com.cardroid.app.helper.PanelHost"
    /** in: Surface, width, height, dpi, package · out: display id, or -1 when the helper has no virtual device. */
    const val TX_CREATE_PANEL = IBinder.FIRST_CALL_TRANSACTION
    /** in: display id, width, height, dpi */
    const val TX_RESIZE_PANEL = IBinder.FIRST_CALL_TRANSACTION + 1
    /** in: display id */
    const val TX_RELEASE_PANEL = IBinder.FIRST_CALL_TRANSACTION + 2
    const val SHELL_UID = 2000

    fun panelName(packageName: String) = "CarDroid Panel: $packageName"
    fun isPackageName(value: String) = value.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))
}

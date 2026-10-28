package com.cardroid.lib

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.os.ParcelUuid

/**
 * Drives the Bluetooth-discovery side of impersonating an iPhone.
 *
 *   Classic BR/EDR:
 *     - Renames the local adapter to [IPhoneIdentity.bluetoothName]
 *     - Becomes discoverable
 *     - SDP record is implicit in [Iap2Transport.start] which
 *       calls listenUsingRfcommWithServiceRecord
 *
 *   BLE:
 *     - Advertises Apple manufacturer data
 *     - Advertises the iPhone-side iAP2 service UUID so head units
 *       scanning for CarPlay-eligible peers see us
 */
class IPhoneAdvertiser(
    private val adapter: BluetoothAdapter,
    private val identity: IPhoneIdentity,
    private val onLog: (String) -> Unit = {},
    private val diagnostics: ConnectionDiagnostics = ConnectionDiagnostics(),
    private val diagnosticToken: Long = 0,
) {
    private var bleAdvertiser: BluetoothLeAdvertiser? = null

    private var advCallback: AdvertiseCallback? = null
    private val lifecycleLock = Any()

    private fun callback(fallback: Boolean): AdvertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) = synchronized(lifecycleLock) {
            if (advCallback !== this) return@synchronized
            diagnostics.ble(diagnosticToken, if (fallback) ConnectionDiagnostics.Status.FALLBACK else ConnectionDiagnostics.Status.FULL)
            onLog("BLE advertisement started (${if (fallback) "manufacturer-only fallback" else "full scan response"})")
        }
        override fun onStartFailure(errorCode: Int) = synchronized(lifecycleLock) {
            if (advCallback !== this) return@synchronized
            val name = when (errorCode) {
                ADVERTISE_FAILED_DATA_TOO_LARGE -> "DATA_TOO_LARGE"
                ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "TOO_MANY_ADVERTISERS"
                ADVERTISE_FAILED_ALREADY_STARTED -> "ALREADY_STARTED"
                ADVERTISE_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR"
                ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED"
                else -> "UNKNOWN"
            }
            diagnostics.ble(diagnosticToken, ConnectionDiagnostics.Status.FAILED, name)
            onLog("BLE advertisement failed: $errorCode ($name)")
            val plan = fallbackPlan
            if (errorCode == ADVERTISE_FAILED_DATA_TOO_LARGE && plan != null) {
                fallbackPlan = null
                val next = callback(true)
                advCallback = next
                diagnostics.ble(diagnosticToken, ConnectionDiagnostics.Status.STARTING)
                try {
                    bleAdvertiser?.startAdvertising(plan.second, plan.third, next)
                } catch (e: Exception) {
                    diagnostics.ble(diagnosticToken, ConnectionDiagnostics.Status.FAILED, e.javaClass.simpleName)
                    onLog("Fallback advertise threw: ${e.message}")
                }
            }
        }
    }

    /** Pre-built fallback (label, settings, data) used if the primary attempt fails. */
    private var fallbackPlan: Triple<String, AdvertiseSettings, AdvertiseData>? = null

    @SuppressLint("MissingPermission")
    fun start() = synchronized(lifecycleLock) {
        if (advCallback != null) return@synchronized
        try {
        if (adapter.name != identity.bluetoothName) {
            adapter.name = identity.bluetoothName
            onLog("Bluetooth name set to '${identity.bluetoothName}'")
        }
        bleAdvertiser = adapter.bluetoothLeAdvertiser
        if (bleAdvertiser == null) {
            onLog("BLE advertiser unavailable on this adapter (not supported?)")
            diagnostics.ble(diagnosticToken, ConnectionDiagnostics.Status.UNAVAILABLE)
            return@synchronized
        }
        diagnostics.ble(diagnosticToken, ConnectionDiagnostics.Status.SUPPORTED)
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()
        // BLE advert hard-capped at 31 bytes. Truncate Apple mfg data to keep it short.
        val mfgPayload = if (identity.manufacturerData.size > 8) {
            identity.manufacturerData.copyOfRange(0, 8)
        } else identity.manufacturerData

        val primary = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addManufacturerData(Iap2Constants.APPLE_COMPANY_ID, mfgPayload)
            .build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(Iap2Constants.SERVICE_UUID_IPHONE))
            .build()

        // Fallback: bare mfg-data only, no scan response (some chipsets reject
        // 4-arg startAdvertising even when each blob fits 31 bytes individually).
        val minimal = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addManufacturerData(Iap2Constants.APPLE_COMPANY_ID, mfgPayload)
            .build()
        fallbackPlan = Triple("mfg-only, no scan response", settings, minimal)

        onLog("BLE advert: mfg=${mfgPayload.size}B + scan-resp(name+UUID)")
        val cb = callback(false)
        advCallback = cb
        diagnostics.ble(diagnosticToken, ConnectionDiagnostics.Status.STARTING)
        bleAdvertiser?.startAdvertising(settings, primary, scanResponse, cb)
        } catch (e: Exception) {
            diagnostics.ble(diagnosticToken, ConnectionDiagnostics.Status.FAILED, e.javaClass.simpleName)
            onLog("BLE start failed: ${e.javaClass.simpleName}")
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() = synchronized(lifecycleLock) {
        val cb = advCallback
        advCallback = null
        fallbackPlan = null
        diagnostics.ble(diagnosticToken, ConnectionDiagnostics.Status.STOPPED)
        try { cb?.let { bleAdvertiser?.stopAdvertising(it) } } finally { bleAdvertiser = null }
    }
}

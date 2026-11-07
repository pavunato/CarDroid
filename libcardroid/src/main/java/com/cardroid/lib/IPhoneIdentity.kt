package com.cardroid.lib

/**
 * Identity values broadcast over Bluetooth + injected into iAP2
 * Identification messages to pose as an iPhone.
 *
 * These defaults are generic iPhone-like metadata. The extracted OkCar behavior
 * is intentionally limited to MFi authentication, not identity or post-auth data.
 */
data class IPhoneIdentity(
    /** Bluetooth Classic name presented over Extended Inquiry Response. */
    val bluetoothName: String = DEVICE_NAME,
    /** Marketing / model name (control-session param). */
    val model: String = "iPhone18,4",
    /** Friendly device name. */
    val name: String = DEVICE_NAME,
    /** iOS-like firmware version. */
    val firmwareVersion: String = "23H30",
    /** Serial number. */
    val serial: String = "F2LL2DEFGHIJ",
    /** Hardware revision. */
    val hardwareVersion: String = "1.0",
    /** Manufacturer string. */
    val manufacturer: String = "Apple Inc.",
    /**
     * Apple's manufacturer-data payload carried in BLE advertisement.
     * Android's BLE API adds the 0x004C company ID separately, so this begins
     * with Apple's payload type rather than 4C 00.
     */
    val manufacturerData: ByteArray = Bytes.iphoneBackgroundAdvertisement(),
) {
    companion object { const val DEVICE_NAME = "CarDroid" }

    object Bytes {
        /** Generic iPhone-background manufacturer payload: type 0x01 + 16 bytes. */
        fun iphoneBackgroundAdvertisement(): ByteArray {
            val out = ByteArray(17)
            out[0] = 0x01
            return out
        }

        fun carPlayAdvertisement(carPlayBytes: ByteArray): ByteArray =
            carPlayBytes
    }
}

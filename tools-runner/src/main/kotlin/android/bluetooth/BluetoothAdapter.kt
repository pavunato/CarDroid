package android.bluetooth

import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** JVM-only surface for testing the production Bluetooth transport with simulated streams. */
open class BluetoothAdapter {
    open fun listenUsingRfcommWithServiceRecord(name: String, uuid: UUID): BluetoothServerSocket = error("stub")
}
open class BluetoothServerSocket {
    open fun accept(): BluetoothSocket = error("stub")
    open fun close() {}
}
open class BluetoothSocket {
    open val inputStream: InputStream get() = error("stub")
    open val outputStream: OutputStream get() = error("stub")
    open val remoteDevice: BluetoothDevice get() = error("stub")
    open fun close() {}
}
open class BluetoothDevice { open val address: String get() = "test-device" }

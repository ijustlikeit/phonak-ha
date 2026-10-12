package app.phonakbridge

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

class AidException(message: String, cause: Throwable? = null) : Exception(message, cause)

private const val TAG = "GattLink"
private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

/**
 * One LE GATT connection, with Android's callback API turned into suspend
 * functions. Android allows one GATT operation at a time, so they take turns.
 *
 * The phone is already bonded with the aids (myPhonak did that), so Android
 * resolves their private addresses and encrypts the link itself.
 */
@SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is checked before the service starts
class GattLink private constructor(val device: BluetoothDevice) {
    private var gatt: BluetoothGatt? = null
    private val opLock = Mutex()
    @Volatile private var pendingOp: CompletableDeferred<Any?>? = null
    private val connected = CompletableDeferred<Unit>()

    /** Notification handlers by characteristic. Called on a binder thread. */
    val notifyHandlers = ConcurrentHashMap<UUID, (ByteArray) -> Unit>()

    @Volatile var isConnected = false
        private set
    @Volatile var mtu = 23
        private set

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "${device.address} state $newState status $status")
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                isConnected = true
                connected.complete(Unit)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isConnected = false
                val error = AidException("Disconnected (status $status)")
                connected.completeExceptionally(error)
                pendingOp?.completeExceptionally(error)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = finish(status, Unit)

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) this@GattLink.mtu = mtu
            finish(BluetoothGatt.GATT_SUCCESS, Unit) // a refused MTU still leaves 23 working
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int,
        ) = finish(status, value)

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) =
            finish(status, Unit)

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) =
            finish(status, Unit)

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            Log.d(TAG, "${device.address} notify ${c.uuid.toString().take(8)}: ${value.toHex()}")
            notifyHandlers[c.uuid]?.invoke(value)
        }
    }

    private fun finish(status: Int, result: Any?) {
        val op = pendingOp ?: return
        if (status == BluetoothGatt.GATT_SUCCESS) op.complete(result)
        else op.completeExceptionally(AidException("GATT error $status"))
    }

    /** Runs one GATT operation: [start] kicks it off, a callback completes it. */
    private suspend fun <T> op(what: String, timeoutMs: Long = 10_000, start: (BluetoothGatt) -> Boolean): T =
        opLock.withLock {
            val g = gatt ?: throw AidException("Not connected")
            if (!isConnected) throw AidException("Not connected")
            val done = CompletableDeferred<Any?>()
            pendingOp = done
            try {
                // The stack refuses a new operation while it is still busy with the last one.
                var attempts = 0
                while (!start(g)) {
                    if (++attempts >= 5) throw AidException("Could not start $what")
                    delay(50)
                }
                @Suppress("UNCHECKED_CAST")
                withTimeout(timeoutMs) { done.await() } as T
            } catch (e: TimeoutCancellationException) {
                throw AidException("Timed out: $what", e)
            } finally {
                pendingOp = null
            }
        }

    fun characteristic(uuid: UUID): BluetoothGattCharacteristic? =
        gatt?.services?.firstNotNullOfOrNull { it.getCharacteristic(uuid) }

    private fun require(uuid: UUID) =
        characteristic(uuid) ?: throw AidException("Hearing aid has no characteristic $uuid")

    suspend fun read(uuid: UUID): ByteArray {
        val c = require(uuid)
        return op("read $uuid") { it.readCharacteristic(c) }
    }

    suspend fun write(uuid: UUID, value: ByteArray, withResponse: Boolean = false) {
        val c = require(uuid)
        Log.d(TAG, "${device.address} write ${uuid.toString().take(8)}: ${value.toHex()}")
        val type = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        op<Unit>("write $uuid") { it.writeCharacteristic(c, value, type) == BluetoothStatusCodes.SUCCESS }
    }

    suspend fun enableNotify(uuid: UUID) {
        val c = require(uuid)
        val g = gatt ?: throw AidException("Not connected")
        if (!g.setCharacteristicNotification(c, true)) throw AidException("Could not enable notifications")
        val d = c.getDescriptor(CCCD) ?: return
        op<Unit>("subscribe $uuid") {
            it.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
        }
    }

    fun close() {
        isConnected = false
        pendingOp?.completeExceptionally(AidException("Closed"))
        gatt?.let {
            it.disconnect()
            it.close()
        }
        gatt = null
    }

    companion object {
        /** Connects over LE, negotiates the MTU and discovers the services. */
        suspend fun connect(context: Context, device: BluetoothDevice, timeoutMs: Long = 20_000): GattLink {
            val link = GattLink(device)
            try {
                link.gatt = device.connectGatt(context, false, link.callback, BluetoothDevice.TRANSPORT_LE)
                    ?: throw AidException("Bluetooth refused to connect")
                try {
                    withTimeout(timeoutMs) { link.connected.await() }
                } catch (e: TimeoutCancellationException) {
                    throw AidException("Not in range (no connection after ${timeoutMs / 1000} s)", e)
                }
                link.op<Unit>("MTU") { it.requestMtu(517) }
                link.op<Unit>("service discovery", 20_000) { it.discoverServices() }
                return link
            } catch (e: Throwable) {
                link.close()
                throw e
            }
        }
    }
}

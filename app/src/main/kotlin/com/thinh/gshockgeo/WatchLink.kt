package com.thinh.gshockgeo

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "GShockGeo"

/**
 * One GATT session to the watch. Android multiplexes GATT clients over one link,
 * so this works while G-SHOCK MOVE keeps its own connection.
 */
@SuppressLint("MissingPermission")
class WatchLink private constructor(private val gatt: BluetoothGatt) {
    private val notifications = ConcurrentHashMap<UUID, Channel<ByteArray>>()
    @Volatile private var pendingWrite: CompletableDeferred<Int>? = null
    @Volatile private var pendingDescriptor: CompletableDeferred<Int>? = null
    @Volatile private var pendingServices: CompletableDeferred<Int>? = null
    @Volatile private var lost: Throwable? = null

    private fun characteristic(uuid: UUID) =
        gatt.getService(WatchProtocol.SERVICE)?.getCharacteristic(uuid) ?: error("watch has no characteristic $uuid")

    private fun channel(uuid: UUID) = notifications.getOrPut(uuid) { Channel(Channel.UNLIMITED) }

    /**
     * Reads one feature: the request goes to 0x2C, the answer comes back as a 0x2D notification.
     * Replies to G-SHOCK MOVE's own reads arrive here too, so the reply must start with the whole request.
     */
    suspend fun read(request: ByteArray): ByteArray? {
        drain(WatchProtocol.ALL_FEATURES)
        Log.i(TAG, "-> read ${request.hex()}")
        write(WatchProtocol.READ_REQUEST, request, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        // An error reply looks like `ff <status> <class>`.
        val reply = awaitNotification(WatchProtocol.ALL_FEATURES, 5_000) {
            it.startsWith(request) || (it.size >= 3 && it[0] == 0xFF.toByte() && it[2] == request[0])
        }
        if (reply == null) Log.w(TAG, "no reply to ${request.hex()}")
        return reply?.takeIf { it.startsWith(request) }
    }

    suspend fun write(frame: ByteArray) {
        Log.i(TAG, "-> write ${frame.hex()}")
        write(WatchProtocol.ALL_FEATURES, frame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
    }

    suspend fun write(uuid: UUID, value: ByteArray, type: Int) {
        val done = CompletableDeferred<Int>().also { pendingWrite = it }
        checkAlive()
        check(gatt.writeCharacteristic(characteristic(uuid), value, type) == BluetoothStatusCodes.SUCCESS) { "write rejected" }
        val status = withTimeout(10_000) { done.await() }
        check(status == BluetoothGatt.GATT_SUCCESS) { "write status $status" }
    }

    suspend fun awaitNotification(uuid: UUID, timeoutMillis: Long, accept: (ByteArray) -> Boolean): ByteArray? {
        checkAlive()
        return withTimeoutOrNull(timeoutMillis) {
            var value: ByteArray
            do value = channel(uuid).receive() while (!accept(value))
            value
        }
    }

    fun drain(uuid: UUID) {
        while (channel(uuid).tryReceive().isSuccess) Unit
    }

    /**
     * Turns notifications on. Never writes "off": the descriptor lives on the watch and is shared with
     * G-SHOCK MOVE, which would stop hearing the watch. [close] drops our local registration.
     */
    suspend fun enableNotify(uuid: UUID) {
        gatt.setCharacteristicNotification(characteristic(uuid), true)
        val descriptor = characteristic(uuid).getDescriptor(WatchProtocol.CCC) ?: error("no CCC on $uuid")
        val done = CompletableDeferred<Int>().also { pendingDescriptor = it }
        checkAlive()
        check(gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS) { "descriptor write rejected" }
        withTimeout(10_000) { done.await() }
    }

    private suspend fun discover() {
        val discovered = CompletableDeferred<Int>().also { pendingServices = it }
        gatt.discoverServices()
        withTimeout(15_000) { discovered.await() }
    }

    private fun checkAlive() {
        lost?.let { throw it }
    }

    /** Fails everything in flight at once instead of letting each wait run out its timeout. */
    private fun onLost(status: Int) {
        val cause = IllegalStateException("watch disconnected, status $status")
        lost = cause
        listOfNotNull(pendingWrite, pendingDescriptor, pendingServices).forEach { it.completeExceptionally(cause) }
        notifications.values.forEach { it.close(cause) }
    }

    fun close() {
        gatt.disconnect()
        gatt.close()
    }

    companion object {
        /**
         * The watch, through the link G-SHOCK MOVE holds. Null without that link: on tested phones a
         * connection from this app goes through the bonded record, whose address type never connects.
         */
        fun findWatch(context: Context): BluetoothDevice? =
            context.getSystemService(BluetoothManager::class.java).getConnectedDevices(BluetoothProfile.GATT)
                .firstOrNull { it.name?.startsWith("CASIO") == true }

        /** Connects over the live link, runs [block], and always closes the session. */
        suspend fun <T> use(context: Context, device: BluetoothDevice, block: suspend (WatchLink) -> T): T {
            val connected = CompletableDeferred<Unit>()
            var holder: WatchLink? = null
            val callback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                    Log.i(TAG, "connection state=$newState status=$status")
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        connected.complete(Unit)
                    } else {
                        connected.completeExceptionally(IllegalStateException("could not connect, status $status"))
                        holder?.onLost(status)
                    }
                }

                override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                    holder?.pendingServices?.complete(status)
                }

                override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
                    holder?.pendingWrite?.complete(status)
                }

                override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
                    holder?.pendingDescriptor?.complete(status)
                }

                override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
                    Log.d(TAG, "<- ${c.uuid.toString().take(8)} ${value.hex()}")
                    holder?.channel(c.uuid)?.trySend(value)
                }
            }
            val gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            val link = WatchLink(gatt)
            holder = link
            try {
                withTimeout(30_000) { connected.await() }
                link.discover()
                link.enableNotify(WatchProtocol.ALL_FEATURES)
                return block(link)
            } finally {
                link.close()
            }
        }
    }
}

private fun ByteArray.startsWith(prefix: ByteArray) = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

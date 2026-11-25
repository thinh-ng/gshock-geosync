package com.thinh.gshockgeo

import android.bluetooth.BluetoothGattCharacteristic
import android.util.Log
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "GShockGeo"

/**
 * Uploads a tide port and selects it, the way G-SHOCK MOVE does (WatchDataWriter.writeTideData):
 * the record goes through the Convoy characteristic inside a data-request transaction, then the
 * tide settings (0x2E unit 2) point at it. The watch computes the tide curve itself.
 */
object TideTransfer {
    private const val CMD_START: Byte = 0x00
    private const val CMD_ABORT: Byte = 0x03
    private const val CMD_END: Byte = 0x04
    private const val CONVOY_DATA: Byte = 0x05
    // The official app waits 400 ms between chunks; faster writes were not tried.
    private const val CHUNK_GAP_MILLIS = 400L
    private val SETTINGS_REQUEST = byteArrayOf(WatchProtocol.CLASS_TIDE, WatchProtocol.TIDE_SETTINGS_UNIT)

    suspend fun upload(link: WatchLink, port: TidePort): Boolean {
        val settings = link.read(SETTINGS_REQUEST)
        if (settings == null || settings.size < 7) {
            Log.w(TAG, "could not read tide settings: ${settings?.hex()}")
            return false
        }
        link.enableNotify(WatchProtocol.DATA_REQUEST)
        link.enableNotify(WatchProtocol.CONVOY)
        val sent = try {
            sendRecord(link, port.record())
        } catch (e: Throwable) {
            abort(link)
            throw e
        }
        if (!sent) {
            abort(link)
            return false
        }
        link.write(selectPort(settings, port.id))
        val after = link.read(SETTINGS_REQUEST)
        Log.i(TAG, "tide settings now ${after?.hex()}")
        return after != null && isSelected(after, port.id)
    }

    fun isSelected(settings: ByteArray, portId: Int) =
        settings.size >= 7 && settings[2] == WatchProtocol.TIDE_SOURCE_APP_PORT && portId(settings) == portId

    private suspend fun sendRecord(link: WatchLink, record: ByteArray): Boolean {
        link.drain(WatchProtocol.CONVOY)
        link.drain(WatchProtocol.DATA_REQUEST)
        link.write(WatchProtocol.DATA_REQUEST, transaction(CMD_START, record.size), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)

        val start = link.awaitNotification(WatchProtocol.CONVOY, 5_000) { it[0] == 0x00.toByte() }
        if (start == null || start.size < 2 || start[1] != 0x00.toByte()) {
            Log.w(TAG, "watch refused the transfer: ${start?.hex()}")
            return false
        }
        val params = link.awaitNotification(WatchProtocol.CONVOY, 5_000) { it[0] == 0x02.toByte() || it[0] == 0x06.toByte() }
        if (params == null || params.size < 3) {
            Log.w(TAG, "no transfer parameters: ${params?.hex()}")
            return false
        }
        val mtu = ByteBuffer.wrap(params, 1, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
        val chunkSize = mtu - 4
        if (chunkSize <= 0) {
            Log.w(TAG, "unusable mtu $mtu")
            return false
        }
        Log.i(TAG, "uploading ${record.size} bytes, mtu $mtu, chunk $chunkSize")

        for (offset in record.indices step chunkSize) {
            val chunk = record.copyOfRange(offset, minOf(offset + chunkSize, record.size))
            link.write(WatchProtocol.CONVOY, byteArrayOf(CONVOY_DATA) + chunk, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            delay(CHUNK_GAP_MILLIS)
        }

        val end = link.awaitNotification(WatchProtocol.DATA_REQUEST, 70_000) {
            it.size >= 2 && it[0] == CMD_END && it[1] == WatchProtocol.CATEGORY_TIDE
        }
        if (end == null) {
            Log.w(TAG, "watch never confirmed the upload")
            return false
        }
        Log.i(TAG, "watch confirmed upload: ${end.hex()}")
        link.write(WatchProtocol.DATA_REQUEST, transaction(CMD_END, 0), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        return true
    }

    /** Closes an open transaction so the next attempt is not refused; runs even when the job was cancelled. */
    private suspend fun abort(link: WatchLink) = withContext(NonCancellable) {
        runCatching {
            link.write(WatchProtocol.DATA_REQUEST, transaction(CMD_ABORT, 0), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        }.onFailure { Log.w(TAG, "abort failed: $it") }
    }

    /** [cmd][category 0x23][length, 24-bit little-endian] */
    private fun transaction(cmd: Byte, length: Int) = byteArrayOf(
        cmd, WatchProtocol.CATEGORY_TIDE, length.toByte(), (length shr 8).toByte(), (length shr 16).toByte(),
    )

    /** Keeps the watch's own bytes and switches the source to "app port" with the new id (`2e 02 04 06 07 c7 09 00`). */
    private fun selectPort(current: ByteArray, id: Int): ByteArray = current.copyOf().also {
        it[2] = WatchProtocol.TIDE_SOURCE_APP_PORT
        it[5] = id.toByte()
        it[6] = (id shr 8).toByte()
    }

    private fun portId(settings: ByteArray): Int = (settings[5].toInt() and 0xFF) or ((settings[6].toInt() and 0xFF) shl 8)
}

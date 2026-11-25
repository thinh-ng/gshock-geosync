package com.thinh.gshockgeo

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

private const val TAG = "GShockGeo"

/**
 * Exported because the Bluetooth stack sends ACL_CONNECTED from its own process, which a
 * non-exported receiver never hears. The manifest requires the sender to hold BLUETOOTH_CONNECT.
 */
class WatchConnectedReceiver : BroadcastReceiver() {
    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
        val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        if (device?.name?.startsWith("CASIO") != true) return
        // G-SHOCK MOVE rewrites location and tide during its own connect sequence; let it finish, then repair.
        Log.i(TAG, "watch connected")
        SyncJob.schedule(context, RECONNECT_DELAY_MILLIS)
    }

    private companion object {
        const val RECONNECT_DELAY_MILLIS = 30_000L
    }
}

package com.thinh.gshockgeo

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.TextView
import java.text.DateFormat
import java.util.Date
import java.util.TimeZone

/** Grants permissions once and shows what the watch should hold. The work happens in [SyncReceiver] and [SyncJob]. */
class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { setPadding(48, 160, 48, 48); textSize = 16f }
        setContentView(status)
    }

    override fun onResume() {
        super.onResume()
        val foreground = arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        when {
            foreground.any { !granted(it) } -> askOnce(foreground)
            // Android only offers "Allow all the time" as a separate, later request.
            !granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION) ->
                askOnce(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
            else -> SyncReceiver.register(this)
        }
        render()
    }

    // Returning from the dialog resumes the activity; asking again there would loop after a denial.
    private val asked = mutableSetOf<String>()

    private fun askOnce(permissions: Array<String>) {
        if (!asked.add(permissions.first())) return
        requestPermissions(permissions, 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) recreate()
    }

    private fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun watchName(): String? {
        if (!granted(Manifest.permission.BLUETOOTH_CONNECT)) return null
        return WatchLink.findWatch(this)?.name
    }

    /** Minutes after UTC midnight -> "HH:mm" in the phone's time zone. */
    private fun localClock(utcMinutes: Double): String {
        val offset = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000
        val minutes = Math.floorMod(Math.round(utcMinutes).toInt() + offset, 1440)
        return "%02d:%02d".format(minutes / 60, minutes % 60)
    }

    private fun render() {
        val target = WatchSync.target(this)
        val port = TidePorts.byId(this, WatchSync.targetPortId(this))
        val synced = WatchSync.lastSynced(this)
        status.text = buildString {
            appendLine("G-Shock GeoSync")
            appendLine()
            appendLine("Background location: ${if (granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) "allowed" else "NOT allowed"}")
            appendLine("Watch: ${watchName() ?: "not found"}")
            appendLine()
            appendLine("Sunrise/sunset location: " + (target?.let { "%.4f, %.4f".format(it.latitude, it.longitude) } ?: "none yet"))
            target?.let { SunTimes.minutes(it.latitude, it.longitude) }?.let { (rise, set) ->
                appendLine("Sunrise ${localClock(rise)}, sunset ${localClock(set)}")
            }
            appendLine("Tide port: ${port?.name ?: "none yet"}")
            appendLine()
            appendLine("Last sync: " + if (synced == 0L) "never" else DateFormat.getDateTimeInstance().format(Date(synced)))
        }
    }
}

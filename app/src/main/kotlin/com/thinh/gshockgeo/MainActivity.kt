package com.thinh.gshockgeo

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle

/** Invisible setup: asks for the permissions, starts location updates and closes. */
class MainActivity : Activity() {
    private val foreground = arrayOf(
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        next()
    }

    private fun next() {
        when {
            foreground.any { !granted(it) } -> requestPermissions(foreground, FOREGROUND)
            // Android only offers "Allow all the time" as a separate, later request.
            !granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION) ->
                requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), BACKGROUND)
            else -> {
                SyncReceiver.register(this)
                SyncJob.schedule(this)
                finish()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) next() else finish()
    }

    private fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val FOREGROUND = 1
        const val BACKGROUND = 2
    }
}

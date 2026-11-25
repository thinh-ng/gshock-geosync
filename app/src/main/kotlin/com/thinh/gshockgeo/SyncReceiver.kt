package com.thinh.gshockgeo

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.location.LocationRequest
import android.util.Log

private const val TAG = "GShockGeo"

/** Location updates and boot land here; the writing happens in [SyncJob]. */
class SyncReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> register(context)
            ACTION_LOCATION -> onLocation(context, intent)
        }
    }

    private fun onLocation(context: Context, intent: Intent) {
        val location = intent.getParcelableExtra(LocationManager.KEY_LOCATION_CHANGED, Location::class.java)
            ?: intent.getParcelableArrayListExtra(LocationManager.KEY_LOCATIONS, Location::class.java)?.lastOrNull()
            ?: return
        Log.i(TAG, "location update ${location.latitude},${location.longitude}")
        if (WatchSync.onLocation(context, location)) SyncJob.schedule(context)
    }

    companion object {
        const val ACTION_LOCATION = "com.thinh.gshockgeo.LOCATION"
        private const val INTERVAL_MILLIS = 15 * 60_000L
        // Far below the ~55 km it takes to move sunrise 2 minutes, so no real change is missed.
        private const val MIN_DISTANCE_METERS = 20_000f

        @SuppressLint("MissingPermission")
        fun register(context: Context) {
            val intent = Intent(context, SyncReceiver::class.java).setAction(ACTION_LOCATION)
            // Mutable: the platform adds the location extras to this intent.
            val pendingIntent = PendingIntent.getBroadcast(
                context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            val request = LocationRequest.Builder(INTERVAL_MILLIS)
                .setQuality(LocationRequest.QUALITY_BALANCED_POWER_ACCURACY)
                .setMinUpdateDistanceMeters(MIN_DISTANCE_METERS)
                // Also take fixes other apps already paid for, without asking for more.
                .setMinUpdateIntervalMillis(60_000L)
                .build()
            val manager = context.getSystemService(LocationManager::class.java)
            val provider = if (manager.hasProvider(LocationManager.FUSED_PROVIDER)) LocationManager.FUSED_PROVIDER else LocationManager.NETWORK_PROVIDER
            try {
                manager.requestLocationUpdates(provider, request, pendingIntent)
                Log.i(TAG, "location updates registered ($provider)")
            } catch (e: SecurityException) {
                Log.w(TAG, "no location permission: $e")
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "no location provider: $e")
            }
        }
    }
}

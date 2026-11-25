package com.thinh.gshockgeo

import android.content.Context
import android.content.SharedPreferences
import android.location.Location
import android.util.Log

private const val TAG = "GShockGeo"

/**
 * Decides what the watch should hold (location for sunrise/sunset, tide port) and makes the watch match.
 * The sync reads the watch first and writes only what differs, so it also repairs the watch after
 * G-SHOCK MOVE rewrites it on reconnect.
 */
object WatchSync {
    private const val PREFS = "sync"
    // The watch shows whole minutes; at 21°N, 30 km east or west moves sunrise about 1 minute.
    private const val MIN_SUN_SHIFT_MINUTES = 2.0
    // Stations are sparse: some beach towns' nearest station is 80 km up the coast.
    private const val PORT_RADIUS_METERS = 100_000f
    // Selecting a port moves sunrise/sunset to the port. 80 km along a north-south coast costs
    // 1-2 min (accepted); 90 km inland east-west costs over 3 min (rejected).
    private const val PORT_MAX_SUN_SHIFT_MINUTES = 3.0
    private const val NO_PORT = 0

    fun target(context: Context): Location? = context.prefs().readLocation("target")
    fun targetPortId(context: Context): Int = context.prefs().getInt("target_port", 0)
    fun lastSynced(context: Context): Long = context.prefs().getLong("synced_at", 0)

    /** Returns true when the watch needs a sync for this location. */
    fun onLocation(context: Context, here: Location): Boolean {
        var changed = false
        val current = target(context)
        val shift = current?.let { SunTimes.shiftMinutes(it.latitude, it.longitude, here.latitude, here.longitude) }
        if (shift == null || shift >= MIN_SUN_SHIFT_MINUTES) {
            Log.i(TAG, "new sun location, shift ${shift?.let { "%.1f".format(it) } ?: "n/a"} min")
            context.prefs().writeLocation("target", here)
            changed = true
        }
        val portId = choosePort(context, here)
        if (portId != targetPortId(context)) {
            Log.i(TAG, "new tide port $portId")
            context.prefs().edit().putInt("target_port", portId).apply()
            changed = true
        }
        return changed
    }

    /** Debug builds: forces a port so an upload can be tested without travelling. */
    fun setTargetPort(context: Context, id: Int) = context.prefs().edit().putInt("target_port", id).apply()

    /**
     * With an uploaded port selected, the watch takes sunrise/sunset from the port's coordinates, so a
     * port is used only when that stays close to the truth. Otherwise the watch goes back to its
     * built-in tide mode and sunrise/sunset follows the real location.
     */
    private fun choosePort(context: Context, here: Location): Int {
        val (nearest, distance) = TidePorts.nearest(context, here.latitude, here.longitude) ?: return NO_PORT
        if (distance > PORT_RADIUS_METERS) return NO_PORT
        val shift = SunTimes.shiftMinutes(here.latitude, here.longitude, nearest.latitude, nearest.longitude)
        return if (shift < PORT_MAX_SUN_SHIFT_MINUTES) nearest.id else NO_PORT
    }

    /** Brings the watch in line with the targets. Returns false when something must be retried. */
    suspend fun sync(context: Context): Boolean {
        val location = target(context) ?: return true
        val port = TidePorts.byId(context, targetPortId(context))
        val device = WatchLink.findWatch(context) ?: return false.also { Log.w(TAG, "no watch") }
        val ok = WatchLink.use(context, device) { link ->
            val locationOk = syncLocation(link, location.latitude, location.longitude)
            val tideOk = if (port == null) releasePort(link) else syncTide(link, port)
            locationOk && tideOk
        }
        if (ok) context.prefs().edit().putLong("synced_at", System.currentTimeMillis()).apply()
        return ok
    }

    private suspend fun syncLocation(link: WatchLink, lat: Double, lon: Double): Boolean {
        if (matches(link.readLocation(), lat, lon)) return true
        // The official app's order: 1D, 1E, 24, 1F, time. A lone 24 write is dropped; 1D/1E/1F are echoed unchanged.
        val dstState = link.read(byteArrayOf(0x1D))
        val dstSetting = link.read(byteArrayOf(0x1E, 0x00))
        val cityName = link.read(byteArrayOf(0x1F, 0x00))
        dstState?.let { link.write(it) }
        dstSetting?.let { link.write(it) }
        link.write(WatchProtocol.locationFrame(lat, lon))
        cityName?.let { link.write(it) }
        link.write(WatchProtocol.timeFrame())
        val stored = link.readLocation()
        Log.i(TAG, "watch location now $stored")
        return matches(stored, lat, lon)
    }

    /** Inland: switch an uploaded port back to the watch's built-in tide mode (`2e 02 01 ...`). */
    private suspend fun releasePort(link: WatchLink): Boolean {
        val settings = link.read(byteArrayOf(WatchProtocol.CLASS_TIDE, WatchProtocol.TIDE_SETTINGS_UNIT)) ?: return false
        if (settings.size < 3 || settings[2] != WatchProtocol.TIDE_SOURCE_APP_PORT) return true
        Log.i(TAG, "inland, back to built-in tide mode")
        link.write(settings.copyOf().also { it[2] = WatchProtocol.TIDE_SOURCE_BUILT_IN })
        val after = link.read(byteArrayOf(WatchProtocol.CLASS_TIDE, WatchProtocol.TIDE_SETTINGS_UNIT))
        return after != null && after.size >= 3 && after[2] == WatchProtocol.TIDE_SOURCE_BUILT_IN
    }

    private suspend fun syncTide(link: WatchLink, port: TidePort): Boolean {
        val settings = link.read(byteArrayOf(WatchProtocol.CLASS_TIDE, WatchProtocol.TIDE_SETTINGS_UNIT))
        if (settings != null && TideTransfer.isSelected(settings, port.id)) return true
        Log.i(TAG, "uploading tide port ${port.name}")
        return TideTransfer.upload(link, port)
    }

    private suspend fun WatchLink.readLocation() =
        read(byteArrayOf(WatchProtocol.CLASS_LOCATION, WatchProtocol.HOME_TIME))?.let(WatchProtocol::parseLocation)

    private fun matches(stored: Pair<Double, Double>?, lat: Double, lon: Double) =
        stored != null && Math.abs(stored.first - lat) < 1e-6 && Math.abs(stored.second - lon) < 1e-6

    private fun Context.prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun SharedPreferences.readLocation(key: String): Location? {
        if (!contains("${key}_lat")) return null
        return Location(key).apply {
            latitude = getString("${key}_lat", null)!!.toDouble()
            longitude = getString("${key}_lon", null)!!.toDouble()
            time = getLong("${key}_time", 0)
        }
    }

    private fun SharedPreferences.writeLocation(key: String, location: Location) =
        edit().putString("${key}_lat", location.latitude.toString())
            .putString("${key}_lon", location.longitude.toString())
            .putLong("${key}_time", location.time)
            .apply()
}

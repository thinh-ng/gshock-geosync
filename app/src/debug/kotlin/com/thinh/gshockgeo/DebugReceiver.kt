package com.thinh.gshockgeo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Debug builds only. Test hooks driven over adb:
 *   am broadcast -n com.thinh.gshockgeo/.DebugReceiver --es cmd raw --es hex "'2e 02'"
 *   am broadcast -n com.thinh.gshockgeo/.DebugReceiver --es cmd tide --ei id 2504
 *   am broadcast -n com.thinh.gshockgeo/.DebugReceiver --es cmd sun --ed lat 21.03 --ed lon 105.85
 *   am broadcast -n com.thinh.gshockgeo/.DebugReceiver --es cmd city --ed lat 21.03 --ed lon 105.85
 *   am broadcast -n com.thinh.gshockgeo/.DebugReceiver --es cmd custom --es name TEST --ed lat 21.0 --ed lon 105.9 --ei port 2503
 *   am broadcast -n com.thinh.gshockgeo/.DebugReceiver --es cmd sync
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                // A background broadcast is killed as an ANR at 60 s; a BLE session can wait longer.
                withTimeout(50_000) { run(context, intent) }
            } catch (e: Exception) {
                Log.e(TAG, "debug command failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun run(context: Context, intent: Intent) {
        when (intent.getStringExtra("cmd")) {
            "sync" -> SyncJob.schedule(context)
            "sun" -> {
                val lat = intent.getDoubleExtra("lat", 0.0)
                val lon = intent.getDoubleExtra("lon", 0.0)
                val (rise, set) = SunTimes.minutes(lat, lon) ?: return
                val local = { m: Double -> "%02d:%02d".format(((m + 420) / 60).toInt() % 24, ((m + 420) % 60).toInt()) }
                Log.i(TAG, "sun at $lat,$lon: rise ${local(rise)} set ${local(set)} (UTC+7)")
            }
            "city" -> {
                val lat = intent.getDoubleExtra("lat", 0.0)
                val lon = intent.getDoubleExtra("lon", 0.0)
                Log.i(TAG, "city at $lat,$lon: ${CityName.lookup(context, lat, lon)}")
            }
            "custom" -> {
                val lat = intent.getDoubleExtra("lat", 0.0)
                val lon = intent.getDoubleExtra("lon", 0.0)
                val name = intent.getStringExtra("name") ?: "TEST"
                val id = intent.getIntExtra("id", 9001)
                val base = intent.getIntExtra("port", 0)
                val station = TidePorts.byId(context, base)?.relocated(id, name, lat, lon) ?: TidePort.flat(id, name, lat, lon)
                val device = WatchLink.findWatch(context) ?: return Unit.also { Log.e(TAG, "no watch") }
                WatchLink.use(context, device) { link -> Log.i(TAG, "custom upload ok=${TideTransfer.upload(link, station)}") }
            }
            "tide" -> {
                // Through the job: an upload outlasts what a broadcast may run.
                WatchSync.setTargetPort(context, intent.getIntExtra("id", 0))
                SyncJob.schedule(context)
            }
            "rawwrite" -> {
                val device = WatchLink.findWatch(context) ?: return Unit.also { Log.e(TAG, "no watch") }
                WatchLink.use(context, device) { link ->
                    link.write(intent.getStringExtra("hex")!!.split(" ").map { it.toInt(16).toByte() }.toByteArray())
                }
            }
            "raw" -> {
                val device = WatchLink.findWatch(context) ?: return Unit.also { Log.e(TAG, "no watch") }
                WatchLink.use(context, device) { link ->
                    link.read(intent.getStringExtra("hex")!!.split(" ").map { it.toInt(16).toByte() }.toByteArray())
                }
            }
        }
    }

    private companion object {
        const val TAG = "GShockGeo"
    }
}

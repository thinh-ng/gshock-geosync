package com.thinh.gshockgeo

import android.content.Context
import android.location.Location
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.TimeZone
import java.util.zip.ZipFile

/** One tide station from Casio's bundled 60-component table (Asia, Americas, Japan, Oceania). */
class TidePort(
    val id: Int,
    val name: String,
    val latitude: Double,
    /** The watch's longitude column; its sign is flipped for most non-Japan ports. */
    private val watchLongitude: Double,
    val longitude: Double,
    private val z0: Double,
    private val graphPattern: Int,
    private val amplitudes: List<Double>,
    private val phases: List<Double>,
    private val hcT: Int,
) {
    fun distanceMeters(lat: Double, lon: Double): Float {
        val result = FloatArray(1)
        Location.distanceBetween(lat, lon, latitude, longitude, result)
        return result[0]
    }

    /** The 1009-byte record the official app uploads (Port60ComponentTideInfo.getWriteData). */
    fun record(timeZone: TimeZone = TimeZone.getDefault()): ByteArray {
        val buf = ByteBuffer.allocate(1009).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(LIST_NO)
        buf.put(name.toByteArray(Charsets.UTF_8).copyOf(18))
        buf.putDouble(latitude).putDouble(watchLongitude)
        // The app takes these from Casio's tz database at the port; the phone's zone is the same place.
        buf.put((timeZone.rawOffset / 60_000 / 15).toByte())
        buf.put((timeZone.dstSavings / 60_000 / 15).toByte())
        buf.put(0)
        buf.putDouble(z0)
        buf.putShort(graphPattern.toShort())
        repeat(60) { buf.putDouble(amplitudes.getOrElse(it) { 0.0 }) }
        repeat(60) { buf.putDouble(phases.getOrElse(it) { 0.0 }) }
        buf.put(hcT.toByte())
        return buf.array()
    }

    companion object {
        private const val LIST_NO: Byte = 2
    }
}

/**
 * Casio's station table is Casio's data, so it is not shipped here: it is read from the G-SHOCK MOVE
 * app installed on the same phone, which the watch needs anyway.
 */
object TidePorts {
    private const val CASIO_APP = "com.casio.gshockmove"
    private const val TABLE = "assets/flutter_assets/packages/casio_watch_lib/assets/tide_csv/move/port_60_component_tide.txt"

    @Volatile
    private var cache: List<TidePort>? = null

    /** Empty when G-SHOCK MOVE is missing; then tide is left alone. Not cached, so a later install is picked up. */
    fun all(context: Context): List<TidePort> =
        cache ?: runCatching { load(context) }
            .onFailure { Log.w("GShockGeo", "no tide table from $CASIO_APP: $it") }
            .getOrDefault(emptyList())
            .also { if (it.isNotEmpty()) cache = it }

    fun byId(context: Context, id: Int): TidePort? = all(context).firstOrNull { it.id == id }

    fun nearest(context: Context, lat: Double, lon: Double): Pair<TidePort, Float>? =
        all(context).map { it to it.distanceMeters(lat, lon) }.minByOrNull { it.second }

    private fun load(context: Context): List<TidePort> {
        val apk = context.packageManager.getApplicationInfo(CASIO_APP, 0).sourceDir
        return ZipFile(apk).use { zip ->
            val entry = zip.getEntry(TABLE) ?: error("table not found in $apk")
            zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).useLines(::parse)
        }
    }

    private fun parse(lines: Sequence<String>): List<TidePort> =
        lines.drop(1).mapNotNull { line ->
            val f = splitCsv(line)
            if (f.size < 133) return@mapNotNull null
            runCatching {
                TidePort(
                    id = f[1].toInt(),
                    name = f[2].trim(),
                    latitude = f[3].toDouble(),
                    watchLongitude = f[4].toDouble(),
                    longitude = f[130].toDouble(),
                    z0 = f[5].toDouble(),
                    graphPattern = graphPatternNumber(f[8].trim()),
                    amplitudes = (9 until 69).map { f[it].toDouble() },
                    phases = (69 until 129).map { f[it].toDouble() },
                    hcT = f[129].toInt(),
                )
            }.getOrNull()
        }.toList()

    /** "10D" -> 104: letters A..I become digits 1..9, as the official app does. Throws on anything else. */
    private fun graphPatternNumber(pattern: String): Int {
        if (pattern.isEmpty()) return 0
        return pattern.map { if (it in 'A'..'I') '1' + (it - 'A') else it }.joinToString("").toInt()
    }

    /** Comma split that keeps quoted names such as "PORT, BAY" intact. */
    private fun splitCsv(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        for (c in line) {
            when {
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> fields += current.toString().also { current.clear() }
                else -> current.append(c)
            }
        }
        fields += current.toString()
        return fields
    }
}

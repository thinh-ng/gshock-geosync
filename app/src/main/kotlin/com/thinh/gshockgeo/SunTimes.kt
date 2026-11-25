package com.thinh.gshockgeo

import java.time.LocalDate
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** Sunrise and sunset in minutes after UTC midnight (NOAA approximation, accurate to about a minute). */
object SunTimes {
    private const val ZENITH = 90.833

    fun minutes(lat: Double, lon: Double, date: LocalDate = LocalDate.now()): Pair<Double, Double>? {
        val rise = event(lat, lon, date, rising = true) ?: return null
        val set = event(lat, lon, date, rising = false) ?: return null
        return rise to set
    }

    /** Largest change, in minutes, of sunrise or sunset between two places. */
    fun shiftMinutes(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Double {
        val from = minutes(fromLat, fromLon) ?: return Double.MAX_VALUE
        val to = minutes(toLat, toLon) ?: return Double.MAX_VALUE
        return maxOf(Math.abs(from.first - to.first), Math.abs(from.second - to.second))
    }

    private fun event(lat: Double, lon: Double, date: LocalDate, rising: Boolean): Double? {
        val dayOfYear = date.dayOfYear
        val lngHour = lon / 15
        val t = dayOfYear + ((if (rising) 6.0 else 18.0) - lngHour) / 24
        val meanAnomaly = 0.9856 * t - 3.289
        val trueLong = normalize(meanAnomaly + 1.916 * sinDeg(meanAnomaly) + 0.020 * sinDeg(2 * meanAnomaly) + 282.634, 360.0)
        var ascension = normalize(Math.toDegrees(atan2(0.91764 * sinDeg(trueLong), cosDeg(trueLong))), 360.0)
        ascension += floor(trueLong / 90) * 90 - floor(ascension / 90) * 90
        ascension /= 15
        val sinDec = 0.39782 * sinDeg(trueLong)
        val cosDec = cos(asin(sinDec))
        val cosHour = (cosDeg(ZENITH) - sinDec * sinDeg(lat)) / (cosDec * cosDeg(lat))
        if (cosHour > 1 || cosHour < -1) return null
        val hourAngle = Math.toDegrees(acos(cosHour)).let { if (rising) 360 - it else it } / 15
        val localMean = hourAngle + ascension - 0.06571 * t - 6.622
        return normalize(localMean - lngHour, 24.0) * 60
    }

    private fun sinDeg(d: Double) = sin(Math.toRadians(d))
    private fun cosDeg(d: Double) = cos(Math.toRadians(d))
    private fun normalize(v: Double, range: Double) = ((v % range) + range) % range
}

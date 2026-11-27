package com.thinh.gshockgeo

import android.content.Context
import android.location.Location
import java.text.Normalizer
import java.util.Locale

/** Names a location after the city around it, from GeoNames' cities of 15,000+ people bundled in assets. */
object CityName {
    private class City(val name: String, val lat: Double, val lon: Double, val population: Long)

    private const val RADIUS_KM = 50.0
    // Weights population against distance, so the outskirts of Hanoi (Gia Lam, 300k at 3 km) still
    // read "HANOI" (8M at 10 km), while a town far from any big city keeps its own name.
    private const val DISTANCE_OFFSET_KM = 5.0

    @Volatile private var cities: List<City>? = null

    fun lookup(context: Context, lat: Double, lon: Double): String? {
        val all = cities ?: load(context).also { cities = it }
        val withDistance = all.map { it to distanceKm(lat, lon, it.lat, it.lon) }
        val near = withDistance.filter { it.second <= RADIUS_KM }
        val best = if (near.isEmpty()) withDistance.minByOrNull { it.second }
        else near.maxByOrNull { (city, km) -> city.population / Math.pow(km + DISTANCE_OFFSET_KM, 2.0) }
        return best?.first?.name?.let(::toWatchText)?.takeIf { it.isNotBlank() }
    }

    private fun load(context: Context): List<City> =
        context.assets.open("cities.tsv").bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() }.map { line ->
                val (name, lat, lon, population) = line.split('\t')
                City(name, lat.toDouble(), lon.toDouble(), population.toLong())
            }.toList()
        }

    private fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val result = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, result)
        return result[0] / 1000.0
    }

    // The watch font has no diacritics or lower case. "Đà Nẵng" -> "DA NANG".
    fun toWatchText(name: String): String =
        Normalizer.normalize(name.replace('đ', 'd').replace('Đ', 'D'), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .uppercase(Locale.ROOT)
            .replace(Regex("[^A-Z0-9 .'-]"), "")
            .trim()
}

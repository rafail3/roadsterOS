package ro.rdstr.car

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Offline maps come in one pack per country. The unit ships with Romania; from the position it tells which country
 * the car is in, and offers that country's pack when it is missing, as a download over Wi-Fi only.
 */
object MapPacks {
    /** A country's outline: polygons of rings (outer ring first, then holes), each ring a flat lon,lat list. */
    class Country(val code: String, val name: String, val polygons: List<List<DoubleArray>>)

    enum class Offer { NONE, DOWNLOAD, NEEDS_WIFI }

    /** The borders file: GeoJSON features with `code` and `name`, Polygon or MultiPolygon. */
    fun parseBorders(geojson: String): List<Country> =
        Json.parseToJsonElement(geojson).jsonObject.getValue("features").jsonArray.map { feature ->
            val props = feature.jsonObject.getValue("properties").jsonObject
            val geometry = feature.jsonObject.getValue("geometry").jsonObject
            val coordinates = geometry.getValue("coordinates").jsonArray
            val polygons = when (geometry.getValue("type").jsonPrimitive.content) {
                "Polygon" -> listOf(rings(coordinates))
                else -> coordinates.map { rings(it.jsonArray) }
            }
            Country(props.getValue("code").jsonPrimitive.content, props.getValue("name").jsonPrimitive.content, polygons)
        }

    private fun rings(polygon: JsonArray): List<DoubleArray> = polygon.map { ring ->
        val points = ring.jsonArray
        DoubleArray(points.size * 2) { i -> points[i / 2].jsonArray[i % 2].jsonPrimitive.double }
    }

    fun countryAt(countries: List<Country>, lon: Double, lat: Double): Country? =
        countries.firstOrNull { c -> c.polygons.any { rings -> inside(rings[0], lon, lat) && rings.drop(1).none { inside(it, lon, lat) } } }

    /** Even-odd ray cast over one ring. */
    private fun inside(ring: DoubleArray, lon: Double, lat: Double): Boolean {
        var hit = false
        var j = ring.size - 2
        for (i in 0 until ring.size step 2) {
            val (xi, yi, xj, yj) = listOf(ring[i], ring[i + 1], ring[j], ring[j + 1])
            if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) hit = !hit
            j = i
        }
        return hit
    }

    fun offer(country: String?, installed: Set<String>, wifi: Boolean): Offer = when {
        country == null || country in installed -> Offer.NONE
        wifi -> Offer.DOWNLOAD
        else -> Offer.NEEDS_WIFI
    }
}

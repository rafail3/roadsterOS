package ro.rdstr.map

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.pow

enum class LayerType { BACKGROUND, FILL, LINE, TEXT }
enum class Font { REGULAR, BOLD, ITALIC }

/** A value by zoom: a constant, or stops interpolated exponentially by [base] (1 is linear), clamped at both ends. */
class Zoomed(private val base: Double, private val stops: List<Pair<Double, Double>>) {
    fun at(zoom: Double): Double {
        if (zoom <= stops.first().first) return stops.first().second
        if (zoom >= stops.last().first) return stops.last().second
        val i = stops.indexOfLast { it.first <= zoom }
        val (z0, v0) = stops[i]
        val (z1, v1) = stops[i + 1]
        val t = if (base == 1.0) (zoom - z0) / (z1 - z0) else (base.pow(zoom - z0) - 1) / (base.pow(z1 - z0) - 1)
        return v0 + t * (v1 - v0)
    }

    companion object {
        fun constant(v: Double) = Zoomed(1.0, listOf(0.0 to v))
        fun parse(e: JsonElement?): Zoomed? = when (e) {
            null -> null
            is JsonPrimitive -> constant(e.double)
            else -> Zoomed(
                e.jsonObject.getValue("base").jsonPrimitive.double,
                e.jsonObject.getValue("stops").jsonArray.map { it.jsonArray[0].jsonPrimitive.double to it.jsonArray[1].jsonPrimitive.double },
            )
        }
    }
}

class StyleLayer(
    val id: String,
    val type: LayerType,
    val source: String?,
    val minzoom: Double,
    val dayOnly: Boolean,
    val nightOnly: Boolean,
    val classes: Set<String>?,
    val where: Map<String, JsonPrimitive>,
    val not: Map<String, JsonPrimitive>,
    val color: String,
    val width: Zoomed?,
    val dash: FloatArray?,
    val alongLine: Boolean,
    val font: Font,
    val size: Zoomed?,
    val halo: Double,
) {
    /** Whether a feature's tags pass this layer's filter. */
    fun matches(tags: Map<String, Any>): Boolean {
        if (classes != null && tags["class"] as? String !in classes) return false
        if (where.any { (k, v) -> !same(tags[k], v) }) return false
        if (not.any { (k, v) -> same(tags[k], v) }) return false
        return true
    }

    private fun same(tag: Any?, v: JsonPrimitive): Boolean = when (tag) {
        null -> false
        is String -> v.isString && v.content == tag
        is Boolean -> !v.isString && v.content == tag.toString()
        is Number -> !v.isString && v.doubleOrNull == tag.toDouble()
        else -> false
    }
}

/** The shared style file (also read by the browser emulator): palettes and layers, in drawing order. */
class MapStyle(val palettes: Map<String, Map<String, Int>>, val layers: List<StyleLayer>) {
    fun layersFor(night: Boolean) = layers.filter { if (night) !it.dayOnly else !it.nightOnly }

    /** A palette colour as ARGB. */
    fun color(night: Boolean, name: String): Int = palettes.getValue(if (night) "night" else "day")[name] ?: 0xFFFF00FF.toInt()

    companion object {
        fun load(): MapStyle = parse(MapStyle::class.java.getResource("/ro/rdstr/map/style.json")!!.readText())

        fun parse(json: String): MapStyle {
            val root = Json.parseToJsonElement(json).jsonObject
            val palettes = root.getValue("palettes").jsonObject.mapValues { (_, p) ->
                p.jsonObject.mapValues { (_, c) -> argb(c.jsonPrimitive.content) }
            }
            val layers = root.getValue("layers").jsonArray.map { layer(it.jsonObject) }
            return MapStyle(palettes, layers)
        }

        private fun layer(o: JsonObject): StyleLayer {
            fun str(k: String) = (o[k] as? JsonPrimitive)?.content
            fun flag(k: String) = (o[k] as? JsonPrimitive)?.boolean ?: false
            fun prims(k: String) = (o[k] as? JsonObject)?.mapValues { it.value.jsonPrimitive } ?: emptyMap()
            return StyleLayer(
                id = str("id")!!,
                type = LayerType.valueOf(str("type")!!.uppercase()),
                source = str("source"),
                minzoom = (o["minzoom"] as? JsonPrimitive)?.double ?: 0.0,
                dayOnly = flag("day"),
                nightOnly = flag("night"),
                classes = (o["classes"] as? JsonArray)?.map { it.jsonPrimitive.content }?.toSet(),
                where = prims("where"),
                not = prims("not"),
                color = str("color")!!,
                width = Zoomed.parse(o["width"]),
                dash = (o["dash"] as? JsonArray)?.map { it.jsonPrimitive.double.toFloat() }?.toFloatArray(),
                alongLine = str("placement") == "line",
                font = str("font")?.let { Font.valueOf(it.uppercase()) } ?: Font.REGULAR,
                size = Zoomed.parse(o["size"]),
                halo = (o["halo"] as? JsonPrimitive)?.double ?: 1.0,
            )
        }

        /** `#rrggbb` as opaque ARGB. */
        fun argb(hex: String): Int = (0xFF000000 or hex.removePrefix("#").toLong(16)).toInt()
    }
}

/** The country borders (Natural Earth, Romania and its neighbours) the packs are offered and masked by; the emulator copies the same file. */
object MapBorders {
    fun geojson(): String = MapBorders::class.java.getResource("/ro/rdstr/map/countries.geojson")!!.readText()
}

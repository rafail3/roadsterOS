package ro.rdstr.map

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

/** What a platform draws with; coordinates are screen pixels. */
interface MapCanvas {
    fun clear(color: Int)
    /**
     * Rings filled together by non-zero winding: tiles wind outer rings one way and holes the other, so holes stay
     * open while overlapping shapes (a suburb over a neighbourhood) stay filled.
     */
    fun fill(rings: List<FloatArray>, color: Int)
    fun stroke(lines: List<FloatArray>, color: Int, width: Float, dash: FloatArray?)
    /** Everything drawn until [restore] turns by [degrees] around ([cx], [cy]). */
    fun rotate(degrees: Float, cx: Float, cy: Float)
    fun restore()
    fun textWidth(text: String, size: Float, font: Font): Float
    /** Text centred on ([x], [y]), turned by [degrees], with a halo of [haloWidth] pixels around the glyphs. */
    fun text(text: String, x: Float, y: Float, size: Float, font: Font, color: Int, halo: Int, haloWidth: Float, degrees: Float)
}

/** Web Mercator in the pixels the style is written for: 512 per tile. */
object TileMath {
    const val TILE = 512.0
    fun worldX(lon: Double, zoom: Double) = (lon + 180) / 360 * TILE * 2.0.pow(zoom)
    fun worldY(lat: Double, zoom: Double): Double {
        val r = Math.toRadians(lat.coerceIn(-85.05112878, 85.05112878))
        return (1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * TILE * 2.0.pow(zoom)
    }
}

/** The view a frame is drawn for: the point at the centre of the screen, the zoom, and the map turned by [bearing]. */
data class View(val lon: Double, val lat: Double, val zoom: Double, val bearing: Double, val width: Int, val height: Int, val night: Boolean)

/**
 * Draws one frame of the map: the tiles under the view, layer by layer in the style's order across all tiles
 * (so a road stays above the water of the next tile), then the labels upright on top, the more important first,
 * each only where it overlaps none already placed.
 */
class MapFrame(private val style: MapStyle, private val maxTileZoom: Int = 14) {

    fun interface Tiles { fun get(z: Int, x: Int, y: Int): VectorTile? }

    private class Placed(val tile: VectorTile, val ox: Double, val oy: Double)

    fun draw(canvas: MapCanvas, tiles: Tiles, v: View) {
        val layers = style.layersFor(v.night)
        val tz = min(maxTileZoom, max(0, floor(v.zoom).toInt()))
        val size = TileMath.TILE * 2.0.pow(v.zoom - tz)
        val cx = TileMath.worldX(v.lon, v.zoom)
        val cy = TileMath.worldY(v.lat, v.zoom)
        // every tile the turned screen can reach
        val reach = hypot(v.width.toDouble(), v.height.toDouble()) / 2
        val count = 1 shl tz
        val placed = ArrayList<Placed>()
        for (ty in floor((cy - reach) / size).toInt()..floor((cy + reach) / size).toInt()) {
            if (ty !in 0 until count) continue
            for (tx in floor((cx - reach) / size).toInt()..floor((cx + reach) / size).toInt()) {
                val tile = tiles.get(tz, ((tx % count) + count) % count, ty) ?: continue
                placed += Placed(tile, tx * size - cx + v.width / 2.0, ty * size - cy + v.height / 2.0)
            }
        }
        val halfW = v.width / 2f
        val halfH = v.height / 2f

        layers.firstOrNull { it.type == LayerType.BACKGROUND }?.let { canvas.clear(style.color(v.night, it.color)) }
        canvas.rotate(-v.bearing.toFloat(), halfW, halfH)
        for (layer in layers) {
            if (layer.type != LayerType.FILL && layer.type != LayerType.LINE) continue
            if (v.zoom < layer.minzoom) continue
            val color = style.color(v.night, layer.color)
            val width = layer.width?.at(v.zoom)?.toFloat() ?: 1f
            for (p in placed) {
                val src = p.tile.layers[layer.source] ?: continue
                val s = size / src.extent
                val shapes = ArrayList<FloatArray>()
                for (f in src.features) {
                    if (!layer.matches(f.tags)) continue
                    if (layer.type == LayerType.FILL && f.type != GeomType.POLYGON) continue
                    if (layer.type == LayerType.LINE && f.type == GeomType.POINT) continue
                    for (part in f.parts()) shapes += toScreen(part, p.ox, p.oy, s)
                }
                if (shapes.isEmpty()) continue
                if (layer.type == LayerType.FILL) canvas.fill(shapes, color) else canvas.stroke(shapes, color, width, layer.dash)
            }
        }
        canvas.restore()

        labels(canvas, placed, layers, v, size)
    }

    private fun toScreen(part: FloatArray, ox: Double, oy: Double, s: Double): FloatArray {
        val out = FloatArray(part.size)
        for (i in part.indices step 2) {
            out[i] = (ox + part[i] * s).toFloat()
            out[i + 1] = (oy + part[i + 1] * s).toFloat()
        }
        return out
    }

    private class Label(val text: String, val x: Float, val y: Float, val degrees: Float, val layer: StyleLayer, val size: Float, val lines: List<String>)

    private class Box(val l: Float, val t: Float, val r: Float, val b: Float) {
        fun hits(o: Box) = l < o.r && o.l < r && t < o.b && o.t < b
    }

    private fun labels(canvas: MapCanvas, placed: List<Placed>, layers: List<StyleLayer>, v: View, size: Double) {
        val rad = Math.toRadians(-v.bearing)
        val c = cos(rad).toFloat()
        val sn = sin(rad).toFloat()
        val halfW = v.width / 2f
        val halfH = v.height / 2f
        // the screen point of a map point once the map is turned
        fun turn(x: Float, y: Float): Pair<Float, Float> {
            val dx = x - halfW
            val dy = y - halfH
            return (halfW + dx * c - dy * sn) to (halfH + dx * sn + dy * c)
        }
        val boxes = ArrayList<Box>()
        val shown = ArrayList<Label>()
        val seen = HashMap<String, MutableList<Pair<Float, Float>>>()
        // the top layers of the style win the space: cities before towns, towns before villages and road names
        for (layer in layers.asReversed()) {
            if (layer.type != LayerType.TEXT || v.zoom < layer.minzoom) continue
            val textSize = layer.size?.at(v.zoom)?.toFloat() ?: 13f
            for (p in placed) {
                val src = p.tile.layers[layer.source] ?: continue
                val s = size / src.extent
                for (f in src.features) {
                    if (!layer.matches(f.tags)) continue
                    val name = f.tags["name"] as? String ?: continue
                    val label = (if (layer.alongLine) lineLabel(canvas, f, name, layer, textSize, p, s, ::turn)
                    else pointLabel(canvas, f, name, layer, textSize, p, s, ::turn)) ?: continue
                    if (label.x < 0 || label.y < 0 || label.x > v.width || label.y > v.height) continue
                    // the same name from the edge of the next tile
                    val near = seen.getOrPut(name) { ArrayList() }
                    if (near.any { hypot(it.first - label.x, it.second - label.y) < textSize * 8 }) continue
                    val box = box(canvas, label)
                    // a name cut by the screen edge reads worse than none
                    if (box.l < 0 || box.t < 0 || box.r > v.width || box.b > v.height) continue
                    if (boxes.any { it.hits(box) }) continue
                    boxes += box
                    near += label.x to label.y
                    shown += label
                }
            }
        }
        for (l in shown.asReversed()) {
            val lineHeight = l.size * 1.2f
            val top = l.y - (l.lines.size - 1) * lineHeight / 2
            l.lines.forEachIndexed { i, text ->
                canvas.text(text, l.x, top + i * lineHeight, l.size, l.layer.font, style.color(v.night, l.layer.color),
                    style.color(v.night, "halo"), l.layer.halo.toFloat(), l.degrees)
            }
        }
    }

    private fun box(canvas: MapCanvas, l: Label): Box {
        val w = l.lines.maxOf { canvas.textWidth(it, l.size, l.layer.font) } + l.size * 0.6f
        val h = l.lines.size * l.size * 1.2f + l.size * 0.4f
        val r = Math.toRadians(l.degrees.toDouble())
        val bw = (kotlin.math.abs(w * cos(r)) + kotlin.math.abs(h * sin(r))).toFloat()
        val bh = (kotlin.math.abs(w * sin(r)) + kotlin.math.abs(h * cos(r))).toFloat()
        return Box(l.x - bw / 2, l.y - bh / 2, l.x + bw / 2, l.y + bh / 2)
    }

    /** Point labels wrap at about ten characters' width, as the browser map does. */
    private fun pointLabel(canvas: MapCanvas, f: TileFeature, name: String, layer: StyleLayer, size: Float, p: Placed, s: Double, turn: (Float, Float) -> Pair<Float, Float>): Label? {
        val pt = f.parts().firstOrNull() ?: return null
        val (x, y) = turn((p.ox + pt[0] * s).toFloat(), (p.oy + pt[1] * s).toFloat())
        val max = size * 10
        val lines = ArrayList<String>()
        var line = ""
        for (word in name.split(' ')) {
            val next = if (line.isEmpty()) word else "$line $word"
            if (line.isNotEmpty() && canvas.textWidth(next, size, layer.font) > max) { lines += line; line = word } else line = next
        }
        if (line.isNotEmpty()) lines += line
        return Label(name, x, y, 0f, layer, size, lines)
    }

    /**
     * A road name along a nearly straight stretch of its line: consecutive segments that turn less than
     * [MAX_TURN] degrees from where the stretch began, long enough for the name; the longest such stretch wins.
     * The name follows the stretch's chord and stays upright.
     */
    private fun lineLabel(canvas: MapCanvas, f: TileFeature, name: String, layer: StyleLayer, size: Float, p: Placed, s: Double, turn: (Float, Float) -> Pair<Float, Float>): Label? {
        val need = canvas.textWidth(name, size, layer.font) + size
        var best: Label? = null
        var bestLen = need
        for (part in f.parts()) {
            val n = part.size / 2
            if (n < 2) continue
            val xs = FloatArray(n)
            val ys = FloatArray(n)
            for (i in 0 until n) {
                val (x, y) = turn((p.ox + part[2 * i] * s).toFloat(), (p.oy + part[2 * i + 1] * s).toFloat())
                xs[i] = x; ys[i] = y
            }
            for (a in 0 until n - 1) {
                val heading = atan2(ys[a + 1] - ys[a], xs[a + 1] - xs[a])
                var len = 0f
                var b = a
                while (b < n - 1) {
                    val h = atan2(ys[b + 1] - ys[b], xs[b + 1] - xs[b])
                    var d = Math.toDegrees((h - heading).toDouble())
                    while (d > 180) d -= 360
                    while (d < -180) d += 360
                    if (kotlin.math.abs(d) > MAX_TURN) break
                    len += hypot(xs[b + 1] - xs[b], ys[b + 1] - ys[b])
                    b++
                }
                if (b == a || len < bestLen) continue
                var deg = Math.toDegrees(atan2((ys[b] - ys[a]).toDouble(), (xs[b] - xs[a]).toDouble())).toFloat()
                if (deg > 90) deg -= 180
                if (deg < -90) deg += 180
                bestLen = len
                best = Label(name, (xs[a] + xs[b]) / 2, (ys[a] + ys[b]) / 2, deg, layer, size, listOf(name))
            }
        }
        return best
    }

    private companion object {
        const val MAX_TURN = 30.0
    }
}

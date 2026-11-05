package ro.rdstr.map

import kotlin.math.PI
import kotlin.math.abs
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

/** What a platform draws with; coordinates are the style's pixels (the canvas may scale them to the screen). */
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
    /** Everything drawn until [restore] stays inside the rectangle. */
    fun clip(left: Float, top: Float, right: Float, bottom: Float)
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
    fun lon(worldX: Double, zoom: Double) = worldX / (TILE * 2.0.pow(zoom)) * 360 - 180
    fun lat(worldY: Double, zoom: Double): Double {
        val n = PI - 2 * PI * worldY / (TILE * 2.0.pow(zoom))
        return Math.toDegrees(kotlin.math.atan(kotlin.math.sinh(n)))
    }
}

/** The view a frame is drawn for: the point at the centre of the screen, the zoom, and the map turned by [bearing]. */
data class View(val lon: Double, val lat: Double, val zoom: Double, val bearing: Double, val width: Int, val height: Int, val night: Boolean)

/** A tile on the screen of a [View]: its address, and where its top-left corner falls before the map is turned. */
class VisibleTile(val z: Int, val x: Int, val y: Int, val left: Double, val top: Double, val size: Double)

/**
 * One picture of the map grid at the view's whole zoom ([z], [x], [y], placed at [left], [top], [size] on the screen
 * before the map is turned), cut from the source tile it lies in: the deepest tiles are drawn larger, a part at a time,
 * so the picture stays sharp. [srcLeft], [srcTop], [srcSize] place the whole source tile on the screen the same way.
 */
class GridTile(
    val z: Int, val x: Int, val y: Int, val left: Double, val top: Double, val size: Double,
    val srcZ: Int, val srcX: Int, val srcY: Int, val srcLeft: Double, val srcTop: Double, val srcSize: Double,
) {
    val key get() = "$z/$x/$y"
    val srcKey get() = "$srcZ/$srcX/$srcY"
    /** Where the source tile lies in this picture, for a picture [px] wide: its left, top and size. */
    fun inPicture(px: Double): Triple<Double, Double, Double> {
        val k = px / size
        return Triple((srcLeft - left) * k, (srcTop - top) * k, srcSize * k)
    }
}

/**
 * A name a tile offers: at a point, or along one of the nearly straight stretches of a road, in tile pixels for a
 * tile drawn [TileRenderer.TILE_PX] wide. Placement on the screen happens per frame, upright and without overlaps.
 */
class LabelCandidate(val layer: StyleLayer, val name: String, val points: FloatArray, val stretches: List<FloatArray>)

/**
 * The map in three steps, so a slow device draws each tile once: [drawTile] puts a tile's shapes into a picture,
 * [candidates] lists its names, and [placeLabels] sets the names of the visible tiles on the screen every frame.
 * [draw] does all three straight onto a canvas, for previews.
 */
class TileRenderer(private val style: MapStyle, val maxTileZoom: Int = 14) {

    fun interface Tiles { fun get(z: Int, x: Int, y: Int): VectorTile? }

    /** The tiles a view needs, at the tile zoom used for its zoom (deeper zooms reuse the deepest tiles). */
    fun visible(v: View): List<VisibleTile> {
        val tz = min(maxTileZoom, max(0, floor(v.zoom).toInt()))
        val size = TileMath.TILE * 2.0.pow(v.zoom - tz)
        val cx = TileMath.worldX(v.lon, v.zoom)
        val cy = TileMath.worldY(v.lat, v.zoom)
        // every tile the turned screen can reach
        val reach = hypot(v.width.toDouble(), v.height.toDouble()) / 2
        val count = 1 shl tz
        val out = ArrayList<VisibleTile>()
        for (ty in floor((cy - reach) / size).toInt()..floor((cy + reach) / size).toInt()) {
            if (ty !in 0 until count) continue
            for (tx in floor((cx - reach) / size).toInt()..floor((cx + reach) / size).toInt()) {
                out += VisibleTile(tz, ((tx % count) + count) % count, ty, tx * size - cx + v.width / 2.0, ty * size - cy + v.height / 2.0, size)
            }
        }
        return out
    }

    /** The pictures a view needs at its whole zoom (up to [maxGridZoom]), each with the source tile it is cut from. */
    fun grid(v: View, maxGridZoom: Int = 18): List<GridTile> {
        val gz = min(maxGridZoom, max(0, floor(v.zoom).toInt()))
        val size = TileMath.TILE * 2.0.pow(v.zoom - gz)
        val cx = TileMath.worldX(v.lon, v.zoom)
        val cy = TileMath.worldY(v.lat, v.zoom)
        val reach = hypot(v.width.toDouble(), v.height.toDouble()) / 2
        val count = 1 shl gz
        val sz = min(gz, maxTileZoom)
        val d = gz - sz
        val out = ArrayList<GridTile>()
        for (ty in floor((cy - reach) / size).toInt()..floor((cy + reach) / size).toInt()) {
            if (ty !in 0 until count) continue
            for (tx in floor((cx - reach) / size).toInt()..floor((cx + reach) / size).toInt()) {
                val x = ((tx % count) + count) % count
                val left = tx * size - cx + v.width / 2.0
                val top = ty * size - cy + v.height / 2.0
                val sx = x shr d
                val sy = ty shr d
                out += GridTile(gz, x, ty, left, top, size, sz, sx, sy,
                    left - (x - (sx shl d)) * size, top - (ty - (sy shl d)) * size, size * (1 shl d))
            }
        }
        return out
    }

    fun background(night: Boolean): Int =
        style.layersFor(night).firstOrNull { it.type == LayerType.BACKGROUND }?.let { style.color(night, it.color) } ?: 0

    /** A tile's shapes, layer by layer, into a square of [size] pixels whose top-left corner is ([left], [top]). */
    fun drawTile(canvas: MapCanvas, tile: VectorTile, left: Double, top: Double, size: Double, zoom: Double, night: Boolean) {
        for (layer in style.layersFor(night)) {
            if (layer.type != LayerType.FILL && layer.type != LayerType.LINE) continue
            if (zoom < layer.minzoom) continue
            val src = tile.layers[layer.source] ?: continue
            val s = size / src.extent
            val shapes = ArrayList<FloatArray>()
            for (f in src.features) {
                if (!layer.matches(f.tags)) continue
                if (layer.type == LayerType.FILL && f.type != GeomType.POLYGON) continue
                if (layer.type == LayerType.LINE && f.type == GeomType.POINT) continue
                for (part in f.parts()) {
                    val out = FloatArray(part.size)
                    for (i in part.indices step 2) {
                        out[i] = (left + part[i] * s).toFloat()
                        out[i + 1] = (top + part[i + 1] * s).toFloat()
                    }
                    shapes += out
                }
            }
            if (shapes.isEmpty()) continue
            val color = style.color(night, layer.color)
            if (layer.type == LayerType.FILL) canvas.fill(shapes, color)
            else canvas.stroke(shapes, color, layer.width?.at(zoom)?.toFloat() ?: 1f, layer.dash)
        }
    }

    /** A tile's names, worked out once: anchor points, and for road names the stretches they can run along. */
    fun candidates(tile: VectorTile, night: Boolean): List<LabelCandidate> {
        val out = ArrayList<LabelCandidate>()
        for (layer in style.layersFor(night)) {
            if (layer.type != LayerType.TEXT) continue
            val src = tile.layers[layer.source] ?: continue
            val s = (TILE_PX / src.extent).toFloat()
            for (f in src.features) {
                if (!layer.matches(f.tags)) continue
                val name = f.tags["name"] as? String ?: continue
                if (!layer.alongLine) {
                    val p = f.parts().firstOrNull() ?: continue
                    out += LabelCandidate(layer, name, floatArrayOf(p[0] * s, p[1] * s), emptyList())
                } else {
                    val stretches = f.parts().flatMap { stretches(it, s) }.sortedByDescending { it[4] }.take(MAX_STRETCHES)
                    if (stretches.isNotEmpty()) out += LabelCandidate(layer, name, FloatArray(0), stretches)
                }
            }
        }
        return out
    }

    /** Maximal runs of a line turning less than [MAX_TURN] degrees from where they start: x0, y0, x1, y1, length. */
    private fun stretches(part: FloatArray, s: Float): List<FloatArray> {
        val n = part.size / 2
        val out = ArrayList<FloatArray>()
        var lastEnd = -1
        for (a in 0 until n - 1) {
            val heading = atan2(part[2 * a + 3] - part[2 * a + 1], part[2 * a + 2] - part[2 * a])
            var len = 0f
            var b = a
            while (b < n - 1) {
                val h = atan2(part[2 * b + 3] - part[2 * b + 1], part[2 * b + 2] - part[2 * b])
                var d = Math.toDegrees((h - heading).toDouble())
                while (d > 180) d -= 360
                while (d < -180) d += 360
                if (abs(d) > MAX_TURN) break
                len += hypot(part[2 * b + 2] - part[2 * b], part[2 * b + 3] - part[2 * b + 1])
                b++
            }
            // a run ending where the previous one ended lies inside it
            if (b == a || b == lastEnd) continue
            lastEnd = b
            out += floatArrayOf(part[2 * a] * s, part[2 * a + 1] * s, part[2 * b] * s, part[2 * b + 1] * s, len * s)
        }
        return out
    }

    private class Label(val x: Float, val y: Float, val degrees: Float, val layer: StyleLayer, val size: Float, val lines: List<String>)

    private class Box(val l: Float, val t: Float, val r: Float, val b: Float) {
        fun hits(o: Box) = l < o.r && o.l < r && t < o.b && o.t < b
    }

    /**
     * Sets the names of the visible tiles on the screen: upright, the top layers of the style first (cities before
     * towns, towns before villages and road names), each only where it overlaps none already set, none cut by the edge.
     */
    fun placeLabels(canvas: MapCanvas, v: View, tiles: List<Pair<VisibleTile, List<LabelCandidate>>>) {
        val rad = Math.toRadians(-v.bearing)
        val c = cos(rad).toFloat()
        val sn = sin(rad).toFloat()
        val halfW = v.width / 2f
        val halfH = v.height / 2f
        val boxes = ArrayList<Box>()
        val shown = ArrayList<Label>()
        val seen = HashMap<String, MutableList<Pair<Float, Float>>>()
        val layers = style.layersFor(v.night).filter { it.type == LayerType.TEXT && v.zoom >= it.minzoom }.asReversed()
        for (layer in layers) {
            val size = layer.size?.at(v.zoom)?.toFloat() ?: 13f
            for ((t, list) in tiles) {
                val k = (t.size / TILE_PX).toFloat()
                // a tile point on the screen once the map is turned
                fun sx(x: Float, y: Float): Float {
                    val dx = (t.left + x * k).toFloat() - halfW; val dy = (t.top + y * k).toFloat() - halfH
                    return halfW + dx * c - dy * sn
                }
                fun sy(x: Float, y: Float): Float {
                    val dx = (t.left + x * k).toFloat() - halfW; val dy = (t.top + y * k).toFloat() - halfH
                    return halfH + dx * sn + dy * c
                }
                for (cand in list) {
                    if (cand.layer !== layer) continue
                    val label = if (cand.stretches.isEmpty()) {
                        Label(sx(cand.points[0], cand.points[1]), sy(cand.points[0], cand.points[1]), 0f, layer, size, wrap(canvas, cand.name, layer.font, size))
                    } else {
                        val need = canvas.textWidth(cand.name, size, layer.font) + size
                        val st = cand.stretches.firstOrNull { it[4] * k >= need } ?: continue
                        val x0 = sx(st[0], st[1]); val y0 = sy(st[0], st[1]); val x1 = sx(st[2], st[3]); val y1 = sy(st[2], st[3])
                        var deg = Math.toDegrees(atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())).toFloat()
                        if (deg > 90) deg -= 180
                        if (deg < -90) deg += 180
                        Label((x0 + x1) / 2, (y0 + y1) / 2, deg, layer, size, listOf(cand.name))
                    }
                    // the same name again from the edge of the next tile
                    val near = seen.getOrPut(cand.name) { ArrayList() }
                    if (near.any { hypot(it.first - label.x, it.second - label.y) < size * 8 }) continue
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

    /** Point names wrap at about ten characters' width, as the browser map does. */
    private fun wrap(canvas: MapCanvas, name: String, font: Font, size: Float): List<String> {
        val lines = ArrayList<String>()
        var line = ""
        for (word in name.split(' ')) {
            val next = if (line.isEmpty()) word else "$line $word"
            if (line.isNotEmpty() && canvas.textWidth(next, size, font) > size * 10) { lines += line; line = word } else line = next
        }
        if (line.isNotEmpty()) lines += line
        return lines
    }

    private fun box(canvas: MapCanvas, l: Label): Box {
        val w = l.lines.maxOf { canvas.textWidth(it, l.size, l.layer.font) } + l.size * 0.6f
        val h = l.lines.size * l.size * 1.2f + l.size * 0.4f
        val r = Math.toRadians(l.degrees.toDouble())
        val bw = (abs(w * cos(r)) + abs(h * sin(r))).toFloat()
        val bh = (abs(w * sin(r)) + abs(h * cos(r))).toFloat()
        return Box(l.x - bw / 2, l.y - bh / 2, l.x + bw / 2, l.y + bh / 2)
    }

    /** All three steps straight onto [canvas]: each tile clipped to its square, then the names on top. */
    fun draw(canvas: MapCanvas, tiles: Tiles, v: View) {
        canvas.clear(background(v.night))
        val visible = visible(v).mapNotNull { t -> tiles.get(t.z, t.x, t.y)?.let { t to it } }
        canvas.rotate(-v.bearing.toFloat(), v.width / 2f, v.height / 2f)
        for ((t, tile) in visible) {
            canvas.clip(t.left.toFloat(), t.top.toFloat(), (t.left + t.size).toFloat(), (t.top + t.size).toFloat())
            drawTile(canvas, tile, t.left, t.top, t.size, v.zoom, v.night)
            canvas.restore()
        }
        canvas.restore()
        placeLabels(canvas, v, visible.map { (t, tile) -> t to candidates(tile, v.night) })
    }

    companion object {
        /** The width a tile's label candidates are worked out for; placement scales them to the tile's size on screen. */
        const val TILE_PX = 512.0
        const val MAX_TURN = 30.0
        /** The longest stretches kept per road name: enough to find one that fits at any zoom. */
        const val MAX_STRETCHES = 4
    }
}

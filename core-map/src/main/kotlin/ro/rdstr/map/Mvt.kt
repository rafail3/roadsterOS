package ro.rdstr.map

import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

enum class GeomType { POINT, LINE, POLYGON, UNKNOWN }

class VectorTile(val layers: Map<String, TileLayer>)

class TileLayer(val name: String, val extent: Int, val features: List<TileFeature>)

/**
 * One feature with its tags; the geometry stays encoded until [parts] is asked for, as most features of a tile
 * are skipped by the style before they are drawn.
 */
class TileFeature(val type: GeomType, val tags: Map<String, Any>, private val geometry: IntArray) {
    /**
     * Points (one part each), lines, or polygon rings, as flat x,y lists in the tile's extent coordinates.
     * A ring ends on its first point again.
     */
    fun parts(): List<FloatArray> {
        val parts = ArrayList<FloatArray>()
        var current = FloatList()
        var x = 0
        var y = 0
        var i = 0
        while (i < geometry.size) {
            val header = geometry[i++]
            val id = header and 7
            val count = header ushr 3
            when (id) {
                MOVE_TO -> repeat(count) {
                    x += zigzag(geometry[i++]); y += zigzag(geometry[i++])
                    if (type == GeomType.POINT) parts += floatArrayOf(x.toFloat(), y.toFloat())
                    else {
                        if (current.size > 0) parts += current.toArray()
                        current = FloatList().apply { add(x.toFloat(), y.toFloat()) }
                    }
                }
                LINE_TO -> repeat(count) {
                    x += zigzag(geometry[i++]); y += zigzag(geometry[i++])
                    current.add(x.toFloat(), y.toFloat())
                }
                CLOSE_PATH -> if (current.size >= 2) current.add(current[0], current[1])
                else -> return parts
            }
        }
        if (current.size > 0) parts += current.toArray()
        return parts
    }

    private companion object {
        const val MOVE_TO = 1
        const val LINE_TO = 2
        const val CLOSE_PATH = 7
        fun zigzag(n: Int) = (n ushr 1) xor -(n and 1)
    }
}

/** A growable float list without boxing. */
internal class FloatList {
    private var data = FloatArray(16)
    var size = 0
        private set
    fun add(x: Float, y: Float) {
        if (size + 2 > data.size) data = data.copyOf(data.size * 2)
        data[size++] = x
        data[size++] = y
    }
    operator fun get(i: Int) = data[i]
    fun toArray() = data.copyOf(size)
}

/** Mapbox Vector Tile 2.1, read straight from the protobuf bytes; gzipped tiles (as MBTiles stores them) too. */
object Mvt {
    fun decode(bytes: ByteArray): VectorTile {
        val raw = if (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte())
            GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() } else bytes
        val layers = HashMap<String, TileLayer>()
        Proto(raw, 0, raw.size).forEach { field, p ->
            if (field == 3) layer(p.message()).let { layers[it.name] = it } else p.skip()
        }
        return VectorTile(layers)
    }

    private fun layer(p: Proto): TileLayer {
        var name = ""
        var extent = 4096
        val keys = ArrayList<String>()
        val values = ArrayList<Any>()
        val features = ArrayList<Proto>()
        p.forEach { field, f ->
            when (field) {
                1 -> name = f.string()
                2 -> features += f.message()
                3 -> keys += f.string()
                4 -> values += value(f.message())
                5 -> extent = f.varint().toInt()
                else -> f.skip()
            }
        }
        return TileLayer(name, extent, features.map { feature(it, keys, values) })
    }

    private fun feature(p: Proto, keys: List<String>, values: List<Any>): TileFeature {
        var type = GeomType.UNKNOWN
        var tags: IntArray = IntArray(0)
        var geometry: IntArray = IntArray(0)
        p.forEach { field, f ->
            when (field) {
                2 -> tags = f.packed()
                3 -> type = when (f.varint().toInt()) { 1 -> GeomType.POINT; 2 -> GeomType.LINE; 3 -> GeomType.POLYGON; else -> GeomType.UNKNOWN }
                4 -> geometry = f.packed()
                else -> f.skip()
            }
        }
        val map = HashMap<String, Any>(tags.size)
        var i = 0
        while (i + 1 < tags.size) {
            val k = keys.getOrNull(tags[i]); val v = values.getOrNull(tags[i + 1])
            if (k != null && v != null) map[k] = v
            i += 2
        }
        return TileFeature(type, map, geometry)
    }

    private fun value(p: Proto): Any {
        var v: Any = ""
        p.forEach { field, f ->
            v = when (field) {
                1 -> f.string()
                2 -> f.fixed32().let { java.lang.Float.intBitsToFloat(it).toDouble() }
                3 -> f.fixed64().let { java.lang.Double.longBitsToDouble(it) }
                4, 5 -> f.varint()
                6 -> f.varint().let { (it ushr 1) xor -(it and 1) }
                7 -> f.varint() != 0L
                else -> { f.skip(); v }
            }
        }
        return v
    }
}

/** A cursor over one protobuf message: [forEach] hands each field to a reader that must consume it. */
internal class Proto(private val b: ByteArray, private var pos: Int, private val end: Int) {
    private var wire = 0

    fun forEach(read: (field: Int, p: Proto) -> Unit) {
        while (pos < end) {
            val key = rawVarint().toInt()
            wire = key and 7
            read(key ushr 3, this)
        }
    }

    private fun rawVarint(): Long {
        var shift = 0
        var result = 0L
        while (true) {
            val c = b[pos++].toInt()
            result = result or ((c and 0x7F).toLong() shl shift)
            if (c and 0x80 == 0) return result
            shift += 7
        }
    }

    fun varint() = rawVarint()
    fun fixed32(): Int { val v = (b[pos].toInt() and 0xFF) or ((b[pos + 1].toInt() and 0xFF) shl 8) or ((b[pos + 2].toInt() and 0xFF) shl 16) or ((b[pos + 3].toInt() and 0xFF) shl 24); pos += 4; return v }
    fun fixed64(): Long { var v = 0L; for (i in 0 until 8) v = v or ((b[pos + i].toLong() and 0xFF) shl (8 * i)); pos += 8; return v }

    private fun length(): Int = rawVarint().toInt()
    fun string(): String { val n = length(); val s = String(b, pos, n, Charsets.UTF_8); pos += n; return s }
    fun message(): Proto { val n = length(); val m = Proto(b, pos, pos + n); pos += n; return m }

    fun packed(): IntArray {
        val n = length()
        val stop = pos + n
        val out = IntArray(n)
        var count = 0
        while (pos < stop) out[count++] = rawVarint().toInt()
        return out.copyOf(count)
    }

    fun skip() {
        when (wire) {
            0 -> rawVarint()
            1 -> pos += 8
            2 -> pos += length()
            5 -> pos += 4
            else -> pos = end
        }
    }
}

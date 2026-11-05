package ro.rdstr.map

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** Builds tiles the way planetiler writes them, to read them back. */
private object Encode {
    fun varint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (v and 0x7F.inv().toLong() != 0L) { out.write(((v and 0x7F) or 0x80).toInt()); v = v ushr 7 }
        out.write(v.toInt())
    }
    fun bytes(out: ByteArrayOutputStream, field: Int, payload: ByteArray) {
        varint(out, (field shl 3 or 2).toLong()); varint(out, payload.size.toLong()); out.write(payload)
    }
    fun uint(out: ByteArrayOutputStream, field: Int, value: Long) { varint(out, (field shl 3).toLong()); varint(out, value) }
    fun packed(values: List<Long>) = ByteArrayOutputStream().also { o -> values.forEach { varint(o, it) } }.toByteArray()
    fun zigzag(n: Int) = ((n shl 1) xor (n shr 31)).toLong() and 0xFFFFFFFFL
    fun command(id: Int, count: Int) = (id and 7 or (count shl 3)).toLong()

    /** A layer with one feature; geometry as (command, points) pairs in absolute coordinates. */
    fun layer(name: String, type: Int, tags: Map<String, Any>, parts: List<Pair<Int, List<Pair<Int, Int>>>>): ByteArray {
        val keys = tags.keys.toList()
        val values = tags.values.toList()
        val geom = mutableListOf<Long>()
        var x = 0; var y = 0
        for ((cmd, points) in parts) {
            if (cmd == 7) { geom += command(7, 1); continue }
            geom += command(cmd, points.size)
            for ((px, py) in points) { geom += zigzag(px - x); geom += zigzag(py - y); x = px; y = py }
        }
        val feature = ByteArrayOutputStream().also { f ->
            bytes(f, 2, packed(keys.indices.flatMap { listOf(it.toLong(), it.toLong()) }))
            uint(f, 3, type.toLong())
            bytes(f, 4, packed(geom))
        }.toByteArray()
        return ByteArrayOutputStream().also { l ->
            uint(l, 15, 2)
            bytes(l, 1, name.toByteArray())
            bytes(l, 2, feature)
            keys.forEach { bytes(l, 3, it.toByteArray()) }
            values.forEach { v ->
                val value = ByteArrayOutputStream().also { o ->
                    when (v) {
                        is String -> bytes(o, 1, v.toByteArray())
                        is Int -> uint(o, 4, v.toLong())
                        is Boolean -> uint(o, 7, if (v) 1 else 0)
                    }
                }.toByteArray()
                bytes(l, 4, value)
            }
            uint(l, 5, 4096)
        }.toByteArray()
    }

    fun tile(vararg layers: ByteArray, gzip: Boolean = true): ByteArray {
        val raw = ByteArrayOutputStream().also { t -> layers.forEach { bytes(t, 3, it) } }.toByteArray()
        if (!gzip) return raw
        return ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(raw) } }.toByteArray()
    }
}

class MvtTest {
    @Test
    fun `layers, tags and a line read back from a gzipped tile`() {
        val road = Encode.layer("transportation", 2, mapOf("class" to "primary", "admin_level" to 2, "oneway" to true),
            listOf(1 to listOf(10 to 20), 2 to listOf(110 to 20, 110 to 220)))
        val tile = Mvt.decode(Encode.tile(road))
        val layer = tile.layers.getValue("transportation")
        assertEquals(4096, layer.extent)
        val f = layer.features.single()
        assertEquals(GeomType.LINE, f.type)
        assertEquals("primary", f.tags["class"])
        assertEquals(2L, f.tags["admin_level"])
        assertEquals(true, f.tags["oneway"])
        assertContentEquals(floatArrayOf(10f, 20f, 110f, 20f, 110f, 220f), f.parts().single())
    }

    @Test
    fun `a polygon closes onto its first point and keeps each ring apart`() {
        val water = Encode.layer("water", 3, mapOf("class" to "lake"), listOf(
            1 to listOf(0 to 0), 2 to listOf(100 to 0, 100 to 100), 7 to emptyList(),
            1 to listOf(20 to 20), 2 to listOf(20 to 40, 40 to 40), 7 to emptyList(),
        ))
        val rings = Mvt.decode(Encode.tile(water, gzip = false)).layers.getValue("water").features.single().parts()
        assertEquals(2, rings.size)
        assertContentEquals(floatArrayOf(0f, 0f, 100f, 0f, 100f, 100f, 0f, 0f), rings[0])
        assertContentEquals(floatArrayOf(20f, 20f, 20f, 40f, 40f, 40f, 20f, 20f), rings[1])
    }

    @Test
    fun `points come one per part`() {
        val place = Encode.layer("place", 1, mapOf("name" to "Brașov"), listOf(1 to listOf(5 to 7, 9 to 11)))
        val f = Mvt.decode(Encode.tile(place)).layers.getValue("place").features.single()
        assertEquals("Brașov", f.tags["name"])
        assertEquals(listOf(listOf(5f, 7f), listOf(9f, 11f)), f.parts().map { it.toList() })
    }
}

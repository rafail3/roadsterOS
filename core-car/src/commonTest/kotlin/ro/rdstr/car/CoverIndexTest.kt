package ro.rdstr.car

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CoverIndexTest {
    @Test
    fun `file names are folded and safe`() {
        assertEquals("cum-e-sa-ti-pacalesti-inima__tzanca-uraganu.jpg", CoverIndex.fileName("Cum e să-ți păcălești inima", "Tzanca Uraganu"))
        assertEquals("night-transit__vela-orsini.jpg", CoverIndex.fileName("Night Transit (Radio Edit)", "Vela Orsini"))
    }

    @Test
    fun `a kept cover is found under the names the car reports`() {
        val names = listOf("amber-coast__vela-orsini.jpg", "night-transit__vela-orsini-feat-lio.jpg")
        assertEquals("night-transit__vela-orsini-feat-lio.jpg", CoverIndex.find(names, "Night Transit", "Vela Orsini"))
        assertEquals("amber-coast__vela-orsini.jpg", CoverIndex.find(names, "AMBER COAST", ""))
        assertNull(CoverIndex.find(names, "Night Transit", "Someone Else"))
        assertNull(CoverIndex.find(names + "stray.txt", "Unknown", "Nobody"))
    }

    @Test
    fun `titles in other scripts keep their letters`() {
        assertEquals("кино__виктор-цой.jpg", CoverIndex.fileName("Кино!", "Виктор Цой"))
    }

    @Test
    fun `a track without a cover always gets the same stock cover`() {
        val first = CoverIndex.stockCover("Night Transit", "Vela Orsini", 3)
        assertEquals(first, CoverIndex.stockCover("NIGHT TRANSIT", "Vela Orsini", 3))
        val picked = (1..30).map { CoverIndex.stockCover("Track $it", "Artist", 3) }.toSet()
        assertEquals(setOf(0, 1, 2), picked)
    }

    @Test
    fun `the size a jpeg declares is read from its header`() {
        assertEquals(1_000_000L, JpegSize.pixels(jpeg(1000, 1000)))
        assertEquals(320L * 240, JpegSize.pixels(jpeg(320, 240)))
        assertNull(JpegSize.pixels(byteArrayOf(1, 2, 3, 4, 5)))
    }

    companion object {
        /** A minimal JPEG: start, an APP0 segment, a baseline frame header of the given size, end. */
        fun jpeg(width: Int, height: Int): ByteArray {
            val app0 = listOf(0xFF, 0xE0, 0x00, 0x04, 0x00, 0x00)
            val sof = listOf(0xFF, 0xC0, 0x00, 0x0B, 0x08, height shr 8, height and 255, width shr 8, width and 255, 0x01, 0x01, 0x11, 0x00)
            return (listOf(0xFF, 0xD8) + app0 + sof + listOf(0xFF, 0xD9)).map { it.toByte() }.toByteArray()
        }
    }
}

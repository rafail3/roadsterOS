package ro.rdstr.car

import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The figures below were read from the approved boot design; any change to the dots changes them. */
class BootArtTest {
    private fun <T> sum(dots: List<T>, f: (T) -> Long) =
        dots.foldIndexed(0L) { i, s, d -> (s + f(d) * (i % 97 + 1)) % 1_000_000_007 }

    @Test
    fun `the wordmark has exactly the approved dots`() {
        val w = BootArt.word()
        assertEquals(3510, w.dots.size)
        assertEquals(123, w.width)
        assertEquals(36, w.height)
        assertEquals(125673655L, sum(w.dots) { (it.x * 7 + it.y * 13 + it.color * 31 + it.layer * 3 + it.letter).toLong() })
        assertEquals(1759761167L, (w.dots.sumOf { it.order } * 1e6).roundToLong())
    }

    @Test
    fun `the cube has exactly the approved dots`() {
        val c = BootArt.cube()
        assertEquals(1977, c.size)
        assertEquals(197243683L, sum(c) { ((it.x + 100) * 7 + (it.y + 100) * 13 + it.color * 31 + it.face * 3 + if (it.glyph) 1 else 0).toLong() })
        assertEquals(988074148L, (c.sumOf { it.order } * 1e6).roundToLong())
    }

    @Test
    fun `the timeline lights the tube, builds r O S, lands the word and types the name`() {
        assertNull(BootArt.frame(50.0).tube)
        assertEquals(1.0, BootArt.frame(300.0).tube!!.alpha)
        assertNull(BootArt.frame(700.0).letters)
        val cube = assertNotNull(BootArt.frame(900.0).cube)
        assertTrue(cube[1] > cube[0] && cube[0] > cube[2], "left first, then top, then right")
        assertTrue(BootArt.frame(1450.0).flash)
        val end = BootArt.frame(BootArt.DONE_MS)
        assertTrue(end.letters!!.all { it == 1.0 })
        assertEquals(1.0, end.depth)
        assertEquals(BootArt.CAPTION_TEXT.length, end.typed)
        assertFalse(BootArt.frame(600.0).flicker)
        assertTrue(BootArt.frame(720.0).flicker)
    }
}

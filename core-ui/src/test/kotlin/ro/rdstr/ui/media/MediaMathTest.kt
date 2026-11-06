package ro.rdstr.ui.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.rdstr.car.CarEvent.Radio.Band

class MediaMathTest {
    @Test fun timeAsTheEmulatorWritesIt() {
        assertEquals("1:12", MediaMath.time(72_000))
        assertEquals("0:07", MediaMath.time(6_600))
        assertEquals("3:48", MediaMath.time(228_000))
        assertEquals("0:00", MediaMath.time(-400))
        assertEquals("62:05", MediaMath.time(3_725_000))
    }

    @Test fun fractionStopsAtTheEnd() {
        assertEquals(0f, MediaMath.fraction(5_000, 0), 0f)
        assertEquals(0.5f, MediaMath.fraction(5_000, 10_000), 1e-6f)
        assertEquals(1f, MediaMath.fraction(15_000, 10_000), 0f)
    }

    @Test fun marqueeRunsOnlyWhenTheTextOverflows() {
        assertNull(MediaMath.marquee(300f, 512f))
        assertNull(MediaMath.marquee(512f, 512f))
        val run = MediaMath.marquee(600f, 300f)!!
        assertEquals(700f, run.distance, 1e-3f)
        assertEquals(23_333L, run.durationMs)
        // still during the first 1.2 s, then a linear loop with no pause
        assertEquals(0f, MediaMath.marqueeShift(run, 1_000), 0f)
        assertEquals(-350f, MediaMath.marqueeShift(run, 1_200 + run.durationMs / 2), 0.1f)
        assertEquals(0f, MediaMath.marqueeShift(run, 1_200 + run.durationMs), 0.1f)
    }

    @Test fun closesOntoTheHomeCard() {
        // the card of the screen grid: x 880, y 56, 376 x 670 on the 1280 x 750 canvas
        assertArrayEquals(floatArrayOf(56f, 24f, 24f, 880f), MediaMath.closeOnto(1280f, 750f, 880f, 56f, 376f, 670f), 0f)
        assertArrayEquals(floatArrayOf(150f, 25.6f, 15f, 921.6f), MediaMath.closeFallback(1280f, 750f), 1e-3f)
    }

    @Test fun crossfadeKeepsLayersUntilTheNewestSettles() {
        val l = MediaMath.Layers<String?>(null)
        assertFalse(l.show(null))
        assertTrue(l.show("a"))
        assertTrue(l.show("b"))
        assertEquals(listOf(null, "a", "b"), l.layers.map { it.src })
        l.settle(l.layers[1].id)
        assertEquals(listOf("a", "b"), l.layers.map { it.src })
        l.limit()
        assertEquals(listOf("b"), l.layers.map { it.src })
    }
}

class RadioMathTest {
    @Test fun genreColours() {
        assertEquals(0xFF5E5CE6.toInt(), RadioMath.genreColor(9))
        assertEquals(0xFFFF375F.toInt(), RadioMath.genreColor(10))
        assertEquals(0xFF8E8E93.toInt(), RadioMath.genreColor(0))
        assertEquals(0xFF8E8E93.toInt(), RadioMath.genreColor(31))
    }

    @Test fun dialGeometry() {
        val fm = RadioMath.Dial(Band.FM)
        assertEquals(410, fm.steps)
        assertEquals(2461f, fm.stripWidth, 0f)
        assertEquals(12f, fm.minor, 0f)
        assertEquals(120f, fm.major, 0f)
        assertEquals(1728f, fm.at(101_900), 1e-3f)
        assertEquals(88_000, fm.labels().first())
        assertEquals(108_000, fm.labels().last())
        assertEquals("88", fm.label(88_000))
        // finger 13 px left: two steps up
        assertEquals(102_000, fm.dragged(101_900, -13f))
        assertEquals(108_000, fm.dragged(107_900, -600f))
        val am = RadioMath.Dial(Band.AM)
        assertEquals(122, am.steps)
        assertEquals(2441f, am.stripWidth, 0f)
        assertEquals(listOf(522, 612, 702), am.labels().take(3))
        assertEquals(531, am.stepped(522, 1))
        assertEquals(522, am.stepped(522, -1))
    }

    @Test fun stationLists() {
        val favorites = listOf(558, 89_200, 101_900)
        assertEquals(listOf(89_200, 101_900), RadioMath.mine(Band.FM, favorites))
        assertEquals(listOf(558), RadioMath.mine(Band.AM, favorites))
        assertEquals(listOf(88_600, 91_400), RadioMath.others(listOf(88_600, 89_200, 91_400), favorites))
    }

    @Test fun rowTexts() {
        assertEquals("POP HITS", RadioMath.rowName(Band.FM, 89_200, "POP HITS"))
        assertEquals("89.2 · Pop Music", RadioMath.rowSub(Band.FM, 89_200, "POP HITS", 10))
        assertEquals("95.35 · Jazz Music", RadioMath.rowSub(Band.FM, 95_350, "JAZZ BAR", 24))
        assertEquals("558", RadioMath.rowName(Band.AM, 558, ""))
        assertEquals("kHz", RadioMath.rowSub(Band.AM, 558, "", 0))
        assertEquals("News", RadioMath.rowSub(Band.FM, 91_400, "", 1))
    }

    @Test fun heroTag() {
        assertEquals("Scanning", RadioMath.tag(scanning = true, radioOn = false, stereo = true))
        assertEquals("Paused", RadioMath.tag(scanning = false, radioOn = false, stereo = true))
        assertEquals("Stereo", RadioMath.tag(scanning = false, radioOn = true, stereo = true))
        assertNull(RadioMath.tag(scanning = false, radioOn = true, stereo = false))
    }
}

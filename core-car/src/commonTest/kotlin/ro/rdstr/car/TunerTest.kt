package ro.rdstr.car

import ro.rdstr.car.CarEvent.Radio.Band.AM
import ro.rdstr.car.CarEvent.Radio.Band.FM
import kotlin.test.Test
import kotlin.test.assertEquals

class TunerTest {
    @Test
    fun `the bands are the european tuner table`() {
        assertEquals(Tuner.Range(87_500, 108_000, 50), Tuner.BANDS[FM])
        assertEquals(Tuner.Range(522, 1_620, 9), Tuner.BANDS[AM])
    }

    @Test
    fun `a star goes on in frequency order and comes off again`() {
        val on = Tuner.toggleFavorite(listOf(89_200, 101_900), 91_400)
        assertEquals(listOf(89_200, 91_400, 101_900), on)
        assertEquals(listOf(89_200, 101_900), Tuner.toggleFavorite(on, 91_400))
    }

    @Test
    fun `playback moves on while playing and stops at the end of the track`() {
        assertEquals(72_000, PlaybackClock.elapsedMs(70_000, 1_000, playing = true, durationMs = 228_000, now = 3_000))
        assertEquals(70_000, PlaybackClock.elapsedMs(70_000, 1_000, playing = false, durationMs = 228_000, now = 3_000))
        assertEquals(228_000, PlaybackClock.elapsedMs(227_000, 0, playing = true, durationMs = 228_000, now = 5_000))
        // a track of unknown length keeps counting
        assertEquals(5_000, PlaybackClock.elapsedMs(0, 0, playing = true, durationMs = 0, now = 5_000))
    }

    @Test
    fun `the bridge answers the same as the tuner`() {
        assertEquals("""{"FM":{"min":87500,"max":108000,"step":50},"AM":{"min":522,"max":1620,"step":9}}""", Bridge.bands())
        assertEquals("[88600,101900]", Bridge.toggleFavorite("[101900]", 88_600))
        assertEquals(72_000.0, Bridge.elapsedMs(70_000.0, 1_000.0, true, 228_000.0, 3_000.0))
    }
}

package ro.rdstr.car

import ro.rdstr.car.CarEvent.Radio.Band

/** The tuner as our radio app sees it: its bands, and the frequencies starred in the app. */
object Tuner {
    data class Range(val min: Int, val max: Int, val step: Int)

    /** Ranges and steps in kHz, European region of the vendor tuner table: FM in 50 kHz steps, AM in 9 kHz. */
    val BANDS: Map<Band, Range> = mapOf(Band.FM to Range(87_500, 108_000, 50), Band.AM to Range(522, 1_620, 9))

    /** Stars or unstars [khz]; the favourites stay in frequency order. */
    fun toggleFavorite(favorites: List<Int>, khz: Int): List<Int> =
        if (khz in favorites) favorites.filter { it != khz } else (favorites + khz).sorted()
}

/** Where playback is: the phone reports its position only when it changes, so the screen moves it on itself. */
object PlaybackClock {
    /**
     * The position [positionMs] reported at [positionAt], advanced to [now] while [playing], never past the track's
     * [durationMs] (0 when the phone did not say).
     */
    fun elapsedMs(positionMs: Long, positionAt: Long, playing: Boolean, durationMs: Long, now: Long): Long {
        val at = positionMs + if (playing) now - positionAt else 0
        return if (durationMs > 0) minOf(durationMs, at) else at
    }
}

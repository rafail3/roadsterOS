package ro.rdstr.ui.media

import ro.rdstr.car.CarEvent.Radio.Band
import ro.rdstr.car.FreqDisplay
import ro.rdstr.car.Rds
import ro.rdstr.car.Tuner
import kotlin.math.max
import kotlin.math.roundToInt

// The decisions of media.tsx, MusicApp.tsx and RadioApp.tsx that are not drawing, free of Android for JVM tests.

/** media.tsx: playback time, the scrolling line, the close onto the card. */
object MediaMath {
    /** media.tsx `TICK_MS`: the position moves on this often while playing. */
    const val TICK_MS = 250L
    /** media.tsx `CROSSFADE_LIMIT_MS`: older image layers go at the latest this long after a new one. */
    const val CROSSFADE_LIMIT_MS = 3000L
    /** media.tsx `SWAP_WAIT_MS`: play keeps its glyph squeezed this long while waiting for the phone. */
    const val SWAP_WAIT_MS = 600L
    const val MARQUEE_PX_PER_S = 30f
    const val MARQUEE_GAP = 1f / 3f
    /** music.css `.marquee.is-running`: the 1.2 s pause before the first loop (CSS `animation-delay`, once). */
    const val MARQUEE_DELAY_MS = 1200L
    /** media.tsx `COVER_SAMPLE`: the cover is sampled this small before its edge colours are taken. */
    const val COVER_SAMPLE = 48

    /** media.tsx `time`: "3:48", "0:07"; minutes not padded, no hours. */
    fun time(ms: Long): String {
        val seconds = max(0L, (ms / 1000.0).roundToInt().toLong())
        return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
    }

    /** media.tsx `Progress`: the share played, 0 without a duration. */
    fun fraction(elapsed: Long, duration: Long): Float = if (duration > 0) minOf(1f, elapsed.toFloat() / duration) else 0f

    /** A running marquee: how far one loop moves the text (px) and how long it takes (ms). */
    data class Run(val distance: Float, val durationMs: Long)

    /** media.tsx `Marquee`: runs only when the text is wider than its box; one loop is the text and a third of the box. */
    fun marquee(textWidth: Float, boxWidth: Float): Run? {
        if (textWidth <= boxWidth) return null
        val distance = textWidth + boxWidth * MARQUEE_GAP
        return Run(distance, (distance / MARQUEE_PX_PER_S * 1000).toLong())
    }

    /** The text's shift [ms] after the marquee started: still for the delay, then a linear loop with no pause. */
    fun marqueeShift(run: Run, ms: Long): Float {
        if (ms < MARQUEE_DELAY_MS || run.durationMs <= 0) return 0f
        val t = ((ms - MARQUEE_DELAY_MS) % run.durationMs).toFloat() / run.durationMs
        return -run.distance * t
    }

    /**
     * media.tsx `useCloseOntoCard`: the insets (top, right, bottom, left) the closing outline lands on, from the card's
     * box in the app's own layout ([l], [t], [w], [h], px).
     */
    fun closeOnto(appW: Float, appH: Float, l: Float, t: Float, w: Float, h: Float): FloatArray = floatArrayOf(t, appW - l - w, appH - t - h, l)

    /** music.css `.music-app` closed without a measured card: inset 20% 2% 2% 72%. */
    fun closeFallback(appW: Float, appH: Float): FloatArray = floatArrayOf(appH * 0.2f, appW * 0.02f, appH * 0.02f, appW * 0.72f)

    /**
     * media.tsx `useCrossfade`: image layers by id, newest last. A new source adds a layer; a layer that finished its
     * fade removes those under it ([settle]); [limit] keeps only the newest.
     */
    class Layers<T>(first: T) {
        data class Layer<T>(val src: T, val id: Int)
        var layers: List<Layer<T>> = listOf(Layer(first, 0)); private set
        private var next = 0

        /** True when [src] made a new layer. */
        fun show(src: T): Boolean {
            if (layers.last().src == src) return false
            layers = layers + Layer(src, ++next)
            return true
        }

        fun settle(id: Int) { layers = layers.filter { it.id >= id } }
        fun limit() { layers = layers.takeLast(1) }
    }
}

/** RadioApp.tsx: genre colours, the dial's geometry, the station lists. */
object RadioMath {
    /** RadioApp.tsx `GENRE_COLOR`: each programme type family has its colour; grey for none. */
    fun genreColor(pty: Int): Int = when (pty) {
        1, 2, 3, 16, 17, 21, 22, 29 -> 0xFF0A84FF.toInt()
        4 -> 0xFF30D158.toInt()
        10 -> 0xFFFF375F.toInt()
        11 -> 0xFFFF9F0A.toInt()
        13, 14 -> 0xFFBF5AF2.toInt()
        24, 27 -> 0xFFFFD60A.toInt()
        25, 26, 28 -> 0xFF40C8E0.toInt()
        5, 6, 7, 8, 9, 12, 15, 18, 19, 20, 23 -> 0xFF5E5CE6.toInt()
        else -> 0xFF8E8E93.toInt()
    }

    fun unit(band: Band) = if (band == Band.FM) "MHz" else "kHz"

    /** RadioApp.tsx `DIAL`: dial px per tuner step, ticks and labels per band. */
    class Dial(val band: Band) {
        val range: Tuner.Range = Tuner.BANDS.getValue(band)
        val px = if (band == Band.FM) 6f else 20f
        val minorEvery = if (band == Band.FM) 2 else 1
        val majorEvery = if (band == Band.FM) 20 else 10
        val firstMajor = if (band == Band.FM) 10 else 0
        val steps = (range.max - range.min) / range.step
        /** `.dial-strip` width. */
        val stripWidth = steps * px + 1
        val minor get() = minorEvery * px
        val major get() = majorEvery * px

        /** Where [khz] sits on the strip, px from its start (the strip starts under the needle). */
        fun at(khz: Number): Float = ((khz.toDouble() - range.min) / range.step * px).toFloat()

        /** The labelled frequencies: every major tick from the first. */
        fun labels(): List<Int> = (firstMajor..steps step majorEvery).map { range.min + it * range.step }

        /** `88` on FM (a plain JS number of MHz: `89.5` would print so), `522` on AM. */
        fun label(khz: Int): String = if (band == Band.FM) {
            if (khz % 1000 == 0) "${khz / 1000}" else (khz / 1000.0).toString().trimEnd('0')
        } else "$khz"

        /** A drag from [from] by [dx] px of the screen's layout (finger left tunes up), on the band's grid. */
        fun dragged(from: Int, dx: Float): Int {
            val moved = (-dx / px).roundToInt()
            return (from + moved * range.step).coerceIn(range.min, range.max)
        }

        /** An arrow key's step. */
        fun stepped(from: Int, by: Int): Int = (from + by * range.step).coerceIn(range.min, range.max)
    }

    /** RadioApp.tsx: the stars of this band (a starred frequency stays even when the scan missed it), then the rest. */
    fun mine(band: Band, favorites: List<Int>): List<Int> {
        val r = Tuner.BANDS.getValue(band)
        return favorites.filter { it in r.min..r.max }
    }

    fun others(found: List<Int>, favorites: List<Int>): List<Int> = found.filter { it !in favorites }

    /** StationRow: its name (the RDS name or the frequency) and the line under it. */
    fun rowName(band: Band, khz: Int, ps: String): String = ps.ifEmpty { FreqDisplay.format(band, khz) }

    fun rowSub(band: Band, khz: Int, ps: String, pty: Int): String {
        val genre = Rds.ptyName(pty)
        return if (ps.isNotEmpty()) listOf(FreqDisplay.format(band, khz), genre).filter { it.isNotEmpty() }.joinToString(" · ")
        else genre.ifEmpty { unit(band) }
    }

    /** The hero's second tag: scanning, paused, stereo, or none. */
    fun tag(scanning: Boolean, radioOn: Boolean, stereo: Boolean): String? = when {
        scanning -> "Scanning"
        !radioOn -> "Paused"
        stereo -> "Stereo"
        else -> null
    }
}

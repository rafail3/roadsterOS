package ro.rdstr.ui.volume

import ro.rdstr.ui.tokens.Bezier
import kotlin.math.roundToInt

// The decisions of the volume bar (emulator/components/VolumeHud.tsx), free of Android so they run as JVM tests.
// Times are milliseconds on one monotonic clock (SystemClock.uptimeMillis on the unit).

/** No time set. */
const val NEVER = Long.MIN_VALUE

/** `bridge.maxVolume()` = core-car `Rules.MAX_VOLUME`. */
const val MAX_VOLUME = 30

/** `VISIBLE_MS`: how long the bar stays after the last change or touch. */
const val VISIBLE_MS = 1500L

/** `volume-shake` / `volume-rock` duration (screen.css). */
const val SHAKE_MS = 460L

/** The level a touch at [x] picks on a slider starting at [left], [width] wide: the whole strip, not only the track. */
fun levelAt(x: Float, left: Float, width: Float, max: Int = MAX_VOLUME): Int =
    (((x - left) / width).coerceIn(0f, 1f) * max).roundToInt()

/** Width of the fill as a part of the track: empty while muted. */
fun fillFraction(volume: Int, muted: Boolean, max: Int = MAX_VOLUME): Float = if (muted) 0f else volume.toFloat() / max

/** The number counting back up after unmuting, [elapsed] ms into the count: ease-out cubic to [to]. */
fun countAt(to: Int, elapsed: Long): Int {
    val t = (elapsed.toFloat() / MuteFill.MS).coerceIn(0f, 1f)
    val k = 1 - t
    return (to * (1 - k * k * k)).roundToInt()
}

/**
 * Android side of `useMuteFill`: for [MS] after `muted` changes, a volume fill moves with [EASE] over [MS] instead of
 * its own transition. Used by every volume fill (this bar, the drawer's slider, the music app's bar).
 */
class MuteFill(muted: Boolean = false) {
    private var shown = muted
    private var changedAt = NEVER

    /** Takes the current muted value; true when it changed. [animate] false (animations off) keeps the own transition. */
    fun update(muted: Boolean, now: Long, animate: Boolean = true): Boolean {
        if (muted == shown) return false
        shown = muted
        changedAt = if (animate) now else NEVER
        return true
    }

    /** Whether a fill transition starting at [now] takes the slow mute movement. */
    fun active(now: Long): Boolean = changedAt != NEVER && now - changedAt < MS

    companion object {
        /** `MUTE_FILL_MS` */
        const val MS = 480L
        /** `MUTE_FILL_EASE` */
        val EASE = Bezier(0.33f, 1f, 0.68f, 1f)
    }
}

/** What the bar shows and when, from the car's values and the finger; the view only draws it. */
class VolumeHudModel {
    var volume = 0; private set
    var muted = false; private set
    var opened = 0; private set
    private var started = false
    /** A screen shows its own volume control (music app, drawer): changes no longer bring the bar up. */
    var quiet = false
    var shown = false; private set
    /** When the bar hides; [NEVER] while hidden or held. */
    var hideAt = NEVER; private set
    var holding = false; private set
    val muteFill = MuteFill()
    /** Start of the count after unmuting, or [NEVER]. */
    var countFrom = NEVER; private set
    private var countTo = 0
    /** Start of the shake after muting, or [NEVER]. */
    var shakeFrom = NEVER; private set

    /**
     * New values from the car, compared by value with the last ones; true when anything changed. The first call only
     * takes the values, as the emulator's first render is not a change.
     */
    fun update(volume: Int, muted: Boolean, opened: Int, now: Long, animate: Boolean = true): Boolean {
        if (!started) {
            started = true
            this.volume = volume; this.muted = muted; this.opened = opened
            muteFill.update(muted, now, animate = false)
            return false
        }
        if (volume == this.volume && muted == this.muted && opened == this.opened) return false
        val muteChanged = muted != this.muted
        this.volume = volume; this.muted = muted; this.opened = opened
        muteFill.update(muted, now, animate)
        if (muteChanged && animate) {
            if (!muted) { countFrom = now; countTo = volume }
            // a shake already running is not restarted: the class stays on until its animationend
            else if (!shaking(now)) shakeFrom = now
        }
        if (!quiet) poke(now)
        return true
    }

    /** Shows the bar and, unless a finger holds it, hides it [VISIBLE_MS] later. */
    fun poke(now: Long) {
        shown = true
        hideAt = if (holding) NEVER else now + VISIBLE_MS
    }

    /** A finger on the slider: the bar stays until it lifts. */
    fun hold(now: Long) { holding = true; poke(now) }

    fun release(now: Long) { holding = false; poke(now) }

    /** Hides the bar once its time is up; true when it just hid. */
    fun tick(now: Long): Boolean {
        if (!shown || hideAt == NEVER || now < hideAt) return false
        shown = false
        hideAt = NEVER
        return true
    }

    fun shaking(now: Long): Boolean = shakeFrom != NEVER && now - shakeFrom < SHAKE_MS

    fun counting(now: Long): Boolean = countFrom != NEVER && now - countFrom < MuteFill.MS

    /** The number shown: "Muted" is drawn instead while muted, else the count after unmuting, else the level. */
    fun number(now: Long): Int = if (counting(now)) countAt(countTo, now - countFrom) else volume

    /** Whether the shake or the count still moves at [now], so the view keeps drawing frames. */
    fun animating(now: Long): Boolean = shaking(now) || counting(now)
}

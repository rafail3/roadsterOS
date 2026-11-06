package ro.rdstr.car

import kotlinx.serialization.Serializable

/**
 * The car's small dash display: a 24-hour clock or the instant fuel consumption. It is driven by three keys and never
 * says what it shows, so after one match by hand it follows the unit's clock: each jump of the unit's local time (a
 * time set by hand or by a sync, another zone, summer time) is pressed into it as hour and minute steps.
 */
object DashClock {
    /** The keys the stock radio had: hour +1, minute +1, and the one switching between the clock and the consumption. */
    @Serializable
    enum class Key { HOUR, MINUTE, CLOCK }

    /** The unit's clock at a moment: wall time, time since boot, and its zone's offset in minutes. */
    data class Mark(val wallMs: Long, val elapsedMs: Long, val offsetMin: Int)

    const val DAY_MIN = 24 * 60
    private const val MINUTE_MS = 60_000L

    /** Minutes the unit's local time jumped between two marks of one run, beyond the time that passed. */
    fun jump(before: Mark, now: Mark): Int {
        val set = ((now.wallMs - before.wallMs) - (now.elapsedMs - before.elapsedMs)).toDouble() / MINUTE_MS
        return kotlin.math.round(set).toInt() + (now.offsetMin - before.offsetMin)
    }

    /** Across a restart the time since boot starts again: only a change of the zone's offset can be told apart. */
    fun jumpAcrossRestart(before: Mark, now: Mark): Int = now.offsetMin - before.offsetMin

    /** The local time of a mark, in minutes of the day. */
    fun localMinutes(m: Mark): Int = mod(((m.wallMs / MINUTE_MS) + m.offsetMin).toInt().let { it % DAY_MIN }, DAY_MIN)

    /**
     * The keys that move the display from [from] to [to], minutes of the day. Both count up and wrap, the minutes
     * without moving the hour, so 10:50 to 11:10 is one hour and twenty minutes.
     */
    fun keys(from: Int, to: Int): List<Key> {
        val f = mod(from, DAY_MIN)
        val t = mod(to, DAY_MIN)
        val hours = mod(t / 60 - f / 60, 24)
        val minutes = mod(t % 60 - f % 60, 60)
        return List(hours) { Key.HOUR } + List(minutes) { Key.MINUTE }
    }

    /** The keys for a jump of [jump] minutes, the display showing [shownBefore] until then. */
    fun follow(shownBefore: Int, jump: Int): List<Key> = if (jump == 0) emptyList() else keys(shownBefore, shownBefore + jump)

    private fun mod(a: Int, m: Int) = ((a % m) + m) % m
}

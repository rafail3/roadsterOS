package ro.rdstr.car

/** What the settings decide beyond storing a value: the clock's text, the volume a start allows, sizes on screen. */
object Settings {
    private const val MB = 1024L * 1024
    private const val GB = 1024L * MB

    /** The big clock: "14:05", or "2:05" on the 12-hour clock (no leading zero; the half of the day goes beside it). */
    fun clockText(hour: Int, minute: Int, h24: Boolean): String {
        val m = minute.toString().padStart(2, '0')
        if (h24) return "${hour.toString().padStart(2, '0')}:$m"
        val h = hour % 12
        return "${if (h == 0) 12 else h}:$m"
    }

    fun meridiem(hour: Int) = if (hour < 12) "AM" else "PM"

    /** 2024-01-01 UTC: a GPS receiver that missed a week rollover reports dates decades back; those fixes are ignored. */
    const val GPS_TIME_FLOOR_MS = 1_704_067_200_000L
    /** The clock is left alone while it is this close to the satellites' time. */
    const val GPS_TIME_TOLERANCE_MS = 2_000L

    /** The time to set the clock to from a GPS fix, or null when the clock is already close or the fix's date is wrong. */
    fun gpsClock(systemMs: Long, gpsMs: Long): Long? = when {
        gpsMs < GPS_TIME_FLOOR_MS -> null
        kotlin.math.abs(gpsMs - systemMs) <= GPS_TIME_TOLERANCE_MS -> null
        else -> gpsMs
    }

    /**
     * The commands that bring a loud last level down to [cap] when the car starts, so the first sound is never a blast.
     * [cap] 0 keeps the level the car was switched off with.
     */
    fun startVolume(s: RulesState, cap: Int): List<CarCommand> =
        if (cap <= 0 || s.volume <= cap) emptyList() else Rules.setVolume(s, cap)

    /** Memory and storage as people read them: whole MB below a GB, one decimal above (dropped when zero). */
    fun size(bytes: Long): String {
        if (bytes < GB) return "${(bytes + MB / 2) / MB} MB"
        val tenths = (bytes * 10 + GB / 2) / GB
        return if (tenths % 10 == 0L) "${tenths / 10} GB" else "${tenths / 10}.${tenths % 10} GB"
    }
}

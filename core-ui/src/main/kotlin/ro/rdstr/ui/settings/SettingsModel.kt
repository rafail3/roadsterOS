package ro.rdstr.ui.settings

import ro.rdstr.car.LaunchableApp
import ro.rdstr.car.Rules
import ro.rdstr.car.ShellState
import ro.rdstr.car.TimeSource
import ro.rdstr.car.Updater
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * What the settings app shows that is not in the shell's state or the rules' state: the radios, the clock, the map
 * packs, the apps on the unit, the journal, About and Software Update. The display app fills it from its runtime.
 */
data class SettingsState(
    val wifiOn: Boolean = false,
    val locationOn: Boolean = false,
    /** The network joined, null with none. */
    val wifiConnected: String? = null,
    val btOn: Boolean = false,
    /** The phone's Bluetooth name. */
    val device: String = "",
    /** Phones and other devices paired with the unit. */
    val savedDevices: Int = 0,
    /** The unit's clock now, ms since 1970, and its zone. */
    val now: Long = 0,
    val timeZone: String = "UTC",
    val packs: List<String> = emptyList(),
    val downloading: PackDownload? = null,
    /** The other apps installed on the unit that can go on home. */
    val installed: List<LaunchableApp> = emptyList(),
    /** Lines written to the journal since recording started. */
    val recorded: Int = 0,
    val about: About = About(),
    val update: Update = Update(),
    /** adb's address on Wi-Fi ("192.168.43.120:5555"), null off Wi-Fi. */
    val adbAddress: String? = null,
    /** The CAN box's steering angle, for the guidelines' preview. */
    val eps: Int = 0,
)

/** A country pack downloading: its code and the share done, 0 to 1. */
data class PackDownload(val code: String, val progress: Float)

/** Settings > About: what the unit reads of itself, as the screen writes it. */
data class About(
    val version: String = "dev",
    val android: String = "8.1.0",
    val model: String = "8227L_demo",
    val screen: String = "1024 × 600",
    val appMemory: String = "",
    val freeMemory: String = "",
    val totalMemory: String = "",
    val freeStorage: String = "",
    val totalStorage: String = "",
)

/** Settings > About > Software Update: the last check, the newer release found and its download. */
data class Update(
    val check: Check = Check.IDLE,
    /** When the last check got an answer, ms since 1970, 0 for never. */
    val checkedAt: Long = 0,
    /** The newer version found, and its APK's size in bytes. */
    val releaseOut: String? = null,
    val releaseBytes: Long = 0,
    val download: Updater.Download? = null,
    /** Seconds before the restart once the new version is in; null while it is not. */
    val restartIn: Int? = null,
    /** When this version went on the unit, ms since 1970. */
    val installedAt: Long = 0,
) {
    enum class Check { IDLE, CHECKING, LATEST, AVAILABLE, FAILED }
}

/** One section of the list (SettingsApp.tsx `PAGES`). */
enum class Section(val id: String, val label: String, val icon: String, val tint: Int) {
    WIFI("wifi", "Wi-Fi", "wifi-high", 0xFF0A84FF.toInt()),
    BLUETOOTH("bluetooth", "Bluetooth", "bluetooth", 0xFF0A84FF.toInt()),
    LOCATION("location", "Location", "navigation-arrow-fill", 0xFF0A84FF.toInt()),
    DISPLAY("display", "Display", "sun-fill", 0xFF5E5CE6.toInt()),
    DATETIME("datetime", "Date & Time", "clock-fill", 0xFF8E8E93.toInt()),
    SOUND("sound", "Sound", "speaker-high-fill", 0xFFFF375F.toInt()),
    PHONE("phone", "Phone", "phone-fill", 0xFF17D847.toInt()),
    DASH("dash", "Car Display", "car-fill", 0xFFFF3B30.toInt()),
    CAMERA("camera", "Reverse Camera", "camera", 0xFFFF9F0A.toInt()),
    HOME("home", "Home Screen", "house-fill", 0xFF5E5CE6.toInt()),
    MAPS("maps", "Maps", "map-trifold-fill", 0xFF30D158.toInt()),
    ABOUT("about", "About", "info-fill", 0xFF8E8E93.toInt()),
    DEVELOPER("developer", "Developer", "code", 0xFF636366.toInt()),
}

/** The settings app's decisions and texts, free of views (SettingsApp.tsx). */
object SettingsLogic {
    /** The level a start is capped at when the limit is first switched on. */
    const val START_VOLUME_DEFAULT = 12
    const val BRIGHTNESS_KEY_STEP = 5
    const val LINE_POS_MIN = -200
    const val LINE_POS_MAX = 70
    const val LINE_POS_STEP = 10
    const val MINUTE = 60_000L
    const val HOUR = 60 * MINUTE

    /** The sections listed: Developer only while it is switched on in About. */
    fun sections(shell: ShellState): List<Section> = Section.entries.filter { it != Section.DEVELOPER || shell.developer }

    /** The section shown for the shell's page: About for one not listed. */
    fun shown(shell: ShellState): Section = sections(shell).firstOrNull { it.id == shell.settingsPage } ?: Section.ABOUT

    /** What the list shows beside a section. */
    fun summary(section: Section, s: SettingsState, btConnected: Boolean, shell: ShellState): String = when (section) {
        Section.WIFI -> if (!s.wifiOn) "Off" else s.wifiConnected ?: "On"
        Section.BLUETOOTH -> if (!s.btOn) "Off" else if (btConnected) s.device else "On"
        Section.LOCATION -> if (s.locationOn) "On" else "Off"
        Section.DASH -> if (shell.dashMatched) "Synced" else ""
        else -> ""
    }

    val GUIDE_FOOTERS = mapOf(
        ro.rdstr.car.Guidelines.OFF to "No lines over the picture.",
        ro.rdstr.car.Guidelines.FIXED to "Two lines show the car's width and how close things are: red is nearest, then yellow and green.",
        ro.rdstr.car.Guidelines.STEERING to "The lines bend as the steering wheel turns, from the angle the car's CAN box sends. Red is nearest.",
    )

    fun syncFooter(autoTime: Boolean, source: TimeSource): String = when {
        !autoTime -> "The clock keeps the time set above and is not corrected."
        source == TimeSource.GPS -> "The time comes from the GPS satellites whenever there is a fix, no internet needed."
        else -> "The time comes from the internet whenever Wi-Fi is connected."
    }

    fun musicUnderNav(n: Int): String = when (n) {
        0 -> "The music stops while a direction is spoken and comes back after."
        100 -> "The music keeps its level under the voice."
        else -> "The music plays at $n% of its level while a direction is spoken."
    }

    fun startVolumeFooter(startVolume: Int): String =
        if (startVolume > 0) "The car starts no louder than $startVolume of ${Rules.MAX_VOLUME}; a quieter level stays as it was."
        else "The car starts at the volume it was switched off with."

    /** The guidelines' height as shown: the screen's y grows downwards, so a smaller value reads higher, "+N" above 0. */
    fun lineHeight(linePos: Int): String = (-linePos).let { if (it > 0) "+$it" else "$it" }

    /** A tile's hue for an app that is not ours, from its id. */
    fun markHue(id: String): Int = id.fold(7) { h, c -> (h * 31 + c.code) % 360 }

    enum class DatePart(val id: String) { DAY("day"), MONTH("month"), YEAR("year") }

    private fun calendar(ms: Long, zone: String) = Calendar.getInstance(TimeZone.getTimeZone(zone), Locale.UK).apply { timeInMillis = ms }

    /** How far the clock moves for one step of a part of the date; a month or a year keeps the day, or the month's last one. */
    fun dateStep(now: Long, zone: String, part: DatePart, by: Int): Long {
        val c = calendar(now, zone)
        if (part == DatePart.DAY) c.add(Calendar.DAY_OF_MONTH, by)
        else {
            val day = c.get(Calendar.DAY_OF_MONTH)
            c.set(Calendar.DAY_OF_MONTH, 1)
            c.add(if (part == DatePart.MONTH) Calendar.MONTH else Calendar.YEAR, by)
            c.set(Calendar.DAY_OF_MONTH, minOf(day, c.getActualMaximum(Calendar.DAY_OF_MONTH)))
        }
        return c.timeInMillis - now
    }

    private val WEEKDAYS = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
    private val MONTHS = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

    class Clock(val hour: Int, val minute: Int, val day: Int, val month: Int, val year: Int, val weekday: String) {
        val monthName get() = MONTHS[month]
    }

    fun clock(now: Long, zone: String): Clock = calendar(now, zone).let {
        Clock(it.get(Calendar.HOUR_OF_DAY), it.get(Calendar.MINUTE), it.get(Calendar.DAY_OF_MONTH), it.get(Calendar.MONTH), it.get(Calendar.YEAR),
            WEEKDAYS[it.get(Calendar.DAY_OF_WEEK) - 1])
    }

    /** `toLocaleDateString("en-GB", { weekday, day, month: "long", year })`: "Saturday 31 October 2026". */
    fun longDate(c: Clock) = "${c.weekday} ${c.day} ${c.monthName} ${c.year}"

    /** Software Update's dates: "31 October 2026", or with the time "31 Oct, 12:00". */
    fun updateDate(ms: Long, zone: String, time: Boolean = false): String = clock(ms, zone).let {
        if (time) "${it.day} ${it.monthName.take(3)}, ${pad(it.hour)}:${pad(it.minute)}" else "${it.day} ${it.monthName} ${it.year}"
    }

    fun pad(n: Int) = n.toString().padStart(2, '0')

    /** "1.1 MB of 2.4 MB · 480 KB/s · 2 s left" (update.ts `downloadFigures`). */
    fun downloadFigures(d: Updater.Download): String {
        val left = d.secondsLeft?.let { " · ${Updater.leftText(it)}" }.orEmpty()
        return "${Updater.sizeText(d.done)} of ${Updater.sizeText(d.total)} · ${Updater.speedText(d.bytesPerSecond)}$left"
    }

    /** The Software Update footer under the check row. */
    fun checkFooter(wifi: Boolean, u: Update, zone: String): String = when {
        !wifi -> "Connect the unit to Wi-Fi to look for a newer version."
        u.check == Update.Check.FAILED -> "GitHub did not answer. Try again in a moment."
        u.checkedAt > 0 -> "Last checked ${updateDate(u.checkedAt, zone, true)}."
        else -> "The unit also looks by itself each time it joins Wi-Fi."
    }

    /** The countries to group numbers by, matched by name, code or calling code ("+40"). */
    fun regionMatches(name: String, id: String, code: Int, query: String): Boolean {
        val q = query.trim().lowercase().removePrefix("+")
        return q.isEmpty() || name.lowercase().contains(q) || id.lowercase() == q || code.toString().startsWith(q)
    }

    /** Home Screen reorder: where a row dragged [dy] px from [from] lands among [n] rows [h] px tall. */
    fun dropIndex(from: Int, dy: Float, h: Float, n: Int): Int = (from + Math.round(dy / h)).coerceIn(0, n - 1)

    /** How far row [i] moves while row [from] is dragged [dy] to [to]: the dragged one with the finger, the others make room. */
    fun dragShift(i: Int, from: Int, to: Int, dy: Float, h: Float): Float = when {
        i == from -> dy
        i in (from + 1)..to -> -h
        i in to until from -> h
        else -> 0f
    }
}

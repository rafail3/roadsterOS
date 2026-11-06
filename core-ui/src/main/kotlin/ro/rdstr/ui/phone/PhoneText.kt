package ro.rdstr.ui.phone

import ro.rdstr.car.CarCommand
import ro.rdstr.car.CarEvent
import ro.rdstr.car.CarEvent.Call.State
import ro.rdstr.car.Contact
import ro.rdstr.car.Conversation
import ro.rdstr.car.PhoneBook
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** The words the phone screens put together from their state, free of Android so the JVM tests reach them. */
object PhoneText {
    private const val DAY_MS = 86_400_000L

    /** PhoneApp.tsx `when`: the time today, then Yesterday, the weekday within a week, else the date ("6 Nov"). */
    fun whenLabel(atMs: Long, nowMs: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val days = Math.round((midnight(nowMs, zone) - midnight(atMs, zone)).toDouble() / DAY_MS)
        fun fmt(p: String) = SimpleDateFormat(p, Locale.UK).apply { timeZone = zone }.format(atMs)
        return when {
            days <= 0 -> fmt("HH:mm")
            days == 1L -> "Yesterday"
            days < 7 -> fmt("EEEE")
            else -> fmt("d MMM")
        }
    }

    private fun midnight(ms: Long, zone: TimeZone): Long = Calendar.getInstance(zone).run {
        timeInMillis = ms
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        timeInMillis
    }

    /** `{name}[ · {label}]` of a keypad suggestion. */
    fun match(m: PhoneBook.Match?): String = m?.let { if (it.label.isNotEmpty()) "${it.name} · ${it.label}" else it.name }.orEmpty()

    /** Who a reply in [thread] goes to: the number the last received message came from, else the thread's own. */
    fun replyTo(thread: Conversation): String = thread.messages.lastOrNull { !it.sent }?.number ?: thread.number

    /** The conversation that holds [number], as the phone app opens it from the banner. */
    fun open(threads: List<Conversation>, number: String?): Conversation? =
        if (number == null) null else threads.firstOrNull { t -> t.messages.any { it.number == number } }

    /** PhoneScreenApp.tsx status: the title and the line under it while no picture plays. */
    fun castStatus(visible: Boolean, cast: CarEvent.PhoneCast.State, device: String, name: String): Pair<String, String> = when {
        !visible -> "Hidden from Phones" to "Phones cannot find the unit while this is off."
        cast == CarEvent.PhoneCast.State.CONNECTING -> "Connecting to ${device.ifEmpty { "the phone" }}…" to "Keep the phone close; the picture starts in a moment."
        cast == CarEvent.PhoneCast.State.OFF -> "Phone Screen" to "Phones can find the unit while the car is on."
        else -> "Phone Screen" to "On the phone, open Smart View or Screen Mirroring and choose $name."
    }
}

/** The call as the call screen and the call bar show it (the emulator's `call`): who, since when, where it is heard. */
data class CallInfo(
    val state: State,
    val number: String,
    val name: String = "",
    val label: String = "",
    /** When it was answered (ms since 1970), null before. */
    val since: Long? = null,
    val inCar: Boolean = true,
    val micMuted: Boolean = false,
    val held: Boolean = false,
    val conference: Boolean = false,
    val other: CarEvent.Call.Other? = null,
) {
    /** Overlays.tsx: the line under the name. */
    fun status(nowMs: Long): String = when {
        state == State.RINGING -> "Incoming call"
        state == State.OUTGOING -> "Calling…"
        held -> "On Hold"
        since != null -> PhoneBook.duration(nowMs - since)
        else -> ""
    }

    /** The call screen's status with the microphone and the phone's own audio. */
    fun statusLine(nowMs: Long) = listOfNotNull(status(nowMs).ifEmpty { null }, "Muted".takeIf { micMuted }, "On the phone".takeIf { !inCar }).joinToString(" · ")

    /** The call bar's status with the microphone. */
    fun barLine(nowMs: Long) = listOfNotNull(status(nowMs).takeIf { state != State.RINGING }?.ifEmpty { null }, "Muted".takeIf { micMuted }).joinToString(" · ")

    fun title(format: (String) -> String) = if (conference) "Conference" else name.ifEmpty { format(number) }

    /** The second call on hold beside this one (a waiting one rings instead). */
    val onHold get() = other?.takeIf { !it.waiting }
}

/**
 * The emulator store's call bookkeeping (store.ts `case "Call"`), which the unit's state does not carry: when each
 * call was answered (a call taken over from the second line starts its own clock), the car's microphone muted by
 * Phone.Mute, the label from the phone book. Reset when the calls end.
 */
class CallTrack {
    private val starts = HashMap<String, Long>()
    private var prev: CallInfo? = null
    private var muted = false

    /** The call to show for [call] (null when none), heard in the car with [inCar], at [nowMs]. */
    fun update(call: CarEvent.Call?, inCar: Boolean, contacts: List<Contact>, nowMs: Long): CallInfo? {
        if (call == null || call.state == State.IDLE) { starts.clear(); prev = null; muted = false; return null }
        val p = prev
        val same = p != null && call.number == p.number
        if (call.state == State.ACTIVE && call.number !in starts) starts[call.number] = if (same && p?.since != null) p.since else nowMs
        val match = PhoneBook.find(contacts, call.number)
        return CallInfo(
            state = call.state, number = call.number, name = call.name.ifEmpty { match?.name.orEmpty() }, label = match?.label.orEmpty(),
            since = starts[call.number], inCar = inCar, micMuted = muted, held = call.held, conference = call.conference,
            other = call.other?.let { o -> o.copy(name = o.name.ifEmpty { PhoneBook.find(contacts, o.number)?.name.orEmpty() }) },
        ).also { prev = it }
    }

    /** A command sent from the call screen: Phone.Mute sets the microphone. */
    fun sent(command: CarCommand) { if (command is CarCommand.Phone.Mute && prev != null) muted = command.on }
}

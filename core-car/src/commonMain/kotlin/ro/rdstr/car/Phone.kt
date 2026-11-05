package ro.rdstr.car

import kotlinx.serialization.Serializable
import ro.rdstr.car.CarEvent.Call.State

/** One number of a contact, with the kind the phone gives it (mobile, work, ...), as the phone book sync delivers it. */
@Serializable
data class PhoneNumber(val number: String, val label: String = "")

@Serializable
data class Contact(val name: String, val numbers: List<PhoneNumber>)

enum class CallType { INCOMING, OUTGOING, MISSED }

/** One line of the phone's call history. */
@Serializable
data class CallRecord(val number: String, val name: String = "", val type: CallType, val atMs: Long)

/** A line of the recents list: calls of one kind with one number in a row count as one, shown at the newest. */
@Serializable
data class RecentRow(val number: String, val name: String, val label: String, val type: CallType, val atMs: Long, val count: Int)

@Serializable
data class ContactSection(val letter: String, val contacts: List<Contact>)

/**
 * What the phone app shows before it can be used. Calls need a phone connected through the hands-free profile
 * (HFP), so the app opens only on one: with Bluetooth off the app switches it on, then asks for a phone.
 */
object PhoneGate {
    enum class Screen { BT_OFF, CONNECT, READY }

    /** The major device class Bluetooth reports for phones (BluetoothClass.Device.Major.PHONE). */
    const val MAJOR_PHONE = 0x0200

    fun screen(btOn: Boolean, hfpConnected: Boolean): Screen = when {
        hfpConnected -> Screen.READY
        btOn -> Screen.CONNECT
        else -> Screen.BT_OFF
    }

    /** Only phones are offered for calls; a tablet or a laptop has no hands-free side. */
    fun callCapable(majorClass: Int) = majorClass == MAJOR_PHONE
}

/** The phone book and call history synced from the phone (PBAP), as the screen lists them. */
object PhoneBook {
    /**
     * Two numbers are the same when their last digits agree, so "+40 721 111 222" is "0721 111 222".
     * ponytail: 9 digits is the national number length in Romania and most of Europe; a country with longer
     * national numbers can match two different numbers that end alike.
     */
    const val MATCH_DIGITS = 9
    /** The keypad suggests a contact from this many typed digits on. */
    const val SUGGEST_FROM = 3

    @Serializable
    data class Match(val name: String, val number: String, val label: String)

    fun digits(number: String): String = number.filter { it in '0'..'9' }

    fun same(a: String, b: String): Boolean {
        val x = digits(a)
        val y = digits(b)
        if (x.isEmpty() || y.isEmpty()) return false
        return if (x.length >= MATCH_DIGITS && y.length >= MATCH_DIGITS) x.takeLast(MATCH_DIGITS) == y.takeLast(MATCH_DIGITS) else x == y
    }

    /** The contact a number belongs to: the name the car shows for a caller, since the phone sends only the number. */
    fun find(contacts: List<Contact>, number: String): Match? {
        for (c in contacts) for (n in c.numbers) if (same(n.number, number)) return Match(c.name, n.number, n.label)
        return null
    }

    private fun sortKey(c: Contact) = TrackMatch.fold(c.name)

    private fun letter(c: Contact): String = sortKey(c).firstOrNull()?.takeIf { it.isLetter() }?.uppercase() ?: "#"

    /** Contacts by first letter, accents folded so "Ș" sits with "S"; names that start with no letter go last, under "#". */
    fun sections(contacts: List<Contact>): List<ContactSection> = contacts
        .sortedWith(compareBy<Contact>({ letter(it) == "#" }, { sortKey(it) }))
        .groupBy { letter(it) }
        .map { (letter, list) -> ContactSection(letter, list) }

    /** The contact under the digits typed on the keypad: an exact number first, else one that contains them. */
    fun suggest(contacts: List<Contact>, typed: String): Match? {
        val d = digits(typed)
        if (d.length < SUGGEST_FROM) return null
        find(contacts, d)?.let { return it }
        val sorted = sections(contacts).flatMap { it.contacts }
        for (c in sorted) for (n in c.numbers) if (digits(n.number).contains(d)) return Match(c.name, n.number, n.label)
        return null
    }

    /** The history newest first, repeated calls of one kind merged, each named from the call or else from the book. */
    fun recents(calls: List<CallRecord>, contacts: List<Contact>): List<RecentRow> {
        val rows = mutableListOf<RecentRow>()
        for (call in calls.sortedByDescending { it.atMs }) {
            val last = rows.lastOrNull()
            if (last != null && last.type == call.type && same(last.number, call.number)) {
                rows[rows.lastIndex] = last.copy(count = last.count + 1)
                continue
            }
            val match = find(contacts, call.number)
            rows += RecentRow(call.number, call.name.ifEmpty { match?.name.orEmpty() }, match?.label.orEmpty(), call.type, call.atMs, 1)
        }
        return rows
    }

    /** The letters on a contact's round badge: the first and last words that start with a letter. */
    fun initials(name: String): String {
        val words = name.trim().split(' ').filter { it.firstOrNull()?.isLetter() == true }
        if (words.isEmpty()) return ""
        val first = words.first().first().uppercase()
        return if (words.size == 1) first else first + words.last().first().uppercase()
    }

    /** A call's running time: 0:07, 12:34, 1:02:03. */
    fun duration(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s / 60) % 60
        val sec = (s % 60).toString().padStart(2, '0')
        return if (h > 0) "$h:${m.toString().padStart(2, '0')}:$sec" else "$m:$sec"
    }
}

/**
 * The hands-free profile as Android's Bluetooth stack reports it (android.bluetooth.BluetoothHeadsetClient, hidden
 * API on 8.1): call states of BluetoothHeadsetClientCall and the extras of its broadcasts.
 */
object Hfp {
    const val ACTIVE = 0
    const val HELD = 1
    const val DIALING = 2
    const val ALERTING = 3
    const val INCOMING = 4
    const val WAITING = 5
    const val HELD_BY_RESPONSE_AND_HOLD = 6
    const val TERMINATED = 7

    /** BluetoothProfile.HEADSET_CLIENT. */
    const val PROFILE = 16

    const val ACTION_CONNECTION_STATE_CHANGED = "android.bluetooth.headsetclient.profile.action.CONNECTION_STATE_CHANGED"
    const val ACTION_AUDIO_STATE_CHANGED = "android.bluetooth.headsetclient.profile.action.AUDIO_STATE_CHANGED"
    const val ACTION_AG_EVENT = "android.bluetooth.headsetclient.profile.action.AG_EVENT"
    const val ACTION_CALL_CHANGED = "android.bluetooth.headsetclient.profile.action.CALL_CHANGED"
    const val EXTRA_CALL = "android.bluetooth.headsetclient.extra.CALL"
    /** 0..5, as the phone reports it over HFP. */
    const val EXTRA_BATTERY_LEVEL = "android.bluetooth.headsetclient.extra.BATTERY_LEVEL"
    /** 0..5. */
    const val EXTRA_NETWORK_SIGNAL_STRENGTH = "android.bluetooth.headsetclient.extra.NETWORK_SIGNAL_STRENGTH"
    const val EXTRA_NETWORK_ROAMING = "android.bluetooth.headsetclient.extra.NETWORK_ROAMING"
    const val EXTRA_OPERATOR_NAME = "android.bluetooth.headsetclient.extra.OPERATOR_NAME"
    /** SCO audio: 0 disconnected, 2 connected (the call is heard in the car). */
    const val AUDIO_CONNECTED = 2

    /** All the phone's calls as one state for the screen: a call coming in first, then one being placed, then a call in progress. */
    fun state(calls: Collection<Int>): State = when {
        INCOMING in calls -> State.RINGING
        DIALING in calls || ALERTING in calls -> State.OUTGOING
        calls.any { it == ACTIVE || it == HELD || it == WAITING || it == HELD_BY_RESPONSE_AND_HOLD } -> State.ACTIVE
        else -> State.IDLE
    }
}

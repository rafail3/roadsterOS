package ro.rdstr.car

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One recorded line: milliseconds since recording start + the event. */
@Serializable
data class JournalLine(val t: Long, val event: CarEvent)

/**
 * Any line of the unit's journal: a car event (the same shape as [JournalLine], so the simulator replays it), a
 * command sent to the car, an action on the screen, or a warning or error the app logged.
 */
@Serializable
data class JournalRecord(
    /** Milliseconds since the app started: the timing the simulator replays by. */
    val t: Long,
    /** When it happened on the unit's clock, as "2026-11-06 14:05:09.123". */
    val at: String = "",
    val event: CarEvent? = null,
    val command: CarCommand? = null,
    val shell: ShellAction? = null,
    val error: String? = null,
    /** The first line of each start: version, Android, model. */
    val start: String? = null,
)

/**
 * Keeps the journal to what matters: a line the same as the last one of its kind is dropped (a phone repeating its
 * status, the same track again), and playback reports only count when playing starts or stops, not the position the
 * player sends every second. Commands, screen actions, errors and start lines are always kept.
 */
class JournalFilter {
    private val last = HashMap<String, String>()

    fun keep(r: JournalRecord): Boolean {
        val e = r.event ?: return true
        val kind = e::class.simpleName ?: return true
        val key = if (e is CarEvent.Playback) "playing=${e.playing}" else Journal.encodeEvent(e)
        if (last[kind] == key) return false
        last[kind] = key
        return true
    }
}

/** JSON Lines codec shared by the recorder (car-vendor) and the replayer (car-sim). */
object Journal {
    private val json = Json {
        classDiscriminator = "type"
        encodeDefaults = false
        ignoreUnknownKeys = true
    }

    fun encode(line: JournalLine): String = json.encodeToString(JournalLine.serializer(), line)
    fun decode(text: String): JournalLine = json.decodeFromString(JournalLine.serializer(), text)
    fun encodeEvent(event: CarEvent): String = json.encodeToString(CarEvent.serializer(), event)
    fun decodeEvent(text: String): CarEvent = json.decodeFromString(CarEvent.serializer(), text)

    /** A stack trace or a log line cut to this many characters, so one error cannot fill the journal. */
    const val MAX_ERROR = 1500

    fun encodeRecord(r: JournalRecord): String = json.encodeToString(JournalRecord.serializer(), r.copy(error = r.error?.take(MAX_ERROR)))

    /** The car events of a whole journal, in order; commands, screen actions and errors are skipped. */
    fun events(text: String): List<JournalLine> =
        text.lineSequence().filter { it.isNotBlank() }.mapNotNull { runCatching { decode(it) }.getOrNull() }.toList()
}

/**
 * The journal on the unit: one file a day, the days before today gzipped (about ten times smaller), the oldest days
 * deleted once all of them pass [MAX_BYTES]. Today's file always stays.
 */
object JournalFiles {
    const val MAX_BYTES = 10L * 1024 * 1024
    const val SUFFIX = ".jsonl"
    const val GZ = ".jsonl.gz"

    fun today(day: String) = "events-$day$SUFFIX"

    /** Days that ended and are still plain text: they get compressed. */
    fun toCompress(names: List<String>, day: String): List<String> = names.filter { it.endsWith(SUFFIX) && it != today(day) }

    /** The oldest files to delete so what is left fits in [max]; names sort by their date. */
    fun toDelete(files: Map<String, Long>, day: String, max: Long = MAX_BYTES): List<String> {
        var total = 0L
        val out = mutableListOf<String>()
        for ((name, size) in files.entries.sortedByDescending { it.key }) {
            total += size
            if (total > max && name != today(day)) out += name
        }
        return out
    }
}

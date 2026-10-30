package ro.rdstr.car

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One recorded line: milliseconds since recording start + the event. */
@Serializable
data class JournalLine(val t: Long, val event: CarEvent)

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
}

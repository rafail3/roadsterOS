package ro.rdstr.car

import kotlinx.serialization.json.Json

/**
 * Shell actions, car events and commands read from the same JSON the emulator and the journal use
 * (`{"type":"OpenApp","app":"SETTINGS"}`), for the unit's adb test commands.
 */
object DebugJson {
    private val json = Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = true
    }

    fun shellAction(text: String): ShellAction = json.decodeFromString(ShellAction.serializer(), text)
    fun event(text: String): CarEvent = json.decodeFromString(CarEvent.serializer(), text)
    fun command(text: String): CarCommand = json.decodeFromString(CarCommand.serializer(), text)
}

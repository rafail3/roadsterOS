package ro.rdstr.car

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport

/** One rules step: the state after an event and the actions it produced. */
@Serializable
data class Step(val state: RulesState, val actions: List<Action>)

/** JSON facade for hosts that cannot use Kotlin types directly (the browser emulator). */
@OptIn(ExperimentalJsExport::class)
@JsExport
object Bridge {
    private val json = Json {
        classDiscriminator = "type"
        encodeDefaults = false
        ignoreUnknownKeys = true
    }

    fun initial(): String = json.encodeToString(RulesState.serializer(), RulesState())

    fun apply(state: String, event: String): String {
        val (next, actions) = Rules.apply(json.decodeFromString(RulesState.serializer(), state), Journal.decodeEvent(event))
        return json.encodeToString(Step.serializer(), Step(next, actions))
    }

    fun command(state: String, command: String): String {
        val next = Rules.onCommand(
            json.decodeFromString(RulesState.serializer(), state),
            json.decodeFromString(CarCommand.serializer(), command),
        )
        return json.encodeToString(RulesState.serializer(), next)
    }

    fun echo(command: String): String {
        val event = CarEcho.respond(json.decodeFromString(CarCommand.serializer(), command)) ?: return "null"
        return Journal.encodeEvent(event)
    }

    fun shellInitial(): String = json.encodeToString(ShellState.serializer(), ShellState())

    fun shell(state: String, action: String): String {
        val next = Shell.reduce(
            json.decodeFromString(ShellState.serializer(), state),
            json.decodeFromString(ShellAction.serializer(), action),
        )
        return json.encodeToString(ShellState.serializer(), next)
    }

    fun effectiveTheme(state: String, hour: Int): String =
        Shell.effectiveTheme(json.decodeFromString(ShellState.serializer(), state), hour).name
}

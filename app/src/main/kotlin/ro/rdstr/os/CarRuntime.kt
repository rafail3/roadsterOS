package ro.rdstr.os

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ro.rdstr.car.Action
import ro.rdstr.car.CarBus
import ro.rdstr.car.CarCommand
import ro.rdstr.car.CarEvent
import ro.rdstr.car.Rules
import ro.rdstr.car.RulesState

data class UiState(
    val state: RulesState = RulesState(),
    val camera: Boolean = false,
    val call: CarEvent.Call? = null,
    val night: Boolean = false,
    val lastEvent: String = "",
) {
    fun reduce(state: RulesState, actions: List<Action>, event: CarEvent): UiState {
        var ui = copy(state = state, lastEvent = event.toString())
        for (a in actions) ui = when (a) {
            Action.ShowCamera -> ui.copy(camera = true)
            Action.HideCamera -> ui.copy(camera = false)
            is Action.ShowCall -> ui.copy(call = a.call)
            Action.HideCall -> ui.copy(call = null)
            is Action.Night -> ui.copy(night = a.on)
            is Action.Send, Action.SaveState, Action.RestoreState -> ui
        }
        return ui
    }
}

/** Process-wide runtime: one bus, one rules state, one UI state. Owned by CarService. */
object CarRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val bus: CarBus by lazy { CarBusFactory.create() }
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui
    private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        val prefs = context.getSharedPreferences("rdstr", Context.MODE_PRIVATE)
        scope.launch {
            bus.events.collect { event ->
                val (next, actions) = Rules.apply(_ui.value.state, event)
                for (a in actions) when (a) {
                    is Action.Send -> bus.send(a.command)
                    Action.SaveState -> prefs.edit().putString("source", next.source.name).putBoolean("playing", next.playing).apply()
                    else -> Unit
                }
                _ui.value = _ui.value.reduce(next, actions, event)
            }
        }
    }

    fun send(command: CarCommand) {
        _ui.value = _ui.value.copy(state = Rules.onCommand(_ui.value.state, command))
        bus.send(command)
    }

    fun ensureService(context: Context) {
        context.startForegroundService(Intent(context, CarService::class.java))
    }
}

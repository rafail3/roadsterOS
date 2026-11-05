package ro.rdstr.os

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.provider.ContactsContract
import android.util.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import ro.rdstr.car.Action
import ro.rdstr.car.CarBus
import ro.rdstr.car.CarCommand
import ro.rdstr.car.CallRecord
import ro.rdstr.car.CarEvent
import ro.rdstr.car.Contact
import ro.rdstr.car.PhoneBook
import ro.rdstr.car.PhoneGate
import ro.rdstr.car.Rules
import ro.rdstr.car.Shell
import ro.rdstr.car.ShellAction
import ro.rdstr.car.ShellState
import ro.rdstr.car.RulesState
import ro.rdstr.os.cover.Cover
import ro.rdstr.os.cover.CoverDesk
import ro.rdstr.os.phone.PhoneBookReader

data class UiState(
    val state: RulesState = RulesState(),
    val camera: Boolean = false,
    val call: CarEvent.Call? = null,
    val night: Boolean = false,
    val track: CarEvent.Track? = null,
    val lastEvent: String = "",
    /** The phone's battery, signal and network over the hands-free link; null until the phone sends them. */
    val phoneStatus: CarEvent.PhoneStatus? = null,
    /** Where the call is heard; back to the car for every new call. */
    val callInCar: Boolean = true,
    val contacts: List<Contact> = emptyList(),
    val calls: List<CallRecord> = emptyList(),
    /** The connected phone's Bluetooth name, for the phone chip and the call screen; empty with none. */
    val phoneName: String = "",
    /** The phone's voice assistant listens. */
    val voiceAssistant: Boolean = false,
    /** Text messages since the phone connected, and the ids read on screen. */
    val messages: List<CarEvent.Message> = emptyList(),
    val read: Set<String> = emptySet(),
) {
    fun reduce(state: RulesState, actions: List<Action>, event: CarEvent): UiState {
        var ui = copy(state = state, lastEvent = event.toString(), track = event as? CarEvent.Track ?: track)
        for (a in actions) ui = when (a) {
            Action.ShowCamera -> ui.copy(camera = true)
            Action.HideCamera -> ui.copy(camera = false)
            // the phone sends only the number; the name comes from its synced phone book
            is Action.ShowCall -> ui.copy(call = named(a.call))
            Action.HideCall -> ui.copy(call = null, callInCar = true)
            is Action.Night -> ui.copy(night = a.on)
            is Action.Send, Action.SaveState, Action.RestoreState, Action.ToggleScreen, Action.Home, Action.Back, Action.ShowVolume -> ui
        }
        when (event) {
            is CarEvent.PhoneStatus -> ui = ui.copy(phoneStatus = event)
            is CarEvent.CallAudio -> ui = ui.copy(callInCar = event.inCar)
            is CarEvent.VoiceAssistant -> ui = ui.copy(voiceAssistant = event.active)
            is CarEvent.Message -> ui = ui.copy(messages = ui.messages + event, read = if (event.sent) ui.read + event.id else ui.read)
            // a dropped phone takes its name and its network with it
            is CarEvent.HfpConnection -> ui = if (event.connected) ui.copy(phoneName = event.device) else ui.copy(phoneName = "", phoneStatus = null)
            else -> Unit
        }
        return ui
    }

    /** The phone sends only numbers; the names, the second call's too, come from its synced phone book. */
    private fun named(c: CarEvent.Call): CarEvent.Call {
        val name = c.name.ifEmpty { PhoneBook.find(contacts, c.number)?.name.orEmpty() }
        val other = c.other?.let { o -> o.copy(name = o.name.ifEmpty { PhoneBook.find(contacts, o.number)?.name.orEmpty() }) }
        return c.copy(name = name, other = other)
    }
}

/** Process-wide runtime: one bus, one rules state, one UI state. Owned by CarService. */
object CarRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var bus: CarBus
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui
    private var started = false
    private var covers: CoverDesk? = null
    private val noCover = MutableStateFlow<Cover?>(null)

    /** The cover of the playing Bluetooth track, ready before any screen asks for it. */
    val cover: StateFlow<Cover?> get() = covers?.cover ?: noCover

    fun start(context: Context) {
        if (started) return
        started = true
        bus = CarBusFactory.create(context)
        watchPhoneBook(context.applicationContext)
        val prefs = context.getSharedPreferences("rdstr", Context.MODE_PRIVATE)
        covers = CoverDesk.create(context.applicationContext, scope).also { desk ->
            desk.follow(ui.map { it.track }.distinctUntilChanged())
        }
        scope.launch {
            bus.events.collect { event ->
                val (next, actions) = Rules.apply(_ui.value.state, event)
                for (a in actions) when (a) {
                    is Action.Send -> bus.send(a.command)
                    Action.SaveState -> prefs.edit().putString("source", next.source.name).putBoolean("playing", next.playing).apply()
                    else -> Unit
                }
                _ui.value = _ui.value.reduce(next, actions, event)
                // a new call fills the screen, an ended one goes; Home folds it into its bar
                if (event is CarEvent.Call) shellAction(ShellAction.CallChanged(event.state))
            }
        }
    }

    /** The shell's own state (core-car Shell): for now the way a call shows. */
    private val _shell = MutableStateFlow(ShellState())
    val shell: StateFlow<ShellState> = _shell

    fun shellAction(action: ShellAction) {
        _shell.value = Shell.reduce(_shell.value, action)
    }

    fun send(command: CarCommand) {
        _ui.value = _ui.value.copy(state = Rules.onCommand(_ui.value.state, command))
        bus.send(command)
    }

    private var phoneBookLoad: Job? = null

    /** The synced phone book and call history, read again a moment after the sync stops changing them. */
    private fun watchPhoneBook(context: Context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                phoneBookLoad?.cancel()
                phoneBookLoad = scope.launch {
                    delay(PHONE_BOOK_SETTLE_MS)
                    val contacts = PhoneBookReader.contacts(context)
                    val calls = PhoneBookReader.calls(context)
                    Log.i(PHONE_TAG, "phone book contacts=${contacts.size} calls=${calls.size}")
                    _ui.value = _ui.value.copy(contacts = contacts, calls = calls)
                }
            }
        }
        runCatching {
            context.contentResolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, observer)
            context.contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, observer)
        }.onFailure { Log.w(PHONE_TAG, "phone book not watched", it) }
        observer.onChange(false)
    }

    /**
     * Opening the phone app: calls need a phone connected for calls, so Bluetooth is switched on when it is off,
     * and the screen asks for a phone until one is connected.
     */
    @SuppressLint("MissingPermission")
    fun openPhone(): PhoneGate.Screen {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        val on = adapter?.isEnabled == true
        if (adapter != null && !on) Log.i(PHONE_TAG, "bluetooth on for the phone app -> ${adapter.enable()}")
        return PhoneGate.screen(on, _ui.value.state.hfpConnected).also { Log.i(PHONE_TAG, "phone app $it") }
    }

    private const val PHONE_TAG = "rdstr.phone"
    /** The phone book sync writes in bursts; read once it has been quiet this long. */
    private const val PHONE_BOOK_SETTLE_MS = 1500L

    fun ensureService(context: Context) {
        context.startForegroundService(Intent(context, CarService::class.java))
    }
}

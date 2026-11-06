package ro.rdstr.os

import android.content.Context
import android.content.SharedPreferences
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
import ro.rdstr.car.JournalRecord
import ro.rdstr.car.Rules
import ro.rdstr.car.Settings
import ro.rdstr.car.Shell
import ro.rdstr.car.ShellAction
import ro.rdstr.car.ShellState
import ro.rdstr.car.RulesState
import ro.rdstr.os.cover.Cover
import ro.rdstr.os.cover.CoverDesk
import ro.rdstr.os.phone.PhoneBookReader
import ro.rdstr.os.settings.Radios
import ro.rdstr.os.settings.SystemInfo

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
    /** This unit can carry text messages; null until its Bluetooth stack has answered. */
    val messagesSupported: Boolean? = null,
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
            is CarEvent.MessageSupport -> ui = ui.copy(messagesSupported = event.available)
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
    private lateinit var prefs: SharedPreferences
    private lateinit var app: Context

    /** The shell's own state; its preferences are kept across starts (core-car Shell.preferences). */
    private val _shell = MutableStateFlow(ShellState())
    val shell: StateFlow<ShellState> = _shell

    /** The clock's sync, the time set by hand and the time zone (Date & Time). */
    lateinit var time: ro.rdstr.os.settings.TimeKeeper
        private set

    /** The dash display following the unit's clock (Car Display). */
    private lateinit var dash: ro.rdstr.os.settings.DashFollower

    /** Bluetooth, Wi-Fi and location, as the settings and the drawer switch them. */
    lateinit var radios: Radios
        private set

    /** The cover of the playing Bluetooth track, ready before any screen asks for it. */
    val cover: StateFlow<Cover?> get() = covers?.cover ?: noCover

    fun start(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
        bus = CarBusFactory.create(context)
        radios = Radios(app)
        prefs = context.getSharedPreferences("rdstr", Context.MODE_PRIVATE)
        _shell.value = Shell.decodePreferences(prefs.getString(SHELL_KEY, null))
        ro.rdstr.os.settings.DeveloperTools.setAdb(app, _shell.value.adbEnabled)
        time = ro.rdstr.os.settings.TimeKeeper(app).also { it.apply(_shell.value.autoTime, _shell.value.timeSource) }
        dash = ro.rdstr.os.settings.DashFollower(app, prefs) { send(CarCommand.DashKey(it)) }.also { it.start { _shell.value.dashMatched } }
        val header = "roadsterOS ${runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull()} " +
            "Android ${android.os.Build.VERSION.RELEASE} ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
        journal = ro.rdstr.os.settings.JournalRecorder(java.io.File(app.filesDir, "journal"), header).also {
            it.catchCrashes()
            it.followLogs(_shell.value.recordEvents)
        }
        watchPhoneBook(app)
        watchIdle()
        covers = CoverDesk.create(context.applicationContext, scope).also { desk ->
            desk.follow(ui.map { it.track }.distinctUntilChanged())
        }
        scope.launch {
            bus.events.collect { event ->
                var (next, actions) = Rules.apply(_ui.value.state, event)
                for (a in actions) when (a) {
                    is Action.Send -> {
                        // the rules already counted this command; it only goes to the car, and into the journal
                        if (_shell.value.recordEvents) journal.write(JournalRecord(0, command = a.command))
                        bus.send(a.command)
                    }
                    Action.SaveState -> prefs.edit().putString("source", next.source.name).putBoolean("playing", next.playing).apply()
                    else -> Unit
                }
                // the car starts: a loud last level comes down to the start volume chosen in the settings
                if (event is CarEvent.Power && event.acc) for (c in Settings.startVolume(next, _shell.value.startVolume)) {
                    bus.send(c)
                    next = Rules.onCommand(next, c)
                }
                _ui.value = _ui.value.reduce(next, actions, event)
                if (Shell.wakesAod(event)) userActivity()
                if (_shell.value.recordEvents) journal.write(JournalRecord(0, event = event))
                // a new call fills the screen, an ended one goes; Home folds it into its bar
                if (event is CarEvent.Call) shellAction(ShellAction.CallChanged(event.state))
            }
        }
    }

    fun send(command: CarCommand) {
        _ui.value = _ui.value.copy(state = Rules.onCommand(_ui.value.state, command))
        if (_shell.value.recordEvents) journal.write(JournalRecord(0, command = command))
        bus.send(command)
    }

    /** A shell action from the screen; a changed preference is written at once, so a power cut does not lose it. */
    fun shellAction(action: ShellAction) {
        val before = _shell.value
        val after = Shell.reduce(before, action)
        _shell.value = after
        if (after.recordEvents) journal.write(JournalRecord(0, shell = action))
        if (after.recordEvents != before.recordEvents) journal.followLogs(after.recordEvents)
        if (after.autoTime != before.autoTime || after.timeSource != before.timeSource) time.apply(after.autoTime, after.timeSource)
        if (after.dashMatched && !before.dashMatched) dash.matched()
        if (after.adbEnabled != before.adbEnabled) ro.rdstr.os.settings.DeveloperTools.setAdb(app, after.adbEnabled)
        if (Shell.preferences(after) != Shell.preferences(before)) {
            prefs.edit().putString(SHELL_KEY, Shell.encodePreferences(after)).apply()
            Log.i(Radios.TAG, "saved ${Shell.encodePreferences(after)}")
        }
    }

    private var lastActivity = android.os.SystemClock.elapsedRealtime()

    /** A touch on any of our screens, or a key: the always-on display ends and its wait starts again. */
    fun userActivity() {
        lastActivity = android.os.SystemClock.elapsedRealtime()
        if (_shell.value.aod) shellAction(ShellAction.ExitAod)
    }

    /** Checks the wait of the always-on display; a call, the reverse camera or the map keep the screen as it is. */
    private fun watchIdle() = scope.launch {
        while (true) {
            delay(IDLE_CHECK_MS)
            val ui = _ui.value
            val busy = ui.camera || ui.state.call != CarEvent.Call.State.IDLE || _shell.value.app == ro.rdstr.car.App.MAPS
            if (Shell.aodDue(_shell.value, android.os.SystemClock.elapsedRealtime() - lastActivity, busy)) shellAction(ShellAction.EnterAod)
        }
    }

    /** The troubleshooting journal in files/journal, one file a day (core-car JournalFiles); adb pull it to read. */
    private lateinit var journal: ro.rdstr.os.settings.JournalRecorder

    /** The other apps that can go on home, and starting one of them. */
    fun installedApps() = ro.rdstr.os.settings.InstalledApps.read(app).also { apps ->
        shellAction(ShellAction.AppsInstalled(apps.map { it.id }))
    }

    fun launch(pkg: String) = ro.rdstr.os.settings.InstalledApps.launch(app, pkg)

    /** The Developer section: adb's address on Wi-Fi, restarting roadsterOS, Android's developer options. */
    fun adbAddress() = ro.rdstr.os.settings.DeveloperTools.adbAddress(app)

    fun restart() = ro.rdstr.os.settings.DeveloperTools.restart(app)

    fun openAndroidSettings() = ro.rdstr.os.settings.DeveloperTools.openAndroidSettings(app)

    /** The About page, read when it opens. */
    fun systemInfo(): SystemInfo = SystemInfo.read(app)

    private const val SHELL_KEY = "shell"

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
    fun openPhone(): PhoneGate.Screen {
        val on = radios.bluetoothOn
        if (!on) radios.bluetooth(true)
        return PhoneGate.screen(on, _ui.value.state.hfpConnected).also { Log.i(PHONE_TAG, "phone app $it") }
    }

    private const val PHONE_TAG = "rdstr.phone"
    private const val IDLE_CHECK_MS = 5_000L
    /** The phone book sync writes in bursts; read once it has been quiet this long. */
    private const val PHONE_BOOK_SETTLE_MS = 1500L

    fun ensureService(context: Context) {
        context.startForegroundService(Intent(context, CarService::class.java))
    }
}

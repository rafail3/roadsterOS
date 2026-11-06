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
import ro.rdstr.car.PlaybackClock
import ro.rdstr.car.Startup
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
import ro.rdstr.ui.Emulates

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
    /** The thread the phone app opens on, from the banner; and the last message received, for the banner. */
    val thread: String? = null,
    val newMessage: CarEvent.Message? = null,
    /** The phone's last reported position and when it came (ms since 1970); [elapsedMs] moves it on. */
    val positionMs: Long = 0,
    val positionAt: Long = 0,
    /** The tuner's report for each frequency heard, by [stationKey]: its RDS name, programme type and signal. */
    val heard: Map<String, CarEvent.Radio> = emptyMap(),
    /** The station tuned now, with the name heard for it; null until the tuner reports one. */
    val radio: CarEvent.Radio? = null,
    /** Frequencies starred in our radio app, in order. */
    val favorites: List<Int> = emptyList(),
    /** Bumped whenever the volume bar should come up, even with nothing changed. */
    val volumeOpened: Int = 0,
    /** Bluetooth switching on, asked by the phone app. */
    val btTurningOn: Boolean = false,
    /** The "restart the unit?" question is on screen. */
    val restartAsked: Boolean = false,
    /** Wi-Fi was asked to turn on with location off: the screen asks to turn location on first. */
    val wifiNeedsLocation: Boolean = false,
    val wifi: ro.rdstr.os.settings.WifiState = ro.rdstr.os.settings.WifiState(),
    /** The country map packs on the unit, and the one downloading. */
    val maps: MapsState = MapsState(),
) {
    fun reduce(state: RulesState, actions: List<Action>, event: CarEvent, now: Long = System.currentTimeMillis()): UiState {
        var ui = copy(state = state, lastEvent = event.toString(), track = event as? CarEvent.Track ?: track)
        for (a in actions) ui = when (a) {
            Action.ShowCamera -> ui.copy(camera = true)
            Action.HideCamera -> ui.copy(camera = false)
            // the phone sends only the number; the name comes from its synced phone book
            is Action.ShowCall -> ui.copy(call = named(a.call))
            Action.HideCall -> ui.copy(call = null, callInCar = true)
            is Action.Night -> ui.copy(night = a.on)
            Action.ShowVolume -> ui.copy(volumeOpened = ui.volumeOpened + 1)
            is Action.Send, Action.SaveState, Action.RestoreState, Action.ToggleScreen, Action.Home, Action.Back, is Action.OpenApp -> ui
        }
        when (event) {
            is CarEvent.PhoneStatus -> ui = ui.copy(phoneStatus = event)
            is CarEvent.CallAudio -> ui = ui.copy(callInCar = event.inCar)
            is CarEvent.VoiceAssistant -> ui = ui.copy(voiceAssistant = event.active)
            is CarEvent.MessageSupport -> ui = ui.copy(messagesSupported = event.available)
            is CarEvent.Playback -> ui = ui.copy(positionMs = event.positionMs, positionAt = now)
            // a station's name arrives a moment after its frequency: a report without one keeps the name heard before
            is CarEvent.Radio -> stationKey(event.band, event.khz).let { key ->
                if (event.rds.isNotEmpty() || key !in ui.heard) ui = ui.copy(heard = ui.heard + (key to event))
                ui = ui.copy(radio = ui.heard[key] ?: event)
            }
            is CarEvent.Message -> ui = ui.copy(
                messages = ui.messages + event,
                read = if (event.sent) ui.read + event.id else ui.read,
                newMessage = if (event.sent) ui.newMessage else event,
            )
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

    /** Where playback is now: the last reported position, advanced while playing (core-car PlaybackClock). */
    @Emulates("store.elapsedMs")
    fun elapsedMs(now: Long = System.currentTimeMillis()): Long =
        PlaybackClock.elapsedMs(positionMs, positionAt, state.playing, track?.durationMs ?: 0, now)

    /**
     * What the tuner reported at a frequency: its RDS name and programme type once heard there, null for a frequency
     * not heard yet. The emulator answers from its simulated stations; the unit only knows what its tuner sent.
     */
    @Emulates("radio.stationAt")
    fun stationAt(band: CarEvent.Radio.Band, khz: Int): CarEvent.Radio? = heard[stationKey(band, khz)]

    companion object {
        fun stationKey(band: CarEvent.Radio.Band, khz: Int) = "$band:$khz"
    }
}

/** The country packs on the unit by ISO code, and the one downloading with its share done, 0 to 1. */
data class MapsState(val installed: List<String> = emptyList(), val downloading: PackProgress? = null)
data class PackProgress(val code: String, val progress: Float)

/** Process-wide runtime: one bus, one rules state, one UI state. Owned by CarService. */
object CarRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var bus: CarBus
    private val _ui = MutableStateFlow(UiState())
    @Emulates("store.useEmulator")
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
    /** New versions from GitHub: checked on each Wi-Fi join; the pop-up and the progress bar read its state. */
    lateinit var updates: ro.rdstr.os.setup.Updates
        private set

    /** The dash display following the unit's clock (Car Display). */
    private lateinit var dash: ro.rdstr.os.settings.DashFollower

    /** Bluetooth, Wi-Fi and location, as the settings and the drawer switch them. */
    lateinit var radios: Radios
        private set

    /** Scans, joins and forgets Wi-Fi networks; its state is [UiState.wifi]. */
    lateinit var wifi: ro.rdstr.os.settings.Wifi
        private set

    /** The cover of the playing Bluetooth track, ready before any screen asks for it. */
    val cover: StateFlow<Cover?> get() = covers?.cover ?: noCover

    fun start(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
        bus = CarBusFactory.create(context)
        radios = Radios(app)
        wifi = ro.rdstr.os.settings.Wifi(app, radios, { _ui.value.wifi }) { change -> patch { it.copy(wifi = change(it.wifi)) } }
            .also { it.start() }
        prefs = context.getSharedPreferences("rdstr", Context.MODE_PRIVATE)
        _shell.value = Shell.decodePreferences(prefs.getString(SHELL_KEY, null))
        val favorites = prefs.getString(FAVORITES_KEY, null).orEmpty().split(',').mapNotNull { it.toIntOrNull() }
        val packs = ro.rdstr.os.maps.MapPacks.codes(ro.rdstr.os.maps.MapPacks.dir(app))
        patch { it.copy(favorites = favorites, maps = it.maps.copy(installed = packs)) }
        // first, so that every command sent at start can go into it
        val header = "roadsterOS ${runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull()} " +
            "Android ${android.os.Build.VERSION.RELEASE} ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
        journal = ro.rdstr.os.settings.JournalRecorder(java.io.File(app.filesDir, "journal"), header).also {
            it.catchCrashes()
            it.followLogs(_shell.value.recordEvents)
        }
        ro.rdstr.os.settings.DeveloperTools.setAdb(app, _shell.value.adbEnabled)
        time = ro.rdstr.os.settings.TimeKeeper(app).also { it.apply(_shell.value.autoTime, _shell.value.timeSource) }
        startCallVolume()
        // the vendor keeps its own copy of the camera settings: ours are written over it at each start
        ro.rdstr.car.Camera.writes(null, _shell.value.camera).forEach { send(CarCommand.Camera.Write(it)) }
        dash = ro.rdstr.os.settings.DashFollower(app, prefs) { send(CarCommand.DashKey(it)) }.also { it.start { _shell.value.dashMatched } }
        watchPhoneBook(app)
        watchIdle()
        resumeAtStart()
        covers = CoverDesk.create(context.applicationContext, scope).also { desk ->
            desk.follow(ui.map { it.track }.distinctUntilChanged())
        }
        scope.launch {
            bus.events.collect(::onEvent)
        }
        updates = ro.rdstr.os.setup.Updates(app).also { it.afterStart(); it.watchWifi() }
        cast = ro.rdstr.os.cast.CastReceiver(app, { _shell.value.carName }) { e -> scope.launch { onEvent(e) } }
        castVisibility()
    }

    /** Every car event, from the bus or from our own receivers, goes through the rules here. */
    @Synchronized private fun onEvent(event: CarEvent) {
        val wasLive = _ui.value.state.cast == CarEvent.PhoneCast.State.LIVE
        var (next, actions) = Rules.apply(_ui.value.state, event)
        for (a in actions) when (a) {
            is Action.Send -> {
                // the rules already counted this command; it only goes to the car, and into the journal
                if (_shell.value.recordEvents) journal.write(JournalRecord(0, command = a.command))
                bus.send(a.command)
            }
            Action.SaveState -> prefs.edit().putString(KEPT_KEY, Startup.encodeKept(next)).apply()
            is Action.OpenApp -> shellAction(ShellAction.OpenApp(a.app))
            else -> Unit
        }
        // the car starts: a loud last level comes down to the start volume chosen in the settings
        if (event is CarEvent.Power && event.acc) startCallVolume()
        if (event is CarEvent.Power) if (event.acc) connectLastPhone() else connecting?.cancel()
        if (event is CarEvent.Power) { accOn = event.acc; castVisibility() }
        if (event is CarEvent.BtConnection && event.connected && event.device.isNotEmpty()) prefs.edit().putString(LAST_PHONE_KEY, event.device).apply()
        if (event is CarEvent.Power && event.acc) for (c in Settings.startVolume(next, _shell.value.startVolume)) {
            bus.send(c)
            next = Rules.onCommand(next, c)
        }
        _ui.value = _ui.value.reduce(next, actions, event)
        if (Shell.wakesAod(event)) userActivity()
        if (_shell.value.recordEvents) journal.write(JournalRecord(0, event = event))
        // a new call fills the screen, an ended one goes; Home folds it into its bar
        if (event is CarEvent.Call) shellAction(ShellAction.CallChanged(event.state))
        // reverse gear takes the screen from a camera opened by hand, and closes it when it comes out
        if (event is CarEvent.ReverseGear) shellAction(ShellAction.CloseCamera)
        // the phone's screen opens over the shell when its picture plays, and leaves when the phone stops
        if (event is CarEvent.PhoneCast && wasLive != (event.state == CarEvent.PhoneCast.State.LIVE)) shellAction(ShellAction.CastChanged(event.state))
    }

    @Emulates("store.carCommand")
    fun send(command: CarCommand) {
        // a phone picked by hand ends the wait for the last one
        if (command is CarCommand.Bluetooth.Connect && !reconnecting) connecting?.cancel()
        patch { it.copy(state = Rules.onCommand(it.state, command)) }
        if (_shell.value.recordEvents) journal.write(JournalRecord(0, command = command))
        bus.send(command)
    }

    /** A shell action from the screen; a changed preference is written at once, so a power cut does not lose it. */
    @Emulates("store.shellAction")
    fun shellAction(action: ShellAction) {
        val before = _shell.value
        val after = Shell.reduce(before, action)
        _shell.value = after
        if (after.recordEvents) journal.write(JournalRecord(0, shell = action))
        if (after.recordEvents != before.recordEvents) journal.followLogs(after.recordEvents)
        if (after.autoTime != before.autoTime || after.timeSource != before.timeSource) time.apply(after.autoTime, after.timeSource)
        if (after.dashMatched && !before.dashMatched) dash.matched()
        if (after.castVisible != before.castVisible) castVisibility()
        if (after.adbEnabled != before.adbEnabled) ro.rdstr.os.settings.DeveloperTools.setAdb(app, after.adbEnabled)
        // a change in the sound window: only the processor's values that moved are written
        if (after.dsp != before.dsp) ro.rdstr.car.Sound.writes(before.dsp, after.dsp).forEach { send(CarCommand.Dsp(it)) }
        if (after.camera != before.camera) ro.rdstr.car.Camera.writes(before.camera, after.camera).forEach { send(CarCommand.Camera.Write(it)) }
        if (Shell.preferences(after) != Shell.preferences(before)) {
            prefs.edit().putString(SHELL_KEY, Shell.encodePreferences(after)).apply()
            Log.i(Radios.TAG, "saved ${Shell.encodePreferences(after)}")
        }
    }

    /** Every change of the UI state outside [onEvent] goes through here, on the same lock, so none is lost. */
    @Synchronized private fun patch(change: (UiState) -> UiState) { _ui.value = change(_ui.value) }

    /** A level picked on screen; the shared rules turn it into steps and mute at zero (core-car Rules.setVolume). */
    @Emulates("store.setVolume")
    fun setVolume(level: Int) = Rules.setVolume(_ui.value.state, level).forEach(::send)

    /** One level up or down from wherever the volume is now; safe to call again from a held key. */
    @Emulates("store.stepVolume")
    fun stepVolume(delta: Int) = setVolume(_ui.value.state.volume + delta)

    /** The volume bar comes up, even with nothing changed. */
    @Emulates("store.openVolume")
    fun openVolume() = patch { it.copy(volumeOpened = it.volumeOpened + 1) }

    /** A transport button on a sound page, its app or the map's strip (core-car HomeCard.press). */
    @Emulates("store.pressSound")
    fun pressSound(page: ro.rdstr.car.CardPage, press: ro.rdstr.car.HomeCard.Press) =
        ro.rdstr.car.HomeCard.press(page, press, _ui.value.state).forEach(::send)

    /** A change from the sound window: kept with the shell's settings; [shellAction] writes only the values that moved. */
    @Emulates("store.setSound")
    fun setSound(next: ro.rdstr.car.SoundSettings) = shellAction(ShellAction.SetSound(next))

    /** The camera settings: kept with the shell's; [shellAction] writes only the vendor values that moved. */
    @Emulates("store.setCamera")
    fun setCamera(next: ro.rdstr.car.CameraSettings) = shellAction(ShellAction.SetCamera(next))

    /** Visible to Phones: the screen-sharing receiver runs while the car is on and this is on. */
    @Emulates("store.setCastVisible")
    fun setCastVisible(on: Boolean) = shellAction(ShellAction.SetCastVisible(on))

    /** Star or unstar a station in our radio app; the stars are kept across starts (core-car Tuner.toggleFavorite). */
    @Emulates("store.toggleFavorite")
    fun toggleFavorite(khz: Int) {
        patch { it.copy(favorites = ro.rdstr.car.Tuner.toggleFavorite(it.favorites, khz)) }
        prefs.edit().putString(FAVORITES_KEY, _ui.value.favorites.joinToString(",")).apply()
    }

    /** A number as the screen shows it: grouped for its country, numbers without + by the Number Country setting. */
    @Emulates("store.formatNumber")
    fun formatNumber(number: String): String = ro.rdstr.car.PhoneFormat.format(number, _shell.value.phoneRegion)

    /** The phone app opens on a thread, from the banner; null back to the list. */
    @Emulates("store.openThread")
    fun openThread(number: String?) {
        patch { it.copy(thread = number, newMessage = null) }
        if (number != null) shellAction(ShellAction.OpenApp(ro.rdstr.car.App.PHONE))
    }

    @Emulates("store.markRead")
    fun markRead(ids: List<String>) = patch { it.copy(read = it.read + ids) }

    /** The message banner goes. */
    @Emulates("store.dismissMessage")
    fun dismissMessage() = patch { if (it.newMessage == null) it else it.copy(newMessage = null) }

    /** The question before a restart, from About or a long press on Settings in the drawer (which closes). */
    @Emulates("store.askRestart")
    fun askRestart(on: Boolean) {
        if (on) shellAction(ShellAction.CloseDrawer)
        patch { it.copy(restartAsked = on) }
    }

    /** Bluetooth on, for the phone app: [UiState.btTurningOn] until the adapter is on, then the last phone is asked for. */
    @Emulates("store.turnOnBluetooth")
    fun turnOnBluetooth() {
        if (_ui.value.btTurningOn) return
        patch { it.copy(btTurningOn = true) }
        scope.launch {
            if (radios.bluetooth(true)) kotlinx.coroutines.withTimeoutOrNull(BT_ON_WAIT_MS) { while (!radios.bluetoothOn) delay(BT_ON_POLL_MS) }
            patch { it.copy(btTurningOn = false) }
            if (radios.bluetoothOn) connectLastPhone()
        }
    }

    private var packDownload: Job? = null

    /**
     * A country's pack from the `maps` release, checked against its digest (SetupSteps.downloadPack), one at a time.
     * It downloads only over Wi-Fi: it waits while the unit is off Wi-Fi and goes on from where it stopped when it
     * joins again.
     */
    @Emulates("store.downloadPack")
    fun downloadPack(code: String) {
        packDownload?.cancel()
        patch { it.copy(maps = it.maps.copy(downloading = PackProgress(code, 0f))) }
        packDownload = scope.launch(Dispatchers.IO) {
            val steps = ro.rdstr.os.setup.SetupSteps(app)
            val job = coroutineContext[Job]!!
            while (true) {
                if (!ro.rdstr.os.maps.PackOffer.onWifi(app)) { delay(WIFI_WAIT_MS); continue }
                val done = steps.downloadPack(code) { d ->
                    // another pack asked for: this one stops, its partial file kept for later
                    if (!job.isActive) throw kotlinx.coroutines.CancellationException()
                    patch { it.copy(maps = it.maps.copy(downloading = PackProgress(code, d.fraction))) }
                }
                if (done) break
                delay(PACK_RETRY_MS)
            }
            Log.i(MAPS_TAG, "pack $code downloaded")
            kotlinx.coroutines.withContext(Dispatchers.Main) { ro.rdstr.os.maps.MapHost.packsChanged() }
            patch { it.copy(maps = MapsState(installed = (it.maps.installed + code).distinct(), downloading = null)) }
        }
    }

    /** Removes a pack from the unit, as from the maps settings; Romania can go too, and the country is offered again. */
    @Emulates("store.removePack")
    fun removePack(code: String) {
        ro.rdstr.os.maps.MapHost.packs(app).remove(code)
        ro.rdstr.os.maps.MapHost.packsChanged()
        ro.rdstr.os.maps.PackOffer.forget(app, code)
        patch { it.copy(maps = it.maps.copy(installed = it.maps.installed - code)) }
    }

    private var lastActivity = android.os.SystemClock.elapsedRealtime()

    /** A touch on any of our screens, or a key: the always-on display ends and its wait starts again. */
    @Emulates("store.userActivity")
    fun userActivity() {
        lastActivity = android.os.SystemClock.elapsedRealtime()
        if (_shell.value.aod) shellAction(ShellAction.ExitAod)
    }

    /**
     * Checks the wait of the always-on display, every [IDLE_CHECK_MS] (the emulator: every second); a call, the reverse
     * camera or the map keep the screen as it is. Started once, with the runtime.
     */
    @Emulates("store.watchIdle")
    private fun watchIdle() = scope.launch {
        while (true) {
            delay(IDLE_CHECK_MS)
            val ui = _ui.value
            val busy = ui.camera || _shell.value.cameraOpen || ui.state.call != CarEvent.Call.State.IDLE || _shell.value.app == ro.rdstr.car.App.MAPS
            if (Shell.aodDue(_shell.value, android.os.SystemClock.elapsedRealtime() - lastActivity, busy)) shellAction(ShellAction.EnterAod)
        }
    }

    /** A full start of the unit: the source it was switched off with (core-car Startup), then the last phone. */
    private fun resumeAtStart() {
        val kept = Startup.decodeKept(prefs.getString(KEPT_KEY, null))
        if (kept != null) {
            val (state, commands) = Startup.resume(kept)
            _ui.value = _ui.value.copy(state = state)
            commands.forEach(::send)
        }
        connectLastPhone()
    }

    private var connecting: Job? = null

    private lateinit var cast: ro.rdstr.os.cast.CastReceiver
    private var accOn = true

    /** Phones find the unit while the car is on and Visible to Phones is on. */
    private fun castVisibility() {
        if (!::cast.isInitialized) return
        if (accOn && _shell.value.castVisible) cast.start() else cast.stop()
    }

    /**
     * The phone screen view's surface while it is shown, null when it goes: the receiver decodes the phone's picture
     * straight into it. Replaces the emulator's castPicture, which hands the view a stream of a laptop window instead.
     */
    @Emulates("store.castPicture")
    fun castSurface(surface: android.view.Surface?) {
        if (::cast.isInitialized) cast.surface = surface
    }

    private var reconnecting = false

    /**
     * A few seconds after a start, or after Bluetooth comes on, with no phone connected: one try for the last phone,
     * with Connect to Last Phone on (Startup.phoneToReconnect).
     */
    @android.annotation.SuppressLint("MissingPermission") // BLUETOOTH is granted at install on API 27
    private fun connectLastPhone() {
        connecting?.cancel()
        connecting = scope.launch {
            delay(Startup.CONNECT_WAIT_MS.toLong())
            val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.takeIf { it.isEnabled } ?: return@launch
            val saved = adapter.bondedDevices.mapNotNull { it.name }
            val phone = Startup.phoneToReconnect(_shell.value.btAutoConnect, saved, prefs.getString(LAST_PHONE_KEY, null)) ?: return@launch
            // one try: a phone out of reach just fails, and the list stays free for any phone picked by hand
            if (!_ui.value.state.btConnected) { reconnecting = true; send(CarCommand.Bluetooth.Connect(phone)); reconnecting = false }
        }
    }

    /** Each start of the unit and of the car: the call level back to its default (core-car Sound.atStart). */
    private fun startCallVolume() {
        val (dsp, writes) = ro.rdstr.car.Sound.atStart(_shell.value.dsp)
        _shell.value = _shell.value.copy(dsp = dsp)
        writes.forEach { send(CarCommand.Dsp(it)) }
    }

    /** The troubleshooting journal in files/journal, one file a day (core-car JournalFiles); adb pull it to read. */
    private lateinit var journal: ro.rdstr.os.settings.JournalRecorder

    /** The other apps that can go on home, and starting one of them. */
    fun installedApps() = ro.rdstr.os.settings.InstalledApps.read(app).also { apps ->
        shellAction(ShellAction.AppsInstalled(apps.map { it.id }))
    }

    @Emulates("store.launchApp")
    fun launch(pkg: String) = ro.rdstr.os.settings.InstalledApps.launch(app, pkg)

    /** The Developer section: adb's address on Wi-Fi, Android's developer options. */
    @Emulates("store.UNIT_IP")
    fun adbAddress() = ro.rdstr.os.settings.DeveloperTools.adbAddress(app)

    /** Settings > About > Restart the Unit, after the question: the whole unit reboots (DeveloperTools.reboot). */
    @Emulates("store.restartUnit")
    fun restartUnit() = ro.rdstr.os.settings.DeveloperTools.reboot(app)

    /**
     * Factory reset: every preference file cleared, our files, paired phones and saved networks erased, then a restart
     * into setup (Shell.factoryState).
     */
    @Emulates("store.factoryReset")
    fun factoryReset() {
        java.io.File(app.applicationInfo.dataDir, "shared_prefs").listFiles()?.forEach { file ->
            app.getSharedPreferences(file.nameWithoutExtension, Context.MODE_PRIVATE).edit().clear().commit()
        }
        ro.rdstr.os.settings.DeveloperTools.erase(app)
        prefs.edit().putString(SHELL_KEY, Shell.encodePreferences(Shell.factoryState())).commit()
        ro.rdstr.os.settings.DeveloperTools.restart(app)
    }

    @Emulates("store.openAndroidSettings")
    fun openAndroidSettings() = ro.rdstr.os.settings.DeveloperTools.openAndroidSettings(app)

    /** The About page, read when it opens. */
    fun systemInfo(): SystemInfo = SystemInfo.read(app)

    private const val SHELL_KEY = "shell"
    private const val KEPT_KEY = "kept"
    private const val LAST_PHONE_KEY = "lastPhone"

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
    @Emulates("store.openPhone")
    fun openPhone(): PhoneGate.Screen {
        val on = radios.bluetoothOn
        if (!on) turnOnBluetooth()
        return PhoneGate.screen(on, _ui.value.state.hfpConnected).also { Log.i(PHONE_TAG, "phone app $it") }
    }

    /**
     * The drawer's Bluetooth and Wi-Fi switches: on opens the radio's list (Shell RadioSwitched). Bluetooth then asks for
     * the last phone with Connect to Last Phone on; Wi-Fi joins a saved network in range by itself (Android's own).
     */
    @Emulates("store.setBluetooth")
    fun switchBluetooth(on: Boolean) {
        if (radios.bluetooth(on) && on) connectLastPhone()
        shellAction(ro.rdstr.car.ShellAction.RadioSwitched(ro.rdstr.car.Radio.BLUETOOTH, on))
    }

    /**
     * The drawer's Wi-Fi switch: RadioSwitched(WIFI) is sent only once Wi-Fi really switched. With location off the
     * location prompt shows over the open drawer; its yes opens the networks, its no opens nothing.
     */
    @Emulates("store.switchWifi")
    fun switchWifi(on: Boolean) {
        setWifi(on)
        if (_ui.value.wifiNeedsLocation) networksAfterLocation = true
        else shellAction(ro.rdstr.car.ShellAction.RadioSwitched(ro.rdstr.car.Radio.WIFI, on))
    }

    /** The location prompt was raised by the drawer's switch, so its yes opens the networks. */
    private var networksAfterLocation = false

    /** Wi-Fi on or off; on this Android a Wi-Fi scan needs location, so with it off the screen asks for it first. */
    @Emulates("store.setWifi")
    fun setWifi(on: Boolean) {
        if (on && !radios.locationOn) return patch { it.copy(wifiNeedsLocation = true) }
        radios.wifi(on)
    }

    /** The answer to "Wi-Fi needs location": yes turns location on, then Wi-Fi; no leaves both as they were. */
    @Emulates("store.answerWifiNeedsLocation")
    fun answerWifiNeedsLocation(turnOn: Boolean) {
        val fromDrawer = networksAfterLocation
        networksAfterLocation = false
        patch { it.copy(wifiNeedsLocation = false) }
        if (!turnOn) return
        radios.location(true)
        setWifi(true)
        if (fromDrawer) shellAction(ro.rdstr.car.ShellAction.RadioSwitched(ro.rdstr.car.Radio.WIFI, true))
    }

    private const val PHONE_TAG = "rdstr.phone"
    private const val FAVORITES_KEY = "favorites"
    private const val MAPS_TAG = "rdstr.map"
    /** Off Wi-Fi, a pack download looks for Wi-Fi again this often; after a broken one, the next try comes later. */
    private const val WIFI_WAIT_MS = 5_000L
    private const val PACK_RETRY_MS = 30_000L
    /** Switching Bluetooth on takes the unit about a second; past this it is given up. */
    private const val BT_ON_WAIT_MS = 5_000L
    private const val BT_ON_POLL_MS = 100L
    private const val IDLE_CHECK_MS = 5_000L
    /** The phone book sync writes in bursts; read once it has been quiet this long. */
    private const val PHONE_BOOK_SETTLE_MS = 1500L

    fun ensureService(context: Context) {
        context.startForegroundService(Intent(context, CarService::class.java))
    }
}

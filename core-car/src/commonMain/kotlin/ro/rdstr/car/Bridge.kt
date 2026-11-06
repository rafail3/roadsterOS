package ro.rdstr.car

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport

@Serializable
private data class HfpCallJson(val id: Int, val state: Int, val number: String, val multiparty: Boolean = false)

/** One rules step: the state after an event and the actions it produced. */
@Serializable
data class Step(val state: RulesState, val actions: List<Action>)

/** JSON facade for hosts that cannot use Kotlin types directly (the browser emulator). */
@OptIn(ExperimentalJsExport::class)
@JsExport
object Bridge {
    // states carry every field so hosts never guess a default; events keep the journal's compact form
    private val json = Json {
        classDiscriminator = "type"
        encodeDefaults = true
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

    fun maxVolume(): Int = Rules.MAX_VOLUME

    fun ptyName(code: Int): String = Rds.ptyName(code)

    private var borders: List<MapPacks.Country> = emptyList()

    /** Loads the country borders once; [countryAt] then answers from them. */
    fun loadBorders(geojson: String) { borders = MapPacks.parseBorders(geojson) }

    /** The ISO code of the country at a position, or an empty string outside every border. */
    fun countryAt(lon: Double, lat: Double): String = MapPacks.countryAt(borders, lon, lat)?.code ?: ""

    /** [installed], [declined]: JSON arrays of country codes; [downloading]: a code, or empty. */
    fun mapOffer(country: String, installed: String, wifi: Boolean, declined: String, downloading: String): String = MapPacks.offer(
        country.ifEmpty { null },
        json.decodeFromString(ListSerializer(String.serializer()), installed).toSet(),
        wifi,
        json.decodeFromString(ListSerializer(String.serializer()), declined).toSet(),
        downloading.ifEmpty { null },
    ).name

    private val speedometer = Speedometer()

    /** A GPS fix for the map's speed; [chipKmh] below 0 when the fix has no speed. Answers {"kmh":n,"stopped":b}. */
    fun speed(lon: Double, lat: Double, timeMs: Double, chipKmh: Double): String =
        speedometer.update(lon, lat, timeMs.toLong(), chipKmh.takeIf { it >= 0 }).let { """{"kmh":${it.kmh},"stopped":${it.stopped}}""" }

    private val speedWarning = SpeedWarning()

    /**
     * The limit sign and the over-limit warning for the last fix given to [speed]; [roadLimit] below 0 when the car's
     * road has none. Answers {"limit":n or null,"over":b,"chime":b,"lit":n,"tone":"WHITE"|"ORANGE"|"RED"}, the last two for
     * the dial's ring.
     */
    fun speedWarning(roadLimit: Int, timeMs: Double): String {
        val reading = speedometer.reading
        val w = speedWarning.update(reading, roadLimit.takeIf { it >= 0 }, timeMs.toLong())
        val ring = SpeedWarning.ring(reading.kmh, w)
        return """{"limit":${w.limit},"over":${w.over},"chime":${w.chime},"lit":${ring.lit},"tone":"${ring.tone}"}"""
    }

    /** A pack's size in MB, or 0 when it is not known. */
    fun packMb(code: String): Int = MapPacks.PACK_MB[code] ?: 0

    /** A country's name from the borders, or its code. */
    fun countryName(code: String): String = borders.firstOrNull { it.code == code }?.name ?: code

    fun setVolume(state: String, level: Int): String = json.encodeToString(
        ListSerializer(CarCommand.serializer()),
        Rules.setVolume(json.decodeFromString(RulesState.serializer(), state), level),
    )

    /** [pixels]: a JSON array of ARGB ints, row by row; answers {"top":"#rrggbb","bottom":"#rrggbb"}. */
    fun coverColors(pixels: String, width: Int): String {
        val (top, bottom) = CoverColors.from(json.decodeFromString(ListSerializer(Int.serializer()), pixels).toIntArray(), width)
        return """{"top":"${CoverColors.hex(top)}","bottom":"${CoverColors.hex(bottom)}"}"""
    }

    /**
     * The card on home for the car's [state], whether Bluetooth is [btOn], and the page chosen last ([chosen], empty for
     * none): answers {"pages":[...],"shown":"...","strip":"NONE"|"BT"|"RADIO","forSource":"..." or ""}.
     */
    fun homeCard(state: String, btOn: Boolean, chosen: String): String {
        val car = json.decodeFromString(RulesState.serializer(), state)
        val pages = HomeCard.pages(car, btOn).joinToString(",") { "\"$it\"" }
        val shown = HomeCard.shown(car, btOn, chosen.takeIf { it.isNotEmpty() }?.let { CardPage.valueOf(it) })
        return """{"pages":[$pages],"shown":"$shown","strip":"${HomeCard.strip(car)}","forSource":"${HomeCard.forSource(car, btOn) ?: ""}"}"""
    }

    /** What a transport button on a card page sends: a JSON array of commands. [press] is PREV, PLAY or NEXT. */
    fun homeCardPress(state: String, page: String, press: String): String = json.encodeToString(
        ListSerializer(CarCommand.serializer()),
        HomeCard.press(CardPage.valueOf(page), HomeCard.Press.valueOf(press), json.decodeFromString(RulesState.serializer(), state)),
    )

    /** A frequency as the screen writes it; [band] is FM or AM. */
    fun freqFormat(band: String, khz: Int): String = FreqDisplay.format(CarEvent.Radio.Band.valueOf(band), khz)

    /** One frame of the frequency's glide to the tuned one. */
    fun freqGlide(shown: Double, target: Int): Double = FreqDisplay.glide(shown, target)

    /** The rolling counter: {"exact":"95.35" or null,"label":"...","decimalAt":n,"columns":[[place,offset],...]}. */
    fun freqCounter(band: String, khz: Double, target: Int): String {
        val c = FreqDisplay.counter(CarEvent.Radio.Band.valueOf(band), khz, target)
        val columns = c.columns.joinToString(",") { "[${it.place},${it.offset}]" }
        return """{"exact":${c.exact?.let { "\"$it\"" } ?: "null"},"label":"${c.label}","decimalAt":${c.decimalAt},"columns":[$columns]}"""
    }

    /** The phone app's screen: BT_OFF, CONNECT or READY. */
    fun phoneScreen(btOn: Boolean, hfpConnected: Boolean): String = PhoneGate.screen(btOn, hfpConnected).name

    fun callCapable(majorClass: Int): Boolean = PhoneGate.callCapable(majorClass)

    private val contacts = ListSerializer(Contact.serializer())

    /** [book]: a JSON array of contacts; answers [{"letter":"A","contacts":[...]},...]. */
    fun phoneSections(book: String): String =
        json.encodeToString(ListSerializer(ContactSection.serializer()), PhoneBook.sections(json.decodeFromString(contacts, book)))

    fun phoneSearch(book: String, query: String): String =
        json.encodeToString(contacts, PhoneBook.search(json.decodeFromString(contacts, book), query))

    /** [calls]: a JSON array of call records; answers the recents rows. */
    fun phoneRecents(calls: String, book: String): String = json.encodeToString(
        ListSerializer(RecentRow.serializer()),
        PhoneBook.recents(json.decodeFromString(ListSerializer(CallRecord.serializer()), calls), json.decodeFromString(contacts, book)),
    )

    /** The contact under typed digits, or for a caller's number: {"name","number","label"} or null. */
    fun phoneSuggest(book: String, typed: String): String = matchJson(PhoneBook.suggest(json.decodeFromString(contacts, book), typed))

    fun phoneFind(book: String, number: String): String = matchJson(PhoneBook.find(json.decodeFromString(contacts, book), number))

    private fun matchJson(m: PhoneBook.Match?): String = if (m == null) "null" else json.encodeToString(PhoneBook.Match.serializer(), m)

    fun initials(name: String): String = PhoneBook.initials(name)

    /** [calls]: a JSON array of {"id","state","number","multiparty"} as the phone reports them; answers the Call event. */
    fun hfpCall(calls: String): String {
        val list = json.decodeFromString(ListSerializer(HfpCallJson.serializer()), calls).map { Hfp.HfpCall(it.id, it.state, it.number, it.multiparty) }
        return Journal.encodeEvent(Hfp.call(list))
    }

    /** [messages]: Message events; [read]: ids already read; answers the conversations. */
    fun conversations(messages: String, read: String, book: String): String = json.encodeToString(
        ListSerializer(Conversation.serializer()),
        Messages.conversations(
            json.decodeFromString(ListSerializer(CarEvent.Message.serializer()), messages),
            json.decodeFromString(ListSerializer(String.serializer()), read).toSet(),
            json.decodeFromString(contacts, book),
        ),
    )

    fun quickReplies(): String = json.encodeToString(ListSerializer(String.serializer()), Messages.QUICK_REPLIES)

    fun callDuration(ms: Double): String = PhoneBook.duration(ms.toLong())

    fun clockText(hour: Int, minute: Int, h24: Boolean): String = Settings.clockText(hour, minute, h24)

    fun meridiem(hour: Int): String = Settings.meridiem(hour)

    /** The commands a car start sends to cap a loud last level at the shell's start volume. */
    fun startVolume(state: String, cap: Int): String = json.encodeToString(
        ListSerializer(CarCommand.serializer()),
        Settings.startVolume(json.decodeFromString(RulesState.serializer(), state), cap),
    )

    fun size(bytes: Double): String = Settings.size(bytes.toLong())

    /** [installed]: a JSON array of {"id","label"}; answers the icons home shows. */
    fun homeApps(chosen: String, installed: String): String {
        val apps = ListSerializer(LaunchableApp.serializer())
        return json.encodeToString(apps, HomeApps.shown(json.decodeFromString(ListSerializer(String.serializer()), chosen), json.decodeFromString(apps, installed)))
    }

    fun homeAppsMax(): Int = HomeApps.MAX

    /** Arranging the icons on home (core-car HomeArrange). */
    fun arrangeHoldMs(): Double = HomeArrange.HOLD_MS.toDouble()
    fun arrangeCancelsHold(dx: Double): Boolean = HomeArrange.cancelsHold(dx)
    fun arrangeDropIndex(from: Int, dx: Double, slot: Double, count: Int): Int = HomeArrange.dropIndex(from, dx, slot, count)
    fun arrangeOffset(i: Int, from: Int, dx: Double, slot: Double, count: Int): Double = HomeArrange.offset(i, from, dx, slot, count)
    fun arrangeSettle(from: Int, dx: Double, slot: Double, count: Int): Double = HomeArrange.settle(from, dx, slot, count)
    fun arrangeKeeps(from: Int, to: Int): Boolean = HomeArrange.keepsArranging(from, to)
    fun arrangeOpens(arranging: Boolean, pickedUp: Boolean): Boolean = HomeArrange.opens(arranging, pickedUp)
    fun arrangeTimes(): String = "[${HomeArrange.REMOVE_MS},${HomeArrange.GLIDE_MS},${HomeArrange.GROW_MS}]"
    /** [before] and [after]: JSON objects of each icon's left edge by id; answers the glides as JSON. */
    fun arrangeGlides(before: String, after: String): String {
        val edges = kotlinx.serialization.builtins.MapSerializer(String.serializer(), Double.serializer())
        return json.encodeToString(ListSerializer(HomeArrange.Glide.serializer()), HomeArrange.glides(json.decodeFromString(edges, before), json.decodeFromString(edges, after)))
    }

    private val strings = ListSerializer(String.serializer())
    fun timeZones(ids: String): String = json.encodeToString(strings, TimeZones.places(json.decodeFromString(strings, ids)))
    fun timeZoneSearch(ids: String, query: String): String = json.encodeToString(strings, TimeZones.search(json.decodeFromString(strings, ids), query))
    fun timeZoneCity(id: String): String = TimeZones.city(id)
    fun timeZoneRegion(id: String): String = TimeZones.region(id)
    fun timeZoneOffset(minutes: Int): String = TimeZones.offset(minutes)

    private val dashKeys = ListSerializer(DashClock.Key.serializer())
    /** The dash display's keys for a jump of the unit's local time (core-car DashClock). */
    fun dashFollow(shownBefore: Int, jump: Int): String = json.encodeToString(dashKeys, DashClock.follow(shownBefore, jump))

    fun ownApps(): String = json.encodeToString(ListSerializer(LaunchableApp.serializer()), HomeApps.OWN)

    fun shellInitial(): String = json.encodeToString(ShellState.serializer(), ShellState())

    /** The shell's preferences as stored across starts, and back (Shell.encodePreferences / decodePreferences). */
    fun encodePreferences(state: String): String = Shell.encodePreferences(json.decodeFromString(ShellState.serializer(), state))

    fun decodePreferences(text: String): String = json.encodeToString(ShellState.serializer(), Shell.decodePreferences(text))

    fun factoryPreferences(): String = Shell.encodePreferences(Shell.factoryState())

    private fun sound(text: String) = json.decodeFromString(SoundSettings.serializer(), text)
    private fun sound(s: SoundSettings) = json.encodeToString(SoundSettings.serializer(), s)

    /** The sound window (core-car Sound): the calls a change makes, presets, bands, resets and the ranges. */
    fun soundWrites(a: String, b: String): String = json.encodeToString(ListSerializer(DspWrite.serializer()), Sound.writes(sound(a), sound(b)))
    fun startupPhones(saved: String, last: String?): String =
        json.encodeToString(ListSerializer(String.serializer()), Startup.phonesToTry(json.decodeFromString(ListSerializer(String.serializer()), saved), last))
    fun startupConnectWaitMs(): Int = Startup.CONNECT_WAIT_MS
    fun startupConnectTryMs(): Int = Startup.CONNECT_TRY_MS
    fun castName(): String = CarEvent.PhoneCast.NAME
    fun cameraGuides(guidelines: String, eps: Int): String = json.encodeToString(Guides.serializer(), Camera.guides(Guidelines.valueOf(guidelines), eps))
    fun cameraWrites(a: String, b: String): String = json.encodeToString(
        ListSerializer(DspWrite.serializer()),
        Camera.writes(json.decodeFromString(CameraSettings.serializer(), a), json.decodeFromString(CameraSettings.serializer(), b)),
    )
    fun soundPreset(s: String, preset: String): String = sound(Sound.withPreset(sound(s), EqPreset.valueOf(preset)))
    fun soundBand(s: String, band: Int, db: Int): String = sound(Sound.withBand(sound(s), band, db))
    fun soundResetLoudness(s: String): String = sound(Sound.resetLoudness(sound(s)))
    fun soundResetBass(s: String): String = sound(Sound.resetBass(sound(s)))
    fun soundBands(): String = json.encodeToString(strings, Sound.BANDS)
    fun soundHpf(): String = json.encodeToString(ListSerializer(Int.serializer()), Sound.HPF_HZ)
    fun soundLpf(): String = json.encodeToString(ListSerializer(Int.serializer()), Sound.LPF_HZ)

    fun shell(state: String, action: String): String {
        val next = Shell.reduce(
            json.decodeFromString(ShellState.serializer(), state),
            json.decodeFromString(ShellAction.serializer(), action),
        )
        return json.encodeToString(ShellState.serializer(), next)
    }

    /** The name a cover of this track is kept under, as on the unit. */
    fun coverFileName(title: String, artist: String): String = CoverIndex.fileName(title, artist)

    /** [names]: a JSON array of kept file names; answers the one of this track, or an empty string. */
    fun coverFind(names: String, title: String, artist: String): String =
        CoverIndex.find(json.decodeFromString(ListSerializer(String.serializer()), names), title, artist).orEmpty()

    fun stockCover(title: String, artist: String, count: Int): Int = CoverIndex.stockCover(title, artist, count)

    /** The pixel size below which a cover is worth a catalog upgrade, per side. */
    fun sharpPx(): Int = CoverCatalog.SHARP_PX

    fun effectiveTheme(state: String, hour: Int): String =
        Shell.effectiveTheme(json.decodeFromString(ShellState.serializer(), state), hour).name

    fun minBrightness(): Int = Shell.MIN_BRIGHTNESS

    fun aodDue(state: String, idleMs: Double, busy: Boolean): Boolean =
        Shell.aodDue(json.decodeFromString(ShellState.serializer(), state), idleMs.toLong(), busy)

    fun aodFace(state: String): String = Shell.aodFace(json.decodeFromString(ShellState.serializer(), state)).name

    fun wakesAod(event: String): Boolean = Shell.wakesAod(json.decodeFromString(CarEvent.serializer(), event))

    fun aodMinutes(): String = json.encodeToString(ListSerializer(Int.serializer()), Shell.AOD_MINUTES)

    fun maxBrightness(): Int = Shell.MAX_BRIGHTNESS

    /** Answers {"dark":0..1,"brightness":factor,"saturation":factor}. */
    fun veil(state: String): String =
        json.encodeToString(Veil.serializer(), Shell.veil(json.decodeFromString(ShellState.serializer(), state)))
}

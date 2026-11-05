package ro.rdstr.car

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
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

    fun shellInitial(): String = json.encodeToString(ShellState.serializer(), ShellState())

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

    fun maxBrightness(): Int = Shell.MAX_BRIGHTNESS

    /** Answers {"dark":0..1,"brightness":factor,"saturation":factor}. */
    fun veil(state: String): String =
        json.encodeToString(Veil.serializer(), Shell.veil(json.decodeFromString(ShellState.serializer(), state)))
}

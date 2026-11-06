package ro.rdstr.car

/**
 * What the unit picks up when the car starts: the last source, and the last phone when none connects by itself.
 * Radio comes back at once (paused if it was); Bluetooth waits for the phone, then plays again if it was playing.
 */
object Startup {
    /** The phone and the vendor's Bluetooth get this long to connect on their own before we ask. */
    const val CONNECT_WAIT_MS = 6_000
    /** Each saved phone gets this long to answer before the next one is tried. */
    const val CONNECT_TRY_MS = 10_000

    /** What is kept across a full restart of the unit. */
    fun kept(s: RulesState) = RulesState(
        source = s.source, lastSource = s.lastSource, playing = s.playing, radioOn = s.radioOn, playOnConnect = s.playOnConnect,
    )

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    fun encodeKept(s: RulesState): String = json.encodeToString(RulesState.serializer(), kept(s))

    /** A missing or damaged text gives null: the unit starts with no source, never a crash. */
    fun decodeKept(text: String?): RulesState? = text?.let { runCatching { json.decodeFromString(RulesState.serializer(), it) }.getOrNull() }

    /** The rules state and the commands at a start of the unit, from what [kept] wrote before it went off. */
    fun resume(kept: RulesState): Pair<RulesState, List<CarCommand>> = when (kept.source) {
        AudioSource.RADIO -> {
            val commands = listOf(CarCommand.Audio.Source(AudioSource.RADIO)) + if (kept.radioOn) emptyList() else listOf(CarCommand.Radio.Pause)
            commands.fold(RulesState(), Rules::onCommand) to commands
        }
        AudioSource.BT -> RulesState(lastSource = AudioSource.BT, playOnConnect = kept.playing) to emptyList()
        AudioSource.NONE -> RulesState(lastSource = kept.lastSource, playOnConnect = kept.playOnConnect) to emptyList()
    }

    /** The saved phones to ask in turn, the last one connected first. */
    fun phonesToTry(saved: List<String>, last: String?): List<String> = (listOfNotNull(last?.takeIf { it in saved }) + saved).distinct()
}

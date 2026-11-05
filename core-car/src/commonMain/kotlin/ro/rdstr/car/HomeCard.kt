package ro.rdstr.car

/** The pages of the card on home, swiped through one at a time. */
enum class CardPage { BLUETOOTH, NO_PHONE, BT_OFF, RADIO, NAVIGATION }

/**
 * The card on home, the same three pages in every state: Bluetooth, the radio, the map.
 *
 * - Bluetooth: the phone's music while one is connected; with Bluetooth on and no phone, the saved phones to connect;
 *   with Bluetooth off, a way to switch it on.
 * - Radio: always, on the last station; when the radio is not the source its play button starts it. Its pause stops
 *   receiving the station, it does not mute the volume.
 * - The map: always, with a strip of the sound that plays under it.
 *
 * Any button on the page of a source that is not the source makes it the source first, and brings the sound back
 * when it was muted. The card shows the page chosen last (a swipe, leaving the map, a new source); the Bluetooth
 * page keeps its place whatever it shows.
 */
object HomeCard {
    enum class Press { PREV, PLAY, NEXT }

    fun bluetoothPage(car: RulesState, btOn: Boolean) = when {
        car.btConnected -> CardPage.BLUETOOTH
        btOn -> CardPage.NO_PHONE
        else -> CardPage.BT_OFF
    }

    fun pages(car: RulesState, btOn: Boolean): List<CardPage> = listOf(bluetoothPage(car, btOn), CardPage.RADIO, CardPage.NAVIGATION)

    fun shown(car: RulesState, btOn: Boolean, chosen: CardPage?): CardPage = when (chosen) {
        null, CardPage.BLUETOOTH, CardPage.NO_PHONE, CardPage.BT_OFF -> bluetoothPage(car, btOn)
        else -> chosen
    }

    /** The page a new source brings forward; none when the sound stops altogether. */
    fun forSource(car: RulesState, btOn: Boolean): CardPage? = when (car.source) {
        AudioSource.BT -> bluetoothPage(car, btOn)
        AudioSource.RADIO -> CardPage.RADIO
        AudioSource.NONE -> null
    }

    /** The sound shown in a strip under the map: the source while it plays, else none. */
    fun strip(car: RulesState): AudioSource = when {
        car.source == AudioSource.BT && car.btConnected && car.playing -> AudioSource.BT
        car.source == AudioSource.RADIO && car.radioOn -> AudioSource.RADIO
        else -> AudioSource.NONE
    }

    /** What a transport button on a sound page (or the map's strip of that sound) sends. */
    fun press(page: CardPage, press: Press, car: RulesState): List<CarCommand> {
        val source = if (page == CardPage.RADIO) AudioSource.RADIO else AudioSource.BT
        val own = car.source == source
        val start = if (own) emptyList() else listOf(CarCommand.Audio.Source(source))
        val unmute = if (car.muted && !own) listOf(CarCommand.Audio.Mute(false)) else emptyList()
        val action: List<CarCommand> = when (source) {
            AudioSource.RADIO -> when (press) {
                Press.PREV -> listOf(CarCommand.Radio.Seek(false))
                Press.NEXT -> listOf(CarCommand.Radio.Seek(true))
                // choosing the radio starts it already
                Press.PLAY -> if (!own) emptyList() else listOf(if (car.radioOn) CarCommand.Radio.Pause else CarCommand.Radio.Play)
            }
            else -> when (press) {
                Press.PREV -> listOf(CarCommand.Media.Prev)
                Press.NEXT -> listOf(CarCommand.Media.Next)
                Press.PLAY -> listOf(if (own && car.playing) CarCommand.Media.Pause else CarCommand.Media.Play)
            }
        }
        return start + unmute + action
    }
}

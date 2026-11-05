package ro.rdstr.car

/** The pages of the card on home, swiped through one at a time. */
enum class CardPage { BLUETOOTH, NO_PHONE, RADIO, NO_SOURCE, NAVIGATION }

/**
 * What the card on home holds: a page for each sound that is on (the connected phone, the radio while it is the
 * source), "no source" when none is, and the map after them, always. It shows the page chosen last (a swipe, leaving
 * the map, a new source) while that page is there, else the first.
 */
object HomeCard {
    fun pages(car: RulesState): List<CardPage> = buildList {
        when {
            car.btConnected -> add(CardPage.BLUETOOTH)
            car.source == AudioSource.BT -> add(CardPage.NO_PHONE)
        }
        if (car.source == AudioSource.RADIO) add(CardPage.RADIO)
        if (isEmpty()) add(CardPage.NO_SOURCE)
        add(CardPage.NAVIGATION)
    }

    fun shown(car: RulesState, chosen: CardPage?): CardPage = pages(car).let { if (chosen in it) chosen!! else it.first() }

    /** The page a source brings forward when it becomes the source. */
    fun forSource(car: RulesState): CardPage = pages(car).first { it != CardPage.NAVIGATION }.let {
        if (car.source == AudioSource.RADIO) CardPage.RADIO else it
    }

    /** The sound shown in a strip under the map on its page: only one that plays now, else none. */
    fun strip(car: RulesState): AudioSource = when {
        car.source == AudioSource.BT && car.btConnected && car.playing -> AudioSource.BT
        car.source == AudioSource.RADIO && !car.muted -> AudioSource.RADIO
        else -> AudioSource.NONE
    }
}

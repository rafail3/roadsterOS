package ro.rdstr.car

import ro.rdstr.car.CardPage.BLUETOOTH
import ro.rdstr.car.CardPage.NAVIGATION
import ro.rdstr.car.CardPage.NO_PHONE
import ro.rdstr.car.CardPage.NO_SOURCE
import ro.rdstr.car.CardPage.RADIO
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeCardTest {
    private val bt = RulesState(source = AudioSource.BT, btConnected = true, playing = true)
    private val radio = RulesState(source = AudioSource.RADIO)

    @Test
    fun `the navigation page is always there, after the sound`() {
        assertEquals(listOf(BLUETOOTH, NAVIGATION), HomeCard.pages(bt))
        assertEquals(listOf(RADIO, NAVIGATION), HomeCard.pages(radio))
        assertEquals(listOf(NO_SOURCE, NAVIGATION), HomeCard.pages(RulesState()))
    }

    @Test
    fun `a connected phone keeps its page while the radio plays`() {
        assertEquals(listOf(BLUETOOTH, RADIO, NAVIGATION), HomeCard.pages(radio.copy(btConnected = true)))
    }

    @Test
    fun `bluetooth as the source without a phone asks for one`() {
        assertEquals(listOf(NO_PHONE, NAVIGATION), HomeCard.pages(RulesState(source = AudioSource.BT)))
    }

    @Test
    fun `the card shows the page chosen last, or the sound's while it has none`() {
        assertEquals(NAVIGATION, HomeCard.shown(bt, NAVIGATION))
        assertEquals(BLUETOOTH, HomeCard.shown(bt, null))
        // a page that went away: the radio's after switching to the phone
        assertEquals(BLUETOOTH, HomeCard.shown(bt, RADIO))
    }

    @Test
    fun `the page of a source`() {
        assertEquals(BLUETOOTH, HomeCard.forSource(bt))
        assertEquals(RADIO, HomeCard.forSource(radio))
        assertEquals(NO_SOURCE, HomeCard.forSource(RulesState()))
    }

    @Test
    fun `the map's sound strip shows only what plays`() {
        assertEquals(AudioSource.BT, HomeCard.strip(bt))
        assertEquals(AudioSource.NONE, HomeCard.strip(bt.copy(playing = false)))
        assertEquals(AudioSource.RADIO, HomeCard.strip(radio))
        assertEquals(AudioSource.NONE, HomeCard.strip(radio.copy(muted = true)))
        assertEquals(AudioSource.NONE, HomeCard.strip(RulesState()))
    }

    @Test
    fun `leaving the map shows it on the card, and a swipe picks a page`() {
        val inMaps = Shell.reduce(ShellState(), ShellAction.OpenApp(App.MAPS))
        assertEquals(NAVIGATION, Shell.reduce(inMaps, ShellAction.Home).card)
        assertEquals(NAVIGATION, Shell.reduce(inMaps, ShellAction.Back).card)
        assertEquals(RADIO, Shell.reduce(ShellState(), ShellAction.ShowCard(RADIO)).card)
        // leaving another app leaves the card as it was
        val inMusic = Shell.reduce(ShellState(card = RADIO), ShellAction.OpenApp(App.MUSIC))
        assertEquals(RADIO, Shell.reduce(inMusic, ShellAction.Home).card)
    }
}

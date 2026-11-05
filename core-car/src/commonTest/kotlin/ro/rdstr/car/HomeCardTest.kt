package ro.rdstr.car

import ro.rdstr.car.CardPage.BLUETOOTH
import ro.rdstr.car.CardPage.BT_OFF
import ro.rdstr.car.CardPage.NAVIGATION
import ro.rdstr.car.CardPage.NO_PHONE
import ro.rdstr.car.CardPage.RADIO
import ro.rdstr.car.HomeCard.Press.NEXT
import ro.rdstr.car.HomeCard.Press.PLAY
import ro.rdstr.car.HomeCard.Press.PREV
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HomeCardTest {
    private val bt = RulesState(source = AudioSource.BT, btConnected = true, playing = true)
    private val radio = RulesState(source = AudioSource.RADIO)
    private val idle = RulesState()

    @Test
    fun `three pages always, bluetooth, radio and the map`() {
        assertEquals(listOf(BLUETOOTH, RADIO, NAVIGATION), HomeCard.pages(bt, btOn = true))
        assertEquals(listOf(BLUETOOTH, RADIO, NAVIGATION), HomeCard.pages(radio.copy(btConnected = true), btOn = true))
    }

    @Test
    fun `the bluetooth page follows the phone and the bluetooth switch`() {
        // on, no phone: the saved phones to connect
        assertEquals(listOf(NO_PHONE, RADIO, NAVIGATION), HomeCard.pages(idle, btOn = true))
        assertEquals(listOf(NO_PHONE, RADIO, NAVIGATION), HomeCard.pages(radio, btOn = true))
        // off: a way to switch it on
        assertEquals(listOf(BT_OFF, RADIO, NAVIGATION), HomeCard.pages(radio, btOn = false))
        assertEquals(listOf(BT_OFF, RADIO, NAVIGATION), HomeCard.pages(idle, btOn = false))
    }

    @Test
    fun `the card shows the page chosen last and a changed bluetooth page keeps its place`() {
        assertEquals(NAVIGATION, HomeCard.shown(bt, true, NAVIGATION))
        assertEquals(RADIO, HomeCard.shown(bt, true, RADIO))
        assertEquals(BLUETOOTH, HomeCard.shown(bt, true, null))
        // the phone connected while its page was shown asking for one
        assertEquals(BLUETOOTH, HomeCard.shown(bt, true, NO_PHONE))
        assertEquals(BT_OFF, HomeCard.shown(idle, false, BLUETOOTH))
    }

    @Test
    fun `a new source brings its page forward and losing the source changes nothing`() {
        assertEquals(BLUETOOTH, HomeCard.forSource(bt, true))
        assertEquals(NO_PHONE, HomeCard.forSource(RulesState(source = AudioSource.BT), true))
        assertEquals(RADIO, HomeCard.forSource(radio, true))
        assertNull(HomeCard.forSource(idle, true))
    }

    @Test
    fun `the map's sound strip shows the source that plays`() {
        assertEquals(AudioSource.BT, HomeCard.strip(bt))
        assertEquals(AudioSource.NONE, HomeCard.strip(bt.copy(playing = false)))
        assertEquals(AudioSource.RADIO, HomeCard.strip(radio))
        assertEquals(AudioSource.NONE, HomeCard.strip(radio.copy(radioOn = false)))
        // muted is the volume, not a pause: the strip stays
        assertEquals(AudioSource.RADIO, HomeCard.strip(radio.copy(muted = true)))
        assertEquals(AudioSource.NONE, HomeCard.strip(idle))
        // switching from the phone to the radio swaps the strip
        assertEquals(AudioSource.RADIO, HomeCard.strip(bt.copy(source = AudioSource.RADIO, playing = false)))
    }

    @Test
    fun `on the source's own page the buttons work it`() {
        assertEquals(listOf(CarCommand.Media.Pause), HomeCard.press(BLUETOOTH, PLAY, bt))
        assertEquals(listOf(CarCommand.Media.Play), HomeCard.press(BLUETOOTH, PLAY, bt.copy(playing = false)))
        assertEquals(listOf(CarCommand.Media.Next), HomeCard.press(BLUETOOTH, NEXT, bt))
        // the radio's pause stops the station and play takes it again, the volume left alone
        assertEquals(listOf(CarCommand.Radio.Pause), HomeCard.press(RADIO, PLAY, radio))
        assertEquals(listOf(CarCommand.Radio.Play), HomeCard.press(RADIO, PLAY, radio.copy(radioOn = false)))
        assertEquals(listOf(CarCommand.Radio.Pause), HomeCard.press(RADIO, PLAY, radio.copy(muted = true)))
        assertEquals(listOf(CarCommand.Radio.Seek(false)), HomeCard.press(RADIO, PREV, radio))
    }

    @Test
    fun `on another source's page any button makes it the source first`() {
        assertEquals(listOf(CarCommand.Audio.Source(AudioSource.RADIO)), HomeCard.press(RADIO, PLAY, bt))
        assertEquals(listOf(CarCommand.Audio.Source(AudioSource.RADIO), CarCommand.Radio.Seek(true)), HomeCard.press(RADIO, NEXT, bt))
        val onRadio = radio.copy(btConnected = true)
        assertEquals(listOf(CarCommand.Audio.Source(AudioSource.BT), CarCommand.Media.Play), HomeCard.press(BLUETOOTH, PLAY, onRadio))
        assertEquals(listOf(CarCommand.Audio.Source(AudioSource.BT), CarCommand.Media.Prev), HomeCard.press(BLUETOOTH, PREV, onRadio))
    }

    @Test
    fun `starting a source while muted brings the sound back`() {
        val muted = bt.copy(muted = true)
        assertEquals(listOf(CarCommand.Audio.Source(AudioSource.RADIO), CarCommand.Audio.Mute(false)), HomeCard.press(RADIO, PLAY, muted))
        val mutedPhone = radio.copy(btConnected = true, muted = true)
        assertEquals(listOf(CarCommand.Audio.Source(AudioSource.BT), CarCommand.Audio.Mute(false), CarCommand.Media.Play), HomeCard.press(BLUETOOTH, PLAY, mutedPhone))
    }

    @Test
    fun `leaving the map shows it on the card, and a swipe picks a page`() {
        val inMaps = Shell.reduce(ShellState(), ShellAction.OpenApp(App.MAPS))
        assertEquals(NAVIGATION, Shell.reduce(inMaps, ShellAction.Home).card)
        assertEquals(NAVIGATION, Shell.reduce(inMaps, ShellAction.Back).card)
        assertEquals(RADIO, Shell.reduce(ShellState(), ShellAction.ShowCard(RADIO)).card)
        val inMusic = Shell.reduce(ShellState(card = RADIO), ShellAction.OpenApp(App.MUSIC))
        assertEquals(RADIO, Shell.reduce(inMusic, ShellAction.Home).card)
    }
}

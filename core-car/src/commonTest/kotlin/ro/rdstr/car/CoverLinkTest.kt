package ro.rdstr.car

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoverLinkTest {
    // the start and end markers of a JPEG around arbitrary bytes
    private val jpeg = ByteArray(300) { (it * 7).toByte() }.also {
        it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte(); it[298] = 0xFF.toByte(); it[299] = 0xD9.toByte()
    }

    @Test
    fun `a frame survives the trip from the phone to the unit`() {
        val sent = CoverFrame("Cupidoane", "Tzanca Uraganu", jpeg)
        val bytes = CoverLink.encode(sent)
        val length = CoverLink.payloadLength(bytes.copyOfRange(0, CoverLink.HEADER_SIZE))
        assertEquals(bytes.size - CoverLink.HEADER_SIZE, length)
        val received = CoverLink.decode(bytes.copyOfRange(CoverLink.HEADER_SIZE, bytes.size))!!
        assertEquals("Cupidoane", received.title)
        assertEquals("Tzanca Uraganu", received.artist)
        assertContentEquals(jpeg, received.jpeg)
    }

    @Test
    fun `names keep their diacritics on the way`() {
        val received = CoverLink.decode(CoverLink.encode(CoverFrame("Inimă de țară", "Ștefan", jpeg)).copyOfRange(CoverLink.HEADER_SIZE, CoverLink.encode(CoverFrame("Inimă de țară", "Ștefan", jpeg)).size))!!
        assertEquals("Inimă de țară", received.title)
        assertEquals("Ștefan", received.artist)
    }

    @Test
    fun `a header that is not ours or announces too much is refused`() {
        val good = CoverLink.encode(CoverFrame("a", "b", jpeg))
        val foreign = good.copyOfRange(0, CoverLink.HEADER_SIZE).also { it[0] = 0 }
        assertNull(CoverLink.payloadLength(foreign))
        val huge = good.copyOfRange(0, CoverLink.HEADER_SIZE).also { it[4] = 0x7f }
        assertNull(CoverLink.payloadLength(huge))
    }

    @Test
    fun `a cut payload decodes to nothing`() {
        val bytes = CoverLink.encode(CoverFrame("a", "b", jpeg))
        assertNull(CoverLink.decode(bytes.copyOfRange(CoverLink.HEADER_SIZE, bytes.size - 10)))
    }

    @Test
    fun `the same track is recognised across case, accents and extras`() {
        assertTrue(TrackMatch.same("Cupidoane", "Tzanca Uraganu", "CUPIDOANE", "tzanca uraganu"))
        assertTrue(TrackMatch.same("Cum e sa-ti pacalesti inima", "Tzanca Uraganu", "Cum e să-ți păcălești inima", "Tzanca Uraganu"))
        assertTrue(TrackMatch.same("Night Transit", "Vela Orsini", "Night Transit (Radio Edit)", "Vela Orsini feat. Lio"))
        assertTrue(TrackMatch.same("Night Transit", "", "Night Transit", "Anyone"))
        assertFalse(TrackMatch.same("Night Transit", "Vela Orsini", "Amber Coast", "Vela Orsini"))
        assertFalse(TrackMatch.same("Night Transit", "Vela Orsini", "Night Transit", "Someone Else"))
    }
}

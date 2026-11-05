package ro.rdstr.car

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CoverIndexTest {
    @Test
    fun `file names are folded and safe`() {
        assertEquals("cum-e-sa-ti-pacalesti-inima__tzanca-uraganu.jpg", CoverIndex.fileName("Cum e să-ți păcălești inima", "Tzanca Uraganu"))
        assertEquals("night-transit__vela-orsini.jpg", CoverIndex.fileName("Night Transit (Radio Edit)", "Vela Orsini"))
    }

    @Test
    fun `a kept cover is found under the names the car reports`() {
        val names = listOf("amber-coast__vela-orsini.jpg", "night-transit__vela-orsini-feat-lio.jpg")
        assertEquals("night-transit__vela-orsini-feat-lio.jpg", CoverIndex.find(names, "Night Transit", "Vela Orsini"))
        assertEquals("amber-coast__vela-orsini.jpg", CoverIndex.find(names, "AMBER COAST", ""))
        assertNull(CoverIndex.find(names, "Night Transit", "Someone Else"))
        assertNull(CoverIndex.find(names + "stray.txt", "Unknown", "Nobody"))
    }

    @Test
    fun `titles in other scripts keep their letters`() {
        assertEquals("кино__виктор-цой.jpg", CoverIndex.fileName("Кино!", "Виктор Цой"))
    }
}

package ro.rdstr.car

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhoneFormatTest {
    private fun f(typed: String, home: String = "RO") = PhoneFormat.format(typed, home)

    @Test
    fun `a Romanian mobile with its code, with 00 and nationally`() {
        assertEquals("+40 721 308 375", f("+40721308375"))
        assertEquals("+40 721 308 375", f("0040721308375"))
        assertEquals("0721 308 375", f("0721308375"))
        assertEquals("+40 721 308 375", f("+40 721-308-375"))
    }

    @Test
    fun `a Bucharest landline keeps its own grouping`() {
        assertEquals("021 310 1010", f("0213101010"))
        assertEquals("+40 21 310 1010", f("+40213101010"))
    }

    @Test
    fun `numbers group as they are typed`() {
        assertEquals("+4", f("+4"))
        assertEquals("+40", f("+40"))
        assertEquals("+40 72", f("+4072"))
        assertEquals("+40 721 3", f("+407213"))
        assertEquals("07", f("07"))
        assertEquals("0721 3", f("07213"))
        assertEquals("0721 308 3", f("07213083"))
    }

    @Test
    fun `other countries by their code`() {
        assertEquals("+1 212-555-1234", f("+12125551234"))
        assertEquals("+44 7911 123456", f("+447911123456"))
        assertEquals("+49 1511 2345678", f("+4915112345678"))
        assertEquals("+33 6 12 34 56 78", f("+33612345678"))
        assertEquals("+39 02 1234 5678", f("+390212345678"))
        assertEquals("+36 20 123 4567", f("+36201234567"))
    }

    @Test
    fun `national numbers follow the chosen country`() {
        assertEquals("(212) 555-1234", f("2125551234", "US"))
        assertEquals("07911 123456", f("07911123456", "GB"))
        assertEquals("06 20 123 4567", f("06201234567", "HU"))
    }

    @Test
    fun `short numbers, codes and unknown input stay as typed`() {
        assertEquals("112", f("112"))
        assertEquals("*123#", f("*123#"))
        assertEquals("", f(""))
        assertEquals("+999123", f("+999123"))
    }

    @Test
    fun `the number country is Romania first, an unknown one is ignored, the choice is kept across starts`() {
        assertEquals("RO", ShellState().phoneRegion)
        val hu = Shell.reduce(ShellState(), ShellAction.SetPhoneRegion("HU"))
        assertEquals("HU", Shell.decodePreferences(Shell.encodePreferences(hu)).phoneRegion)
        assertEquals("HU", Shell.reduce(hu, ShellAction.SetPhoneRegion("XX")).phoneRegion)
    }

    @Test
    fun `every region of the table can be chosen`() {
        assertTrue(PhoneFormat.regions().containsAll(listOf("RO", "US", "GB", "DE", "HU", "MD")))
        assertEquals(40, PhoneFormat.callingCode("RO"))
    }

    @Test
    fun `no region and no length of a number being typed breaks the formatting`() {
        for (region in PhoneFormat.regions()) {
            val code = PhoneFormat.callingCode(region)!!
            for (n in 0..15) {
                val digits = "987654321098765".take(n)
                val intl = f("+$code$digits", region)
                assertTrue(intl.filter { it.isDigit() } == "$code$digits", "$region +$code$digits -> $intl")
                // where 0 starts an international call (Cape Verde), it becomes "+", as 00 does here
                val national = f("0$digits", region)
                assertTrue(national.filter { it.isDigit() } == "0$digits" || national.startsWith("+"), "$region 0$digits -> $national")
            }
        }
    }
}

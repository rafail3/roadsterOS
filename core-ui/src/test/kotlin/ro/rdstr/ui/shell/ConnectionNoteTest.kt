package ro.rdstr.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionNoteTest {
    @Test
    fun bluetoothFirstThenWifiNamedByTheNetworkJoinedOrLeft() {
        assertEquals(ConnectionNote(1, false, true, "Pixel 8"), ConnectionNote.of(1, false, true, "Pixel 8", "Home", "Home"))
        assertEquals(ConnectionNote(2, true, true, "Home"), ConnectionNote.of(2, true, true, "Pixel 8", null, "Home"))
        assertEquals(ConnectionNote(3, true, false, "Home"), ConnectionNote.of(3, true, true, "Pixel 8", "Home", null))
        assertNull(ConnectionNote.of(4, true, true, "Pixel 8", "Home", "Home"))
    }
}

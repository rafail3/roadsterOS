package ro.rdstr.ui.drawer

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class DrawerGridTest {
    @Test
    fun equalColumnsWhenAllFit() {
        assertArrayEquals(FloatArray(6) { 184f }, DrawerGrid.columns(FloatArray(6) { 150f }, 1184f, 16f), 0.001f)
    }

    @Test
    fun aWideToggleKeepsItsWidthAndTheOthersShare() {
        // the emulator's drawer: Wi-Fi's "Phone hotspot" needs 197.5, the five others share the rest (181.3)
        val mins = floatArrayOf(160f, 197.5f, 150f, 120f, 130f, 145f)
        assertArrayEquals(floatArrayOf(181.3f, 197.5f, 181.3f, 181.3f, 181.3f, 181.3f), DrawerGrid.columns(mins, 1184f, 16f), 0.001f)
    }
}

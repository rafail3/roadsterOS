package ro.rdstr.gradle.display

import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals

class ParityTest {
    private fun layout(vararg elements: String) = Parity.read("""{"scene":"s","root":null,"elements":[${elements.joinToString(",")}]}""")
    private fun el(key: String, x: Double = 10.0, color: String = "#FFEEF1F5") =
        """{"key":"$key","box":{"x":$x,"y":0,"w":24,"h":24},"style":{"background":"#00000000","color":"$color","opacity":1},"text":null}"""

    @Test
    fun layoutFindings() {
        val web = layout(el("bar"), el("bar/wifi-bars"), el("bar/icon"))
        assertEquals(emptyList(), Parity.compare(web, layout(el("bar", x = 10.4), el("bar/wifi-bars"), el("bar/icon"))))
        assertEquals(
            listOf("bar/extra: on Android only", "bar/icon: missing on Android", "bar/wifi-bars: color #FF000000, browser #FFEEF1F5", "bar: x 10.600 dp, browser 10"),
            Parity.compare(web, layout(el("bar", x = 10.6), el("bar/wifi-bars", color = "#FF000000"), el("bar/extra"))),
        )
    }

    private fun square(x0: Int, edge: Int = 0xFF808080.toInt()) = BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB).apply {
        for (y in 5 until 15) for (x in x0 until x0 + 10) setRGB(x, y, if (x == x0) edge else 0xFFFFFFFF.toInt())
    }

    @Test
    fun pixels() {
        assertEquals(0, Parity.pixels(square(5), square(5), Parity.Limits()).differing)
        // the same outline anti-aliased differently: edge pixels only, not counted
        val aa = Parity.pixels(square(5), square(5, edge = 0xFFA0A0A0.toInt()), Parity.Limits())
        assertEquals(10, aa.edges)
        assertEquals(0, aa.over)
        // one pixel to the right: the three columns that changed (left edge, its old place, the new right column) count
        assertEquals(30, Parity.pixels(square(5), square(6), Parity.Limits()).over)
    }
}

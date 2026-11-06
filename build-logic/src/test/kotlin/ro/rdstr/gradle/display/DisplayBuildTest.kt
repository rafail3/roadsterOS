package ro.rdstr.gradle.display

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DisplayBuildTest {
    private val sheet = """
        :root { --on: 255 255 255; --ink: #eef1f5; --glass: rgb(var(--on) / 0.08); --space-1: 4px; --fast: 150ms; --t: .7s;
                --ease: cubic-bezier(0.16, 1, 0.3, 1); --font: "Inter", system-ui, sans-serif; }
        .screen.light { --on: 20 28 38; --ink: #171c23; }
        .settings-app { --row: rgb(var(--on) / 0.1); --ink: #fff; }
        .screen.light .settings-app { --ink: #000; }
        .tx { --tx-size: 96px; }
        .np .tx { --tx-size: 76px; --bloom: color-mix(in srgb, var(--ink) 14%, transparent); }
        [data-design="glassy"] { --glassy-fill: rgb(44 44 48 / 0.42); --frost: blur(28px) saturate(1.9) brightness(1.06);
            --shadow: inset 0 0 0 1px var(--glassy-fill), 0 10px 30px rgb(0 0 0 / 0.34);
            --sheen: linear-gradient(155deg, rgb(255 255 255 / 0.2), rgb(255 255 255 / 0.04) 40%, rgb(255 255 255 / 0)); }
        [data-design="glassy"][data-theme="light"] { --glassy-fill: rgb(255 255 255 / 0.5); }
        [data-design="glassy"] .tx { --plate: 0.15; }
        .i-bluetooth { --m: url(/icons/bluetooth.svg); }
    """.trimIndent()

    private val tokens = Tokens(Css.declarations("t.css", sheet))
    private fun ctx(vararg chain: String, light: Boolean = false, glassy: Boolean = false) =
        Context(chain.map { c -> c.split('.').filter { it.isNotEmpty() }.toSet() }, light, glassy)

    @Test
    fun `var() is substituted in the element's own theme`() {
        assertEquals(ColorValue(0x14FFFFFF), tokens.resolve("--glass", ctx()))
        assertEquals(ColorValue(0x14141C26), tokens.resolve("--glass", ctx(light = true)))
        // declared once for the dark theme, the light theme's --on still reaches it
        assertEquals(ColorValue(0x1A141C26), tokens.resolve("--row", ctx("settings-app", light = true)))
    }

    @Test
    fun `the more specific rule wins and a component beats the screen`() {
        assertEquals(ColorValue(0xFFFFFFFF.toInt()), tokens.resolve("--ink", ctx("settings-app")))
        assertEquals(ColorValue(0xFF000000.toInt()), tokens.resolve("--ink", ctx("settings-app", light = true)))
        assertEquals(DpValue(96.0), tokens.resolve("--tx-size", ctx("tx")))
        assertEquals(DpValue(76.0), tokens.resolve("--tx-size", ctx("np", "card-strip", "tx")))
    }

    @Test
    fun `glassy rules apply only to the glassy design`() {
        assertNull(tokens.resolve("--plate", ctx("tx")))
        assertEquals(NumberValue(0.15), tokens.resolve("--plate", ctx("tx", glassy = true)))
        assertEquals(ColorValue(0x80FFFFFF.toInt()), tokens.resolve("--glassy-fill", ctx(light = true, glassy = true)))
    }

    @Test
    fun `values are typed`() {
        assertEquals(MsValue(700.0), tokens.resolve("--t", ctx()))
        assertEquals(BezierValue(0.16, 1.0, 0.3, 1.0), tokens.resolve("--ease", ctx()))
        assertEquals(FontsValue(listOf("Inter", "system-ui", "sans-serif")), tokens.resolve("--font", ctx()))
        assertEquals(FrostValue(28.0, 1.9, 1.06), tokens.resolve("--frost", ctx(glassy = true)))
        val shadow = tokens.resolve("--shadow", ctx(glassy = true)) as ShadowsValue
        assertEquals(Shadow(true, 0.0, 0.0, 0.0, 1.0, ColorValue(0x6B2C2C30)), shadow.shadows[0])
        assertEquals(Shadow(false, 0.0, 10.0, 30.0, 0.0, ColorValue(0x57000000)), shadow.shadows[1])
        val sheen = tokens.resolve("--sheen", ctx(glassy = true)) as GradientValue
        assertEquals(155.0, sheen.angle)
        assertEquals(listOf(0.0, 0.4, 1.0), sheen.at)
    }

    @Test
    fun `color-mix mixes premultiplied, as the browser`() {
        // 14 % of #eef1f5 over transparent: the ink's channels, 14 % alpha
        assertEquals(ColorValue.of(238.0, 241.0, 245.0, 0.14), tokens.resolve("--bloom", ctx("np", "tx")))
        assertEquals(ColorValue.of(128.0, 128.0, 128.0, 1.0), Values.color("color-mix(in srgb, #fff 50%, #000)")!!.let {
            ColorValue.of(it.r, it.g, it.b, it.a) })
    }

    @Test
    fun `stop positions follow the css rules`() {
        assertEquals(listOf(0.0, 0.25, 0.5, 1.0), Values.positions(listOf(null, null, 0.5, null)))
        assertEquals(listOf(0.0, 0.6, 0.6, 1.0), Values.positions(listOf(0.0, 0.6, 0.4, null)))
    }

    @Test
    fun `classes per component with an instance per theme and design`() {
        val classes = tokens.classes().associateBy { it.name }
        val base = classes.getValue("Base")
        assertTrue(base.fields.none { it.property == "--glassy-fill" })
        assertEquals(setOf("GLASSY_DARK", "GLASSY_LIGHT"), classes.getValue("Glassy").instances.keys)
        val tx = classes.getValue("Tx")
        assertTrue(tx.fields.single { it.property == "--plate" }.nullable)
        assertEquals(listOf("i-bluetooth"), tokens.icons.map { it.cssClass })
    }

    @Test
    fun `a selector that is not a chain of classes stops the build`() {
        assertFailsWith<DisplayBuildException> { Tokens.scopeOf("t.css", ".a > .b") }
        assertFailsWith<DisplayBuildException> { Tokens.scopeOf("t.css", ".a:hover") }
        assertFailsWith<DisplayBuildException> { Values.parse("12em", "t") }
        assertFailsWith<DisplayBuildException> { Css.declarations("t.css", "@media (min-width: 1px) { .a { --x: 1px; } }") }
    }

    @Test
    fun `specificity counts like the browser`() {
        assertEquals(200, Css.specificity(".screen.light"))
        assertEquals(300, Css.specificity(""".screen.light .settings-app"""))
        assertEquals(400, Css.specificity(""".screen.light :is(.music-app:not(.settings-app, .phone-app), .call)"""))
        assertEquals(200, Css.specificity("""[data-design="glassy"][data-theme="light"]"""))
    }

    @Test
    fun `icons become vector drawables tinted by the view`() {
        val xml = Svg.toVector("info", """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 256 256"><circle cx="128" cy="128" r="96" fill="none" stroke="#000" stroke-width="16"/><rect x="120" y="112" width="16" height="68" rx="8" fill="#000"/></svg>""")
        assertTrue("M32,128 A96,96 0 1,0 224,128 A96,96 0 1,0 32,128 Z" in xml)
        assertTrue("android:strokeWidth=\"16\"" in xml)
        assertTrue("android:viewportWidth=\"256\"" in xml)
        assertFailsWith<DisplayBuildException> { Svg.toVector("x", """<svg viewBox="0 0 2 2"><path d="M0 0" fill="#f00"/></svg>""") }
    }

    @Test
    fun `a stroke cut out of a mask is a counter-clockwise hole`() {
        val c = Svg.capsule(48.0, 40.0, 208.0, 216.0, 20.0, round = true)
        val pts = Regex("""(-?[\d.]+),(-?[\d.]+)""").findAll(c.replace(Regex("""A[\d.]+,[\d.]+ 0 0,0 """), ""))
            .map { it.groupValues[1].toDouble() to it.groupValues[2].toDouble() }.toList().dropLast(1)
        val area = pts.indices.sumOf { i -> val a = pts[i]; val b = pts[(i + 1) % pts.size]; a.first * b.second - b.first * a.second }
        assertTrue(area < 0, "outline must wind counter-clockwise, area $area")
        assertTrue("0 0,0" in c)
    }

    /** The real sheets and icons: everything the emulator uses must convert, or the build says what does not. */
    @Test
    fun `the emulator's own styles and icons generate`() {
        val emulator = File("../emulator")
        val order = DisplayBuild.sheetOrder(File(emulator, "app/layout.tsx").readText(), listOf("chrome.css"))
        assertTrue("tokens.css" == order.first() && "chrome.css" !in order)
        val result = DisplayBuild.run(order.map { it to File(emulator, "styles/$it").readText() },
            File(emulator, "public/icons").listFiles()!!.filter { it.extension == "svg" }.associate { it.nameWithoutExtension to it.readText() },
            "ro.rdstr.ui.tokens", "ro.rdstr.ui",
            File(emulator, "public/fonts").listFiles()!!.associate { it.name to it.readBytes() })
        assertTrue("class Base(" in result.tokens && "class SettingsApp(" in result.tokens && "class ToggleWifi(" in result.tokens)
        assertTrue("toggleOn = 0xFFFF9500.toInt()" in result.tokens)
        assertTrue(result.drawables.size == File(emulator, "public/icons").listFiles()!!.count { it.extension == "svg" })
        assertTrue("<clip-path" in result.drawables.getValue("rdstr_icon_speaker_slash_fill"))
        assertEquals(setOf("rdstr_font_inter.ttf", "rdstr_font_geist.ttf"), result.fontFiles.keys)
        assertTrue("\"Geist\" to 100..900" in result.fonts)
    }

    @Test
    fun `a font split by unicode-range or in woff2 stops the build`() {
        val split = "@font-face { font-family: \"A\"; src: url(/fonts/a.ttf); unicode-range: U+0000-00FF; }"
        assertFailsWith<DisplayBuildException> { DisplayBuild.fontFaces(listOf("t.css" to split), mapOf("a.ttf" to ByteArray(1))) }
        val woff = "@font-face { font-family: \"A\"; src: url(/fonts/a.woff2) format(\"woff2\"); }"
        assertFailsWith<DisplayBuildException> { DisplayBuild.fontFaces(listOf("t.css" to woff), mapOf("a.woff2" to ByteArray(1))) }
    }
}

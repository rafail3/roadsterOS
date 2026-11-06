package ro.rdstr.ui.maps

import ro.rdstr.ui.kit.CssBox
import ro.rdstr.ui.kit.CssColor
import ro.rdstr.ui.kit.CssFilter
import ro.rdstr.ui.kit.UiTheme
import ro.rdstr.ui.tokens.Glassy
import ro.rdstr.ui.tokens.MapsApp
import ro.rdstr.ui.tokens.Shadow

/**
 * The maps screen's colours (maps.css, its day rules, and glassy.css over `.maps-app`). The screen is not forced dark
 * by day (tokens.css:133): its buttons and pills turn light over the day map. On Glassy every surface takes the glass
 * material with the map's own glass tokens (`MapsApp`), in both themes; the locate arrow is the location blue in both.
 */
internal object MapsLook {
    const val BLUE = 0xFF0A84FF.toInt()
    const val RED = 0xFFFF453A.toInt()
    const val ORANGE = 0xFFFF9F0A.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val BLACK = 0xFF000000.toInt()

    val light get() = UiTheme.light
    val glassy get() = UiTheme.glassy

    /** `rgb(var(--on) / .12)` by night, the day rules' `rgba(0, 0, 0, .08)` by day. */
    val edge get() = if (light) CssColor.withAlpha(BLACK, 0.08f) else CssColor.withAlpha(WHITE, 0.12f)

    /** The day rules' drop, `0 6px 18px rgba(0, 0, 0, .14)`. */
    private val dayDrop = listOf(Shadow(false, 0f, 6f, 18f, 0f, CssColor.withAlpha(BLACK, 0.14f)))
    /** `0 8px 24px rgba(0, 0, 0, .35)`, the night pills' drop. */
    private val nightDrop = listOf(Shadow(false, 0f, 8f, 24f, 0f, CssColor.withAlpha(BLACK, 0.35f)))
    /** `.glass-button` and `.map-zoom` by night: a lit top line over the drop. */
    private val nightButton = listOf(Shadow(true, 0f, 1f, 0f, 0f, CssColor.withAlpha(WHITE, 0.3f))) + nightDrop

    /** The ink of the round buttons' glyphs: `--button-glass-ink` by night, `--ink` by day. */
    val buttonInk get() = if (light) UiTheme.base.ink else UiTheme.base.buttonGlassInk
    /** The zoom keys' `+` and `−`. */
    val zoomInk get() = if (light) UiTheme.base.ink else CssColor.withAlpha(0xFFEBEBF5.toInt(), 0.9f)
    /** The street pill's text, the speed's number. */
    val strongInk get() = if (light) UiTheme.base.ink else WHITE
    /** The speed's unit (`.map-speed` colour). */
    val speedInk get() = if (light) UiTheme.base.inkMuted else CssColor.withAlpha(WHITE, 0.85f)
    val creditInk get() = if (light) UiTheme.base.ink else CssColor.withAlpha(WHITE, 0.9f)

    /** The compass's south half (`.map-compass::after`). */
    val south get() = if (light) CssColor.withAlpha(BLACK, 0.55f) else CssColor.withAlpha(WHITE, 0.85f)
    /** The speed ring's ticks: unlit, and lit in white (`is-lit`); orange and red are the same in both themes. */
    val tickOff get() = if (light) CssColor.withAlpha(BLACK, 0.16f) else CssColor.withAlpha(WHITE, 0.2f)
    val tickLit get() = if (light) CssColor.withAlpha(BLACK, 0.7f) else CssColor.withAlpha(WHITE, 0.92f)

    /** The round glass buttons (`.glass-button`) and the zoom pill (`.map-zoom`), [pressed] on Glassy. */
    fun button(box: CssBox, ground: Int, pressed: Boolean = false) {
        box.border = 1f
        box.borderColor = edge
        if (glassy) glass(box, ground, pressed)
        else {
            box.solid(if (light) CssColor.withAlpha(WHITE, 0.88f) else UiTheme.base.buttonGlass)
            box.shadows = if (light) dayDrop else nightButton
        }
    }

    /** The street pill, the speed dial and the credits (`.map-pill`, `.map-speed`, `.map-credit-text`). */
    fun panel(box: CssBox, ground: Int, night: Float, day: Float, drop: Boolean = true) {
        box.border = 1f
        box.borderColor = edge
        if (glassy) glass(box, ground, false)
        else {
            box.solid(if (light) CssColor.withAlpha(WHITE, day) else CssColor.withAlpha(0xFF14161A.toInt(), night))
            box.shadows = if (!drop) emptyList() else if (light) dayDrop else nightDrop
        }
    }

    /**
     * The glass material over the map: fill, sheen and shadow from the map's glass tokens. The frost under it is the
     * flat [ground] frosted (the unit blurs nothing live), folded into the fill, so a frost captured for another
     * screen never shows here.
     */
    private fun glass(box: CssBox, ground: Int, pressed: Boolean) {
        val g = Glassy.of(light)
        val m = MapsApp.of(light)
        box.fill = CssColor.over(if (pressed) g.glassyFillStrong else m.glassyFill, frosted(ground))
        box.gradient = g.glassySheen
        box.shadows = m.glassyShadow
        box.frost = null
    }

    /** The flat [ground] through the glass frost (`saturate(1.9) brightness(1.06)`; a blur leaves a flat colour as it is). */
    fun frosted(ground: Int): Int {
        val frost = Glassy.of(light).glassyFrost
        return CssFilter.apply(CssFilter.saturateBrightness(frost.saturate, frost.brightness), ground)
    }

    /**
     * The credits by day (`.screen.light .map-credit-text`): its white `background` outranks the glass base on Glassy
     * too, which keeps only the glass shadow and the frost under it.
     */
    fun credit(box: CssBox, ground: Int) {
        panel(box, ground, 0.82f, 0.92f, drop = false)
        if (glassy && light) { box.gradient = null; box.fill = CssColor.over(CssColor.withAlpha(WHITE, 0.92f), frosted(ground)) }
    }
}

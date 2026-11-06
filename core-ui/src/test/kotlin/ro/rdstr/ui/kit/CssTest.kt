package ro.rdstr.ui.kit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CssTest {
    private fun near(expected: Float, actual: Float, eps: Float = 1e-3f) = assertEquals(expected, actual, eps)

    @Test fun alphaRoundsAsTheTokens() {
        assertEquals(0x14FFFFFF, CssColor.withAlpha(0xFFFFFFFF.toInt(), 0.08f))
        assertEquals(0x6BFFFFFF, CssColor.withAlpha(-1, 0.42f))
        // color-mix(in srgb, #eef1f5 76%, transparent)
        assertEquals(0xC2EEF1F5.toInt(), CssColor.fade(0xFFEEF1F5.toInt(), 0.76f))
    }

    @Test fun transitionsInterpolatePremultiplied() {
        // transparent black to white: never greyer than white, only fainter
        assertEquals(0x80FFFFFF.toInt(), CssColor.lerp(0, -1, 0.5f))
        assertEquals(0xFF808080.toInt(), CssColor.lerp(0xFF000000.toInt(), -1, 0.5f))
        assertEquals(-1, CssColor.lerp(0, -1, 1f))
        assertEquals(0, CssColor.lerp(0, -1, 0f))
    }

    @Test fun sourceOver() {
        assertEquals(0xFF808080.toInt(), CssColor.over(0x80FFFFFF.toInt(), 0xFF000000.toInt()))
        assertEquals(0xFF0A0D12.toInt(), CssColor.over(0, 0xFF0A0D12.toInt()))
        val c = CssColor.over(0x14FFFFFF, 0xFF0F1216.toInt())
        assertEquals(255, CssColor.alpha(c))
        assertEquals(0x0F + (0xFF - 0x0F) * 20 / 255.0, (c shr 16 and 0xFF).toDouble(), 1.0)
    }

    @Test fun filtersMatchTheSpecMatrices() {
        val identity = CssFilter.saturateBrightness(1f, 1f)
        assertEquals(0xFF336699.toInt(), CssFilter.apply(identity, 0xFF336699.toInt()))
        // greys stay grey under any saturation
        val grey = CssFilter.apply(CssFilter.saturateBrightness(1.9f, 1f), 0xFF7F7F7F.toInt())
        assertEquals(0xFF7F7F7F.toInt(), grey)
        assertEquals(0xFF888888.toInt(), CssFilter.apply(CssFilter.saturateBrightness(1f, 1.0705f), 0xFF7F7F7F.toInt()))
        // saturate(0) is the luminance grey
        val pure = CssFilter.apply(CssFilter.saturateBrightness(0f, 1f), 0xFFFF0000.toInt())
        assertEquals(54, pure shr 16 and 0xFF)
    }

    @Test fun blurKeepsFlatAndSpreadsAPoint() {
        val flat = IntArray(40 * 30) { 0xFF336699.toInt() }
        CssBlur.gaussian(flat, 40, 30, 3f)
        assertTrue(flat.all { it == 0xFF336699.toInt() })
        val w = 41
        val px = IntArray(w * w) { 0xFF000000.toInt() }
        px[20 * w + 20] = -1
        CssBlur.gaussian(px, w, w, 2f)
        val centre = px[20 * w + 20] and 0xFF
        assertTrue(centre in 1..254)
        assertEquals(px[20 * w + 17] and 0xFF, px[20 * w + 23] and 0xFF)
        assertEquals(px[17 * w + 20] and 0xFF, px[23 * w + 20] and 0xFF)
        assertTrue((px[20 * w + 23] and 0xFF) < centre)
    }

    @Test fun blurSigmaAndMaskRadius() {
        near(14f, CssBlur.sigma(28f))
        near(0f, CssBlur.maskRadius(0.5f))
        // Skia's radius to sigma: 0.57735 r + 0.5
        val r = CssBlur.maskRadius(12f)
        near(12f, 0.57735f * r + 0.5f)
    }

    @Test fun gradientLineFollowsCssAngles() {
        val down = CssGradient.line(180f, 100f, 50f)
        near(50f, down[0]); near(0f, down[1]); near(50f, down[2]); near(50f, down[3])
        val right = CssGradient.line(90f, 100f, 50f)
        near(0f, right[0]); near(25f, right[1]); near(100f, right[2]); near(25f, right[3])
        // 155deg: the line reaches the corners' perpendiculars, so the far corners take the end colours
        val g = CssGradient.line(155f, 200f, 100f)
        val len = Math.hypot((g[2] - g[0]).toDouble(), (g[3] - g[1]).toDouble())
        val a = Math.toRadians(155.0)
        assertEquals(abs(200 * Math.sin(a)) + abs(100 * Math.cos(a)), len, 1e-3)
    }

    @Test fun bezierSolvesLikeBrowsers() {
        val ease = CssBezier(0.25f, 0.1f, 0.25f, 1f)
        near(0f, ease.at(0f)); near(1f, ease.at(1f))
        near(0.8024f, ease.at(0.5f), 2e-3f)
        val linear = CssBezier(0f, 0f, 1f, 1f)
        for (t in listOf(0.1f, 0.37f, 0.9f)) near(t, linear.at(t))
        // --ease-out: fast start
        assertTrue(CssBezier(0.16f, 1f, 0.3f, 1f).at(0.2f) > 0.6f)
    }

    @Test fun linearStopsInterpolate() {
        val glyph = CssStops(floatArrayOf(0f, 0.1f, 0.319f, 1.095f, 1f), floatArrayOf(0f, 0.05f, 0.1f, 0.4f, 1f))
        near(0f, glyph.at(0f)); near(1f, glyph.at(1f))
        near(0.1f, glyph.at(0.05f))
        near(1.095f, glyph.at(0.4f))
        near((1.095f + 1f) / 2f, glyph.at(0.7f))
    }

    @Test fun transitionRetargetsAndShortensReversals() {
        val t = Transition(0f)
        t.to(10f, 0, 100, Easing.LINEAR)
        near(5f, t.value(50))
        assertTrue(t.running(50))
        // sent back half way: from where it is, in half the time
        t.to(0f, 50, 100, Easing.LINEAR)
        near(5f, t.value(50))
        near(0f, t.value(100))
        assertTrue(!t.running(100))
        t.to(4f, 200, 0, Easing.LINEAR)
        near(4f, t.value(200))
    }

    @Test fun colorTransitionEnds() {
        val c = ColorTransition(0)
        c.to(-1, 0, 200, Easing.LINEAR)
        assertEquals(0x80FFFFFF.toInt(), c.value(100))
        assertEquals(-1, c.value(200))
    }

    @Test fun springSettlesOnItsTarget() {
        val s = Spring(0.0, GlisseTokens.SWITCH_K, GlisseTokens.SWITCH_D)
        s.set(1.0)
        var max = 0.0
        var frames = 0
        while (s.step(1 / 60.0) && frames < 600) { max = maxOf(max, s.value); frames++ }
        assertEquals(1.0, s.value, 0.0)
        // 1000/80 is overdamped: no overshoot
        assertTrue(max <= 1.0)
        assertTrue(frames in 10..120)
        val lift = Spring(0.0, GlisseTokens.LIFT_K, GlisseTokens.LIFT_D)
        lift.set(1.0)
        repeat(600) { lift.step(1 / 60.0) }
        assertEquals(1.0, lift.value, 0.0)
        val instant = Spring(0.0, 100.0, 10.0)
        instant.set(3.0, instant = true)
        assertEquals(3.0, instant.value, 0.0)
        assertTrue(!instant.step(0.016))
    }

    @Test fun scaleHelpers() {
        assertEquals(128, UiScale.densityDpi(1024))
        assertEquals(160, UiScale.densityDpi(1280))
        near(44.8f, UiScale.px(56f, 0.8f))
        near(1f, UiScale.borderPx(1f, 0.8f))
        near(2f, UiScale.borderPx(2.5f, 0.8f))
        near(0f, UiScale.borderPx(0f, 0.8f))
    }
}

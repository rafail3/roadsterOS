package ro.rdstr.ui.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardTest {
    private val eps = 0.01f
    private fun List<Key>.char(c: String) = first { it.type == KeyType.CHAR && it.lower == c }
    private fun List<Key>.of(t: KeyType) = first { it.type == t }

    @Test fun rowsAndKeySets() {
        val letters = KeyboardLayout.keys(Layer.LETTERS, symbols = true, width = 1280f)
        assertEquals("1234567890" + "qwertyuiop" + "asdfghjkl" + "zxcvbnm", letters.filter { it.type == KeyType.CHAR }.joinToString("") { it.lower })
        assertEquals(listOf(KeyType.SHIFT, KeyType.BACKSPACE, KeyType.LAYER, KeyType.SPACE, KeyType.SUBMIT), letters.filter { it.type != KeyType.CHAR }.map { it.type })
        val symbols = KeyboardLayout.keys(Layer.SYMBOLS, symbols = true, width = 1280f)
        assertEquals("1234567890" + "-/:;()$&@\"" + "[]{}#%^*+=" + ".,?!'_~", symbols.filter { it.type == KeyType.CHAR }.joinToString("") { it.lower })
        assertEquals(KeyType.SPACER, symbols.filter { it.type != KeyType.CHAR }[0].type)
        assertEquals("ABC", symbols.of(KeyType.LAYER).lower)
        assertEquals("#+=", letters.of(KeyType.LAYER).lower)
    }

    @Test fun carNameHasNoLayerKey() {
        val keys = KeyboardLayout.keys(Layer.LETTERS, symbols = false, width = 1280f)
        assertTrue(keys.none { it.type == KeyType.LAYER })
        // .kb-space flex 6 and .kb-submit flex 3 share the row
        assertEquals((1264f - 10f) * 6 / 9, keys.of(KeyType.SPACE).width, eps)
        assertEquals(8f, keys.of(KeyType.SPACE).left, eps)
    }

    /** Boxes measured in the emulator at 1280 px (getBoundingClientRect, relative to the keyboard). */
    @Test fun boxesMatchTheEmulator() {
        val k = KeyboardLayout.keys(Layer.LETTERS, symbols = true, width = 1280f)
        assertEquals(368f, KeyboardLayout.HEIGHT, eps)
        fun box(key: Key, x: Float, y: Float, w: Float, h: Float) {
            assertEquals(x, key.left, eps); assertEquals(y, key.top, eps); assertEquals(w, key.width, eps); assertEquals(h, key.height, eps)
        }
        box(k.char("1"), 8f, 12f, 117.4f, 52f)
        box(k.char("0"), 1154.6f, 12f, 117.4f, 52f)
        box(k.char("q"), 8f, 76f, 117.4f, 60f)
        box(k.char("a"), 66.14f, 148f, 118.64f, 60f)
        box(k.of(KeyType.SHIFT), 8f, 220f, 156.86f, 60f)
        box(k.char("z"), 225.42f, 220f, 109.88f, 60f)
        box(k.of(KeyType.BACKSPACE), 1115.14f, 220f, 156.86f, 60f)
        box(k.of(KeyType.LAYER), 8f, 292f, 226.18f, 60f)
        box(k.of(KeyType.SPACE), 244.18f, 292f, 678.55f, 60f)
        box(k.of(KeyType.SUBMIT), 932.73f, 292f, 339.27f, 60f)
    }

    @Test fun symbolsThirdRowKeysHaveTheLetterWidth() {
        val letters = KeyboardLayout.keys(Layer.LETTERS, true, 1280f)
        val symbols = KeyboardLayout.keys(Layer.SYMBOLS, true, 1280f)
        // measured in the emulator at 1280 px: "." sits where "z" does, 109.88 wide
        assertEquals(225.42f, symbols.char(".").left, eps)
        assertEquals(109.88f, symbols.char(".").width, eps)
        assertEquals(letters.char("m").right, symbols.char("~").right, eps)
        // the side keys keep their place, so the row keeps its shape
        assertEquals(letters.of(KeyType.BACKSPACE).left, symbols.of(KeyType.BACKSPACE).left, eps)
        assertEquals(letters.of(KeyType.BACKSPACE).width, symbols.of(KeyType.BACKSPACE).width, eps)
    }

    @Test fun shiftOnceLockAndOff() {
        val keys = KeyboardLayout.keys(Layer.LETTERS, true, 1280f)
        val s = KeyboardState()
        assertEquals("a", s.type(keys.char("a")))
        s.pressShift(1000)
        assertEquals(Shift.ONCE, s.shift)
        assertEquals("A", s.shown(keys.char("a")))
        assertEquals("1", s.type(keys.char("1")))
        assertEquals(Shift.ONCE, s.shift)
        assertEquals("A", s.type(keys.char("a")))
        assertEquals(Shift.OFF, s.shift)
        // two taps closer than 350 ms lock capitals
        s.pressShift(2000); s.pressShift(2349)
        assertEquals(Shift.LOCK, s.shift)
        assertEquals("B", s.type(keys.char("b"))); assertEquals("C", s.type(keys.char("c")))
        s.pressShift(5000)
        assertEquals(Shift.OFF, s.shift)
        // 350 ms or more apart: the second tap turns Shift off
        s.pressShift(6000); s.pressShift(6350)
        assertEquals(Shift.OFF, s.shift)
    }

    @Test fun symbolsIgnoreShiftButSpendIt() {
        val s = KeyboardState()
        s.pressShift(0)
        s.toggleLayer()
        val sym = KeyboardLayout.keys(s.layer, true, 1280f)
        assertEquals("[", s.shown(sym.char("[")))
        assertEquals("[", s.type(sym.char("[")))
        assertEquals(Shift.OFF, s.shift)
        s.toggleLayer()
        assertEquals(Layer.LETTERS, s.layer)
    }

    @Test fun repeatSchedule() {
        assertEquals(listOf(0L), HoldRepeat.schedule(100))
        assertEquals(listOf(0L, 270L), HoldRepeat.schedule(300))
        assertEquals(listOf(0L, 270L, 480L, 690L, 900L), HoldRepeat.schedule(1000))
    }

    @Test fun looks() {
        // dark: a pressed key lightens, the submit key stays blue or amber when pressed
        assertEquals(KeyboardLook.KEY_DOWN_DARK, KeyboardLook.key(KeyType.CHAR, true, false, true, light = false, glassy = false, amber = false).fill)
        assertEquals(KeyboardLook.SUBMIT_DARK, KeyboardLook.key(KeyType.SUBMIT, true, false, true, false, false, false).fill)
        assertEquals(0xFFFFA41B.toInt(), KeyboardLook.key(KeyType.SUBMIT, false, false, true, false, false, amber = true).fill)
        assertEquals(KeyboardLook.AMBER_OFF_ALPHA, KeyboardLook.key(KeyType.SUBMIT, false, false, false, false, false, amber = true).alpha)
        // by day the setup's submit key stays amber: `.screen.light .sf .kb-submit` outranks `.screen.light .kb-submit`
        assertEquals(0xFFFFA41B.toInt(), KeyboardLook.key(KeyType.SUBMIT, false, false, true, true, false, amber = true).fill)
        assertEquals(KeyboardLook.SUBMIT_LIGHT, KeyboardLook.key(KeyType.SUBMIT, false, false, true, true, false, amber = false).fill)
        // by day a pressed key takes the function keys' grey, a pressed function key turns white, the bubble stays white
        assertEquals(KeyboardLook.FN_LIGHT, KeyboardLook.key(KeyType.CHAR, true, false, true, true, false, false).fill)
        assertEquals(KeyboardLook.FN_LIGHT, KeyboardLook.key(KeyType.SPACE, true, false, true, true, false, false).fill)
        assertEquals(KeyboardLook.WHITE, KeyboardLook.key(KeyType.BACKSPACE, true, false, true, true, false, false).fill)
        assertEquals(KeyboardLook.FN_LIGHT, KeyboardLook.key(KeyType.BACKSPACE, false, false, true, true, false, false).fill)
        assertEquals(KeyboardLook.WHITE, KeyboardLook.bubbleFill(true))
        // glassy dark: glass fill with sheen; a press or Shift on drops the sheen and keeps the glass edge
        val glass = KeyboardLook.key(KeyType.CHAR, false, false, true, false, true, false)
        assertTrue(glass.sheen && glass.glass && glass.hard == 0)
        val on = KeyboardLook.key(KeyType.SHIFT, false, true, true, false, true, false)
        assertFalse(on.sheen); assertTrue(on.glass); assertEquals(KeyboardLook.WHITE, on.fill)
        // glassy light: solid keys with the hard shadow, glass only on the submit key
        assertTrue(KeyboardLook.key(KeyType.CHAR, false, false, true, true, true, false).hard != 0)
        assertTrue(KeyboardLook.key(KeyType.SUBMIT, false, false, false, true, true, false).let { it.glass && it.sheen })
    }
}

package ro.rdstr.ui.keyboard

// The keyboard's rows, key boxes and typing rules as emulator/components/Keyboard.tsx and the `.keyboard` rules in
// emulator/styles/music.css define them. Lengths are canvas px (dp in our app). No Android types here: the JVM tests
// check these tables against the CSS.

/** What a key does. */
enum class KeyType { CHAR, SHIFT, SPACER, BACKSPACE, LAYER, SPACE, SUBMIT }

enum class Layer { LETTERS, SYMBOLS }

/** One key's box; [lower] and [upper] are the labels (and the typed text) without and with Shift. */
class Key(
    val type: KeyType,
    val lower: String,
    val upper: String,
    /** the digit row: 24 px labels, typed without Shift */
    val digit: Boolean,
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
) {
    val right get() = left + width
    val bottom get() = top + height
    fun contains(x: Float, y: Float) = x >= left && x < right && y >= top && y < bottom
}

object KeyboardLayout {
    // digits stay on top of both layers, so a password with numbers never needs a layer switch
    const val DIGITS = "1234567890"
    val LETTERS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    val SYMBOLS = listOf("-/:;()$&@\"", "[]{}#%^*+=", ".,?!'_~")

    // .keyboard { gap: 12px; padding: 12px 8px 16px }
    const val PAD_TOP = 12f
    const val PAD_SIDE = 8f
    const val PAD_BOTTOM = 16f
    const val ROW_GAP = 12f
    // .kb-row { gap: 10px }, .kb-row-keys { gap: 10px }
    const val KEY_GAP = 10f
    // .kb-row-digits .kb-key { height: 52px }, .kb-key { height: 60px }
    const val DIGIT_HEIGHT = 52f
    const val KEY_HEIGHT = 60f
    // .kb-row-inset { padding: 0 4.6% }, .kb-row-keys { flex: 7.4; margin: 0 4% }
    const val INSET = 0.046f
    const val ROW_KEYS_MARGIN = 0.04f
    // flex-grow of .kb-key, .kb-fn, .kb-row-keys, .kb-layer, .kb-space, .kb-submit
    const val GROW_KEY = 1f
    const val GROW_FN = 1.4f
    const val GROW_ROW_KEYS = 7.4f
    const val GROW_LAYER = 2f
    const val GROW_SPACE = 6f
    const val GROW_SUBMIT = 3f

    /** The keyboard's height: padding, the digit row, four rows of keys and the gaps between them. */
    const val HEIGHT = PAD_TOP + DIGIT_HEIGHT + 4 * KEY_HEIGHT + 4 * ROW_GAP + PAD_BOTTOM

    /**
     * Flex boxes with `flex-basis: 0`: the free length after the gaps (and [margins]) is shared by the grow factors.
     * Returns the left edge and width of each item, from [left].
     */
    fun flex(left: Float, width: Float, grows: FloatArray, gap: Float = KEY_GAP, margins: FloatArray? = null): FloatArray {
        val n = grows.size
        var free = width - gap * (n - 1)
        if (margins != null) for (m in margins) free -= m
        val total = grows.sum()
        val out = FloatArray(n * 2)
        var x = left
        for (i in 0 until n) {
            if (margins != null) x += margins[2 * i]
            val w = free * grows[i] / total
            out[2 * i] = x
            out[2 * i + 1] = w
            x += w + gap
            if (margins != null) x += margins[2 * i + 1]
        }
        return out
    }

    /**
     * Every key of [layer] on a keyboard [width] wide, top to bottom, left to right. [symbols] false drops the
     * layer key (the car's name: letters, digits and space only).
     */
    fun keys(layer: Layer, symbols: Boolean, width: Float): List<Key> {
        val out = ArrayList<Key>(48)
        val left = PAD_SIDE
        val inner = width - 2 * PAD_SIDE
        val rows = if (layer == Layer.LETTERS) LETTERS else SYMBOLS
        var top = PAD_TOP

        fun chars(text: String, x: Float, w: Float, y: Float, h: Float, digit: Boolean) {
            val boxes = flex(x, w, FloatArray(text.length) { GROW_KEY })
            for (i in text.indices) {
                val c = text[i].toString()
                val up = if (layer == Layer.LETTERS && !digit) c.uppercase() else c
                out += Key(KeyType.CHAR, c, up, digit, boxes[2 * i], y, boxes[2 * i + 1], h)
            }
        }

        chars(DIGITS, left, inner, top, DIGIT_HEIGHT, true)
        top += DIGIT_HEIGHT + ROW_GAP
        chars(rows[0], left, inner, top, KEY_HEIGHT, false)
        top += KEY_HEIGHT + ROW_GAP
        val inset = if (layer == Layer.LETTERS) inner * INSET else 0f
        chars(rows[1], left + inset, inner - 2 * inset, top, KEY_HEIGHT, false)
        top += KEY_HEIGHT + ROW_GAP

        // Shift (or its empty slot on the symbols layer), the third row's keys, backspace; the symbols' third row has
        // wide keys (`is-wide`): the side keys stay, the keys take the margins too, the whole width between them
        val m = inner * ROW_KEYS_MARGIN
        val third = flex(left, inner, floatArrayOf(GROW_FN, GROW_ROW_KEYS, GROW_FN), margins = floatArrayOf(0f, 0f, m, m, 0f, 0f))
        out += Key(if (layer == Layer.LETTERS) KeyType.SHIFT else KeyType.SPACER, "", "", false, third[0], top, third[1], KEY_HEIGHT)
        val wide = if (layer == Layer.LETTERS) 0f else m
        chars(rows[2], third[2] - wide, third[3] + 2 * wide, top, KEY_HEIGHT, false)
        out += Key(KeyType.BACKSPACE, "", "", false, third[4], top, third[5], KEY_HEIGHT)
        top += KEY_HEIGHT + ROW_GAP

        val layerLabel = if (layer == Layer.LETTERS) "#+=" else "ABC"
        if (symbols) {
            val b = flex(left, inner, floatArrayOf(GROW_LAYER, GROW_SPACE, GROW_SUBMIT))
            out += Key(KeyType.LAYER, layerLabel, layerLabel, false, b[0], top, b[1], KEY_HEIGHT)
            out += Key(KeyType.SPACE, "space", "space", false, b[2], top, b[3], KEY_HEIGHT)
            out += Key(KeyType.SUBMIT, "", "", false, b[4], top, b[5], KEY_HEIGHT)
        } else {
            val b = flex(left, inner, floatArrayOf(GROW_SPACE, GROW_SUBMIT))
            out += Key(KeyType.SPACE, "space", "space", false, b[0], top, b[1], KEY_HEIGHT)
            out += Key(KeyType.SUBMIT, "", "", false, b[2], top, b[3], KEY_HEIGHT)
        }
        return out
    }
}

enum class Shift { OFF, ONCE, LOCK }

/** The layer and Shift, as Keyboard.tsx keeps them. */
class KeyboardState {
    var layer = Layer.LETTERS
        private set
    var shift = Shift.OFF
        private set
    private var lastShift = Long.MIN_VALUE / 2

    /** The text a character key types now; a single Shift is spent by it. */
    fun type(key: Key): String {
        if (key.digit) return key.lower
        val text = if (layer == Layer.LETTERS && shift != Shift.OFF) key.upper else key.lower
        if (shift == Shift.ONCE) shift = Shift.OFF
        return text
    }

    /** The label a character key shows now. */
    fun shown(key: Key) = if (layer == Layer.LETTERS && shift != Shift.OFF) key.upper else key.lower

    /** Two Shift taps closer than [CAPS_LOCK_MS] lock capitals on; a tap on a lock turns it off. */
    fun pressShift(nowMs: Long) {
        shift = when {
            shift == Shift.LOCK -> Shift.OFF
            shift == Shift.ONCE && nowMs - lastShift < CAPS_LOCK_MS -> Shift.LOCK
            shift == Shift.OFF -> Shift.ONCE
            else -> Shift.OFF
        }
        lastShift = nowMs
    }

    fun toggleLayer() {
        layer = if (layer == Layer.LETTERS) Layer.SYMBOLS else Layer.LETTERS
    }

    companion object {
        const val CAPS_LOCK_MS = 350L
    }
}

/** A held backspace repeats as controls.tsx `holdToRepeat` does: once on press, again after 270 ms, then every 210 ms. */
object HoldRepeat {
    const val FIRST_REPEAT_MS = 270L
    const val REPEAT_MS = 210L

    /** The delay before the next send, after [sent] sends (the first one on press). */
    fun delayAfter(sent: Int) = if (sent <= 1) FIRST_REPEAT_MS else REPEAT_MS

    /** The times, from the press, at which a key held for [heldMs] sends. */
    fun schedule(heldMs: Long): List<Long> {
        val out = mutableListOf(0L)
        var t = 0L
        while (true) {
            t += delayAfter(out.size)
            if (t >= heldMs) return out
            out += t
        }
    }
}

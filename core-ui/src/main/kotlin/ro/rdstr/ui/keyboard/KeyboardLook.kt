package ro.rdstr.ui.keyboard

import ro.rdstr.ui.tokens.Glassy
import ro.rdstr.ui.tokens.Sf

/**
 * How one key looks: the cascade of the `.kb-*` rules (music.css), the glassy material (glassy.css) and the setup's
 * amber submit key (screen.css `.sf .kb-submit`) resolved per theme, design and key state, as the emulator computes
 * it. The `.kb-*` colours are literals in the CSS, not custom properties, so they are repeated here.
 */
class KeyLook(
    val fill: Int,
    /** the glassy sheen gradient over the fill */
    val sheen: Boolean,
    /** the glassy inset edge, rim and floor */
    val glass: Boolean,
    /** `box-shadow: 0 1px 0 <colour>` under the key, 0 for none */
    val hard: Int,
    val ink: Int,
    /** `opacity` of the whole key */
    val alpha: Float,
)

object KeyboardLook {
    // music.css .keyboard / .kb-* (dark), .screen.light .kb-* (light)
    const val PANEL_DARK = 0xFF1D1D20.toInt()
    const val PANEL_LIGHT = 0xFFD2D5DB.toInt()
    const val KEY_DARK = 0xFF505055.toInt()
    const val KEY_DOWN_DARK = 0xFF6A6A70.toInt()
    const val FN_DARK = 0xFF323236.toInt()
    const val FN_LIGHT = 0xFFABB1BB.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
    const val INK_ON = 0xFF111111.toInt()
    const val SUBMIT_DARK = 0xFF0A84FF.toInt()
    const val SUBMIT_LIGHT = 0xFF007AFF.toInt()
    const val SUBMIT_OFF_INK_DARK = 0x66FFFFFF
    const val SUBMIT_OFF_INK_LIGHT = 0x59000000
    const val HARD_DARK = 0x80000000.toInt()
    const val HARD_LIGHT = 0x4D000000
    /** `.sf .kb-submit:disabled { opacity: 0.4 }` */
    const val AMBER_OFF_ALPHA = 0.4f

    // caps lock: `.kb-fn.is-locked::after`, a 26 x 3 bar (radius 2, the key's ink) 12 px above the key's bottom edge
    const val LOCK_BAR_WIDTH = 26f
    const val LOCK_BAR_HEIGHT = 3f
    const val LOCK_BAR_RADIUS = 2f
    const val LOCK_BAR_BOTTOM = 12f

    // the pressed key's bubble: .kb-key.is-down[data-char]::after
    const val BUBBLE_SIZE = 72f
    const val BUBBLE_GAP = 6f
    const val BUBBLE_RADIUS = 14f
    const val BUBBLE_TEXT = 36f
    const val BUBBLE_SHADOW_Y = 6f
    const val BUBBLE_SHADOW_BLUR = 16f
    const val BUBBLE_SHADOW = 0x66000000

    // .kb-key { border-radius: 10px; font-size: 26px }, .kb-row-digits .kb-key { font-size: 24px },
    // .kb-fn { font-size: 18px; font-weight: 500 }, .kb-space { font-size: 18px }, .kb-fn .icon { 26px }
    const val RADIUS = 10f
    const val TEXT = 26f
    const val TEXT_DIGIT = 24f
    const val TEXT_FN = 18f
    const val ICON = 26f

    /** The panel behind the keys: fill, with the sheen and the inset rims on the glassy design. */
    fun panel(light: Boolean, glassy: Boolean): KeyLook = when {
        glassy && !light -> KeyLook(Glassy.of(false).glassyFill, sheen = true, glass = true, hard = 0, ink = WHITE, alpha = 1f)
        glassy -> KeyLook(PANEL_LIGHT, sheen = false, glass = true, hard = 0, ink = INK_ON, alpha = 1f)
        else -> KeyLook(if (light) PANEL_LIGHT else PANEL_DARK, sheen = false, glass = false, hard = 0, ink = WHITE, alpha = 1f)
    }

    /**
     * [shiftOn]: Shift is on or locked. [enabled]: the submit key can send. [amber]: the keyboard sits in the setup,
     * whose submit key is amber in both themes (`.screen.light .sf .kb-submit` outranks `.screen.light .kb-submit`).
     * By day a pressed key takes the function keys' grey and a pressed function key turns white (`.kb-fn:active`).
     */
    fun key(type: KeyType, pressed: Boolean, shiftOn: Boolean, enabled: Boolean, light: Boolean, glassy: Boolean, amber: Boolean): KeyLook {
        val fn = type == KeyType.SHIFT || type == KeyType.BACKSPACE || type == KeyType.LAYER || type == KeyType.SUBMIT || type == KeyType.SPACER
        if (light) {
            val hard = HARD_LIGHT
            return when {
                type == KeyType.SUBMIT && enabled && amber -> Sf.of(true, glassy).let { sf ->
                    KeyLook(sf.amber, sheen = false, glass = glassy, hard = if (glassy) 0 else hard, ink = sf.amberInk, alpha = 1f)
                }
                type == KeyType.SUBMIT && enabled -> KeyLook(SUBMIT_LIGHT, sheen = false, glass = glassy, hard = if (glassy) 0 else hard, ink = WHITE, alpha = 1f)
                type == KeyType.SUBMIT -> KeyLook(FN_LIGHT, sheen = glassy, glass = glassy, hard = if (glassy) 0 else hard, ink = SUBMIT_OFF_INK_LIGHT, alpha = if (amber) AMBER_OFF_ALPHA else 1f)
                type == KeyType.SHIFT && shiftOn -> KeyLook(WHITE, sheen = false, glass = false, hard = hard, ink = INK_ON, alpha = 1f)
                pressed -> KeyLook(if (fn) WHITE else FN_LIGHT, sheen = false, glass = false, hard = hard, ink = INK_ON, alpha = 1f)
                !fn -> KeyLook(WHITE, sheen = false, glass = false, hard = hard, ink = INK_ON, alpha = 1f)
                else -> KeyLook(FN_LIGHT, sheen = false, glass = false, hard = hard, ink = INK_ON, alpha = 1f)
            }
        }
        // dark: on glassy every key takes the glass edge in place of the hard shadow
        val hard = if (glassy) 0 else HARD_DARK
        val glassFill = Glassy.of(false).glassyFill
        return when {
            type == KeyType.SUBMIT && enabled -> {
                val sf = Sf.of(false, glassy)
                KeyLook(if (amber) sf.amber else SUBMIT_DARK, sheen = glassy, glass = glassy, hard = hard, ink = if (amber) sf.amberInk else WHITE, alpha = 1f)
            }
            type == KeyType.SUBMIT -> KeyLook(if (glassy) glassFill else FN_DARK, sheen = glassy, glass = glassy, hard = hard, ink = SUBMIT_OFF_INK_DARK, alpha = if (amber) AMBER_OFF_ALPHA else 1f)
            type == KeyType.SHIFT && shiftOn -> KeyLook(WHITE, sheen = false, glass = glassy, hard = hard, ink = INK_ON, alpha = 1f)
            pressed -> KeyLook(KEY_DOWN_DARK, sheen = false, glass = glassy, hard = hard, ink = WHITE, alpha = 1f)
            glassy -> KeyLook(glassFill, sheen = true, glass = true, hard = 0, ink = WHITE, alpha = 1f)
            else -> KeyLook(if (fn) FN_DARK else KEY_DARK, sheen = false, glass = false, hard = hard, ink = WHITE, alpha = 1f)
        }
    }

    /** The pressed key's bubble: the pressed key's colour, its label at 36 px. */
    fun bubbleFill(light: Boolean) = if (light) WHITE else KEY_DOWN_DARK
    fun bubbleInk(light: Boolean) = if (light) INK_ON else WHITE
}

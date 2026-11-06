package ro.rdstr.ui.phone

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import ro.rdstr.ui.kit.ColorTransition
import ro.rdstr.ui.kit.CssBox
import ro.rdstr.ui.kit.CssColor
import ro.rdstr.ui.kit.CssFilter
import ro.rdstr.ui.kit.Easing
import ro.rdstr.ui.kit.IconView
import ro.rdstr.ui.kit.Motion
import ro.rdstr.ui.kit.TextStyle
import ro.rdstr.ui.kit.UiTheme
import ro.rdstr.ui.tokens.Base
import ro.rdstr.ui.tokens.Glassy
import ro.rdstr.ui.tokens.Gradient
import ro.rdstr.ui.tokens.Icons
import ro.rdstr.ui.tokens.Immersive
import ro.rdstr.ui.tokens.Shadow
import ro.rdstr.ui.uiKey

/**
 * The colours a phone surface draws with: the shell's theme, or the call screen's forced dark ([immersive], `.call`
 * in tokens.css). Read it in an `onTheme` block: it is the theme of that moment.
 */
class Ink(immersive: Boolean) {
    private val b: Base = UiTheme.base
    private val im: Immersive? = if (immersive) Immersive.of(UiTheme.light, UiTheme.glassy) else null
    private val g: Glassy = if (immersive) Glassy.GLASSY_DARK else UiTheme.glass
    val glassy = UiTheme.glassy
    val on = im?.on ?: b.on
    val ink = im?.ink ?: b.ink
    val ink2 = im?.phoneInk2 ?: b.phoneInk2
    val key = im?.phoneKey ?: b.phoneKey
    val keyDown = im?.phoneKeyDown ?: b.phoneKeyDown
    val ground = im?.phoneGround ?: b.phoneGround
    val panelRaised = im?.panelRaised ?: b.panelRaised
    val glassFill = im?.glassyFill ?: g.glassyFill
    val glassFillStrong = im?.glassyFillStrong ?: g.glassyFillStrong
    val sheen: Gradient = im?.glassySheen ?: g.glassySheen
    val glassShadow: List<Shadow> = im?.glassyShadow ?: g.glassyShadow
    val frost = g.glassyFrost
    val green = b.callGreen
    val red = b.callRed

    /** `rgb(var(--on) / [a])`. */
    fun on(a: Float) = CssColor.withAlpha(on, a)

    /** What the glass's frost makes of a flat [ground] under it: a blurred flat colour is the colour, saturated and brightened. */
    fun frosted(ground: Int): Int = CssFilter.apply(CssFilter.saturateBrightness(frost.saturate, frost.brightness), ground)

    /**
     * The Glassy base (glassy.css `:where(…)`) on [box] over a flat [ground] (the app's own, opaque): the frost of
     * that ground, the fill, the sheen, the glass rim and drop. Inside an opaque app the frost needs no snapshot.
     */
    fun glass(box: Box, ground: Int, fill: Int = glassFill, pressed: Int = 0) {
        val frost = frosted(ground)
        box.css.gradient = sheen; box.css.shadows = glassShadow; box.css.frost = null
        box.fillWith(CssColor.over(fill, frost), if (pressed != 0) CssColor.over(pressed, frost) else 0)
    }

    /** The Glassy base over what is behind the screen's layers (a banner over home): the snapshot frost. */
    fun glassOverScreen(box: Box) {
        box.css.gradient = sheen; box.css.shadows = glassShadow; box.css.frost = frost
        box.fillWith(glassFill)
    }

    /** Tinted glass (glassy.css `:is(…)`): [color] keeps, the sheen, rim and drop over it. */
    fun tinted(box: Box, color: Int, pressed: Int = 0) {
        box.css.gradient = sheen; box.css.shadows = glassShadow; box.css.frost = null
        box.fillWith(color, pressed)
    }

    /** Solid: [color] alone. */
    fun solid(box: Box, color: Int, pressed: Int = 0) {
        box.css.gradient = null; box.css.shadows = emptyList(); box.css.frost = null
        box.fillWith(color, pressed)
    }

    companion object {
        /** `--phone-avatar: linear-gradient(180deg, #8e8e93, #5d5d62)` */
        val AVATAR = Gradient(180f, intArrayOf(0xFF8E8E93.toInt(), 0xFF5D5D62.toInt()), floatArrayOf(0f, 1f))
        const val BLUE = 0xFF0A84FF.toInt()
        /** `rgba(118, 118, 128, 0.24)`: the tabs' and search field's fill. */
        const val FIELD = 0x3D767680
    }
}

/** An `.icon` of the emulator (`span.icon.[extra].[cls]`), [size] canvas px, in [IconView.color]. */
fun icon(context: Context, cls: String, size: Float, extra: String? = null) =
    IconView(context, Icons.byClass.getValue(cls), size).apply { uiKey = listOfNotNull("icon", extra, cls).joinToString(".") }

/** A view that only names the element another view draws (an icon a kit button paints itself), for the parity keys. */
class Mark(context: Context, key: String) : View(context) {
    init { uiKey = key; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
}

/** A text of the given style; colour set with [Txt.color]. */
fun txt(context: Context, size: Float, weight: Int = 400, key: String? = null, text: CharSequence = "", tracking: Float = 0f, lineHeight: Float = 0f) =
    Txt(context, TextStyle(size, weight, tracking, lineHeight), key, text)

/** phone.css `.phone-empty`: a note where a list would be (18 px, line-height 1.5, at most 32ch, 48 px down). */
fun phoneEmpty(context: Context, text: String, immersive: Boolean) = txt(context, 18f, 400, "phone-empty", text, lineHeight = 1.5f).apply {
    multi = true
    lp.ml = 16f; lp.mt = 48f; lp.mr = 16f
    lp.maxW = 32 * paint.measureText("0") / resources.displayMetrics.density
    lp.self = Align.START
    UiTheme.link(this) { color = Ink(immersive).ink2 }
}

/** A pressable box: [onTap] on a tap. */
fun Box.tappable(onTap: () -> Unit): Box { isClickable = true; setOnClickListener { onTap() }; return this }

/**
 * phone.css `.call-key`: the round green (accept) or red (decline) key, the handset filling it; pressed it shrinks to
 * 0.94 and brightens by 1.12 (120 ms, back in 300 ms on `--spring-glyph`); disabled at 0.4. Tinted glass on Glassy.
 */
@SuppressLint("ViewConstructor")
class CallKey(context: Context, val accept: Boolean, size: Float, key: String? = "call-key", immersive: Boolean = false) : Box(context, key) {
    val glyph = add(icon(context, if (accept) "i-call-accept" else "i-call-decline", size)) { w = size; h = size }

    init {
        dir = Dir.STACK; align = Align.CENTER; justify = Align.CENTER
        lp.w = size; lp.h = size
        css.radius = 999f
        pressScale = 0.94f
        onTheme = {
            val ink = Ink(immersive)
            val c = if (accept) ink.green else ink.red
            val lit = CssFilter.apply(CssFilter.saturateBrightness(1f, 1.12f), c)
            if (ink.glassy) ink.tinted(this, c, lit) else ink.solid(this, c, lit)
            glyph.color = -1
        }
    }

    override fun setEnabled(enabled: Boolean) { super.setEnabled(enabled); alpha = if (enabled) 1f else 0.4f }
}

/**
 * phone.css `.pad-key`: a dial pad key, the digit (36 px, 30 compact) over its letters (11 px, 700, 0.14em). With
 * [plus], holding 0 for 550 ms types "+" and the release types nothing (the dialer only: tones have no "+").
 */
@SuppressLint("ViewConstructor")
class PadKey(context: Context, digit: String, letters: String, compact: Boolean, private val plus: Boolean, immersive: Boolean, onPress: (String) -> Unit) : Box(context, "pad-key") {
    private val digitTxt = add(txt(context, if (compact) 30f else 36f, 400, "pad-digit", digit, lineHeight = 1f))
    private val lettersTxt = add(txt(context, 11f, 700, "pad-letters", letters, tracking = 0.14f)) { minH = 13f; mt = 2f }
    private val handler = Handler(Looper.getMainLooper())
    private var held = false
    private val hold = Runnable { held = true; onPress("+") }

    init {
        dir = Dir.COLUMN; align = Align.CENTER; justify = Align.CENTER
        val s = if (compact) 72f else 84f
        lp.w = s; lp.h = s
        css.radius = 999f
        contentDescription = if (letters.isNotEmpty() && digit != "0") "$digit, $letters" else digit
        isClickable = true
        setOnClickListener { if (held) held = false else onPress(digit) }
        onTheme = {
            val ink = Ink(immersive)
            if (ink.glassy) ink.glass(this, ink.ground, ink.glassFill, ink.glassFillStrong) else ink.solid(this, ink.key, ink.keyDown)
            digitTxt.color = ink.ink
            lettersTxt.color = ink.on(0.72f)
        }
        pressOutMs = 260
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (plus && digit() == "0") when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { held = false; handler.postDelayed(hold, PLUS_HOLD_MS) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(hold)
            MotionEvent.ACTION_MOVE -> if (e.x < 0 || e.y < 0 || e.x > width || e.y > height) handler.removeCallbacks(hold)
        }
        return super.onTouchEvent(e)
    }

    private fun digit() = digitTxt.text.toString()

    companion object {
        /** PhoneApp.tsx PLUS_HOLD_MS */
        const val PLUS_HOLD_MS = 550L
        val KEYS = listOf("1" to "", "2" to "ABC", "3" to "DEF", "4" to "GHI", "5" to "JKL", "6" to "MNO", "7" to "PQRS", "8" to "TUV", "9" to "WXYZ", "*" to "", "0" to "+", "#" to "")
        /** Overlays.tsx TONE_KEYS: the keypad's, with nothing under 0. */
        val TONE_KEYS = KEYS.map { if (it.first == "0") "0" to "" else it }
    }
}

/** `.pad-grid`: the twelve keys three across (84 px, gaps 14/26; compact 72 px, 10/24). */
fun padGrid(context: Context, compact: Boolean, tones: Boolean, plus: Boolean, immersive: Boolean, onPress: (String) -> Unit) = Box(context, "pad-grid").apply {
    dir = Dir.GRID
    val s = if (compact) 72f else 84f
    tracks = floatArrayOf(s, s, s)
    gap = if (compact) 24f else 26f; gap2 = if (compact) 10f else 14f
    for ((d, l) in if (!tones) PadKey.KEYS else PadKey.TONE_KEYS) add(PadKey(context, d, l, compact, plus, immersive, onPress))
}

/**
 * phone.css `.phone-avatar` / `.call-avatar`: initials on the grey gradient, or a person (people for a conference).
 * On Glassy the sheen and rim go over the gradient, which stays.
 */
@SuppressLint("ViewConstructor")
class Avatar(context: Context, key: String, private val size: Float, fontSize: Float, iconSize: Float, tracking: Float, private val immersive: Boolean) : Box(context, key) {
    private val initials = add(txt(context, fontSize, 600, null, tracking = tracking))
    private val person = add(icon(context, "i-user", iconSize)) { w = iconSize; h = iconSize }
    private val people = add(icon(context, "i-users-fill", iconSize)) { w = iconSize; h = iconSize }
    /** The text colour the avatar inherits (`--ink`, or white on the call screen). */
    var inkColor: Int? = null

    init {
        dir = Dir.STACK; align = Align.CENTER; justify = Align.CENTER
        lp.w = size; lp.h = size
        css.radius = 999f
        css.gradient = Ink.AVATAR
        painted = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        onTheme = {
            val ink = Ink(immersive)
            val c = inkColor ?: ink.ink
            initials.color = c; person.color = c; people.color = c
            css.shadows = if (ink.glassy) ink.glassShadow else emptyList()
            sheen = if (ink.glassy) (sheen ?: CssBox().apply { radius = 999f }).also { it.gradient = ink.sheen } else null
        }
        set("", false)
    }

    /** [letters] (core-car PhoneBook.initials), else a person, or people for a conference. */
    fun set(letters: String, conference: Boolean) {
        initials.text = letters
        initials.visibility = if (letters.isNotEmpty()) VISIBLE else GONE
        person.visibility = if (letters.isEmpty() && !conference) VISIBLE else GONE
        people.visibility = if (letters.isEmpty() && conference) VISIBLE else GONE
        uiKeyText = letters
    }

    /** The initials as the element's own text, for the parity check. */
    var uiKeyText: String = ""
        private set
}

/**
 * phone.css `.call-pill`: a pill button (56 px high, 17 px 600); pressed it shrinks to 0.96 and darkens. Green or red
 * for [Kind.ACCEPT] / [Kind.DECLINE]. On Glassy the glass base, the coloured ones keep their colour under its rim.
 */
@SuppressLint("ViewConstructor")
class Pill(context: Context, label: String, kind: Kind = Kind.PLAIN, ground: () -> Int, immersive: Boolean, onTap: () -> Unit) : Box(context, "call-pill") {
    enum class Kind { PLAIN, ACCEPT, DECLINE }
    val labelTxt = add(txt(context, 17f, 600, null, label))

    init {
        dir = Dir.STACK; align = Align.CENTER; justify = Align.CENTER
        lp.minH = 56f
        padL = 20f; padR = 20f
        css.radius = 999f
        pressScale = 0.96f
        contentDescription = label
        tappable(onTap)
        onTheme = {
            val ink = Ink(immersive)
            labelTxt.color = ink.ink
            when {
                kind == Kind.ACCEPT || kind == Kind.DECLINE -> {
                    val c = if (kind == Kind.ACCEPT) ink.green else ink.red
                    ink.solid(this, c)
                    if (ink.glassy) css.shadows = ink.glassShadow
                }
                ink.glassy -> ink.glass(this, ground(), ink.glassFill, ink.on(0.26f))
                else -> ink.solid(this, ink.on(0.16f), ink.on(0.26f))
            }
        }
    }
}

/** A colour eased between two values over `--fast` (a text whose colour follows a state). */
internal class Eased(initial: Int) {
    private val t = ColorTransition(initial)
    fun to(c: Int, ms: Long = UiTheme.base.fast) = t.to(c, Motion.now(), UiTheme.duration(ms), Motion.seamless)
    fun snap(c: Int) = t.snap(c)
    fun now() = t.value(Motion.now())
    fun running() = t.running(Motion.now())
    val target get() = t.target
}


package ro.rdstr.ui.keyboard

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import ro.rdstr.ui.Mirrors
import ro.rdstr.ui.tokens.Base
import ro.rdstr.ui.tokens.Fonts
import ro.rdstr.ui.tokens.Glassy
import ro.rdstr.ui.tokens.Gradient
import ro.rdstr.ui.tokens.Icons
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Our own on-screen keyboard (emulator/components/Keyboard.tsx): letters with Shift and caps lock, the digit row on
 * both layers, symbols, a held backspace that repeats, and a submit key that names the action. It rises from the
 * bottom edge across the whole width and types into whatever field the screen shows above it.
 *
 * Place it at the bottom of a full-width parent; it measures [KeyboardLayout.HEIGHT] plus a strip above for the
 * pressed key's bubble, and touches on that strip go through to the views below.
 */
@Mirrors("Keyboard.tsx")
class KeyboardView(context: Context) : View(context) {
    var onKey: (Char) -> Unit = {}
    var onBackspace: () -> Unit = {}
    var onSubmit: () -> Unit = {}
    var submitLabel: String = ""
        set(value) { field = value; invalidate() }
    var submitEnabled: Boolean = true
        set(value) { if (field != value) { field = value; restyle() } }
    /** false: letters, digits and space only, no symbols layer (the car's name). */
    var symbols: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            if (!value && state.layer == Layer.SYMBOLS) state.toggleLayer()
            rebuild()
        }
    /** The setup's amber submit key (`.sf .kb-submit`). */
    var amber: Boolean = false
        set(value) { if (field != value) { field = value; restyle() } }
    var open: Boolean = false
        set(value) { if (field != value) { field = value; slide() } }

    private val density = resources.displayMetrics.density
    private fun px(dp: Float) = dp * density

    /** The bubble's reach above the keyboard: over the digit row, with its shadow's blur. */
    private val headroom = px(KeyboardLook.BUBBLE_SIZE + KeyboardLook.BUBBLE_GAP - KeyboardLayout.PAD_TOP +
        KeyboardLook.BUBBLE_SHADOW_BLUR - KeyboardLook.BUBBLE_SHADOW_Y)
    /** The keyboard's own height, for content that has to stay above it. */
    val keyboardHeight = px(KeyboardLayout.HEIGHT)

    private var light = false
    private var glassy = false
    private var glass: Glassy = Glassy.of(false)
    private val state = KeyboardState()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = px(1f) }
    private val textKey = text(KeyboardLook.TEXT, 400)
    private val textDigit = text(KeyboardLook.TEXT_DIGIT, 400)
    private val textSpace = text(KeyboardLook.TEXT_FN, 400)
    private val textFn = text(KeyboardLook.TEXT_FN, 500)
    private val textBubble = text(KeyboardLook.BUBBLE_TEXT, 400)
    private val texts = arrayOf(textKey, textDigit, textSpace, textFn, textBubble)
    /** Per text paint: the baseline below the box's centre, so the label sits where CSS centres its line box. */
    private val baselines = FloatArray(texts.size)
    private val shadowPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { color = KeyboardLook.BUBBLE_SHADOW }
    private val shiftIcon: Drawable = context.getDrawable(Icons.ARROW_FAT_UP)!!.mutate()
    private val backspaceIcon: Drawable = context.getDrawable(Icons.BACKSPACE)!!.mutate()
    private var shiftTint = 0
    private var backspaceTint = 0

    /** One drawn box: a key or the panel, in px, with its glassy sheen, rim and floor prepared. */
    private class Box(val rect: RectF, val radius: Float) {
        var sheen: Shader? = null
        val rim = Path()
        val floor = Path()
        var look: KeyLook? = null
    }
    private var keys: List<Key> = emptyList()
    private var boxes: Array<Box> = emptyArray()
    private var panel = Box(RectF(), 0f)
    private val bubble = RectF()
    private var bubbleShadow: Bitmap? = null
    private var bubbleShadowInset = 0f

    private var pressed = -1
    private val handler = Handler(Looper.getMainLooper())
    private var sent = 0
    private val repeat = object : Runnable {
        override fun run() {
            onBackspace()
            sent++
            handler.postDelayed(this, HoldRepeat.delayAfter(sent))
        }
    }
    private var slider: ValueAnimator? = null
    private val sheetIn = Base.of(false, false).sheetIn.let { PathInterpolator(it.x1, it.y1, it.x2, it.y2) }

    init {
        contentDescription = "Keyboard"
        isFocusable = false
        visibility = INVISIBLE
        translationY = keyboardHeight
        setFont("Inter")
    }

    /** The screen's theme and design; called again whenever either changes. */
    fun setTheme(light: Boolean, glassy: Boolean) {
        if (light == this.light && glassy == this.glassy && boxes.isNotEmpty()) return
        this.light = light
        this.glassy = glassy
        rebuild()
    }

    /** The screen's font family by its CSS name ("Inter", "Geist"). */
    fun setFont(family: String) {
        val res = Fonts.byFamily.entries.firstOrNull { it.key.equals(family, ignoreCase = true) }?.value ?: Fonts.INTER
        val face: Typeface = resources.getFont(res)
        for ((i, p) in texts.withIndex()) {
            // the weight is a variation of the typeface, so it is set again over the new one
            p.typeface = face
            p.fontVariationSettings = "'wght' ${if (p === textFn) 500 else 400}"
            val fm = p.fontMetrics
            baselines[i] = -(fm.ascent + fm.descent) / 2f
        }
        invalidate()
    }

    private fun text(size: Float, weight: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = px(size)
        textAlign = Paint.Align.CENTER
        fontVariationSettings = "'wght' $weight"
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (keyboardHeight + headroom + 0.5f).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = rebuild()

    /** Key boxes, sheens and rims for the current size, layer, variant and theme. */
    private fun rebuild() {
        glass = Glassy.of(light)
        if (width == 0) return
        val widthDp = width / density
        keys = KeyboardLayout.keys(state.layer, symbols, widthDp)
        boxes = Array(keys.size) { i ->
            val k = keys[i]
            box(RectF(px(k.left), px(k.top), px(k.right), px(k.bottom)), px(KeyboardLook.RADIUS), glass.glassySheen)
        }
        panel = box(RectF(0f, 0f, width.toFloat(), keyboardHeight), 0f, glass.glassySheen)
        if (bubbleShadow == null) bubbleShadow = bubbleShadow()
        restyle()
    }

    private fun box(rect: RectF, radius: Float, gradient: Gradient): Box {
        val b = Box(rect, radius)
        b.sheen = cssGradient(rect, gradient)
        val shape = Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) }
        // an inset shadow offset by y shows where the box is not covered by itself moved by y
        b.rim.set(shape); b.rim.op(Path().apply { addRoundRect(rect.left, rect.top + px(1.5f), rect.right, rect.bottom + px(1.5f), radius, radius, Path.Direction.CW) }, Path.Op.DIFFERENCE)
        b.floor.set(shape); b.floor.op(Path().apply { addRoundRect(rect.left, rect.top - px(1f), rect.right, rect.bottom - px(1f), radius, radius, Path.Direction.CW) }, Path.Op.DIFFERENCE)
        return b
    }

    /** `linear-gradient(<angle>, …)` over [r]: the gradient line through the centre, as long as CSS makes it. */
    private fun cssGradient(r: RectF, g: Gradient): Shader {
        val a = Math.toRadians(g.angle.toDouble())
        val dx = sin(a).toFloat()
        val dy = -cos(a).toFloat()
        val half = (abs(r.width() * dx) + abs(r.height() * dy)) / 2f
        return LinearGradient(r.centerX() - dx * half, r.centerY() - dy * half, r.centerX() + dx * half, r.centerY() + dy * half,
            g.colors, g.at, Shader.TileMode.CLAMP)
    }

    /** The bubble's `0 6px 16px` shadow, blurred once into an alpha mask. */
    private fun bubbleShadow(): Bitmap {
        val sigma = px(KeyboardLook.BUBBLE_SHADOW_BLUR) / 2f
        bubbleShadowInset = 3f * sigma
        val size = px(KeyboardLook.BUBBLE_SIZE)
        val side = (size + 2 * bubbleShadowInset + 0.5f).toInt()
        val bmp = Bitmap.createBitmap(side, side, Bitmap.Config.ALPHA_8)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            // Skia turns a blur radius into sigma as 0.57735 r + 0.5
            maskFilter = BlurMaskFilter(((sigma - 0.5f) / 0.57735f).coerceAtLeast(0.1f), BlurMaskFilter.Blur.NORMAL)
        }
        val r = px(KeyboardLook.BUBBLE_RADIUS)
        Canvas(bmp).drawRoundRect(bubbleShadowInset, bubbleShadowInset, bubbleShadowInset + size, bubbleShadowInset + size, r, r, p)
        return bmp
    }

    /** Each key's look for the current state; called on every press, Shift, layer or theme change, never in onDraw. */
    private fun restyle() {
        val shiftOn = state.shift != Shift.OFF
        panel.look = KeyboardLook.panel(light, glassy)
        for (i in boxes.indices) {
            boxes[i].look = KeyboardLook.key(keys[i].type, i == pressed, shiftOn, submitEnabled, light, glassy, amber)
            val ink = boxes[i].look!!.ink
            if (keys[i].type == KeyType.SHIFT && ink != shiftTint) { shiftTint = ink; shiftIcon.setTint(ink) }
            if (keys[i].type == KeyType.BACKSPACE && ink != backspaceTint) { backspaceTint = ink; backspaceIcon.setTint(ink) }
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (boxes.isEmpty()) return
        canvas.save()
        canvas.translate(0f, headroom)
        draw(canvas, panel)
        for (i in boxes.indices) {
            val k = keys[i]
            if (k.type == KeyType.SPACER) continue
            val b = boxes[i]
            val look = b.look ?: continue
            val layered = look.alpha < 1f
            if (layered) canvas.saveLayerAlpha(b.rect.left, b.rect.top, b.rect.right, b.rect.bottom + px(1f), (look.alpha * 255 + 0.5f).toInt())
            draw(canvas, b)
            when (k.type) {
                KeyType.SHIFT -> {
                    icon(canvas, shiftIcon, b.rect)
                    if (state.shift == Shift.LOCK) lockBar(canvas, b.rect, look.ink)
                }
                KeyType.BACKSPACE -> icon(canvas, backspaceIcon, b.rect)
                KeyType.CHAR -> label(canvas, state.shown(k), b.rect, if (k.digit) 1 else 0, look.ink)
                KeyType.SPACE -> label(canvas, k.lower, b.rect, 2, look.ink)
                KeyType.LAYER -> label(canvas, k.lower, b.rect, 3, look.ink)
                KeyType.SUBMIT -> label(canvas, submitLabel, b.rect, 3, look.ink)
                KeyType.SPACER -> Unit
            }
            if (layered) canvas.restore()
        }
        if (pressed >= 0 && keys[pressed].type == KeyType.CHAR) drawBubble(canvas, keys[pressed], boxes[pressed].rect)
        canvas.restore()
    }

    private fun draw(canvas: Canvas, b: Box) {
        val look = b.look ?: return
        val r = b.radius
        if (look.hard != 0) {
            fill.color = look.hard
            canvas.drawRoundRect(b.rect.left, b.rect.top + px(1f), b.rect.right, b.rect.bottom + px(1f), r, r, fill)
        }
        fill.color = look.fill
        canvas.drawRoundRect(b.rect, r, r, fill)
        if (look.sheen) {
            sheen.shader = b.sheen
            canvas.drawRoundRect(b.rect, r, r, sheen)
        }
        if (look.glass) {
            // box-shadow layers paint the first listed on top: the floor, the rim, then the edge ring
            fill.color = glass.glassyFloor
            canvas.drawPath(b.floor, fill)
            fill.color = glass.glassyRim
            canvas.drawPath(b.rim, fill)
            ring.color = glass.glassyEdge
            val h = ring.strokeWidth / 2f
            canvas.drawRoundRect(b.rect.left + h, b.rect.top + h, b.rect.right - h, b.rect.bottom - h, r - h, r - h, ring)
        }
    }

    private fun label(canvas: Canvas, text: String, r: RectF, paint: Int, ink: Int) {
        val p = texts[paint]
        p.color = ink
        canvas.drawText(text, r.centerX(), r.centerY() + baselines[paint], p)
    }

    private fun icon(canvas: Canvas, icon: Drawable, r: RectF) {
        val half = px(KeyboardLook.ICON) / 2f
        icon.setBounds((r.centerX() - half + 0.5f).toInt(), (r.centerY() - half + 0.5f).toInt(), (r.centerX() + half + 0.5f).toInt(), (r.centerY() + half + 0.5f).toInt())
        icon.draw(canvas)
    }

    /** Caps lock: the bar under the Shift arrow. */
    private fun lockBar(canvas: Canvas, r: RectF, ink: Int) {
        val half = px(KeyboardLook.LOCK_BAR_WIDTH) / 2f
        val bottom = r.bottom - px(KeyboardLook.LOCK_BAR_BOTTOM)
        val rad = px(KeyboardLook.LOCK_BAR_RADIUS)
        fill.color = ink
        canvas.drawRoundRect(r.centerX() - half, bottom - px(KeyboardLook.LOCK_BAR_HEIGHT), r.centerX() + half, bottom, rad, rad, fill)
    }

    private fun drawBubble(canvas: Canvas, k: Key, r: RectF) {
        val size = px(KeyboardLook.BUBBLE_SIZE)
        val bottom = r.top - px(KeyboardLook.BUBBLE_GAP)
        bubble.set(r.centerX() - size / 2f, bottom - size, r.centerX() + size / 2f, bottom)
        bubbleShadow?.let { canvas.drawBitmap(it, bubble.left - bubbleShadowInset, bubble.top - bubbleShadowInset + px(KeyboardLook.BUBBLE_SHADOW_Y), shadowPaint) }
        fill.color = KeyboardLook.bubbleFill(light)
        val rad = px(KeyboardLook.BUBBLE_RADIUS)
        canvas.drawRoundRect(bubble, rad, rad, fill)
        label(canvas, state.shown(k), bubble, 4, KeyboardLook.bubbleInk(light))
    }

    private fun keyAt(x: Float, y: Float): Int {
        val ky = y - headroom
        for (i in boxes.indices) if (keys[i].type != KeyType.SPACER && boxes[i].rect.contains(x, ky)) return i
        return -1
    }

    private fun press(i: Int) {
        if (pressed == i) return
        pressed = i
        restyle()
    }

    private fun stopRepeat() = handler.removeCallbacks(repeat)

    // one finger at a time: the key under it is pressed, leaving the key drops the press, lifting on it acts
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!open || boxes.isEmpty()) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (e.y < headroom) return false
                val i = keyAt(e.x, e.y)
                if (i < 0 || (keys[i].type == KeyType.SUBMIT && !submitEnabled)) return true
                press(i)
                if (keys[i].type == KeyType.BACKSPACE) {
                    stopRepeat()
                    onBackspace()
                    sent = 1
                    handler.postDelayed(repeat, HoldRepeat.delayAfter(sent))
                }
            }
            MotionEvent.ACTION_MOVE -> if (pressed >= 0 && !boxes[pressed].rect.contains(e.x, e.y - headroom)) {
                stopRepeat()
                press(-1)
            }
            MotionEvent.ACTION_UP -> {
                stopRepeat()
                val i = pressed
                if (i >= 0) {
                    press(-1)
                    act(keys[i])
                    performClick()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                stopRepeat()
                press(-1)
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun act(k: Key) {
        when (k.type) {
            KeyType.CHAR -> { onKey(state.type(k)[0]); restyle() }
            KeyType.SHIFT -> { state.pressShift(android.os.SystemClock.uptimeMillis()); restyle() }
            KeyType.LAYER -> { state.toggleLayer(); rebuild() }
            KeyType.SPACE -> onKey(' ')
            KeyType.SUBMIT -> if (submitEnabled) onSubmit()
            KeyType.BACKSPACE, KeyType.SPACER -> Unit
        }
    }

    /**
     * `transform: translateY(100%)` to none in 300 ms on `--sheet-in`, both ways. A reversal midway takes the share
     * of the 300 ms already travelled, as CSS shortens a reversed transition.
     */
    private fun slide() {
        if (!open) { stopRepeat(); pressed = -1; restyle() }
        val from = translationY
        val to = if (open) 0f else keyboardHeight
        slider?.cancel()
        visibility = VISIBLE
        val duration = (SLIDE_MS * abs(to - from) / keyboardHeight).toLong()
        slider = ValueAnimator.ofFloat(from, to).apply {
            this.duration = duration
            interpolator = sheetIn
            addUpdateListener { translationY = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { if (!open && slider === animation) visibility = INVISIBLE }
            })
            start()
        }
    }

    override fun onDetachedFromWindow() {
        stopRepeat()
        slider?.cancel()
        super.onDetachedFromWindow()
    }

    private companion object {
        /** `.keyboard { transition: transform 300ms var(--sheet-in) }` */
        const val SLIDE_MS = 300f
    }
}

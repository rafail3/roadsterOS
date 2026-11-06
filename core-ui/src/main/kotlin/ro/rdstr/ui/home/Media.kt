package ro.rdstr.ui.home

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.widget.LinearLayout
import ro.rdstr.ui.kit.CssBox
import ro.rdstr.ui.kit.CssBezier
import ro.rdstr.ui.kit.CssColor
import ro.rdstr.ui.kit.Easing
import ro.rdstr.ui.kit.GlassBead
import ro.rdstr.ui.kit.IconView
import ro.rdstr.ui.kit.KitView
import ro.rdstr.ui.kit.Motion
import ro.rdstr.ui.kit.TextStyle
import ro.rdstr.ui.kit.Transition
import ro.rdstr.ui.kit.Type
import ro.rdstr.ui.kit.UiTheme
import ro.rdstr.ui.tokens.Icons
import ro.rdstr.ui.uiKey

// The media pieces of the card on home (media.tsx `Marquee`, `Progress`, `TransportRow`) at the card's sizes. The
// music app's views share the same component; these are the card's copy until both use one.

/**
 * media.tsx `Marquee`: one line of text; when it does not fit it scrolls left at 30 px/s after 1.2 s, a second copy a
 * third of the box behind, without end.
 */
@SuppressLint("ViewConstructor")
class Marquee(context: Context, style: TextStyle) : KitView(context) {
    var style = style; set(v) { field = v; onTheme(); requestLayout() }
    var text = ""; set(v) { if (field != v) { field = v; textWidth = paint.measureText(v); started = Motion.now(); requestLayout(); invalidate() } }
    var color = 0; set(v) { if (field != v) { field = v; paint.color = v; invalidate() } }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var textWidth = 0f
    private var started = 0L

    init { onTheme() }

    override fun onTheme() {
        Type.apply(paint, style, context)
        paint.color = color
        textWidth = paint.measureText(text)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val m = paint.fontMetrics
        val h = (m.descent - m.ascent + 0.5f).toInt()
        setMeasuredDimension(resolveSize((textWidth + 0.5f).toInt(), widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val baseline = -paint.fontMetrics.ascent
        if (textWidth <= width) { canvas.drawText(text, 0f, baseline, paint); return }
        val distance = textWidth + width * GAP
        val t = (Motion.now() - started - DELAY_MS) * UiTheme.speed.let { if (it <= 0f) 0f else 1f / it }
        val x = if (t <= 0f) 0f else -((t / 1000f * PX_PER_S) % distance)
        canvas.save()
        canvas.clipRect(0, 0, width, height)
        canvas.drawText(text, x, baseline, paint)
        canvas.drawText(text, x + distance, baseline, paint)
        canvas.restore()
        postInvalidateOnAnimation()
    }

    private companion object {
        const val PX_PER_S = 30f
        const val GAP = 1f / 3f
        const val DELAY_MS = 1200f
    }
}

/**
 * media.tsx `Progress` as `.np-progress`: a 6 px pill of `--hairline-strong` filled with `--ink` to the position
 * (250 ms linear). Glassy: a recessed rail (`rgb(255 255 255 / .14)`, inner shade, lower lit edge), the fill in
 * `--accent` and a 14 px glass bead at the position.
 */
class ProgressLine(context: Context) : KitView(context) {
    private val track = CssBox().apply { radius = 999f }
    private val fill = CssBox().apply { radius = 999f }
    private val bead = GlassBead()
    private val at = Transition(0f)

    init { uiKey = "progress-line.np-progress"; onTheme() }

    fun set(fraction: Float, animate: Boolean = true) {
        val f = fraction.coerceIn(0f, 1f)
        if (animate && isAttachedToWindow) at.to(f, Motion.now(), UiTheme.duration(250), Easing.LINEAR) else at.snap(f)
        invalidate()
    }

    override fun onTheme() {
        val b = UiTheme.base
        if (UiTheme.glassy) {
            track.fill = 0x24FFFFFF
            track.shadows = listOf(ro.rdstr.ui.tokens.Shadow(true, 0f, 1f, 2f, 0f, 0x59000000), ro.rdstr.ui.tokens.Shadow(false, 0f, 0.5f, 0f, 0f, 0x2EFFFFFF))
            fill.fill = b.accent
        } else {
            track.fill = b.hairlineStrong; track.shadows = emptyList()
            fill.fill = b.ink
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension(resolveSize(0, widthMeasureSpec), resolveSize((6 * d + 0.5f).toInt(), heightMeasureSpec))

    override fun onDraw(canvas: Canvas) {
        val now = Motion.now()
        val p = at.value(now)
        val w = width.toFloat(); val h = height.toFloat()
        track.draw(canvas, this, w, h)
        // scaleX of the fill from its left edge, clipped by the rail's round ends
        if (p > 0f) {
            canvas.save()
            canvas.clipRect(0f, 0f, w * p, h)
            fill.draw(canvas, this, w, h)
            canvas.restore()
        }
        if (UiTheme.glassy) bead.draw(canvas, this, w * p, h / 2f, 14f)
        if (at.running(now)) postInvalidateOnAnimation()
    }
}

/** The sizes and colours of one transport row (`.np .tx`, `.np .card-strip .tx`). */
class TransportLook(val size: Float, val glyph: Float, val playSize: Float, val playGlyph: Float, val plate: Float, val playPlate: Float)

/**
 * media.tsx `TransportRow`: previous, play/pause, next. [onPress] gets 0, 1, 2 for previous, play, next.
 * The card's row has 4 px between the keys; the strip's none.
 */
@SuppressLint("ViewConstructor")
class TransportRow(context: Context, look: TransportLook, gap: Float, onPress: (Int) -> Unit) : LinearLayout(context) {
    val prev = TransportButton(context, TransportButton.Kind.PREV, look) { onPress(0) }
    val play = TransportButton(context, TransportButton.Kind.PLAY, look) { onPress(1) }
    val next = TransportButton(context, TransportButton.Kind.NEXT, look) { onPress(2) }

    var playing: Boolean
        get() = play.playing
        set(v) { play.playing = v }

    /** The keys' ink and bloom (`--tx-color`, `--tx-bloom`). */
    fun colors(ink: Int, bloom: Int) { for (b in listOf(prev, play, next)) b.colors(ink, bloom) }

    init {
        uiKey = "transport"
        orientation = HORIZONTAL
        gravity = android.view.Gravity.CENTER
        val g = (gap * resources.displayMetrics.density + 0.5f).toInt()
        addView(prev)
        addView(play, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { leftMargin = g; rightMargin = g })
        addView(next)
    }

    companion object {
        /** `.np .tx`: 76 px (play 92), glyphs 36 (48); on Glassy discs at 0.17 (play 0.15) of the key. */
        val CARD = TransportLook(76f, 36f, 92f, 48f, 0.17f, 0.15f)
        /** `.np .card-strip .tx`: 46 px (play 52), glyphs 22 (28); discs at 0.12. */
        val STRIP = TransportLook(46f, 22f, 52f, 28f, 0.12f, 0.12f)
    }
}

/**
 * media.tsx `TransportButton`: a filled glyph with no plate; pressing blooms a soft disc behind it (60 ms in,
 * 320 ms out, `--seamless`) and squeezes the glyph: play to 0.2 (220 ms), held there after the release for 600 ms
 * or until the other glyph replaces it, which pops in from 0.2 (350 ms `--spring-swap`); previous and next to 0.88
 * (120 ms) and a nudge of 8 px their way (340 ms). Glassy: a glass disc inside the key.
 */
@SuppressLint("ViewConstructor")
class TransportButton(context: Context, private val kind: Kind, private val look: TransportLook, private val onPress: () -> Unit) : KitView(context) {
    enum class Kind { PREV, PLAY, NEXT }

    var playing = false
        set(v) {
            if (field == v) return
            field = v
            if (kind != Kind.PLAY) return
            glyph = icon()
            waiting = false
            // the new glyph pops in (pause after a 120 ms delay)
            val now = Motion.now()
            pop.snap(0.2f); pop.to(1f, now + if (v) 120 else 0, UiTheme.duration(350), Motion.springSwap)
            popAt = now + if (v) 120 else 0
            squeeze.snap(1f)
            invalidate()
        }

    private val size get() = if (kind == Kind.PLAY) look.playSize else look.size
    private val glyphSize get() = if (kind == Kind.PLAY) look.playGlyph else look.glyph
    private var glyph: Drawable = icon()
    private var ink = 0
    private val bloomPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bloomAlpha = Transition(0f)
    private val bloomScale = Transition(0.85f)
    private val squeeze = Transition(1f)
    private val pop = Transition(1f)
    private var popAt = 0L
    private val nudge = Transition(0f)
    private var waiting = false
    private val unwait = Runnable { waiting = false; release() }
    private val disc = CssBox().apply { radius = 999f }
    private val discBloom = CssBox().apply { radius = 999f; fill = 0x1FFFFFFF }
    private var down = false

    init {
        uiKey = "tx.tx-" + kind.name.lowercase()
        isClickable = true
        onTheme()
    }

    private fun icon(): Drawable = context.getDrawable(
        when (kind) { Kind.PREV -> Icons.REWIND_FILL; Kind.NEXT -> Icons.FAST_FORWARD_FILL; Kind.PLAY -> if (playing) Icons.PAUSE_FILL else Icons.PLAY_FILL },
    )!!.mutate().apply { setTint(ink) }

    fun colors(ink: Int, bloom: Int) {
        this.ink = ink; glyph.setTint(ink); bloomPaint.color = bloom; invalidate()
    }

    override fun onTheme() {
        val g = UiTheme.glass
        if (UiTheme.glassy) disc.glassy(g, discFill()) else disc.solid(0)
    }

    private fun discFill() = when {
        down -> UiTheme.glass.glassyFillStrong
        kind == Kind.PLAY -> 0x29FFFFFF
        else -> UiTheme.glass.glassyFill
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = (size * d + 0.5f).toInt()
        setMeasuredDimension(s, s)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> press(true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> press(false)
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean {
        super.performClick()
        onPress()
        val now = Motion.now()
        if (kind == Kind.PLAY) {
            waiting = true
            removeCallbacks(unwait); postDelayed(unwait, UiTheme.duration(SWAP_WAIT_MS))
        } else {
            nudge.snap(0f); nudge.to(1f, now, UiTheme.duration(340), Easing.LINEAR)
        }
        invalidate()
        return true
    }

    private fun press(on: Boolean) {
        if (down == on) return
        down = on
        val now = Motion.now()
        if (on) {
            bloomAlpha.to(1f, now, UiTheme.duration(60), Easing.LINEAR); bloomScale.to(1f, now, UiTheme.duration(60), Easing.LINEAR)
            if (kind == Kind.PLAY) squeeze.to(0.2f, now, UiTheme.duration(220), PLAY_SQUEEZE)
            else squeeze.to(0.88f, now, UiTheme.duration(120), SIDE_SQUEEZE)
        } else {
            bloomAlpha.to(0f, now, UiTheme.duration(320), Motion.seamless); bloomScale.to(0.85f, now, UiTheme.duration(320), Motion.seamless)
            if (!(kind == Kind.PLAY && waiting)) release()
        }
        if (UiTheme.glassy) disc.fill = discFill()
        invalidate()
    }

    private fun release() {
        if (down) return
        squeeze.to(1f, Motion.now(), UiTheme.duration(485), Motion.springGlyph)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val now = Motion.now()
        val w = width.toFloat()
        val c = w / 2f
        val inset = if (UiTheme.glassy) size * (if (kind == Kind.PLAY) look.playPlate else look.plate) * d else 0f
        val a = bloomAlpha.value(now)
        if (UiTheme.glassy) {
            canvas.save(); canvas.translate(inset, inset)
            disc.draw(canvas, this, w - 2 * inset, w - 2 * inset)
            canvas.restore()
        }
        if (a > 0f) {
            val r = (c - inset) * bloomScale.value(now)
            if (UiTheme.glassy) {
                canvas.save(); canvas.translate(c - r, c - r); discBloom.shadowAlpha = 1f
                val keep = discBloom.fill
                discBloom.fill = CssColor.fade(keep, a); discBloom.draw(canvas, this, 2 * r, 2 * r); discBloom.fill = keep
                canvas.restore()
            } else {
                val base = bloomPaint.color
                bloomPaint.color = CssColor.fade(base, a); canvas.drawCircle(c, c, r, bloomPaint); bloomPaint.color = base
            }
        }
        // glyph: squeezed while pressed, popped in after a swap, nudged after a skip
        val popping = now >= popAt
        val s = squeeze.value(now) * (if (popping) pop.value(now) else 0.2f)
        val n = nudge.value(now)
        val shift = if (nudge.running(now)) NUDGE_PX * d * nudgeAt(n) * (if (kind == Kind.PREV) -1 else 1) else 0f
        val fade = if (!popping) 0f else (((now - popAt) / UiTheme.duration(120).coerceAtLeast(1).toFloat()).coerceIn(0f, 1f))
        canvas.save()
        canvas.translate(shift, 0f)
        canvas.scale(s, s, c, c)
        IconView.place(glyph, 0f, 0f, w, w, glyphSize * d)
        glyph.alpha = (255 * (if (pop.running(now) || !popping) fade else 1f)).toInt()
        glyph.draw(canvas)
        canvas.restore()
        if (bloomAlpha.running(now) || bloomScale.running(now) || squeeze.running(now) || pop.running(now) || nudge.running(now) || !popping) postInvalidateOnAnimation()
    }

    /** `tx-nudge`: 0 → 8 px at 38 % → 0, each half on `--seamless`. */
    private fun nudgeAt(t: Float) = if (t < 0.38f) Motion.seamless.at(t / 0.38f) else 1f - Motion.seamless.at((t - 0.38f) / 0.62f)

    override fun onDetachedFromWindow() { removeCallbacks(unwait); super.onDetachedFromWindow() }

    private companion object {
        const val SWAP_WAIT_MS = 600L
        const val NUDGE_PX = 8f
        val PLAY_SQUEEZE: Easing = CssBezier(0.4f, 0f, 0.2f, 1f)
        val SIDE_SQUEEZE: Easing = CssBezier(0.2f, 0f, 0f, 1f)
    }
}

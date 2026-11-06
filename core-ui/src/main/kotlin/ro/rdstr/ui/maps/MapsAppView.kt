package ro.rdstr.ui.maps

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import ro.rdstr.ui.Mirrors
import ro.rdstr.ui.kit.CssBezier
import ro.rdstr.ui.kit.CssBox
import ro.rdstr.ui.kit.IconView
import ro.rdstr.ui.kit.KitText
import ro.rdstr.ui.kit.Motion
import ro.rdstr.ui.kit.TextStyle
import ro.rdstr.ui.kit.UiTheme
import ro.rdstr.ui.tokens.Icons
import ro.rdstr.ui.uiKey

/**
 * The maps screen (`section.music-app.maps-app`, MapsApp.tsx:197-243): the map edge to edge, the close button, the
 * compass, zoom and locate buttons on the right, the map data credits, and the street and speed in the map's own
 * layer ([overlay]). The map itself is the shell's one map: the glue puts it into [mapSlot] (MapHost's app slot).
 *
 * The frame is the music app's (`.music-app`, media.md 1): opened it rises from the bottom in 420 ms on `--sheet-in`;
 * closed, its content fades in 100 ms and its outline closes onto the card on home ([cardBox]) in 280 ms while it
 * fades out. By day the screen stays light (it is not one of the apps kept dark).
 */
@Mirrors("MapsApp.tsx")
class MapsAppView(context: Context) : CssGroup(context) {
    /** What the screen asks for, exactly what MapsApp.tsx does on its buttons. */
    interface Actions {
        /** The close button: `shellAction({ type: "Home" })`. */
        fun close()
        /** The compass button: `setHeadingUp(!headingUp)`. */
        fun headingUp(on: Boolean)
        /** The zoom keys: `zoom(+1)`, `zoom(-1)`. */
        fun zoom(by: Double)
        /** The locate button: `recentre()`. */
        fun recentre()
    }

    var actions: Actions? = null

    /** Where the shell's one map is placed while the screen is open (MapHost's app slot, `div.map-canvas`). */
    val mapSlot = FrameLayout(context)
    val status = MapStatusView(context)
    /** The map's own layer with the street and the speed; it goes with the map onto the card on home. */
    val overlay = MapOverlay(context, status)
    private val host = Fill(context, "map-host", mapSlot, overlay)
    private val canvasBox = Fill(context, "map-canvas", host)

    private val close = MapButton(context, "glass-button.music-close", 56f, icon(Icons.CARET_DOWN, "icon.i-caret-down", 28f), 28f).apply {
        contentDescription = "Close maps"
        setOnClickListener { actions?.close() }
    }
    private val compass = MapCompass(context)
    private val compassButton = MapButton(context, "glass-button", 56f, compass, 26f).apply {
        setOnClickListener { actions?.headingUp(!headingUp) }
    }
    private val zoom = MapZoom(context) { actions?.zoom(it) }
    private val locateIcon = icon(Icons.NAVIGATION_ARROW_FILL, "icon.i-navigation-arrow-fill", 28f)
    private val locate = MapButton(context, "glass-button.map-locate", 56f, locateIcon, 28f).apply {
        ink = { MapsLook.BLUE }
        setOnClickListener { actions?.recentre() }
        // `.map-locate:active .icon { transform: scale(.85) }`, 300 ms on `--spring-glyph`
        onPress = { on ->
            val s = if (on) 0.85f else 1f
            locateIcon.animate().scaleX(s).scaleY(s).setDuration(UiTheme.duration(300L)).setInterpolator(Motion.interpolator(Motion.springGlyph)).start()
        }
    }
    private val controls = Column(context)
    private val credit = Credit(context)

    private var follow = true
    private var headingUp = true
    var ground: Int? = null; set(v) { field = v; groundChanged() }

    /** The home card's box in canvas px (`.panel`), which the closing outline shrinks onto (media.tsx useCloseOntoCard). */
    var cardBox = RectF(880f, 56f, 1256f, 726f)

    init {
        uiKey = "music-app.maps-app"
        setWillNotDraw(false)
        addView(canvasBox)
        addView(close)
        addView(controls)
        addView(credit)
        UiTheme.link(this) { groundChanged(); invalidate() }
        bind(follow = true, headingUp = true)
    }

    private fun icon(res: Int, key: String, size: Float) = IconView(context, res, size).apply { uiKey = key }

    /** The map follows the car (the locate button filled) and turns with it (the compass button's label). */
    fun bind(follow: Boolean, headingUp: Boolean) {
        if (follow != this.follow) {
            locateIcon.setIcon(if (follow) Icons.NAVIGATION_ARROW_FILL else Icons.NAVIGATION_ARROW)
            locateIcon.uiKey = if (follow) "icon.i-navigation-arrow-fill" else "icon.i-navigation-arrow"
        }
        this.follow = follow
        this.headingUp = headingUp
        locate.contentDescription = if (follow) "Following the car" else "Follow the car"
        compassButton.contentDescription = if (headingUp) "North up" else "Heading up"
    }

    /** The map's bearing, on every frame it turns (MapView.onRotate). */
    fun bearing(b: Double) = compass.bearing(b)

    private fun groundChanged() {
        val g = ground ?: UiTheme.base.canvas
        for (b in listOf(close, compassButton, locate, credit.button)) b.ground = g
        zoom.ground = g
        status.ground = g
        credit.theme()
    }

    /** Puts the map's layer into [parent] (the card on home holding the map), or back into this screen with null. */
    fun placeOverlay(parent: ViewGroup?) {
        val to = parent ?: host
        if (overlay.parent === to) return
        (overlay.parent as? ViewGroup)?.removeView(overlay)
        to.addView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        status.onCard = parent != null
    }

    override fun measureCss(maxW: Float, maxH: Float) { cssW = maxW; cssH = maxH }

    override fun placeCss() {
        place(canvasBox, 0f, 0f, cssW, cssH)
        place(close, 32f, 76f, 56f, 56f)
        measureBox(controls, 56f, cssH)
        place(controls, cssW - 32f - 56f, 76f, 56f, controls.cssH)
        measureBox(credit, cssW, 44f)
        place(credit, cssW - 38f - credit.cssW, cssH - 54f - credit.cssH, credit.cssW, credit.cssH)
    }

    override fun onDraw(canvas: Canvas) = canvas.drawColor(UiTheme.base.canvas)

    // ----- the music app's frame: open and close

    private var opened = true
    private var motion: ValueAnimator? = null
    private val inset = RectF()
    private var radius = 0f

    init {
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) =
                outline.setRoundRect(inset.left.toInt(), inset.top.toInt(), (view.width - inset.right).toInt(), (view.height - inset.bottom).toInt(), radius)
        }
    }

    /** Open (`.is-open`) or closed; [animate] false puts it there at once. Closed, it is not drawn and takes no touch. */
    fun open(on: Boolean, animate: Boolean = true) {
        if (on == opened && motion == null) return
        opened = on
        motion?.cancel()
        motion = null
        val run = animate && UiTheme.speed > 0f && isAttachedToWindow
        if (on) {
            visibility = VISIBLE
            alpha = 1f
            clipToOutline = false
            fadeChildren(1f, run)
            if (!run) { translationY = 0f; return }
            // `@keyframes music-in { from { transform: translateY(100%) } }`, 420 ms on `--sheet-in`
            motion = ValueAnimator.ofFloat(1f, 0f).apply {
                duration = UiTheme.duration(420L)
                interpolator = Motion.interpolator(Motion.sheetIn)
                addUpdateListener { translationY = (it.animatedValue as Float) * height }
                addListener(done())
                start()
            }
        } else {
            fadeChildren(0f, run)
            if (!run) { visibility = INVISIBLE; return }
            val d = resources.displayMetrics.density
            val to = RectF(cardBox.left * d, cardBox.top * d, width - cardBox.right * d, height - cardBox.bottom * d)
            clipToOutline = true
            // clip-path and opacity in 280 ms: the outline on `--sheet-in`, the fade on `cubic-bezier(.4, 0, 1, 1)`
            motion = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = UiTheme.duration(280L)
                addUpdateListener {
                    val t = it.animatedFraction
                    val k = Motion.sheetIn.at(t)
                    inset.set(to.left * k, to.top * k, to.right * k, to.bottom * k)
                    radius = UiTheme.base.radius * d * k
                    alpha = 1f - FADE_OUT.at(t)
                    invalidateOutline()
                }
                addListener(done { visibility = INVISIBLE; clipToOutline = false; inset.setEmpty(); radius = 0f; alpha = 1f })
                start()
            }
        }
    }

    private fun done(end: () -> Unit = {}) = object : AnimatorListenerAdapter() {
        private var cancelled = false
        override fun onAnimationCancel(animation: Animator) { cancelled = true }
        override fun onAnimationEnd(animation: Animator) {
            if (cancelled) return
            motion = null
            end()
        }
    }

    // `.music-app > * { transition: opacity 100ms var(--ease-out) }`
    private fun fadeChildren(to: Float, run: Boolean) {
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            c.animate().cancel()
            if (run) c.animate().alpha(to).setDuration(UiTheme.duration(100L)).setInterpolator(Motion.interpolator(Motion.easeOut)).start()
            else c.alpha = to
        }
    }

    /** A box filling its parent with its children filling it too (`.map-canvas`, `.map-host`). */
    private class Fill(context: Context, key: String, vararg kids: View) : CssGroup(context) {
        init { uiKey = key; kids.forEach { addView(it) } }
        override fun measureCss(maxW: Float, maxH: Float) { cssW = maxW; cssH = maxH }
        override fun placeCss() { for (i in 0 until childCount) place(getChildAt(i), 0f, 0f, cssW, cssH) }
    }

    /** `.map-controls`: the compass button, the zoom pill and the locate button in a column, 12 px apart. */
    private inner class Column(context: Context) : CssGroup(context) {
        init { uiKey = "map-controls"; addView(compassButton); addView(zoom); addView(locate) }

        override fun measureCss(maxW: Float, maxH: Float) {
            measureBox(zoom, 56f, maxH)
            cssW = 56f; cssH = 56f + 12f + zoom.cssH + 12f + 56f
        }

        override fun placeCss() {
            place(compassButton, 0f, 0f, 56f, 56f)
            place(zoom, 0f, 68f, 56f, zoom.cssH)
            place(locate, 0f, 80f + zoom.cssH, 56f, 56f)
        }
    }

    /** `.map-credit`: the credits button (44 px) and, while it is on, the data credit beside it, 10 px apart. */
    private inner class Credit(context: Context) : CssGroup(context) {
        val button = MapButton(context, "glass-button", 44f, icon(Icons.INFO, "icon.i-info", 22f), 22f).apply {
            contentDescription = "Map data credits"
            setOnClickListener { toggle() }
        }
        private val text = KitText(context, TextStyle(13f)).apply {
            uiKey = "map-credit-text"
            text = "© OpenMapTiles © OpenStreetMap contributors"
            visibility = GONE
            includeFontPadding = false
            maxLines = 1
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        private val box = CssBox().apply { radius = 16f }

        init {
            uiKey = "map-credit"
            setWillNotDraw(false)
            addView(text)
            addView(button)
            // `padding: 8px 14px` inside the 1 px edge
            text.setPadding(((14f + border(1f)) * d).toInt(), 0, 0, 0)
            UiTheme.link(this) { theme() }
        }

        fun theme() {
            MapsLook.credit(box, ground ?: UiTheme.base.canvas)
            text.setTextColor(MapsLook.creditInk)
            invalidate()
        }

        private fun toggle() {
            val on = text.visibility != VISIBLE
            text.visibility = if (on) VISIBLE else GONE
            // `animation: media-fade 200ms var(--ease-out)`
            if (on && UiTheme.speed > 0f) { text.alpha = 0f; text.animate().alpha(1f).setDuration(UiTheme.duration(200L)).setInterpolator(Motion.interpolator(Motion.easeOut)).start() }
            requestLayout()
            invalidate()
        }

        private val textW get() = text.cssWidth() + 28f + 2 * border(1f)
        private val textH get() = text.paint.fontMetrics.let { (it.descent - it.ascent) / d } + 16f + 2 * border(1f)

        override fun measureCss(maxW: Float, maxH: Float) {
            val shown = text.visibility == VISIBLE
            cssW = 44f + if (shown) textW + 10f else 0f
            cssH = maxOf(44f, if (shown) textH else 0f)
        }

        override fun placeCss() {
            if (text.visibility == VISIBLE) {
                place(text, 0f, (cssH - textH) / 2, textW, textH)
            }
            place(button, cssW - 44f, (cssH - 44f) / 2, 44f, 44f)
        }

        override fun dispatchDraw(canvas: Canvas) {
            if (text.visibility == VISIBLE) {
                canvas.save()
                canvas.translate(text.left.toFloat(), text.top.toFloat())
                val a = text.alpha
                if (a < 1f) canvas.saveLayerAlpha(0f, 0f, text.width.toFloat(), text.height.toFloat(), (a * 255).toInt())
                box.draw(canvas, this, text.width.toFloat(), text.height.toFloat())
                if (a < 1f) canvas.restore()
                canvas.restore()
            }
            super.dispatchDraw(canvas)
        }
    }

    private companion object {
        val FADE_OUT = CssBezier(0.4f, 0f, 1f, 1f)
    }
}

/**
 * The map's own layer (`div.map-overlay`, mapHost.ts): over the map, taking no touch, holding [status]; in the Maps
 * screen and on the card on home, wherever the map is.
 */
class MapOverlay(context: Context, val status: MapStatusView) : CssGroup(context) {
    init {
        uiKey = "map-overlay"
        addView(status)
    }

    override fun measureCss(maxW: Float, maxH: Float) {
        cssW = maxW; cssH = maxH
        status.measure(MeasureSpec.makeMeasureSpec(snap(maxW), MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(snap(maxH), MeasureSpec.AT_MOST))
    }

    // `.map-status { left: 32px; bottom: 44px }`; on the card `left: 14px; bottom: 34px` (118 over a sound strip)
    override fun placeCss() {
        val left = if (status.onCard) 14f else 32f
        val bottom = if (!status.onCard) 44f else if (status.strip) 118f else 34f
        place(status, left, cssH - bottom - status.cssH, status.cssW, status.cssH)
    }
}

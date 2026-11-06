package ro.rdstr.ui.sheets

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import ro.rdstr.car.MapPacks
import ro.rdstr.ui.Mirrors
import ro.rdstr.ui.kit.CssBox
import ro.rdstr.ui.kit.Motion
import ro.rdstr.ui.kit.TextStyle
import ro.rdstr.ui.kit.UiTheme
import ro.rdstr.ui.tokens.Bezier
import ro.rdstr.ui.tokens.Icons
import ro.rdstr.ui.tokens.Shadow
import ro.rdstr.ui.uiKey
import kotlin.math.roundToInt

/**
 * LocationPrompt.tsx and RestartPrompt.tsx: the alert over everything (`div.alert`, music.css), a dimming scrim and a
 * card in the middle with an icon, a question, a line, the primary answer and Cancel. Opening fades the card in
 * (220 ms) as it settles from 1.08 with a small spring (320 ms `--spring-glyph`). [restart] picks the restart
 * question, else Wi-Fi's location one.
 */
@Mirrors("LocationPrompt.tsx", "RestartPrompt.tsx")
@SuppressLint("ViewConstructor")
class AlertPromptView(context: Context, restart: Boolean) : ViewGroup(context) {
    /** Location: `answerWifiNeedsLocation(true/false)`; restart: `restartUnit()` and `askRestart(false)`. */
    interface Actions {
        fun primary()
        fun cancel()
    }

    var actions: Actions? = null
    private val scrim = View(context).apply { uiKey = "alert-scrim"; isClickable = true; contentDescription = "Cancel" }
    private val scrimBox = CssBox().apply { fill = 0x73000000 }
    private val card = Flex(context, "alert-card", column = true)
    private val disc = Flex(context, "alert-icon")
    private val glyph = icon(context, "icon", if (restart) Icons.POWER else Icons.NAVIGATION_ARROW_FILL, 30f)
    private val title = text(context, "h2", TextStyle(22f, 600))
    private val line = paragraph(context, "p", TextStyle(16f, lineHeight = 1.4f), center = true)
    private val buttons = Flex(context, "alert-actions", column = true)
    private val primary = TextButton(context, "alert-button", TextStyle(18f, 600))
    private val cancel = TextButton(context, "alert-button", TextStyle(18f, 600)).apply { text = "Cancel" }
    var open = false; private set

    init {
        uiKey = "alert"
        clipChildren = false
        scrim.background = BoxDrawable(scrim, scrimBox)
        addView(scrim)
        addView(card)
        glyph.uiKey = glyph.iconKey(if (restart) Icons.POWER else Icons.NAVIGATION_ARROW_FILL)
        title.text = if (restart) "Restart the unit?" else "Wi-Fi needs Location"
        line.text = if (restart) "The screen goes dark and the music stops while the unit starts again. It takes about a minute."
            else "Location must be on for the unit to find Wi-Fi networks."
        primary.text = if (restart) "Restart" else "Turn on Location"
        card.align = Flex.Align.CENTER; card.gap = 10f; card.pad(28f, 24f, 20f, 24f)
        card.box.radius = 28f; card.box.border = 1f
        card.add(disc, Flex.Item(w = 64f, h = 64f))
        disc.align = Flex.Align.CENTER; disc.justify = Flex.Justify.CENTER; disc.box.radius = 999f
        disc.add(glyph, Flex.Item(w = 30f, h = 30f))
        card.add(title, Flex.Item(mT = 8f))
        card.add(line)
        card.add(buttons, Flex.Item(alignSelf = Flex.Align.STRETCH, mT = 14f))
        buttons.gap = 10f
        for (b in listOf(primary, cancel)) {
            buttons.add(b, Flex.Item(h = 56f))
            b.box.radius = 999f
            b.pressScale = 0.97f
        }
        scrim.setOnClickListener { actions?.cancel() }
        cancel.setOnClickListener { actions?.cancel() }
        primary.setOnClickListener { actions?.primary() }
        UiTheme.link(this) { restyle() }
        restyle()
        show(false, animate = false)
    }

    private fun restyle() {
        val b = UiTheme.base
        card.box.look(b.panel, listOf(Shadow(false, 0f, 18f, 48f, 0f, 0x73000000)))
        card.box.borderColor = on(0.1f)
        // the glass frosts the screen dimmed by the scrim under it (see SheetFrame)
        card.box.under = if (UiTheme.glassy) { c -> c.drawColor(ro.rdstr.ui.kit.CssColor.fade(0x73000000, scrim.alpha)) } else null
        disc.box.look(0x2E0A84FF)
        glyph.color = 0xFF0A84FF.toInt()
        title.setTextColor(b.ink); line.setTextColor(on(0.62f))
        primary.box.look(0xFF0A84FF.toInt(), tinted = true); primary.fill(primary.box.fill); primary.setTextColor(-1)
        cancel.box.look(on(0.12f)); cancel.fill(cancel.box.fill); cancel.setTextColor(b.ink)
        invalidate(); card.invalidate()
    }

    fun show(open: Boolean, animate: Boolean = true) {
        if (open == this.open && animate) return
        this.open = open
        visibility = VISIBLE
        scrim.animate().cancel(); card.animate().cancel()
        if (!animate || UiTheme.speed <= 0f || !isAttachedToWindow) {
            scrim.alpha = if (open) 1f else 0f
            card.alpha = if (open) 1f else 0f
            card.scaleX = if (open) 1f else 1.08f; card.scaleY = card.scaleX
            if (!open) visibility = INVISIBLE
            return
        }
        scrim.animate().alpha(if (open) 1f else 0f).setDuration(UiTheme.duration(220)).setInterpolator(Motion.interpolator(UiTheme.base.seamless)).start()
        card.animate().alpha(if (open) 1f else 0f).setDuration(UiTheme.duration(220)).setInterpolator(Motion.interpolator(UiTheme.base.seamless)).start()
        val s = if (open) 1f else 1.08f
        card.animate().scaleX(s).scaleY(s).setDuration(UiTheme.duration(320)).setInterpolator(Motion.interpolator(UiTheme.base.springGlyph))
            .withEndAction { if (!this.open) visibility = INVISIBLE }.start()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = open

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec); val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        scrim.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        card.measure(MeasureSpec.makeMeasureSpec((CARD_W * resources.displayMetrics.density).roundToInt(), MeasureSpec.EXACTLY), MeasureSpec.UNSPECIFIED)
        // place-items: center, from the exact position
        val d = resources.displayMetrics.density
        card.errX = (w / d - CARD_W) / 2 - ((w - card.measuredWidth) / 2) / d
        card.errY = (h / d - card.contentH) / 2 - ((h - card.measuredHeight) / 2) / d
        card.forceLayout()
        card.measure(MeasureSpec.makeMeasureSpec(card.measuredWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(card.measuredHeight, MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        scrim.layout(0, 0, width, height)
        val x = (width - card.measuredWidth) / 2; val y = (height - card.measuredHeight) / 2
        card.layout(x, y, x + card.measuredWidth, y + card.measuredHeight)
    }

    private companion object { const val CARD_W = 420f }
}

/**
 * What the map offer shows: [offer] of the country under the car ([name], its pack [mb]), or the [progress] of a
 * download running (null when none), [waiting] for Wi-Fi to go on; [shown] when it is on screen at all.
 */
data class MapOfferState(
    val shown: Boolean = false,
    val offer: MapPacks.Offer = MapPacks.Offer.NONE,
    val name: String = "",
    val mb: Int = 0,
    val progress: Float? = null,
    val waiting: Boolean = false,
)

/**
 * MapOffer.tsx: over any screen at the bottom, the map of the country the car just entered (`.map-offer`, maps.css):
 * a green arrow, what is offered, "Not now" and Download (or Wi-Fi first); a running download shows its bar. It rises
 * from below in 600 ms on `--spring-rise` and drops in 240 ms. Placed by the screen: centred, 44 px over the bottom.
 */
@Mirrors("MapOffer.tsx")
@SuppressLint("ViewConstructor")
class MapOfferView(context: Context) : Flex(context, "map-offer") {
    /** `declinePack(code)`, `downloadPack(code)`, `OpenNetworks`. */
    interface Actions {
        fun decline()
        fun download()
        fun openNetworks()
    }

    var actions: Actions? = null
    private var state = MapOfferState()
    private val badge = Flex(context, "map-offer-icon")
    private val arrow = icon(context, "icon.i-navigation-arrow-fill", Icons.NAVIGATION_ARROW_FILL, 26f).apply { rotation = 45f }
    private val texts = Flex(context, "map-offer-text", column = true)
    private val title = text(context, "map-offer-title", TextStyle(19f, 600))
    private val sub = text(context, "map-offer-sub", TextStyle(15f))
    private val bar = Flex(context, "map-offer-bar")
    private val barFill = Flex(context, "span")
    private val quiet = TextButton(context, "map-offer-button", TextStyle(17f, 600)).apply { text = "Not now" }
    private val go = TextButton(context, "map-offer-button", TextStyle(17f, 600))
    private var shownNow = false

    init {
        align = Align.CENTER; gap = 16f; pad(12f, 14f)
        box.radius = 28f; box.border = 1f
        add(badge, Item(w = 56f, h = 56f))
        badge.align = Align.CENTER; badge.justify = Justify.CENTER; badge.box.radius = 18f
        badge.add(arrow, Item(w = 26f, h = 26f))
        add(texts, Item(grow = 1f, shrink = true))
        texts.gap = 4f
        texts.add(title); texts.add(sub)
        texts.add(bar, Item(h = 6f, mT = 6f))
        bar.box.radius = 3f
        bar.add(barFill, Item(grow = 1f))
        barFill.box.radius = 3f
        barFill.pivotX = 0f
        for (b in listOf(quiet, go)) {
            add(b, Item(h = 52f))
            b.box.radius = 26f; b.pressScale = 0.95f
            val p = (22 * resources.displayMetrics.density).roundToInt(); b.setPadding(p, 0, p, 0)
        }
        quiet.setOnClickListener { actions?.decline() }
        go.setOnClickListener { if (state.offer == MapPacks.Offer.DOWNLOAD) actions?.download() else actions?.openNetworks() }
        UiTheme.link(this) { restyle() }
        bind(state)
    }

    fun bind(s: MapOfferState) {
        state = s
        val downloading = s.progress != null
        title.text = when {
            downloading && s.waiting -> "Waiting for Wi-Fi · ${s.name}"
            downloading -> "Downloading the map of ${s.name}"
            else -> "You are in ${s.name}"
        }
        sub.text = if (s.offer == MapPacks.Offer.DOWNLOAD) "Download its offline map, ${if (s.mb > 0) s.mb else "?"} MB" else "Connect to Wi-Fi to download its map"
        sub.visibility = if (downloading) GONE else VISIBLE
        bar.visibility = if (downloading) VISIBLE else GONE
        quiet.visibility = if (downloading) GONE else VISIBLE
        go.visibility = if (downloading) GONE else VISIBLE
        go.text = if (s.offer == MapPacks.Offer.DOWNLOAD) "Download" else "Wi-Fi"
        val p = s.progress ?: 0f
        barFill.animate().cancel()
        if (UiTheme.speed > 0f && isAttachedToWindow) barFill.animate().scaleX(p).setDuration(UiTheme.duration(250)).setInterpolator { it }.start() else barFill.scaleX = p
        isEnabled = s.shown
        rise(s.shown)
        requestLayout()
    }

    private fun rise(shown: Boolean) {
        if (shown == shownNow && isLaidOut) return
        shownNow = shown
        animate().cancel()
        val down = 1.4f * height
        if (UiTheme.speed <= 0f || !isAttachedToWindow || !isLaidOut) { alpha = if (shown) 1f else 0f; translationY = if (shown) 0f else down; return }
        if (shown) {
            animate().translationY(0f).setDuration(UiTheme.duration(600)).setInterpolator(Motion.interpolator(UiTheme.base.springRise)).start()
            animate().alpha(1f).setDuration(UiTheme.duration(220)).setInterpolator(Motion.interpolator(UiTheme.base.easeOut)).start()
        } else {
            val drop = Motion.interpolator(Bezier(0.4f, 0f, 1f, 1f))
            animate().translationY(down).alpha(0f).setDuration(UiTheme.duration(240)).setInterpolator(drop).start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!shownNow) translationY = 1.4f * h
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = !shownNow

    private fun restyle() {
        val b = UiTheme.base
        box.look(b.sheetRaised, listOf(Shadow(false, 0f, 8f, 24f, 0f, 0x4D000000)))
        box.borderColor = b.hairlineStrong
        badge.box.look(0xFF30D158.toInt(), tinted = true); arrow.color = -1
        title.setTextColor(b.ink); sub.setTextColor(b.inkMuted)
        bar.box.fill = b.hairlineStrong; barFill.box.fill = 0xFF30D158.toInt()
        quiet.box.look(b.glassStrong, tinted = true); quiet.fill(quiet.box.fill); quiet.setTextColor(b.ink)
        go.box.look(0xFF0A84FF.toInt(), tinted = true); go.fill(go.box.fill); go.setTextColor(-1)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // width: 640px; min-height: 84px
        val d = resources.displayMetrics.density
        super.onMeasure(MeasureSpec.makeMeasureSpec((WIDTH * d).roundToInt(), MeasureSpec.EXACTLY), MeasureSpec.UNSPECIFIED)
        if (measuredHeight < (MIN_H * d).roundToInt()) super.onMeasure(MeasureSpec.makeMeasureSpec((WIDTH * d).roundToInt(), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec((MIN_H * d).roundToInt(), MeasureSpec.EXACTLY))
    }

    private companion object {
        const val WIDTH = 640f
        const val MIN_H = 84f
    }
}

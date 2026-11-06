package ro.rdstr.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import ro.rdstr.car.Camera
import ro.rdstr.car.CameraSettings
import ro.rdstr.car.Guidelines
import ro.rdstr.ui.kit.ColorTransition
import ro.rdstr.ui.kit.CssBox
import ro.rdstr.ui.kit.CssColor
import ro.rdstr.ui.kit.Easing
import ro.rdstr.ui.kit.Gap
import ro.rdstr.ui.kit.IconView
import ro.rdstr.ui.kit.KitText
import ro.rdstr.ui.kit.Motion
import ro.rdstr.ui.kit.TextStyle
import ro.rdstr.ui.kit.Transition
import ro.rdstr.ui.kit.UiTheme
import ro.rdstr.ui.tokens.Icons
import ro.rdstr.ui.tokens.Shadow
import ro.rdstr.ui.uiKey

// The settings app's own small parts (settings.css), on the kit's CssBox and transitions.

internal fun View.dp(css: Float): Int = (css * resources.displayMetrics.density + 0.5f).toInt()

/** The icon of an emulator class name (`i-wifi-high` → "wifi-high"). */
internal fun iconRes(name: String): Int = when (name) {
    "wifi-high" -> Icons.WIFI_HIGH
    "bluetooth" -> Icons.BLUETOOTH
    "navigation-arrow-fill" -> Icons.NAVIGATION_ARROW_FILL
    "sun-fill" -> Icons.SUN_FILL
    "clock-fill" -> Icons.CLOCK_FILL
    "speaker-high-fill" -> Icons.SPEAKER_HIGH_FILL
    "speaker-slash-fill" -> Icons.SPEAKER_SLASH_FILL
    "phone-fill" -> Icons.PHONE_FILL
    "car-fill" -> Icons.CAR_FILL
    "camera" -> Icons.CAMERA
    "house-fill" -> Icons.HOUSE_FILL
    "map-trifold-fill" -> Icons.MAP_TRIFOLD_FILL
    "info-fill" -> Icons.INFO_FILL
    "code" -> Icons.CODE
    "minus" -> Icons.MINUS
    "plus" -> Icons.PLUS
    "caret-up" -> Icons.CARET_UP
    "caret-down" -> Icons.CARET_DOWN
    "caret-left" -> Icons.CARET_LEFT
    "caret-right" -> Icons.CARET_RIGHT
    "check" -> Icons.CHECK
    "list" -> Icons.LIST
    "magnifying-glass" -> Icons.MAGNIFYING_GLASS
    "power" -> Icons.POWER
    else -> error("no icon $name")
}

/** An icon keyed as the emulator's `span.icon.i-<name>`. */
internal fun icon(context: Context, name: String, size: Float) = IconView(context, iconRes(name), size).apply { uiKey = "icon.i-$name" }

/** The children of [this] in this order; nothing is touched when they already are. */
internal fun ViewGroup.children(list: List<View>) {
    if (childCount == list.size && list.indices.all { getChildAt(it) === list[it] }) return
    removeAllViews()
    for (v in list) { (v.parent as? ViewGroup)?.removeView(v); addView(v) }
}

/** Views kept across renders by key, as React keeps elements: a control's own state lives on in it. */
internal class Keep(private val context: Context) {
    private val views = HashMap<String, View>()
    private val seen = HashSet<String>()

    @Suppress("UNCHECKED_CAST")
    fun <T : View> get(key: String, make: (Context) -> T): T { seen += key; return views.getOrPut(key) { make(context) } as T }

    /** The view kept under [key], without asking for it. */
    fun peek(key: String): View? = views[key]

    /** Forgets the views not asked for since the last sweep. */
    fun sweep() { views.keys.retainAll(seen); seen.clear() }
    fun clear() { views.clear(); seen.clear() }
}

/** A CSS box as a view's background, its fill moving to [pressed] while the view is pressed (`:active`). */
internal class Plate(private val owner: View, radius: Float, private val ms: Long = UiTheme.base.fast, private val ease: Easing = Easing.EASE) : Drawable() {
    val box = CssBox().apply { this.radius = radius }
    private var rest = 0
    private var pressed = 0
    private val fill = ColorTransition(0)
    private var down = false

    /** The fills at rest and pressed; the box's other parts are set on [box]. */
    fun look(rest: Int, pressed: Int = rest) {
        this.rest = rest; this.pressed = pressed
        fill.snap(if (down) pressed else rest); invalidateSelf()
    }

    /** A new fill at rest, reached over the plate's transition (a section becoming the current one). */
    fun restTo(rest: Int, pressed: Int) {
        this.rest = rest; this.pressed = pressed
        fill.to(if (down) pressed else rest, Motion.now(), UiTheme.duration(ms), ease); invalidateSelf()
    }

    override fun isStateful() = true
    override fun onStateChange(state: IntArray): Boolean {
        val d = android.R.attr.state_pressed in state && android.R.attr.state_enabled in state
        if (d == down) return false
        down = d
        fill.to(if (d) pressed else rest, Motion.now(), UiTheme.duration(ms), ease)
        invalidateSelf()
        return true
    }

    override fun draw(canvas: Canvas) {
        val now = Motion.now()
        box.fill = fill.value(now)
        box.draw(canvas, owner, bounds.width().toFloat(), bounds.height().toFloat())
        if (fill.running(now)) owner.postInvalidateOnAnimation()
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** A text in a style, coloured and keyed. */
internal fun text(context: Context, key: String, style: TextStyle, single: Boolean = true) = KitText(context, style).apply {
    uiKey = key
    if (single) { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
}

/**
 * settings.css `.set-inline`: a text button inside a row, 48 px tall, 16 px in, 17 px 600 in `--set-blue`; pressed
 * `rgb(var(--on) / .08)`. [Kind.DANGER] white on `#ff453a`, [Kind.REMOVE] red text; disabled `rgb(var(--on) / .3)`.
 * On Glassy (glassy.css) the glass material, `--glassy-fill-strong` pressed; the red one keeps its colour without the
 * sheen (its `background` shorthand clears the image) under the glass shadow.
 */
@SuppressLint("ViewConstructor")
internal class InlineButton(context: Context, label: String) : FrameLayout(context) {
    enum class Kind { PLAIN, DANGER, REMOVE }
    val label = text(context, "", TextStyle(17f, 600)).apply { text = label; gravity = Gravity.CENTER }
    private val plate = Plate(this, 12f)
    var kind = Kind.PLAIN; set(v) { if (field != v) { field = v; theme() } }
    var onClick: (() -> Unit)? = null

    init {
        uiKey = "set-inline"
        background = plate
        minimumHeight = dp(48f)
        setPadding(dp(16f), 0, dp(16f), 0)
        clipChildren = false
        addView(this.label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        this.label.uiKey = null
        setOnClickListener { onClick?.invoke() }
        UiTheme.link(this) { theme() }
        theme()
    }

    override fun setEnabled(enabled: Boolean) { if (enabled != isEnabled) { super.setEnabled(enabled); theme() } }

    private fun theme() {
        val on = UiTheme.base.on
        val glassy = UiTheme.glassy
        val g = UiTheme.glass
        val rest = if (kind == Kind.DANGER) DANGER else if (glassy) g.glassyFill else 0
        val pressed = if (kind == Kind.DANGER) DANGER else if (glassy) g.glassyFillStrong else CssColor.withAlpha(on, 0.08f)
        if (glassy) { plate.box.glassy(g, rest); if (kind == Kind.DANGER) plate.box.gradient = null } else plate.box.solid(rest)
        plate.look(rest, pressed)
        label.setTextColor(when {
            !isEnabled -> CssColor.withAlpha(on, 0.3f)
            kind == Kind.DANGER -> -1
            kind == Kind.REMOVE -> DANGER
            else -> UiTheme.settings.setBlue
        })
    }

    companion object { const val DANGER = 0xFFFF453A.toInt() }
}

/** settings.css `.set-stepper` buttons: 56 x 48, a 22 px icon in `--ink`, `rgb(var(--on) / .16)` pressed, `/ .3` disabled. */
@SuppressLint("ViewConstructor")
internal class StepKey(context: Context, iconName: String) : FrameLayout(context) {
    private val glyph = icon(context, iconName, 22f)
    private val plate = Plate(this, 10f)
    var onClick: (() -> Unit)? = null

    init {
        uiKey = "button"
        background = plate
        addView(glyph, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        setOnClickListener { onClick?.invoke() }
        UiTheme.link(this) { theme() }
        theme()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        super.onMeasure(MeasureSpec.makeMeasureSpec(dp(56f), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(dp(48f), MeasureSpec.EXACTLY))

    override fun setEnabled(enabled: Boolean) { if (enabled != isEnabled) { super.setEnabled(enabled); theme() } }

    private fun theme() {
        val on = UiTheme.base.on
        plate.look(0, CssColor.withAlpha(on, 0.16f))
        glyph.color = if (isEnabled) UiTheme.settings.ink else CssColor.withAlpha(on, 0.3f)
    }
}

/**
 * settings.css `.set-stepper`: − value + on a 12 px rounded `rgba(118,118,128,.24)` strip, 3 px in, 4 px apart; the
 * value (`output`, 20 px 600, 44 px at least) only when [withValue]. On Glassy the glass material.
 */
@SuppressLint("ViewConstructor")
internal class Stepper(context: Context, down: String, up: String, withValue: Boolean, minValueWidth: Float = 44f) : LinearLayout(context) {
    val minus = StepKey(context, down)
    val plus = StepKey(context, up)
    val output = text(context, "output", TextStyle(20f, 600)).apply { gravity = Gravity.CENTER; minWidth = dp(minValueWidth) }
    private val plate = Plate(this, 12f)

    init {
        uiKey = "set-stepper"
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = plate
        setPadding(dp(3f), dp(3f), dp(3f), dp(3f))
        dividerDrawable = Gap(dp(4f))
        showDividers = SHOW_DIVIDER_MIDDLE
        addView(minus)
        if (withValue) addView(output)
        addView(plus)
        UiTheme.link(this) { theme() }
        theme()
    }

    private fun theme() {
        if (UiTheme.glassy) plate.box.glassy(UiTheme.glass) else plate.box.solid(SEG_GROUND)
        plate.look(plate.box.fill)
        output.setTextColor(UiTheme.settings.ink)
    }

    companion object { const val SEG_GROUND = 0x3D767680 }
}

/** settings.css `.set-back`: caret and a label in `--set-blue`, 18 px 600, 48 px tall; pressed `rgb(var(--on) / .08)`. */
@SuppressLint("ViewConstructor")
internal class BackButton(context: Context, label: String) : LinearLayout(context) {
    private val caret = icon(context, "caret-left", 22f)
    private val title = text(context, "", TextStyle(18f, 600)).apply { text = label; uiKey = null }
    private val plate = Plate(this, 12f)
    var onClick: (() -> Unit)? = null

    init {
        uiKey = "set-back"
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = plate
        minimumHeight = dp(48f)
        setPadding(dp(8f), 0, dp(8f), 0)
        dividerDrawable = Gap(dp(4f))
        showDividers = SHOW_DIVIDER_MIDDLE
        addView(caret); addView(title)
        setOnClickListener { onClick?.invoke() }
        UiTheme.link(this) { theme() }
        theme()
    }

    private fun theme() {
        plate.look(0, CssColor.withAlpha(UiTheme.base.on, 0.08f))
        caret.color = UiTheme.settings.setBlue
        title.setTextColor(UiTheme.settings.setBlue)
    }
}

/**
 * The search of a picker (phone.css `.phone-search`): the field (52 px, 14 px rounded, `rgba(118,118,128,.24)`, a
 * 2 px `#0a84ff` ring while typing, the magnifier and the text or placeholder in `--phone-ink-2`, a blinking caret)
 * and Cancel beside it while there is a query or typing. On Glassy the field is glass.
 */
@SuppressLint("ViewConstructor")
internal class SearchBar(context: Context, private val placeholder: String, private val emptyLabel: String) : LinearLayout(context) {
    private val field = LinearLayout(context)
    private val glass = icon(context, "magnifying-glass", 22f)
    private val shown = text(context, "phone-search-placeholder", TextStyle(18f))
    private val caret = Caret(context)
    private val cancel = text(context, "phone-search-cancel", TextStyle(18f, 600)).apply {
        text = "Cancel"; gravity = Gravity.CENTER; minHeight = dp(52f); setPadding(dp(6f), 0, dp(6f), 0); isClickable = true
    }
    private val plate = Plate(field, 14f)
    var onField: (() -> Unit)? = null
    var onCancel: (() -> Unit)? = null
    private var query = ""
    private var typing = false

    init {
        uiKey = "phone-search"
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        dividerDrawable = Gap(dp(12f))
        showDividers = SHOW_DIVIDER_MIDDLE
        field.uiKey = "phone-search-field"
        field.orientation = HORIZONTAL
        field.gravity = Gravity.CENTER_VERTICAL
        field.background = plate
        field.setPadding(dp(16f), 0, dp(16f), 0)
        field.dividerDrawable = Gap(dp(10f))
        field.showDividers = SHOW_DIVIDER_MIDDLE
        field.addView(glass)
        field.addView(shown, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        field.setOnClickListener { onField?.invoke() }
        addView(field, LayoutParams(0, dp(52f), 1f))
        addView(cancel)
        cancel.setOnClickListener { onCancel?.invoke() }
        field.contentDescription = emptyLabel
        UiTheme.link(this) { theme() }
        theme()
    }

    fun show(query: String, typing: Boolean) {
        this.query = query; this.typing = typing
        shown.text = query.ifEmpty { placeholder }
        shown.uiKey = if (query.isEmpty()) "phone-search-placeholder" else "phone-search-text"
        field.contentDescription = if (query.isEmpty()) emptyLabel else "Search, $query"
        // the caret sits right after the text, 8 px back into the gap (margin-left: -8px)
        (shown.layoutParams as LayoutParams).apply { width = if (typing) LayoutParams.WRAP_CONTENT else 0; weight = if (typing) 0f else 1f }
        if (typing && caret.parent == null) field.addView(caret, LayoutParams(dp(2f), dp(24f)).apply { leftMargin = -dp(8f) })
        if (!typing && caret.parent != null) field.removeView(caret)
        cancel.visibility = if (query.isNotEmpty() || typing) VISIBLE else GONE
        theme()
        requestLayout()
    }

    private fun theme() {
        val b = UiTheme.base
        if (UiTheme.glassy) plate.box.glassy(UiTheme.glass) else plate.box.solid(SEARCH_GROUND)
        if (typing) plate.box.shadows = plate.box.shadows + Shadow(true, 0f, 0f, 0f, 2f, BLUE)
        plate.look(plate.box.fill)
        field.invalidate()
        glass.color = b.phoneInk2
        shown.setTextColor(if (query.isEmpty()) b.phoneInk2 else UiTheme.settings.ink)
        cancel.setTextColor(BLUE)
    }

    /** phone.css `.phone-search-caret`: 2 x 24 `#0a84ff`, blinking each half second (`caret-blink 1s steps(1)`). */
    private class Caret(context: Context) : View(context) {
        private val paint = Paint().apply { color = BLUE }
        init { uiKey = "phone-search-caret" }
        override fun onDraw(canvas: Canvas) {
            if (Motion.now() % 1000 < 500) canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            postInvalidateDelayed(500 - Motion.now() % 500)
        }
    }

    companion object {
        const val SEARCH_GROUND = 0x3D767680
        const val BLUE = 0xFF0A84FF.toInt()
    }
}

/** settings.css `.set-progress`: an 8 px bar, `rgb(var(--on) / .12)`, filled in `--set-blue` (200 ms linear). */
internal class ProgressBar(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val fill = Transition(0f)
    var fraction = 0f; set(v) { if (field != v) { field = v; fill.to(v, Motion.now(), UiTheme.duration(200), Easing.LINEAR); invalidate() } }

    init { uiKey = "set-progress"; UiTheme.link(this) { invalidate() } }

    override fun onDraw(canvas: Canvas) {
        val now = Motion.now()
        val r = height / 2f
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        paint.color = CssColor.withAlpha(UiTheme.base.on, 0.12f)
        canvas.drawRoundRect(rect, r, r, paint)
        // the inner span keeps its rounded ends and is scaled from the left, ends and all
        canvas.save(); canvas.scale(fill.value(now), 1f, 0f, 0f)
        paint.color = UiTheme.settings.setBlue
        canvas.drawRoundRect(rect, r, r, paint)
        canvas.restore()
        if (fill.running(now)) postInvalidateOnAnimation()
    }
}

/**
 * The page's name on its pill (`.set-page-title`): 26 px 700, 8 px 22 px in, a 1 px `--hairline` edge on
 * `--glass-strong`; Solid in both designs.
 */
internal class TitlePill(context: Context) : FrameLayout(context) {
    val title = text(context, "", TextStyle(26f, 700, -0.01f)).apply { uiKey = null }
    private val plate = Plate(this, 999f)

    init {
        uiKey = "set-page-title"
        background = plate
        plate.box.border = 1f
        setPadding(dp(23f), dp(9f), dp(23f), dp(9f))
        addView(title)
        UiTheme.link(this) { theme() }
        theme()
    }

    private fun theme() {
        plate.box.borderColor = UiTheme.settings.hairline
        plate.look(UiTheme.base.glassStrong)
        title.setTextColor(UiTheme.settings.ink)
    }
}

/**
 * One section in the list (`.set-nav-item`): its tile (36 px, 9 px rounded, its colour, a white 22 px icon), the name
 * (19 px 600) and a value (16 px `--ink-muted`, 130 px at most), 60 px tall, 14 px rounded; the current one on
 * `--set-current` with its value white and a 1 px ring round the tile (200 ms `--seamless`); pressed
 * `rgb(var(--on) / .08)`. On Glassy the current one takes the sheen and the glass shadow over its blue.
 */
@SuppressLint("ViewConstructor")
internal class NavItem(context: Context, val section: Section) : LinearLayout(context) {
    private val tile = FrameLayout(context)
    private val tileBox = Plate(tile, 9f)
    private val glyph = icon(context, section.icon, 22f)
    private val label = text(context, "set-nav-label", TextStyle(19f, 600)).apply { text = section.label }
    val value = text(context, "set-nav-value", TextStyle(16f)).apply { maxWidth = dp(130f) }
    private val plate = Plate(this, 14f, 200, Motion.seamless)
    var current = false; set(v) { if (field != v) { field = v; theme(animate = true) } }

    init {
        uiKey = "set-nav-item"
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = plate
        minimumHeight = dp(60f)
        setPadding(dp(10f), 0, dp(16f), 0)
        dividerDrawable = Gap(dp(14f))
        showDividers = SHOW_DIVIDER_MIDDLE
        clipChildren = false
        tile.uiKey = "set-tile"
        tile.background = tileBox
        tile.addView(glyph, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        glyph.color = -1
        tileBox.look(section.tint)
        addView(tile, LayoutParams(dp(36f), dp(36f)))
        addView(label, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(value)
        isClickable = true
        UiTheme.link(this) { theme(animate = false) }
        theme(animate = false)
    }

    private fun theme(animate: Boolean) {
        val st = UiTheme.settings
        val on = UiTheme.base.on
        val rest = if (current) st.setCurrent else 0
        plate.box.gradient = if (current && UiTheme.glassy) UiTheme.glass.glassySheen else null
        plate.box.shadows = if (current && UiTheme.glassy) UiTheme.glass.glassyShadow else emptyList()
        // `.is-current` is declared after `:active`: the current one stays blue while pressed
        val pressed = if (current) rest else CssColor.withAlpha(on, 0.08f)
        if (animate) plate.restTo(rest, pressed) else plate.look(rest, pressed)
        tileBox.box.shadows = if (current) listOf(Shadow(false, 0f, 0f, 0f, 1f, CssColor.withAlpha(on, 0.35f))) else emptyList()
        tile.invalidate()
        label.setTextColor(st.ink)
        value.setTextColor(if (current) -1 else st.inkMuted)
        invalidate()
    }
}

/**
 * An app's tile and name in the Home Screen list (`.set-app-name`): ours with their own picture (the [own] drawable),
 * another app on a 145° gradient of its hue with its initial; 40 px, 10 px rounded, 14 px before the name.
 */
@SuppressLint("ViewConstructor")
internal class AppName(context: Context, id: String, label: String, own: Int?) : LinearLayout(context) {
    private val tile = FrameLayout(context).apply { uiKey = "set-app-icon" }
    val name = text(context, "", TextStyle(19f, 500)).apply { text = label; uiKey = null }

    init {
        uiKey = "set-app-name"
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        dividerDrawable = Gap(dp(14f))
        showDividers = SHOW_DIVIDER_MIDDLE
        val box = Plate(tile, 10f)
        when {
            own != null -> box.box.image = android.graphics.BitmapFactory.decodeResource(resources, own)
            id == "PHONE_SCREEN" -> {
                // cast.css .app-mark-cast: a dark gradient under a white phone, 46% of the tile
                box.box.gradient = ro.rdstr.ui.tokens.Gradient(160f, intArrayOf(0xFF3A3A3C.toInt(), 0xFF1C1C1E.toInt()), floatArrayOf(0f, 1f))
                tile.addView(IconView(context, Icons.DEVICE_MOBILE, 40f * 0.46f).apply { color = -1 },
                    FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            }
            else -> {
                val hue = SettingsLogic.markHue(id).toFloat()
                box.box.gradient = ro.rdstr.ui.tokens.Gradient(145f, intArrayOf(hsl(hue, 0.45f, 0.34f), hsl(hue, 0.45f, 0.16f)), floatArrayOf(0f, 1f))
                tile.addView(text(context, "", TextStyle(18f, 700)).apply { text = label.take(1); uiKey = null; setTextColor(-1) },
                    FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            }
        }
        box.look(box.box.fill)
        tile.background = box
        addView(tile, LayoutParams(dp(40f), dp(40f)))
        addView(name)
        UiTheme.link(this) { name.setTextColor(UiTheme.settings.ink) }
    }

    private fun hsl(h: Float, s: Float, l: Float): Int {
        val c = (1 - kotlin.math.abs(2 * l - 1)) * s
        val x = c * (1 - kotlin.math.abs((h / 60f) % 2 - 1))
        val m = l - c / 2
        val (r, g, b) = when ((h / 60).toInt()) { 0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x); 3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x) }
        fun ch(v: Float) = Math.round((v + m) * 255).coerceIn(0, 255)
        return (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }
}

/**
 * Reverse Camera's preview (`.camera-feed.cam-preview`): the synthetic frame (screen.css `.camera-feed`, a post and a
 * kerb on the left, flipped when mirrored) under the guidelines as the vendor draws them on the 1024 x 600 screen
 * (Overlays.tsx `GuideLines`, core-car Camera.guides), raised or lowered by the line height; 12 px rounded.
 */
internal class CameraPreview(context: Context) : View(context) {
    var camera = CameraSettings(); set(v) { if (field != v) { field = v; invalidate() } }
    var eps = 0; set(v) { if (field != v) { field = v; invalidate() } }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 8f }
    private val clip = Path()
    private val path = Path()
    private val rect = RectF()
    private var ground: Shader? = null
    private var post: Shader? = null

    init { uiKey = "camera-feed.cam-preview"; contentDescription = "Preview of the guidelines" }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, Math.round(w * 600f / 1024f))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val r = 12 * resources.displayMetrics.density
        clip.reset(); clip.addRoundRect(0f, 0f, w.toFloat(), h.toFloat(), r, r, Path.Direction.CW)
        ground = LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(0xFF1A1D22.toInt(), 0xFF2A2D33.toInt(), 0xFF15171B.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        val ph = h * 0.30f
        post = LinearGradient(0f, 0f, 0f, ph * 0.36f, intArrayOf(GOLD, GOLD, DARK, DARK), floatArrayOf(0f, 0.5f, 0.5f, 1f), Shader.TileMode.REPEAT)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        canvas.save(); canvas.clipPath(clip)
        paint.shader = ground; canvas.drawRect(0f, 0f, w, h, paint); paint.shader = null
        canvas.save()
        if (camera.mirror) canvas.scale(-1f, 1f, w / 2, 0f)
        // the post: left 14%, bottom 34%, 3.4% x 30%, a yellow and dark stripe every 18%
        val pl = w * 0.14f; val pb = h * (1 - 0.34f); val ph = h * 0.30f; val pw = w * 0.034f
        canvas.save(); canvas.translate(pl, pb - ph)
        rect.set(0f, 0f, pw, ph)
        paint.shader = post
        val pr = 6 * resources.displayMetrics.density
        canvas.drawRoundRect(rect, pr, pr, paint); canvas.drawRect(0f, pr, pw, ph, paint)
        paint.shader = null
        canvas.restore()
        // the kerb: left -10%, bottom 14%, 42% x 2.4%, turned -10° about its left edge
        val kl = -0.1f * w; val kh = 0.024f * h; val kb = h * (1 - 0.14f)
        canvas.save(); canvas.rotate(-10f, kl, kb - kh / 2)
        paint.color = 0xFF5B5F66.toInt(); canvas.drawRect(kl, kb - kh, kl + 0.42f * w, kb, paint)
        canvas.restore()
        canvas.restore()
        if (camera.guidelines != Guidelines.OFF) drawLines(canvas, w / 1024f)
        canvas.restore()
    }

    private fun drawLines(canvas: Canvas, k: Float) {
        val g = Camera.guides(camera.guidelines, eps)
        canvas.save()
        canvas.scale(k, k)
        canvas.translate(0f, camera.linePos.toFloat())
        val curves = listOf(g.leftFix, g.leftControl, g.leftFar, g.rightFix, g.rightControl, g.rightFar)
        path.reset()
        for (i in listOf(0, 3)) {
            path.moveTo(curves[i].x.toFloat(), curves[i].y.toFloat())
            path.quadTo(curves[i + 1].x.toFloat(), curves[i + 1].y.toFloat(), curves[i + 2].x.toFloat(), curves[i + 2].y.toFloat())
        }
        line.color = YELLOW; canvas.drawPath(path, line)
        fun band(color: Int, vararg r: FloatArray) {
            canvas.save()
            clip2.reset(); for (b in r) clip2.addRect(b[0], b[1], b[2], b[3], Path.Direction.CW)
            canvas.clipPath(clip2); line.color = color; canvas.drawPath(path, line); canvas.restore()
        }
        band(RED, floatArrayOf(0f, (g.leftNear.y - 5).toFloat(), (g.leftNear.x + 5).toFloat(), (g.leftFix.y + 5).toFloat()),
            floatArrayOf((g.rightNear.x - 5).toFloat(), (g.rightNear.y - 5).toFloat(), 1024f, (g.rightFix.y + 5).toFloat()))
        band(GREEN, floatArrayOf(0f, (g.leftFar.y - 5).toFloat(), (maxOf(g.leftFar.x, g.leftMiddle.x) + 8).toFloat(), (g.leftMiddle.y - 3).toFloat()),
            floatArrayOf((minOf(g.rightFar.x, g.rightMiddle.x) - 8).toFloat(), (g.rightFar.y - 5).toFloat(), 1024f, (g.rightMiddle.y - 3).toFloat()))
        fun bar(a: ro.rdstr.car.Point, b: ro.rdstr.car.Point, color: Int) { line.color = color; canvas.drawLine(a.x.toFloat(), a.y.toFloat(), b.x.toFloat(), b.y.toFloat(), line) }
        bar(g.leftFar, g.rightFar, GREEN)
        bar(g.leftMiddle, g.rightMiddle, YELLOW)
        bar(g.leftNear, g.rightNear, RED)
        canvas.restore()
    }

    private val clip2 = Path()

    private companion object {
        const val GOLD = 0xFFE8B730.toInt()
        const val DARK = 0xFF2A2A2A.toInt()
        const val GREEN = 0xFF4CAF50.toInt()
        const val YELLOW = 0xFFFFFF00.toInt()
        const val RED = 0xFFFF0000.toInt()
    }
}

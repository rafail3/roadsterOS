package ro.rdstr.ui.home

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import ro.rdstr.car.App
import ro.rdstr.car.AudioSource
import ro.rdstr.car.CardPage
import ro.rdstr.car.CarEvent
import ro.rdstr.car.HomeCard
import ro.rdstr.car.RulesState
import ro.rdstr.ui.kit.CssBox
import ro.rdstr.ui.kit.CssColor
import ro.rdstr.ui.kit.KitText
import ro.rdstr.ui.kit.TextStyle
import ro.rdstr.ui.kit.UiTheme
import ro.rdstr.ui.tokens.Shadow
import ro.rdstr.ui.uiKey

/** What the card on home shows: core-car HomeCard's pages and the sound's details. */
data class CardState(
    val pages: List<CardPage> = listOf(CardPage.BT_OFF, CardPage.RADIO, CardPage.NAVIGATION),
    val shown: CardPage = CardPage.BT_OFF,
    val strip: AudioSource = AudioSource.NONE,
    val car: RulesState = RulesState(),
    val track: CarEvent.Track = CarEvent.Track(""),
    /** The track's cover, decoded; null without one. */
    val cover: Bitmap? = null,
    /** The playback position as last reported, and when (core-car PlaybackClock advances it while playing). */
    val positionMs: Long = 0,
    val positionAt: Long = 0,
    val radio: CarEvent.Radio = CarEvent.Radio(87500, CarEvent.Radio.Band.FM),
    val favorite: Boolean = false,
    /** The saved phones, by name, and the one connecting or failed. */
    val saved: List<String> = emptyList(),
    val connecting: String? = null,
    val failed: String? = null,
)

/** Everything the card's pages trigger (HomeCard.tsx, NowPlaying.tsx, the card's NoPhone). */
interface CardActions {
    fun showCard(page: CardPage)
    fun openApp(app: App)
    fun pressSound(page: CardPage, press: HomeCard.Press)
    fun toggleFavorite(khz: Int)
    /** `Bt.Connect` to a saved phone. */
    fun connect(device: String)
    fun openDevices()
    /** `setBluetooth(true)`. */
    fun turnOnBluetooth()
}

/** One page of the card: [bind] takes the card's state. */
interface CardPageView {
    val view: View
    fun bind(s: CardState)
}

/**
 * `.np`: the card's surface, 16 px round, `--glass` with a 1 px `--hairline` edge and a lit top line (by day a
 * brighter one and a faint drop). Glassy: the glass material, the hairline kept; by day the light shadow wins over
 * the glass one, as `.screen.light .np` is the more specific rule.
 */
class NpSurface {
    val box = CssBox().apply { radius = 16f; border = 1f }
    private val clip = Path()
    private val rect = RectF()

    fun theme(fill: Int? = null) {
        val b = UiTheme.base
        val light = UiTheme.light
        val shadows = if (light) listOf(Shadow(true, 0f, 1f, 0f, 0f, 0xCCFFFFFF.toInt()), Shadow(false, 0f, 1f, 2f, 0f, 0x0F141C26))
        else listOf(Shadow(true, 0f, 1f, 0f, 0f, 0x0FFFFFFF))
        if (UiTheme.glassy) {
            box.glassy(UiTheme.glass, fill ?: UiTheme.glass.glassyFill)
            if (light) box.shadows = shadows
        } else { box.solid(fill ?: b.glass); box.shadows = shadows }
        box.borderColor = b.hairline
    }

    fun draw(canvas: Canvas, view: View) = box.draw(canvas, view, view.width.toFloat(), view.height.toFloat())

    /** The surface's rounded shape, for content clipped to it (`overflow: hidden`). */
    fun clip(canvas: Canvas, view: View) {
        val r = 16f * view.resources.displayMetrics.density
        rect.set(0f, 0f, view.width.toFloat(), view.height.toFloat())
        clip.reset(); clip.addRoundRect(rect, r, r, Path.Direction.CW)
        canvas.clipPath(clip)
    }
}

/** A line of text in a [TextStyle], one line, ellipsized, with its parity key. */
internal fun Context.line(style: TextStyle, key: String?, ellipsize: Boolean = true) = KitText(this, style).apply {
    uiKey = key
    maxLines = 1
    isSingleLine = true
    if (ellipsize) this.ellipsize = TextUtils.TruncateAt.END
}

/** A column with [gap] canvas px between its children (CSS flex column with `gap`). */
open class Column(context: Context, gap: Float) : LinearLayout(context) {
    init {
        orientation = VERTICAL
        if (gap > 0f) {
            dividerDrawable = gapDrawable(gap * resources.displayMetrics.density)
            showDividers = SHOW_DIVIDER_MIDDLE
        }
    }
}

/** A row with [gap] canvas px between its children. */
open class Row(context: Context, gap: Float) : LinearLayout(context) {
    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        if (gap > 0f) {
            dividerDrawable = gapDrawable(gap * resources.displayMetrics.density)
            showDividers = SHOW_DIVIDER_MIDDLE
        }
    }
}

private fun gapDrawable(px: Float) = object : android.graphics.drawable.Drawable() {
    val s = (px + 0.5f).toInt()
    override fun getIntrinsicWidth() = s
    override fun getIntrinsicHeight() = s
    override fun draw(canvas: Canvas) = Unit
    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSPARENT
}

/** A box painted by a [CssBox] behind its children. */
@SuppressLint("ViewConstructor")
internal open class BoxFrame(context: Context, val box: CssBox = CssBox()) : FrameLayout(context) {
    init { setWillNotDraw(false) }
    override fun onDraw(canvas: Canvas) = box.draw(canvas, this, width.toFloat(), height.toFloat())
}

/** RadioApp.tsx `genreColor`: each programme type family's colour. */
object Genre {
    fun color(pty: Int): Int = when (pty) {
        1, 2, 3, 16, 17, 21, 22, 29 -> 0xFF0A84FF.toInt()
        4 -> 0xFF30D158.toInt()
        10 -> 0xFFFF375F.toInt()
        11 -> 0xFFFF9F0A.toInt()
        13, 14 -> 0xFFBF5AF2.toInt()
        24, 27 -> 0xFFFFD60A.toInt()
        25, 26, 28 -> 0xFF40C8E0.toInt()
        5, 6, 7, 8, 9, 12, 15, 18, 19, 20, 23 -> 0xFF5E5CE6.toInt()
        else -> 0xFF8E8E93.toInt()
    }

    /** `color-mix(in srgb, a p%, b)`. */
    fun mix(a: Int, p: Float, b: Int) = CssColor.lerp(b, a, p)
}

/** media.tsx `time`: m:ss of [ms], rounded to the second. */
fun mediaTime(ms: Long): String {
    val s = maxOf(0L, Math.round(ms / 1000.0))
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

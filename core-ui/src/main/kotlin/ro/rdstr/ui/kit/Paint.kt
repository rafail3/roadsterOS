package ro.rdstr.ui.kit

import ro.rdstr.ui.R
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.util.LruCache
import android.view.View
import ro.rdstr.ui.tokens.Frost
import ro.rdstr.ui.tokens.Glassy
import ro.rdstr.ui.tokens.Gradient
import ro.rdstr.ui.tokens.Shadow
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * One CSS box, painted in the CSS order: outer box-shadows (outside the border box only), the frost
 * (`backdrop-filter`), `background-color`, `background-image` (a gradient, then an image stretched over the box),
 * inset box-shadows (inside the padding box), the border. Lengths in canvas px; drawn at (0, 0) in device px.
 *
 * Geometry is rebuilt only when the size or a value changes; [draw] allocates nothing.
 * Blurred shadows are bitmaps made once in software and kept in [ShadowCache]: an outer one as a nine-slice of a
 * small template when the box is large enough, else at the box's size; inset ones at the box's size. Unblurred
 * shadows are exact paths. No blur is computed while drawing.
 */
class CssBox {
    var radius = 0f; set(v) { if (field != v) { field = v; dirty = true } }
    var fill = 0
    var gradient: Gradient? = null; set(v) { if (field !== v) { field = v; dirty = true } }
    var image: Bitmap? = null
    var border = 0f; set(v) { if (field != v) { field = v; dirty = true } }
    var borderColor = 0
    var shadows: List<Shadow> = emptyList(); set(v) { if (field !== v) { field = v; dirty = true } }
    var frost: Frost? = null
    /** Drawn where the frost goes, clipped to the box: what a lens shows through it (the glass knobs' track). */
    var under: ((Canvas) -> Unit)? = null
    /** The outer shadows' opacity (a shadow colour mixed toward transparent, as `color-mix(… p%, transparent)`). */
    var shadowAlpha = 1f

    private var dirty = true
    private var w = -1f
    private var h = -1f
    private var density = 0f
    private val box = Path()
    private val pad = Path()
    private val borderRing = Path()
    private val boxRect = RectF()
    private var shader: Shader? = null
    private var paths = arrayOfNulls<Path>(0)
    private var bitmaps = arrayOfNulls<ShadowCache.Piece>(0)
    private var hasOuter = false
    private var hasInset = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val src = Rect()
    private val dst = RectF()

    /** Sets fill, sheen, glass shadow and frost of the Glassy material from [g]. */
    fun glassy(g: Glassy, fill: Int = g.glassyFill) {
        this.fill = fill; gradient = g.glassySheen; shadows = g.glassyShadow; frost = g.glassyFrost
    }

    /** Solid again: no sheen, shadow or frost. */
    fun solid(fill: Int) {
        this.fill = fill; gradient = null; shadows = emptyList(); frost = null
    }

    private fun rebuild(width: Float, height: Float, d: Float) {
        w = width; h = height; density = d; dirty = false
        val r = min(radius * d, min(w, h) / 2f)
        val b = UiScale.borderPx(border, d)
        boxRect.set(0f, 0f, w, h)
        box.reset(); box.addRoundRect(boxRect, r, r, Path.Direction.CW)
        val pr = max(0f, r - b)
        pad.reset(); pad.addRoundRect(b, b, w - b, h - b, pr, pr, Path.Direction.CW)
        borderRing.reset()
        if (b > 0f) borderRing.op(box, pad, Path.Op.DIFFERENCE)
        shader = gradient?.let { g ->
            val line = CssGradient.line(g.angle, w, h)
            LinearGradient(line[0], line[1], line[2], line[3], g.colors, g.at, Shader.TileMode.CLAMP)
        }
        hasOuter = shadows.any { !it.inset }
        hasInset = shadows.any { it.inset }
        paths = arrayOfNulls(shadows.size)
        bitmaps = arrayOfNulls(shadows.size)
        for ((i, s) in shadows.withIndex()) {
            val sigma = CssBlur.sigma(s.blur) * d
            val x = s.x * d; val y = s.y * d; val spread = s.spread * d
            if (s.inset) {
                val l = b + x + spread; val t = b + y + spread
                val rr = l + (w - 2 * b) - 2 * spread; val bb = t + (h - 2 * b) - 2 * spread
                val sr = max(0f, pr - spread)
                if (CssBlur.maskRadius(sigma) <= 0f) {
                    val hole = Path().apply { if (rr > l && bb > t) addRoundRect(l, t, rr, bb, sr, sr, Path.Direction.CW) }
                    paths[i] = Path().apply { op(pad, hole, Path.Op.DIFFERENCE) }
                } else bitmaps[i] = ShadowCache.inset(w - 2 * b, h - 2 * b, pr, x, y, spread, sigma, s.color)
            } else {
                val sr = if (r > 0f) r + spread else 0f
                if (CssBlur.maskRadius(sigma) <= 0f) {
                    paths[i] = Path().apply { addRoundRect(x - spread, y - spread, w + x + spread, h + y + spread, sr, sr, Path.Direction.CW) }
                } else bitmaps[i] = ShadowCache.outer(w + 2 * spread, h + 2 * spread, sr, sigma, s.color)
            }
        }
    }

    fun draw(canvas: Canvas, view: View, width: Float, height: Float) {
        val d = view.resources.displayMetrics.density
        if (dirty || width != w || height != h || d != density) rebuild(width, height, d)
        // outer shadows, last listed lowest; never under the box itself
        if (hasOuter) {
            canvas.save()
            canvas.clipOutPath(box)
            for (i in shadows.size - 1 downTo 0) {
                val s = shadows[i]
                if (s.inset) continue
                paths[i]?.let { paint.shader = null; paint.color = CssColor.fade(s.color, shadowAlpha); canvas.drawPath(it, paint) }
                bitmaps[i]?.let { piece(canvas, it, s.x * d - s.spread * d, s.y * d - s.spread * d, shadowAlpha) }
            }
            canvas.restore()
        }
        frost?.let { Backdrop.draw(canvas, view, box, paint, it) }
        under?.let { canvas.save(); canvas.clipPath(box); it(canvas); canvas.restore() }
        if (CssColor.alpha(fill) > 0) { paint.shader = null; paint.color = fill; canvas.drawPath(box, paint) }
        shader?.let { paint.color = -1; paint.shader = it; canvas.drawPath(box, paint); paint.shader = null }
        image?.let { img ->
            canvas.save(); canvas.clipPath(box)
            src.set(0, 0, img.width, img.height); paint.color = -1
            canvas.drawBitmap(img, src, boxRect, paint)
            canvas.restore()
        }
        if (hasInset) {
            canvas.save()
            canvas.clipPath(pad)
            val b = UiScale.borderPx(border, d)
            for (i in shadows.size - 1 downTo 0) {
                val s = shadows[i]
                if (!s.inset) continue
                paths[i]?.let { paint.shader = null; paint.color = s.color; canvas.drawPath(it, paint) }
                bitmaps[i]?.let { piece(canvas, it, b, b, 1f) }
            }
            canvas.restore()
        }
        if (CssColor.alpha(borderColor) > 0 && border > 0f) { paint.shader = null; paint.color = borderColor; canvas.drawPath(borderRing, paint) }
    }

    /** A cached shadow bitmap with its box's top-left at ([left], [top]). */
    private fun piece(canvas: Canvas, p: ShadowCache.Piece, left: Float, top: Float, alpha: Float) {
        paint.shader = null; paint.color = CssColor.fade(-1, alpha)
        if (!p.sliced) {
            canvas.drawBitmap(p.bitmap, left - p.margin, top - p.margin, paint)
            return
        }
        // nine slices: the corners as drawn, the 2 px middle rows and columns stretched along the straight edges
        val m = p.margin; val c = p.corner
        val bw = p.bitmap.width; val bh = p.bitmap.height
        val xs = intArrayX; val ys = intArrayY
        xs[0] = 0; xs[1] = (m + c).toInt(); xs[2] = xs[1] + 2; xs[3] = bw
        ys[0] = 0; ys[1] = (m + c).toInt(); ys[2] = ys[1] + 2; ys[3] = bh
        val right = left + p.boxW; val bottom = top + p.boxH
        fx[0] = left - m; fx[1] = left - m + xs[1]; fx[2] = right + m - (bw - xs[2]); fx[3] = right + m
        fy[0] = top - m; fy[1] = top - m + ys[1]; fy[2] = bottom + m - (bh - ys[2]); fy[3] = bottom + m
        for (j in 0 until 3) for (i in 0 until 3) {
            src.set(xs[i], ys[j], xs[i + 1], ys[j + 1])
            dst.set(fx[i], fy[j], fx[i + 1], fy[j + 1])
            canvas.drawBitmap(p.bitmap, src, dst, paint)
        }
    }

    private val intArrayX = IntArray(4)
    private val intArrayY = IntArray(4)
    private val fx = FloatArray(4)
    private val fy = FloatArray(4)
}

/** Blurred box-shadow bitmaps, made once in software (BlurMaskFilter) and shared by every box of that shape. */
internal object ShadowCache {
    class Piece(val bitmap: Bitmap, val margin: Float, val corner: Float, val sliced: Boolean, val boxW: Float, val boxH: Float)

    private val cache = object : LruCache<String, Piece>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Piece) = value.bitmap.byteCount
    }

    private fun blurPaint(sigma: Float, color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        maskFilter = BlurMaskFilter(CssBlur.maskRadius(sigma), BlurMaskFilter.Blur.NORMAL)
    }

    /** An outer shadow of a [w] x [h] box of corner [r] (spread already in), device px. */
    fun outer(w: Float, h: Float, r: Float, sigma: Float, color: Int): Piece {
        val m = ceil(3 * sigma) + 1
        val c = ceil(r + 3 * sigma)
        val sliced = w >= 2 * c + 2 && h >= 2 * c + 2
        val bw = if (sliced) 2 * c + 2 else w
        val bh = if (sliced) 2 * c + 2 else h
        val key = "o$bw,$bh,$r,$sigma,$color"
        cache.get(key)?.let { return if (sliced) Piece(it.bitmap, m, c, true, w, h) else it }
        val bitmap = Bitmap.createBitmap(ceil(bw + 2 * m).toInt(), ceil(bh + 2 * m).toInt(), Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawRoundRect(m, m, m + bw, m + bh, r, r, blurPaint(sigma, color))
        val made = Piece(bitmap, m, c, sliced, w, h)
        cache.put(key, made)
        return made
    }

    /** An inset shadow inside a [w] x [h] padding box of corner [r], offset ([x], [y]), device px. */
    fun inset(w: Float, h: Float, r: Float, x: Float, y: Float, spread: Float, sigma: Float, color: Int): Piece {
        val key = "i$w,$h,$r,$x,$y,$spread,$sigma,$color"
        cache.get(key)?.let { return it }
        val bitmap = Bitmap.createBitmap(max(1, ceil(w).toInt()), max(1, ceil(h).toInt()), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.clipPath(Path().apply { addRoundRect(0f, 0f, w, h, r, r, Path.Direction.CW) })
        val far = 3 * sigma + abs(x) + abs(y) + abs(spread) + 2
        val ring = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addRect(-far, -far, w + far, h + far, Path.Direction.CW)
            val hr = max(0f, r - spread)
            if (w - 2 * spread > 0 && h - 2 * spread > 0) addRoundRect(x + spread, y + spread, x + w - spread, y + h - spread, hr, hr, Path.Direction.CW)
        }
        canvas.drawPath(ring, blurPaint(sigma, color))
        val made = Piece(bitmap, 0f, 0f, false, w, h)
        cache.put(key, made)
        return made
    }
}

/**
 * The Glassy frost without a live blur: one blurred, saturated and brightened snapshot of the background, made when
 * the background is set or changes ([capture]), and each glass surface draws the part of it under itself. Until a
 * background is captured, the frost of the flat `--canvas` colour stands in for it, which is exact over a flat
 * ground.
 *
 * The snapshot is a quarter of the screen's size: 256 x 150 px on the 1024 x 600 panel (150 KB), blurred at a
 * quarter of the token's radius and drawn back filtered, which a 28 px blur leaves unchanged to the eye.
 */
object Backdrop {
    private const val DOWN = 4
    private var frosted: Bitmap? = null
    private var shader: BitmapShader? = null
    private val originX = IntArray(1)
    private val originY = IntArray(1)
    private val at = IntArray(2)
    private val matrix = Matrix()
    private var flatFrost: Frost? = null
    private var flatMatrix = FloatArray(0)
    private var flatFrom = 0
    private var flatColor = 0

    /** Takes [background] (the layers behind the glass: canvas, glow, wallpaper) as it is drawn now. */
    fun capture(background: View, frost: Frost) {
        if (background.width == 0 || background.height == 0) return
        val bw = (background.width + DOWN - 1) / DOWN
        val bh = (background.height + DOWN - 1) / DOWN
        val bmp = frosted?.takeIf { it.width == bw && it.height == bh } ?: Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        bmp.eraseColor(0)
        canvas.scale(1f / DOWN, 1f / DOWN)
        background.draw(canvas)
        val px = IntArray(bw * bh)
        bmp.getPixels(px, 0, bw, 0, 0, bw, bh)
        val d = background.resources.displayMetrics.density
        CssBlur.gaussian(px, bw, bh, CssBlur.sigma(frost.blur) * d / DOWN)
        CssFilter.apply(CssFilter.saturateBrightness(frost.saturate, frost.brightness), px)
        bmp.setPixels(px, 0, bw, 0, 0, bw, bh)
        background.getLocationInWindow(at)
        originX[0] = at[0]; originY[0] = at[1]
        if (bmp !== frosted) { frosted = bmp; shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        background.invalidate()
    }

    /** A view inside one that draws frost (the glass rows of a glass card, the toggles of the glass drawer). */
    fun onGlass(view: View): Boolean {
        var p = view.parent
        while (p is View) {
            if (p.getTag(R.id.rdstr_glass) == true) return true
            p = p.parent
        }
        return false
    }

    /** A background has been captured (else the flat stand-in is drawn). */
    val captured: Boolean get() = shader != null

    /** Back to the flat stand-in (no background captured). */
    fun clear() { frosted = null; shader = null }

    internal fun draw(canvas: Canvas, view: View, shape: Path, paint: Paint, frost: Frost) {
        view.setTag(R.id.rdstr_glass, true)
        // glass inside glass: the browser's backdrop-filter sees the outer glass already painted there, so the inner one
        // keeps those pixels and adds its own fill and sheen over them, instead of the screen's frost
        if (onGlass(view)) return
        val s = shader
        if (s == null) {
            val ground = UiTheme.base.canvas
            if (flatFrost !== frost) { flatFrost = frost; flatMatrix = CssFilter.saturateBrightness(frost.saturate, frost.brightness); flatFrom = ground.inv() }
            if (flatFrom != ground) { flatFrom = ground; flatColor = CssFilter.apply(flatMatrix, ground) }
            paint.shader = null; paint.color = flatColor
            canvas.drawPath(shape, paint)
            return
        }
        view.getLocationInWindow(at)
        matrix.setScale(DOWN.toFloat(), DOWN.toFloat())
        matrix.postTranslate((originX[0] - at[0]).toFloat(), (originY[0] - at[1]).toFloat())
        s.setLocalMatrix(matrix)
        paint.color = -1; paint.shader = s
        canvas.drawPath(shape, paint)
        paint.shader = null
    }
}

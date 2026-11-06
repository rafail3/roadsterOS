package ro.rdstr.ui.kit

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// The arithmetic of the CSS the emulator draws with, free of Android so it runs in JVM tests.
// Colours are ARGB ints with straight alpha, as Android's; lengths are in canvas px unless named Px.

/** CSS colour values and their mixing. */
object CssColor {
    fun alpha(c: Int): Int = c ushr 24

    /** `rgb(var(--on) / a)`: the channels of [c] at alpha [a] (0..1), rounded to 8 bits as browsers store it. */
    fun withAlpha(c: Int, a: Float): Int = ((a.coerceIn(0f, 1f) * 255f).roundToInt() shl 24) or (c and 0xFFFFFF)

    /** `color-mix(in srgb, c p, transparent)` and `opacity: p`: the alpha of [c] scaled by [p]. */
    fun fade(c: Int, p: Float): Int = withAlpha(c, alpha(c) / 255f * p)

    /** The value a CSS transition between two colours shows at [t]: interpolated premultiplied, as browsers do. */
    fun lerp(from: Int, to: Int, t: Float): Int {
        if (t <= 0f) return from
        if (t >= 1f) return to
        val a1 = alpha(from) / 255f
        val a2 = alpha(to) / 255f
        val a = a1 + (a2 - a1) * t
        if (a <= 0f) return 0
        fun ch(shift: Int): Int {
            val c1 = (from shr shift and 0xFF) * a1
            val c2 = (to shr shift and 0xFF) * a2
            return ((c1 + (c2 - c1) * t) / a).roundToInt().coerceIn(0, 255)
        }
        return ((a * 255f).roundToInt() shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** [top] drawn over [bottom] (source-over). */
    fun over(top: Int, bottom: Int): Int {
        val at = alpha(top) / 255f
        val ab = alpha(bottom) / 255f
        val a = at + ab * (1f - at)
        if (a <= 0f) return 0
        fun ch(shift: Int): Int {
            val ct = (top shr shift and 0xFF) * at
            val cb = (bottom shr shift and 0xFF) * ab * (1f - at)
            return ((ct + cb) / a).roundToInt().coerceIn(0, 255)
        }
        return ((a * 255f).roundToInt() shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}

/** CSS filter functions as Android `ColorMatrix` arrays (4x5, row-major, offsets in 0..255). */
object CssFilter {
    /** `saturate(s) brightness(b)`, in that order, on sRGB values (Filter Effects 1, the shorthand matrices). */
    fun saturateBrightness(s: Float, b: Float): FloatArray = floatArrayOf(
        (0.213f + 0.787f * s) * b, (0.715f - 0.715f * s) * b, (0.072f - 0.072f * s) * b, 0f, 0f,
        (0.213f - 0.213f * s) * b, (0.715f + 0.285f * s) * b, (0.072f - 0.072f * s) * b, 0f, 0f,
        (0.213f - 0.213f * s) * b, (0.715f - 0.715f * s) * b, (0.072f + 0.928f * s) * b, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )

    /** [m] applied to one colour's straight channels. */
    fun apply(m: FloatArray, c: Int): Int {
        val r = c shr 16 and 0xFF
        val g = c shr 8 and 0xFF
        val b = c and 0xFF
        val a = c ushr 24
        fun row(i: Int) = (m[i] * r + m[i + 1] * g + m[i + 2] * b + m[i + 3] * a + m[i + 4]).roundToInt().coerceIn(0, 255)
        return (row(15) shl 24) or (row(0) shl 16) or (row(5) shl 8) or row(10)
    }

    /** [m] applied in place to every pixel of [pixels]. */
    fun apply(m: FloatArray, pixels: IntArray) {
        for (i in pixels.indices) pixels[i] = apply(m, pixels[i])
    }
}

/** `blur()` and box-shadow blur. */
object CssBlur {
    /** A CSS blur length is twice the Gaussian's standard deviation (CSS Backgrounds 3, Filter Effects 1). */
    fun sigma(blur: Float): Float = blur / 2f

    /**
     * The radius to give Android's `BlurMaskFilter` for a Gaussian of [sigmaPx]: Skia turns a radius r into
     * sigma 0.57735 r + 0.5. 0 when the blur is too small to draw (a sharp edge).
     */
    fun maskRadius(sigmaPx: Float): Float = ((sigmaPx - 0.5f) / 0.57735f).coerceAtLeast(0f)

    /**
     * A Gaussian blur of [sigma] px over [pixels] ([w] x [h], ARGB), in place: three box blurs per axis, the
     * approximation the SVG and Filter Effects specs give for feGaussianBlur, edges clamped.
     */
    fun gaussian(pixels: IntArray, w: Int, h: Int, sigma: Float) {
        val d = floor(sigma * 3.0 * sqrt(2.0 * Math.PI) / 4.0 + 0.5).toInt()
        if (d < 2) return
        // odd d: three boxes of d centred; even d: two boxes of d off centre by half a pixel each way, then d + 1
        val passes = if (d % 2 == 1) arrayOf(intArrayOf(d / 2, d / 2), intArrayOf(d / 2, d / 2), intArrayOf(d / 2, d / 2))
        else arrayOf(intArrayOf(d / 2, d / 2 - 1), intArrayOf(d / 2 - 1, d / 2), intArrayOf(d / 2, d / 2))
        val line = IntArray(maxOf(w, h))
        for (p in passes) for (y in 0 until h) box(pixels, y * w, 1, w, p[0], p[1], line)
        for (p in passes) for (x in 0 until w) box(pixels, x, w, h, p[0], p[1], line)
    }

    /** One box pass along a line of [n] pixels from [start] every [stride], summing [left] before and [right] after. */
    private fun box(px: IntArray, start: Int, stride: Int, n: Int, left: Int, right: Int, line: IntArray) {
        for (i in 0 until n) line[i] = px[start + i * stride]
        val size = left + right + 1
        var a = 0; var r = 0; var g = 0; var b = 0
        fun at(i: Int) = line[i.coerceIn(0, n - 1)]
        for (k in -left..right) { val c = at(k); a += c ushr 24; r += c shr 16 and 0xFF; g += c shr 8 and 0xFF; b += c and 0xFF }
        for (i in 0 until n) {
            px[start + i * stride] = ((a + size / 2) / size shl 24) or ((r + size / 2) / size shl 16) or ((g + size / 2) / size shl 8) or ((b + size / 2) / size)
            val out = at(i - left)
            val inn = at(i + right + 1)
            a += (inn ushr 24) - (out ushr 24)
            r += (inn shr 16 and 0xFF) - (out shr 16 and 0xFF)
            g += (inn shr 8 and 0xFF) - (out shr 8 and 0xFF)
            b += (inn and 0xFF) - (out and 0xFF)
        }
    }
}

/** `linear-gradient(angle, …)` geometry. */
object CssGradient {
    /**
     * The gradient line of a CSS [angle] (0 = to top, clockwise) over a [w] x [h] box: start x, start y, end x,
     * end y. Its length makes the corners take the first and last colours, as CSS Images 3 defines.
     */
    fun line(angle: Float, w: Float, h: Float): FloatArray {
        val a = Math.toRadians(angle.toDouble())
        val dx = sin(a).toFloat()
        val dy = -cos(a).toFloat()
        val half = (abs(w * dx) + abs(h * dy)) / 2f
        return floatArrayOf(w / 2f - dx * half, h / 2f - dy * half, w / 2f + dx * half, h / 2f + dy * half)
    }
}

/** A timing function: output progress for input progress 0..1. */
fun interface Easing {
    fun at(t: Float): Float

    companion object {
        val LINEAR = Easing { it }
        /** CSS `ease`. */
        val EASE: Easing = CssBezier(0.25f, 0.1f, 0.25f, 1f)
    }
}

/** `cubic-bezier(x1, y1, x2, y2)`, solved as browsers do (Newton's method, then bisection). */
class CssBezier(x1: Float, private val y1: Float, x2: Float, private val y2: Float) : Easing {
    private val cx = 3.0 * x1
    private val bx = 3.0 * (x2 - x1) - cx
    private val ax = 1.0 - cx - bx
    private val cy = 3.0 * y1
    private val by = 3.0 * (y2 - y1) - cy
    private val ay = 1.0 - cy - by

    private fun x(t: Double) = ((ax * t + bx) * t + cx) * t
    private fun y(t: Double) = ((ay * t + by) * t + cy) * t
    private fun dx(t: Double) = (3.0 * ax * t + 2.0 * bx) * t + cx

    private fun solve(target: Double): Double {
        var t = target
        repeat(8) {
            val err = x(t) - target
            if (abs(err) < EPS) return t
            val d = dx(t)
            if (abs(d) < 1e-6) return@repeat
            t -= err / d
        }
        var lo = 0.0
        var hi = 1.0
        t = target
        while (lo < hi) {
            val v = x(t)
            if (abs(v - target) < EPS) return t
            if (target > v) lo = t else hi = t
            t = (hi - lo) / 2.0 + lo
            if (hi - lo < EPS) break
        }
        return t
    }

    override fun at(t: Float): Float = when {
        t <= 0f -> 0f
        t >= 1f -> 1f
        else -> y(solve(t.toDouble())).toFloat()
    }

    private companion object { const val EPS = 1e-7 }
}

/** CSS `linear(…)`: [values] at input points [at], straight lines between them. */
class CssStops(private val values: FloatArray, private val at: FloatArray) : Easing {
    override fun at(t: Float): Float {
        val n = values.size
        if (t <= at[0]) return values[0]
        if (t >= at[n - 1]) return values[n - 1]
        var i = 0
        while (i < n - 2 && t > at[i + 1]) i++
        val span = at[i + 1] - at[i]
        if (span <= 0f) return values[i + 1]
        return values[i] + (values[i + 1] - values[i]) * (t - at[i]) / span
    }
}

/**
 * A CSS transition of one number: from where it is to a new value over a duration, along an easing. Retargeted
 * mid-way it starts from the current value; sent back to where it came from, it takes the shortened duration
 * CSS Transitions 1 gives a reversed transition. Times in ms.
 */
class Transition(initial: Float) {
    private var from = initial
    var target = initial; private set
    private var start = 0L
    private var duration = 0L
    private var easing = Easing.LINEAR
    private var shortening = 1f

    private fun progress(now: Long): Float =
        if (duration <= 0L || now - start >= duration) 1f else easing.at(((now - start).toFloat() / duration).coerceAtLeast(0f))

    fun value(now: Long): Float = from + (target - from) * progress(now)
    fun running(now: Long): Boolean = duration > 0L && now - start < duration

    fun to(next: Float, now: Long, durationMs: Long, ease: Easing) {
        if (next == target) return
        val current = value(now)
        var d = durationMs
        var f = 1f
        if (running(now) && next == from) {
            f = (progress(now) * shortening + 1f - shortening).coerceIn(0f, 1f)
            d = (durationMs * f).toLong()
        }
        from = current; target = next; start = now; duration = d; easing = ease; shortening = f
    }

    fun snap(v: Float) { from = v; target = v; duration = 0L; shortening = 1f }
}

/** A CSS transition of a colour, interpolated premultiplied. */
class ColorTransition(initial: Int) {
    private var from = initial
    var target = initial; private set
    private val p = Transition(1f)

    fun value(now: Long): Int = CssColor.lerp(from, target, p.value(now))
    fun running(now: Long): Boolean = p.running(now)

    fun to(next: Int, now: Long, durationMs: Long, ease: Easing) {
        if (next == target) return
        // sent back to where it came from: shortened by how far it got (the first reversal's factor)
        val d = if (p.running(now) && next == from) (durationMs * p.value(now)).toLong() else durationMs
        from = value(now); target = next
        p.snap(0f)
        p.to(1f, now, d, ease)
    }

    fun snap(c: Int) { from = c; target = c; p.snap(1f) }
}

/**
 * A damped spring on one value (unit mass), stepped exactly as glisse's (`src/internal/spring.ts`): the closed-form
 * solution per step, settled once within 1e-4 of the target and slower than 0.001 per second.
 */
class Spring(initial: Double, var stiffness: Double, var damping: Double) {
    var value = initial; private set
    var velocity = 0.0; private set
    var target = initial; private set
    var moving = false; private set

    fun set(next: Double, instant: Boolean = false) {
        target = next
        if (instant) { value = next; velocity = 0.0; moving = false } else moving = true
    }

    fun tune(k: Double, d: Double) { stiffness = k; damping = d }

    /** Advances [dt] seconds; true while it still moves. */
    fun step(dt: Double): Boolean {
        if (!moving) return false
        val x = value - target
        val v = velocity
        val half = damping / 2.0
        val disc = half * half - stiffness
        if (abs(disc) < 1e-4) {
            val decay = exp(-half * dt)
            val b = v + half * x
            value = target + decay * (x + b * dt)
            velocity = decay * (v - half * b * dt)
        } else if (disc > 0) {
            val root = sqrt(disc)
            val r1 = -half + root
            val r2 = -half - root
            val a = (v - r2 * x) / (r1 - r2)
            val b = x - a
            value = target + a * exp(r1 * dt) + b * exp(r2 * dt)
            velocity = a * r1 * exp(r1 * dt) + b * r2 * exp(r2 * dt)
        } else {
            val omega = sqrt(-disc)
            val decay = exp(-half * dt)
            val c = cos(omega * dt)
            val s = sin(omega * dt)
            value = target + decay * (x * c + ((v + half * x) / omega) * s)
            velocity = decay * (v * c - ((half * v + stiffness * x) / omega) * s)
        }
        if (abs(value - target) < 1e-4 && abs(velocity) < 0.001) { value = target; velocity = 0.0; moving = false }
        return moving
    }
}

/** Canvas px to device px. */
object UiScale {
    /** The emulator's canvas width (`--canvas-w`): the layout is drawn so that it spans the panel. */
    const val CANVAS_W = 1280f

    /** The density that puts the 1280 px canvas across a panel [widthPx] wide: 128 on the 1024 px panel. */
    fun densityDpi(widthPx: Int): Int = (160f * widthPx / CANVAS_W).roundToInt()

    /** [css] px at [density] (device px per dp). */
    fun px(css: Float, density: Float): Float = css * density

    /** A CSS border width in device px: whole device pixels, never under one, as Chrome rounds them. */
    fun borderPx(css: Float, density: Float): Float = if (css <= 0f) 0f else floor(css * density + 1e-3f).coerceAtLeast(1f)
}

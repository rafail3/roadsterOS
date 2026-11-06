package ro.rdstr.gradle.display

import kotlin.math.roundToInt

/** A custom property's value once its `var()`s are substituted, typed the way the Android side uses it. */
sealed interface TokenValue {
    /** Kotlin type of the generated field. */
    val type: String
    /** Kotlin expression of the value. */
    fun kotlin(): String
}

/** An sRGB colour, straight (not premultiplied) alpha, as Android's ARGB int. */
data class ColorValue(val argb: Int) : TokenValue {
    override val type get() = "Int"
    override fun kotlin() = "0x%08X.toInt()".format(argb)

    companion object {
        fun of(r: Double, g: Double, b: Double, a: Double): ColorValue {
            fun ch(v: Double) = v.roundToInt().coerceIn(0, 255)
            return ColorValue((ch(a * 255) shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b))
        }
    }
    val a get() = (argb ushr 24 and 0xFF) / 255.0
    val r get() = (argb shr 16 and 0xFF).toDouble()
    val g get() = (argb shr 8 and 0xFF).toDouble()
    val b get() = (argb and 0xFF).toDouble()
}

/** A length in canvas pixels; inside our app on Android one canvas pixel is one dp (density 128 = scale 0.8). */
data class DpValue(val dp: Double) : TokenValue {
    override val type get() = "Float"
    override fun kotlin() = num(dp)
}

data class MsValue(val ms: Double) : TokenValue {
    override val type get() = "Long"
    override fun kotlin() = "${ms.roundToInt()}L"
}

data class NumberValue(val n: Double) : TokenValue {
    override val type get() = "Float"
    override fun kotlin() = num(n)
}

data class BezierValue(val x1: Double, val y1: Double, val x2: Double, val y2: Double) : TokenValue {
    override val type get() = "Bezier"
    override fun kotlin() = "Bezier(${num(x1)}, ${num(y1)}, ${num(x2)}, ${num(y2)})"
}

/** `linear()` easing: output [values] at input [at], both 0..1. */
data class StopsValue(val values: List<Double>, val at: List<Double>) : TokenValue {
    override val type get() = "Stops"
    override fun kotlin() = "Stops(${floats(values)}, ${floats(at)})"
}

data class GradientValue(val angle: Double, val colors: List<ColorValue>, val at: List<Double>) : TokenValue {
    override val type get() = "Gradient"
    override fun kotlin() = "Gradient(${num(angle)}, intArrayOf(${colors.joinToString { it.kotlin() }}), ${floats(at)})"
}

data class Shadow(val inset: Boolean, val x: Double, val y: Double, val blur: Double, val spread: Double, val color: ColorValue)

data class ShadowsValue(val shadows: List<Shadow>) : TokenValue {
    override val type get() = "List<Shadow>"
    override fun kotlin() = shadows.joinToString(prefix = "listOf(", postfix = ")") {
        "Shadow(${it.inset}, ${num(it.x)}, ${num(it.y)}, ${num(it.blur)}, ${num(it.spread)}, ${it.color.kotlin()})"
    }
}

/** `backdrop-filter: blur() saturate() brightness()`. */
data class FrostValue(val blur: Double, val saturate: Double, val brightness: Double) : TokenValue {
    override val type get() = "Frost"
    override fun kotlin() = "Frost(${num(blur)}, ${num(saturate)}, ${num(brightness)})"
}

data class FontsValue(val families: List<String>) : TokenValue {
    override val type get() = "List<String>"
    override fun kotlin() = families.joinToString(prefix = "listOf(", postfix = ")") { "\"$it\"" }
}

data class UrlValue(val url: String) : TokenValue {
    override val type get() = "String"
    override fun kotlin() = "\"$url\""
}

private fun num(v: Double): String {
    val f = v.toFloat()
    return if (f == f.toLong().toFloat()) "${f.toLong()}f" else "${f}f"
}

private fun floats(list: List<Double>) = list.joinToString(prefix = "floatArrayOf(", postfix = ")") { num(it) }

/** Parses substituted CSS values into [TokenValue]s. Anything it does not know stops the build. */
object Values {
    fun parse(raw: String, where: String): TokenValue {
        val s = raw.trim()
        try {
            color(s)?.let { return it }
            channels(s)?.let { return it }
            return when {
                s.startsWith("linear-gradient(") -> gradient(args(s, "linear-gradient"))
                s.startsWith("linear(") -> stops(args(s, "linear"))
                s.startsWith("cubic-bezier(") -> args(s, "cubic-bezier").map { it.toDouble() }.let { BezierValue(it[0], it[1], it[2], it[3]) }
                s.startsWith("url(") -> UrlValue(args(s, "url").single().trim('"', '\''))
                s.startsWith("\"") || s.startsWith("'") -> FontsValue(Css.splitTop(s, ',').map { it.trim('"', '\'') })
                Regex("""^(blur|saturate|brightness)\(""").containsMatchIn(s) -> frost(s)
                Regex("""^-?[\d.]+ms$""").matches(s) -> MsValue(s.removeSuffix("ms").toDouble())
                Regex("""^-?[\d.]+s$""").matches(s) -> MsValue(s.removeSuffix("s").toDouble() * 1000)
                Regex("""^-?[\d.]+px$""").matches(s) -> DpValue(s.removeSuffix("px").toDouble())
                s == "0" -> NumberValue(0.0)
                Regex("""^-?[\d.]+$""").matches(s) -> NumberValue(s.toDouble())
                else -> shadows(s) ?: throw DisplayBuildException("unknown value")
            }
        } catch (e: DisplayBuildException) {
            throw DisplayBuildException("$where: ${e.message}: `$s`")
        } catch (e: RuntimeException) {
            throw DisplayBuildException("$where: cannot read `$s` (${e.message})")
        }
    }

    /** `#rgb[a]`, `#rrggbb[aa]`, `rgb()`/`rgba()` in either syntax, `color-mix(in srgb, …)` and the named colours used. */
    fun color(s: String): ColorValue? {
        val t = s.trim().lowercase()
        when (t) {
            "transparent" -> return ColorValue(0)
            "white" -> return ColorValue(0xFFFFFFFF.toInt())
            "black" -> return ColorValue(0xFF000000.toInt())
        }
        if (t.startsWith("#")) {
            val h = t.substring(1)
            val full = when (h.length) {
                3, 4 -> h.map { "$it$it" }.joinToString("")
                6, 8 -> h
                else -> return null
            }
            if (!full.all { it.isDigit() || it in 'a'..'f' }) return null
            val v = full.chunked(2).map { it.toInt(16).toDouble() }
            return ColorValue.of(v[0], v[1], v[2], if (v.size == 4) v[3] / 255 else 1.0)
        }
        if (t.startsWith("rgb(") || t.startsWith("rgba(")) {
            val inner = t.substring(t.indexOf('(') + 1, t.lastIndexOf(')'))
            val parts = inner.replace("/", " / ").replace(",", " ").split(Regex("\\s+")).filter { it.isNotEmpty() }
            val slash = parts.indexOf("/")
            val rgb = (if (slash >= 0) parts.subList(0, slash) else parts.take(3)).map { channel(it) }
            val alphaText = if (slash >= 0) parts.getOrNull(slash + 1) else parts.getOrNull(3)
            val a = alphaText?.let { if (it.endsWith("%")) it.removeSuffix("%").toDouble() / 100 else it.toDouble() } ?: 1.0
            if (rgb.size != 3) throw DisplayBuildException("rgb() needs three channels")
            return ColorValue.of(rgb[0], rgb[1], rgb[2], a)
        }
        if (t.startsWith("color-mix(")) return mix(args(t, "color-mix"))
        return null
    }

    /** `255 255 255`: the ink's channels, kept as an opaque colour. */
    private fun channels(s: String): ColorValue? {
        val m = Regex("""^(\d+) (\d+) (\d+)$""").find(s) ?: return null
        val (r, g, b) = m.destructured
        return ColorValue.of(r.toDouble(), g.toDouble(), b.toDouble(), 1.0)
    }

    private fun channel(p: String) = if (p.endsWith("%")) p.removeSuffix("%").toDouble() * 2.55 else p.toDouble()

    /** CSS Color 5 `color-mix()` in sRGB: premultiplied mixing, the percentages normalised, a sum under 100 % fades it. */
    private fun mix(a: List<String>): ColorValue {
        if (a.size != 3 || a[0].trim() != "in srgb") throw DisplayBuildException("color-mix() only in srgb with two colours")
        fun part(p: String): Pair<ColorValue, Double?> {
            val w = Css.words(p)
            val pct = w.lastOrNull()?.takeIf { it.endsWith("%") }?.removeSuffix("%")?.toDouble()?.div(100)
            val c = color((if (pct != null) w.dropLast(1) else w).joinToString(" ")) ?: throw DisplayBuildException("color-mix() colour")
            return c to pct
        }
        val (c1, p1o) = part(a[1])
        val (c2, p2o) = part(a[2])
        var p1 = p1o ?: (p2o?.let { 1 - it } ?: 0.5)
        var p2 = p2o ?: (1 - p1)
        val sum = p1 + p2
        val fade = if (sum < 1) sum else 1.0
        p1 /= sum; p2 /= sum
        val alpha = c1.a * p1 + c2.a * p2
        if (alpha == 0.0) return ColorValue(0)
        fun ch(x: Double, y: Double) = (x * c1.a * p1 + y * c2.a * p2) / alpha
        return ColorValue.of(ch(c1.r, c2.r), ch(c1.g, c2.g), ch(c1.b, c2.b), alpha * fade)
    }

    private fun gradient(a: List<String>): GradientValue {
        val first = a.first().trim()
        val angle = when {
            first.endsWith("deg") -> first.removeSuffix("deg").toDouble()
            first.endsWith("turn") -> first.removeSuffix("turn").toDouble() * 360
            first == "to top" -> 0.0
            first == "to right" -> 90.0
            first == "to bottom" -> 180.0
            first == "to left" -> 270.0
            first.startsWith("to ") -> throw DisplayBuildException("corner gradients")
            else -> null
        }
        val stops = if (angle != null) a.drop(1) else a
        val colors = mutableListOf<ColorValue>()
        val at = mutableListOf<Double?>()
        for (stop in stops) {
            val w = Css.words(stop)
            val pos = w.last().takeIf { it.endsWith("%") || it.endsWith("px") }
            if (pos != null && pos.endsWith("px")) throw DisplayBuildException("gradient stops in px")
            colors += color((if (pos != null) w.dropLast(1) else w).joinToString(" ")) ?: throw DisplayBuildException("gradient colour")
            at += pos?.removeSuffix("%")?.toDouble()?.div(100)
        }
        return GradientValue(angle ?: 180.0, colors, positions(at))
    }

    private fun stops(a: List<String>): StopsValue {
        val values = mutableListOf<Double>()
        val at = mutableListOf<Double?>()
        for (p in a) {
            val w = Css.words(p)
            values += w[0].toDouble()
            at += w.getOrNull(1)?.removeSuffix("%")?.toDouble()?.div(100)
            if (w.size > 2) throw DisplayBuildException("linear() points with two positions")
        }
        return StopsValue(values, positions(at))
    }

    /** CSS stop positions: first 0, last 1, never going back, runs without one spread evenly. */
    fun positions(given: List<Double?>): List<Double> {
        val p = given.toMutableList()
        if (p.first() == null) p[0] = 0.0
        if (p.last() == null) p[p.lastIndex] = 1.0
        var max = 0.0
        for (i in p.indices) p[i]?.let { if (it < max) p[i] = max else max = it }
        var i = 0
        while (i < p.size) {
            if (p[i] == null) {
                val from = i - 1
                var to = i
                while (p[to] == null) to++
                val a = p[from]!!
                val b = p[to]!!
                for (k in i until to) p[k] = a + (b - a) * (k - from) / (to - from)
                i = to
            }
            i++
        }
        return p.map { it!! }
    }

    private fun frost(s: String): FrostValue {
        var blur = 0.0
        var saturate = 1.0
        var brightness = 1.0
        for (w in Css.words(s)) {
            val name = w.substringBefore('(')
            val v = w.substringAfter('(').removeSuffix(")")
            when (name) {
                "blur" -> blur = v.removeSuffix("px").toDouble()
                "saturate" -> saturate = v.toDouble()
                "brightness" -> brightness = v.toDouble()
                else -> throw DisplayBuildException("filter $name()")
            }
        }
        return FrostValue(blur, saturate, brightness)
    }

    /** `[inset] x y [blur [spread]] colour`, comma separated. */
    private fun shadows(s: String): ShadowsValue? {
        val out = Css.splitTop(s, ',').map { part ->
            val w = Css.words(part)
            val inset = "inset" in w
            val rest = w.filter { it != "inset" }
            val lengths = rest.filter { it == "0" || Regex("""^-?[\d.]+px$""").matches(it) }.map { it.removeSuffix("px").toDouble() }
            val colorText = rest.filterNot { it == "0" || Regex("""^-?[\d.]+px$""").matches(it) }.joinToString(" ")
            if (lengths.size < 2 || lengths.size > 4) return null
            val color = color(colorText) ?: return null
            Shadow(inset, lengths[0], lengths[1], lengths.getOrElse(2) { 0.0 }, lengths.getOrElse(3) { 0.0 }, color)
        }
        return ShadowsValue(out)
    }

    /** The comma separated arguments of `fn(…)`. */
    fun args(s: String, fn: String): List<String> {
        val open = s.indexOf('(')
        if (!s.startsWith("$fn(") || Css.matching(s, open) != s.lastIndex) throw DisplayBuildException("$fn() must be the whole value")
        return Css.splitTop(s.substring(open + 1, s.lastIndex), ',')
    }
}

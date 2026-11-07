package ro.rdstr.gradle.apk

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/**
 * Visual parity: compares the browser emulator's capture (`emulator/scripts/capture-layout.mjs`, build/parity/web)
 * with the Android one (core-ui ParityCapture, build/parity/android), scene by scene and element by element.
 *
 * Elements are matched by key (the scheme is described in capture-layout.mjs and core-ui UiKey.kt). A scene fails on
 * an element missing or extra on Android, a box off by more than [BOX_DP] dp, a colour that is not exactly the same,
 * a font size or weight, an opacity, or on its pixels (see [Limits]). Only scenes Android has a capture for are
 * compared; the others are listed.
 */
object Parity {
    const val BOX_DP = 0.5

    /**
     * Pixels are compared on their premultiplied channels. Inside a flat area (a pixel the same as its 8 neighbours in
     * the browser picture) a difference over [tolerance] counts; on an edge, where the two renderers anti-alias the
     * same outline differently, only one over [edgeTolerance] does (a shape off by half a pixel gives about 128).
     * The scene fails when more than [maxPercent] % of the painted pixels count.
     */
    data class Limits(val tolerance: Int = 16, val edgeTolerance: Int = 96, val maxPercent: Double = 0.0)

    class Element(val key: String, val box: DoubleArray, val style: Map<String, Any?>, val text: String?)
    class Layout(val scene: String, val root: String?, val elements: List<Element>)

    class Pixels(val painted: Int, val differing: Int, val edges: Int, val over: Int, val maxDelta: Int, val diff: BufferedImage) {
        val percent get() = if (painted == 0) 0.0 else over * 100.0 / painted
    }

    /**
     * What a scene is accepted with (the baseline, core-ui/parity-baseline.json): its share of counted pixels and its
     * number of layout findings. A scene fails only when it gets worse than that.
     */
    data class Accepted(val percent: Double, val layout: Int)

    class Scene(val name: String, val layout: List<String>, val pixels: Pixels?, val pixelFailure: String?, val acceptedLayout: Int = 0) {
        // ponytail: layout findings are accepted by count, so one fixed and another new in the same scene go unseen;
        // the pixel share still catches a visible change. Accept by element key if that ever matters.
        val failures get() = (if (layout.size > acceptedLayout) layout else emptyList()) + listOfNotNull(pixelFailure)
    }

    class Report(val scenes: List<Scene>, val notBuilt: List<String>) {
        val failures get() = scenes.flatMap { s -> s.failures.map { "${s.name}: $it" } }
    }

    @Suppress("UNCHECKED_CAST")
    fun read(json: String): Layout {
        val o = JsonSlurper().parseText(json) as Map<String, Any?>
        val elements = (o["elements"] as List<Map<String, Any?>>).map { e ->
            val b = e["box"] as Map<String, Number>
            Element(e["key"] as String, doubleArrayOf(b.getValue("x").toDouble(), b.getValue("y").toDouble(), b.getValue("w").toDouble(), b.getValue("h").toDouble()),
                e["style"] as Map<String, Any?>, e["text"] as String?)
        }
        return Layout(o["scene"] as String, o["root"] as String?, elements)
    }

    /** Layout differences: what Android draws against what the browser draws. */
    fun compare(web: Layout, android: Layout): List<String> {
        val out = mutableListOf<String>()
        if (web.root != android.root) out += "root is ${android.root} on Android, ${web.root} in the browser"
        val a = android.elements.associateBy { it.key }
        val w = web.elements.associateBy { it.key }
        for (key in w.keys - a.keys) out += "$key: missing on Android"
        for (key in a.keys - w.keys) out += "$key: on Android only"
        for (key in w.keys.intersect(a.keys)) {
            val we = w.getValue(key)
            val ae = a.getValue(key)
            val names = listOf("x", "y", "w", "h")
            for (i in 0..3) if (abs(we.box[i] - ae.box[i]) > BOX_DP) out += "$key: ${names[i]} ${fmt(ae.box[i])} dp, browser ${fmt(we.box[i])}"
            // what a side does not report (null) is not compared: a plain container has no text colour on Android
            for (p in listOf("background", "color")) {
                val av = ae.style[p] ?: continue
                val wv = we.style[p] ?: continue
                if (av != wv) out += "$key: $p $av, browser $wv"
            }
            for ((p, eps) in listOf("fontSize" to 0.01, "fontWeight" to 0.0, "opacity" to 0.005)) {
                val av = (ae.style[p] as Number?)?.toDouble() ?: continue
                val wv = (we.style[p] as Number?)?.toDouble() ?: continue
                if (abs(av - wv) > eps) out += "$key: $p ${fmt(av)}, browser ${fmt(wv)}"
            }
            if (ae.text != null && ae.text != we.text) out += "$key: text \"${ae.text}\", browser \"${we.text}\""
        }
        return out.sorted()
    }

    /**
     * Per pixel, the largest difference of the premultiplied channels, judged by [limits]. The diff image is the
     * browser picture dimmed: red where a pixel counts, orange for edge differences within the edge tolerance,
     * yellow for differences within the tolerance.
     */
    fun pixels(web: BufferedImage, android: BufferedImage, limits: Limits): Pixels {
        val diff = BufferedImage(web.width, web.height, BufferedImage.TYPE_INT_ARGB)
        var painted = 0
        var differing = 0
        var edges = 0
        var over = 0
        var maxDelta = 0
        for (y in 0 until web.height) for (x in 0 until web.width) {
            val p = web.getRGB(x, y)
            val d = delta(p, android.getRGB(x, y))
            if (p ushr 24 != 0 || android.getRGB(x, y) ushr 24 != 0) painted++
            maxDelta = max(maxDelta, d)
            val edge = d > limits.tolerance && edge(web, x, y, limits.tolerance)
            val counts = d > (if (edge) limits.edgeTolerance else limits.tolerance)
            if (d > 0) differing++
            if (edge && !counts) edges++
            if (counts) over++
            val grey = (luma(p) * (p ushr 24) / 255) / 3
            diff.setRGB(x, y, when {
                counts -> 0xFFFF2020.toInt()
                edge -> 0xFFFF8C00.toInt()
                d > 0 -> 0xFFFFD000.toInt()
                else -> (0xFF shl 24) or (grey shl 16) or (grey shl 8) or grey
            })
        }
        return Pixels(painted, differing, edges, over, maxDelta, diff)
    }

    /** A pixel of [image] that differs from one of its 8 neighbours by more than [tolerance]. */
    private fun edge(image: BufferedImage, x: Int, y: Int, tolerance: Int): Boolean {
        val c = image.getRGB(x, y)
        for (dy in -1..1) for (dx in -1..1) {
            val nx = x + dx
            val ny = y + dy
            if (nx in 0 until image.width && ny in 0 until image.height && delta(c, image.getRGB(nx, ny)) > tolerance) return true
        }
        return false
    }

    /** Compares every Android scene in [androidDir] with the browser's and writes the report into [reportDir]. */
    fun run(webDir: File, androidDir: File, reportDir: File, limits: Limits, accepted: Map<String, Accepted> = emptyMap(), margin: Double = 0.0): Report {
        reportDir.deleteRecursively()
        reportDir.mkdirs()
        val names = androidDir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().map { it.name.removeSuffix(".json") }.sorted()
        val scenes = names.map { name ->
            val web = File(webDir, "$name.json")
            if (!web.isFile) return@map Scene(name, listOf("no browser capture; run npm run parity:capture"), null, null)
            val ok = accepted[name]
            val allowed = ok?.let { max(limits.maxPercent, it.percent + margin) } ?: limits.maxPercent
            val wl = read(web.readText())
            val layout = compare(wl, read(File(androidDir, "$name.json").readText()))
            val wi = ImageIO.read(File(webDir, "$name.png"))
            val ai = ImageIO.read(File(androidDir, "$name.png"))
            if (wi.width != ai.width || wi.height != ai.height) {
                return@map Scene(name, layout, null, "picture is ${ai.width}x${ai.height} on Android, ${wi.width}x${wi.height} in the browser", ok?.layout ?: 0)
            }
            val px = pixels(wi, ai, limits)
            ImageIO.write(wi, "png", File(reportDir, "$name.web.png"))
            ImageIO.write(ai, "png", File(reportDir, "$name.android.png"))
            ImageIO.write(px.diff, "png", File(reportDir, "$name.diff.png"))
            wl.elements.firstOrNull { it.key == wl.root }?.let { root -> ImageIO.write(zoom(root.box, listOf(wi, ai, px.diff)), "png", File(reportDir, "$name.zoom.png")) }
            Scene(name, layout, px, if (px.percent > allowed) "${px.over} pixels (${fmt(px.percent)} % of the painted ones, accepted ${fmt(allowed)} %) over the limits ${limits.tolerance}/${limits.edgeTolerance}" else null,
                ok?.layout ?: 0)
        }
        val notBuilt = webDir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().map { it.name.removeSuffix(".json") }.filter { it !in names }.sorted()
        val report = Report(scenes, notBuilt)
        File(reportDir, "index.md").writeText(markdown(report, limits, reportDir))
        File(reportDir, "baseline.json").writeText(baselineJson(report))
        return report
    }

    /** The baseline file: each scene's accepted pixel share (rounded up to 0.001 %) and layout findings. */
    fun readBaseline(json: String): Map<String, Accepted> {
        @Suppress("UNCHECKED_CAST")
        val o = JsonSlurper().parseText(json) as Map<String, Map<String, Number>>
        return o.mapValues { (_, v) -> Accepted(v.getValue("percent").toDouble(), v.getValue("layout").toInt()) }
    }

    /** What this run measured, in the baseline's form: copied over the baseline to accept it. */
    fun baselineJson(r: Report): String = JsonOutput.prettyPrint(JsonOutput.toJson(r.scenes.sortedBy { it.name }.associate { s ->
        s.name to mapOf("percent" to ceil((s.pixels?.percent ?: 100.0) * 1000) / 1000, "layout" to s.layout.size)
    })) + "\n"

    private fun markdown(r: Report, limits: Limits, dir: File) = buildString {
        appendLine("# Visual parity")
        appendLine()
        appendLine("${r.scenes.count { it.failures.isEmpty() }}/${r.scenes.size} scenes pass. Boxes within $BOX_DP dp, colours exact; " +
            "pixels count over a channel difference of ${limits.tolerance} (${limits.edgeTolerance} on edges), at most ${limits.maxPercent} % of the painted ones.")
        appendLine()
        appendLine("| Scene | Layout | Painted px | Differing px | Edge-only px | Counted px | % counted | Max delta |")
        appendLine("|---|---|---|---|---|---|---|---|")
        for (s in r.scenes) {
            val p = s.pixels
            appendLine("| ${s.name} | ${if (s.layout.isEmpty()) "ok" else "${s.layout.size} findings"} | ${p?.painted ?: "-"} | ${p?.differing ?: "-"} | ${p?.edges ?: "-"} | ${p?.over ?: "-"} | ${p?.let { fmt(it.percent) } ?: "-"} | ${p?.maxDelta ?: "-"} |")
        }
        for (s in r.scenes) {
            appendLine()
            appendLine("## ${s.name}")
            appendLine()
            if (s.failures.isEmpty()) appendLine(if (s.layout.isEmpty() && s.pixelFailure == null && (s.pixels?.over ?: 0) == 0) "No differences over the limits." else "Within the baseline.")
            (s.failures.ifEmpty { s.layout }).forEach { appendLine("- $it") }
            if (s.pixels != null) {
                appendLine()
                appendLine("Browser, Android, difference (red: counted, orange: edge within ${limits.edgeTolerance}, yellow: within ${limits.tolerance}):")
                appendLine()
                if (File(dir, "${s.name}.zoom.png").isFile) appendLine("![${s.name} zoomed](${s.name}.zoom.png)").appendLine()
                appendLine("![${s.name} browser](${s.name}.web.png) ![${s.name} Android](${s.name}.android.png) ![${s.name} difference](${s.name}.diff.png)")
            }
        }
        if (r.notBuilt.isNotEmpty()) {
            appendLine()
            appendLine("## Captured in the browser, no Android scene yet")
            appendLine()
            r.notBuilt.forEach { appendLine("- $it") }
        }
    }

    /** The component's box and a margin, from each picture, side by side and scaled up. */
    private fun zoom(box: DoubleArray, pictures: List<BufferedImage>): BufferedImage {
        val scale = pictures[0].width / 1280.0
        val margin = 4
        val x0 = max(0, (box[0] * scale).toInt() - margin)
        val y0 = max(0, (box[1] * scale).toInt() - margin)
        val w = minOf(pictures[0].width - x0, (box[2] * scale).toInt() + 2 * margin + 1)
        val h = minOf(pictures[0].height - y0, (box[3] * scale).toInt() + 2 * margin + 1)
        val k = max(1, 240 / max(w, h))
        val out = BufferedImage((w * k + 8) * pictures.size, h * k, BufferedImage.TYPE_INT_ARGB)
        pictures.forEachIndexed { i, p ->
            for (y in 0 until h * k) for (x in 0 until w * k) {
                val c = p.getRGB(x0 + x / k, y0 + y / k)
                // transparent pixels on a dark grey, so the component shows
                out.setRGB(i * (w * k + 8) + x, y, over(c, 0xFF202428.toInt()))
            }
        }
        return out
    }

    private fun over(c: Int, bg: Int): Int {
        val a = c ushr 24
        fun ch(s: Int) = (((c shr s) and 0xFF) * a + ((bg shr s) and 0xFF) * (255 - a)) / 255
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun delta(p: Int, q: Int): Int {
        val pa = p ushr 24
        val qa = q ushr 24
        var d = abs(pa - qa)
        for (s in intArrayOf(16, 8, 0)) d = max(d, abs(((p shr s) and 0xFF) * pa / 255 - ((q shr s) and 0xFF) * qa / 255))
        return d
    }

    private fun luma(c: Int) = (((c shr 16) and 0xFF) * 3 + ((c shr 8) and 0xFF) * 6 + (c and 0xFF)) / 10

    private fun fmt(v: Double) = if (v == v.toLong().toDouble()) v.toLong().toString() else "%.3f".format(java.util.Locale.ROOT, v)
}

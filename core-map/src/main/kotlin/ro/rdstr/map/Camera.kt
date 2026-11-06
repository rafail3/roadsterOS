package ro.rdstr.map

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** The curves of the map camera's eases: MapLibre's default one, and the straight one the follow ease asks for. */
enum class Easing {
    /** `easing: t => t`. */
    LINEAR,
    /** MapLibre's `defaultEasing`, `bezier(0.25, 0.1, 0.25, 1)`. */
    DEFAULT;

    fun at(t: Double): Double = if (this == LINEAR) t else CURVE.solve(t)

    private companion object { val CURVE = UnitBezier(0.25, 0.1, 0.25, 1.0) }
}

/** A CSS-style cubic Bézier from (0, 0) to (1, 1), solved for x as `@mapbox/unitbezier` does (Newton, then bisection). */
class UnitBezier(p1x: Double, p1y: Double, p2x: Double, p2y: Double) {
    private val cx = 3 * p1x
    private val bx = 3 * (p2x - p1x) - cx
    private val ax = 1 - cx - bx
    private val cy = 3 * p1y
    private val by = 3 * (p2y - p1y) - cy
    private val ay = 1 - cy - by

    private fun sampleX(t: Double) = ((ax * t + bx) * t + cx) * t
    private fun sampleY(t: Double) = ((ay * t + by) * t + cy) * t
    private fun slopeX(t: Double) = (3 * ax * t + 2 * bx) * t + cx

    private fun solveX(x: Double, epsilon: Double): Double {
        if (x < 0) return 0.0
        if (x > 1) return 1.0
        var t = x
        for (i in 0 until 8) {
            val x2 = sampleX(t) - x
            if (abs(x2) < epsilon) return t
            val d2 = slopeX(t)
            if (abs(d2) < 1e-6) break
            t -= x2 / d2
        }
        var t0 = 0.0
        var t1 = 1.0
        t = x
        for (i in 0 until 20) {
            val x2 = sampleX(t)
            if (abs(x2 - x) < epsilon) break
            if (x > x2) t0 = t else t1 = t
            t = (t1 - t0) * 0.5 + t0
        }
        return t
    }

    fun solve(x: Double, epsilon: Double = 1e-6) = sampleY(solveX(x, epsilon))
}

/**
 * One camera ease at a time, as MapLibre's `easeTo`: the centre goes straight in world pixels, the zoom evenly, the
 * bearing the short way round, all along one [Easing] over the ease's duration. A new ease starts from wherever the
 * running one got to and replaces it, leaving what it does not move where it was (MapLibre stops the running ease
 * before it starts the next). [step] writes the camera of a frame into [lon], [lat], [zoom] and [bearing]; nothing is
 * allocated per frame.
 */
class CameraEase {
    var lon = 0.0; private set
    var lat = 0.0; private set
    var zoom = 0.0; private set
    /** In -180..180, as MapLibre's `getBearing()`. */
    var bearing = 0.0; private set
    var running = false; private set

    private var startMs = 0.0
    private var durationMs = 0.0
    private var easing = Easing.DEFAULT
    // the centre in world pixels at zoom 0, and how far it goes
    private var x0 = 0.0
    private var y0 = 0.0
    private var dx = 0.0
    private var dy = 0.0
    private var z0 = 0.0
    private var z1 = 0.0
    private var b0 = 0.0
    private var b1 = 0.0
    // a zoom around a point (a double tap): that point stays where it is on the screen
    private var around = false
    private var ax = 0.0
    private var ay = 0.0

    /**
     * Starts an ease from the camera ([fromLon], [fromLat], [fromZoom], [fromBearing]) to the target; a target equal
     * to the start leaves that part still. The zoom is clamped to [minZoom]..[maxZoom], as MapLibre's constrain does.
     */
    fun start(
        nowMs: Double, fromLon: Double, fromLat: Double, fromZoom: Double, fromBearing: Double,
        toLon: Double, toLat: Double, toZoom: Double, toBearing: Double, durationMs: Double, easing: Easing,
        minZoom: Double = 0.0, maxZoom: Double = 24.0,
    ) {
        lon = fromLon; lat = fromLat; zoom = fromZoom; bearing = wrap(fromBearing)
        startMs = nowMs
        this.durationMs = durationMs
        this.easing = easing
        x0 = TileMath.worldX(fromLon, 0.0); y0 = TileMath.worldY(fromLat, 0.0)
        dx = TileMath.worldX(toLon, 0.0) - x0; dy = TileMath.worldY(toLat, 0.0) - y0
        z0 = fromZoom; z1 = toZoom.coerceIn(minZoom, maxZoom)
        b0 = bearing; b1 = normalizeBearing(toBearing, b0)
        around = false
        running = true
    }

    /** MapLibre's `easeTo({ zoom, around })`: the zoom changes while the point ([aroundLon], [aroundLat]) keeps its place. */
    fun startAround(
        nowMs: Double, fromLon: Double, fromLat: Double, fromZoom: Double, fromBearing: Double,
        toZoom: Double, aroundLon: Double, aroundLat: Double, durationMs: Double, easing: Easing,
        minZoom: Double = 0.0, maxZoom: Double = 24.0,
    ) {
        start(nowMs, fromLon, fromLat, fromZoom, fromBearing, fromLon, fromLat, toZoom, fromBearing, durationMs, easing, minZoom, maxZoom)
        around = true
        ax = TileMath.worldX(aroundLon, 0.0); ay = TileMath.worldY(aroundLat, 0.0)
    }

    /** MapLibre's `stop()`: the camera stays where the ease got to. */
    fun stop() { running = false }

    /** The camera at [nowMs]; false once the ease has reached its end (that frame is written too). */
    fun step(nowMs: Double): Boolean {
        if (!running) return false
        val t = if (durationMs <= 0) 1.0 else ((nowMs - startMs) / durationMs).coerceIn(0.0, 1.0)
        val k = easing.at(t)
        zoom = z0 + (z1 - z0) * k
        bearing = wrap(b0 + (b1 - b0) * k)
        val x: Double
        val y: Double
        if (around) {
            // the point under the finger keeps its distance from the centre in screen pixels
            val s = 2.0.pow(z0 - zoom)
            x = ax - (ax - x0) * s
            y = ay - (ay - y0) * s
        } else {
            // MapLibre's `speedup`: while zooming, the centre moves ahead of the zoom
            val scale = 2.0.pow(z1 - z0)
            val base = if (z1 > z0) min(2.0, scale) else max(0.5, scale)
            val f = k * base.pow(1 - k)
            x = x0 + dx * f
            y = y0 + dy * f
        }
        lon = TileMath.lon(x, 0.0)
        lat = TileMath.lat(y, 0.0)
        if (t >= 1) running = false
        return running
    }

    companion object {
        /** A bearing in -180..180, as MapLibre's `wrap(bearing, -180, 180)`. */
        fun wrap(b: Double): Double {
            val w = ((b + 180) % 360 + 360) % 360 - 180
            return if (w == -180.0) 180.0 else w
        }

        /** MapLibre's `_normalizeBearing`: the target written within half a turn of [current], so the ease turns the short way. */
        fun normalizeBearing(bearing: Double, current: Double): Double {
            var b = wrap(bearing)
            val diff = abs(b - current)
            if (abs(b - 360 - current) < diff) b -= 360
            if (abs(b + 360 - current) < diff) b += 360
            return b
        }
    }
}

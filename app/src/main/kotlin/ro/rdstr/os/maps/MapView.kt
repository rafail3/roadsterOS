package ro.rdstr.os.maps

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import ro.rdstr.map.GridTile
import ro.rdstr.map.LabelCandidate
import ro.rdstr.map.MapStyle
import ro.rdstr.map.Mvt
import ro.rdstr.map.TileMath
import ro.rdstr.map.TileRenderer
import ro.rdstr.map.VisibleTile
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import ro.rdstr.map.View as MapViewState

/**
 * The map on the unit. Each picture of the map grid is drawn once, off the main thread, into a small bitmap kept in a
 * bounded cache; a frame only moves and turns the finished pictures and sets the names on top, so panning, zooming
 * and turning with the car stay cheap on the Mali-450. The car follows at the centre, heading up, until a finger
 * moves the map.
 */
class MapView(context: Context, private val packs: MapPacks) : View(context) {
    private val renderer = TileRenderer(MapStyle.load())

    var lon = 25.5887
    var lat = 45.6427
    var zoom = 15.5
    var bearing = 0.0
    var night = true
        set(value) { if (field != value) { field = value; pictures.evictAll(); invalidate() } }
    /** Whether the map keeps the car in the middle, heading up. */
    var follow = true
    private var carLon = lon
    private var carLat = lat
    private var carHeading = 0.0

    /** The style is written for a 1280 px wide screen; the unit's screen is drawn at this fraction of it. */
    private var scale = 1f
    private var logicalW = 1280
    private var logicalH = 750

    private val pictures = object : LruCache<String, Bitmap>(PICTURE_BUDGET) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val candidates = LruCache<String, List<LabelCandidate>>(CANDIDATE_TILES)
    private val pending = HashSet<String>()
    private val worker = HandlerThread("map-pictures").apply { start() }
    private val workerHandler = Handler(worker.looper)
    private val main = Handler(Looper.getMainLooper())
    private val fonts = AndroidMapCanvas.fonts(context.cacheDir)
    private val frameCanvas = AndroidMapCanvas(fonts)
    private val pictureCanvas = AndroidMapCanvas(fonts)
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()

    // measured on the unit, written to logcat every few seconds: frame and picture times
    private var frames = 0
    private var frameMs = 0.0
    private var pictureCount = 0
    private var pictureMs = 0.0
    private var lastLog = 0L

    fun setCar(lon: Double, lat: Double, heading: Double?) {
        carLon = lon; carLat = lat
        if (heading != null) carHeading = heading
        if (follow) { this.lon = lon; this.lat = lat; bearing = carHeading }
        invalidate()
    }

    fun recentre() { follow = true; setCar(carLon, carLat, null) }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        scale = w / 1280f
        logicalW = 1280
        logicalH = ceil(h / scale).toInt()
        pictures.evictAll()
    }

    private fun state() = MapViewState(lon, lat, zoom, bearing, logicalW, logicalH, night)

    override fun onDraw(canvas: Canvas) {
        val started = SystemClock.elapsedRealtimeNanos()
        val v = state()
        canvas.save()
        canvas.scale(scale, scale)
        canvas.drawColor(renderer.background(night))
        val grid = renderer.grid(v)
        canvas.save()
        canvas.rotate(-bearing.toFloat(), logicalW / 2f, logicalH / 2f)
        for (g in grid) {
            val picture = pictures.get(g.key)
            if (picture == null) { request(g); continue }
            rect.set(g.left.toFloat(), g.top.toFloat(), (g.left + g.size).toFloat(), (g.top + g.size).toFloat())
            canvas.drawBitmap(picture, null, rect, bitmapPaint)
        }
        canvas.restore()

        frameCanvas.canvas = canvas
        val sources = grid.distinctBy { it.srcKey }.mapNotNull { g ->
            candidates.get(g.srcKey)?.let { VisibleTile(g.srcZ, g.srcX, g.srcY, g.srcLeft, g.srcTop, g.srcSize) to it }
        }
        renderer.placeLabels(frameCanvas, v, sources)
        drawCar(canvas, v)
        canvas.restore()

        frames++
        frameMs += (SystemClock.elapsedRealtimeNanos() - started) / 1e6
        logTimes()
    }

    /** Draws a missing picture on the worker thread; its tile's names are worked out at the same time. */
    private fun request(g: GridTile) {
        if (!pending.add(g.key)) return
        val px = ceil(TileRenderer.TILE_PX * scale).toInt()
        val drawNight = night
        val picScale = scale
        workerHandler.post {
            val started = SystemClock.elapsedRealtimeNanos()
            val bytes = packs.tile(g.srcZ, g.srcX, g.srcY)
            val tile = bytes?.let { Mvt.decode(it) }
            val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.RGB_565)
            val c = Canvas(bitmap)
            c.scale(picScale, picScale)
            pictureCanvas.canvas = c
            pictureCanvas.clear(renderer.background(drawNight))
            if (tile != null) {
                val (left, top, size) = g.inPicture(TileRenderer.TILE_PX)
                renderer.drawTile(pictureCanvas, tile, left, top, size, g.z.toDouble(), drawNight)
            }
            val names = tile?.let { renderer.candidates(it, drawNight) } ?: emptyList()
            val ms = (SystemClock.elapsedRealtimeNanos() - started) / 1e6
            main.post {
                pending.remove(g.key)
                if (drawNight != night) { bitmap.recycle(); return@post }
                pictures.put(g.key, bitmap)
                if (candidates.get(g.srcKey) == null) candidates.put(g.srcKey, names)
                pictureCount++
                pictureMs += ms
                invalidate()
            }
        }
    }

    private val carPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chevron = Path().apply { moveTo(0f, -13f); lineTo(11f, 10f); lineTo(0f, 4.5f); lineTo(-11f, 10f); close() }
    private val face = android.graphics.LinearGradient(0f, -21f, 0f, 21f, 0xFF4FB0FF.toInt(), 0xFF0A64E8.toInt(), android.graphics.Shader.TileMode.CLAMP)
    private val gloss = android.graphics.LinearGradient(0f, -21f, 0f, 2f, 0x59FFFFFF, 0x00FFFFFF, android.graphics.Shader.TileMode.CLAMP)

    private fun drawChevron(canvas: Canvas, dy: Float, color: Int, v: MapViewState) = onRoad(canvas, dy) {
        canvas.rotate((carHeading - v.bearing).toFloat())
        carPaint.color = color
        carPaint.style = Paint.Style.FILL
        canvas.drawPath(chevron, carPaint)
        carPaint.style = Paint.Style.STROKE
        carPaint.strokeJoin = Paint.Join.ROUND
        carPaint.strokeWidth = 2.4f
        canvas.drawPath(chevron, carPaint)
        carPaint.style = Paint.Style.FILL
    }

    /** Draws [block] in the road's plane at [dy] below the car: flattened by [TILT], as the disc is seen at an angle. */
    private inline fun onRoad(canvas: Canvas, dy: Float, block: () -> Unit) {
        canvas.save()
        canvas.translate(0f, dy)
        canvas.scale(1f, TILT)
        block()
        canvas.restore()
    }

    /**
     * The car where the GPS puts it: a disc lying on the road seen at an angle, a white rim with some thickness and a
     * blue face, with a white chevron on it turning with the heading; a soft shadow under it. Same layers as the emulator.
     */
    private fun drawCar(canvas: Canvas, v: MapViewState) {
        val dx = TileMath.worldX(carLon, v.zoom) - TileMath.worldX(v.lon, v.zoom)
        val dy = TileMath.worldY(carLat, v.zoom) - TileMath.worldY(v.lat, v.zoom)
        val r = Math.toRadians(-v.bearing)
        val x = (logicalW / 2 + dx * cos(r) - dy * sin(r)).toFloat()
        val y = (logicalH / 2 + dx * sin(r) + dy * cos(r)).toFloat()
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(CAR_SCALE, CAR_SCALE)
        carPaint.style = Paint.Style.FILL
        carPaint.shader = null
        onRoad(canvas, 7f) {
            carPaint.color = 0x0F000000; canvas.drawCircle(0f, 0f, 31f, carPaint)
            carPaint.color = 0x14000000; canvas.drawCircle(0f, 0f, 28.5f, carPaint)
            carPaint.color = 0x1F000000; canvas.drawCircle(0f, 0f, 26.5f, carPaint)
        }
        onRoad(canvas, 4.5f) { carPaint.color = 0xFF8E9BB0.toInt(); canvas.drawCircle(0f, 0f, 26f, carPaint) }
        onRoad(canvas, 2.2f) { carPaint.color = 0xFFCDD5E1.toInt(); canvas.drawCircle(0f, 0f, 26f, carPaint) }
        onRoad(canvas, 0f) {
            carPaint.color = 0xFFFFFFFF.toInt(); canvas.drawCircle(0f, 0f, 26f, carPaint)
            carPaint.shader = face; canvas.drawCircle(0f, 0f, 20.5f, carPaint)
            carPaint.shader = gloss; canvas.drawCircle(0f, 0f, 20.5f, carPaint)
            carPaint.shader = null
            carPaint.style = Paint.Style.STROKE
            carPaint.strokeWidth = 1.2f
            carPaint.color = 0x730A4FC0
            canvas.drawCircle(0f, 0f, 20.5f, carPaint)
            carPaint.style = Paint.Style.FILL
        }
        // the chevron turns on the disc, in the road's plane; its shadow a little below it
        drawChevron(canvas, 1.4f, 0x5906357F, v)
        drawChevron(canvas, 0f, 0xFFFFFFFF.toInt(), v)
        canvas.restore()
    }

    private fun logTimes() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastLog < LOG_EVERY_MS) return
        lastLog = now
        Log.i(TAG, "frames %d avg %.1f ms; pictures %d avg %.1f ms; cache %.1f MB".format(
            frames, if (frames > 0) frameMs / frames else 0.0, pictureCount, if (pictureCount > 0) pictureMs / pictureCount else 0.0,
            pictures.size() / 1e6))
        frames = 0; frameMs = 0.0; pictureCount = 0; pictureMs = 0.0
    }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            follow = false
            // the finger moves the turned map: undo the turn, then move the centre in world pixels
            val r = Math.toRadians(bearing)
            val dx = distanceX / scale
            val dy = distanceY / scale
            val wx = dx * cos(r) - dy * sin(r)
            val wy = dx * sin(r) + dy * cos(r)
            lon = TileMath.lon(TileMath.worldX(lon, zoom) + wx, zoom)
            lat = TileMath.lat(TileMath.worldY(lat, zoom) + wy, zoom)
            invalidate()
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            zoom = (zoom + 1).coerceAtMost(MAX_ZOOM)
            invalidate()
            return true
        }
    })

    private val pinch = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            zoom = (zoom + ln(d.scaleFactor.toDouble()) / ln(2.0)).coerceIn(MIN_ZOOM, MAX_ZOOM)
            invalidate()
            return true
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        pinch.onTouchEvent(event)
        if (!pinch.isInProgress) gestures.onTouchEvent(event)
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        worker.quitSafely()
        pictures.evictAll()
    }

    companion object {
        const val TAG = "rdstr.map"
        const val MIN_ZOOM = 4.0
        const val MAX_ZOOM = 18.0
        /** Bitmaps of finished pictures kept: about 36 at the unit's size, enough for the screen and some around it. */
        const val PICTURE_BUDGET = 12 * 1024 * 1024
        const val CANDIDATE_TILES = 48
        const val LOG_EVERY_MS = 3000L
        /** How flat the car's disc looks: the road seen at about 50 degrees. */
        const val TILT = 0.62f
        /** The car's disc is drawn in a 72 unit box at this size, as in the emulator. */
        const val CAR_SCALE = 1.35f
    }
}

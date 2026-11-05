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
import ro.rdstr.map.Street
import ro.rdstr.map.TileMath
import ro.rdstr.map.TileRenderer
import ro.rdstr.map.VectorTile
import ro.rdstr.map.VisibleTile
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import ro.rdstr.map.View as MapViewState

/**
 * The map on the unit. Each picture of the map grid is drawn once, off the main thread, into a small bitmap kept in a
 * bounded cache; a frame only moves and turns the finished pictures and sets the names on top, so panning, zooming
 * and turning with the car stay cheap on the Mali-450. The car follows at the centre, heading up (or north up), until a
 * finger moves the map; the screen's controls read [street], [follow], [headingUp] and [bearing] on [onChange].
 */
class MapView(context: Context, private val packs: MapPacks) : View(context) {
    private val renderer = TileRenderer(MapStyle.load())

    var lon = 25.5887
    var lat = 45.6427
    var zoom = 15.5
    var bearing = 0.0
    var night = true
        set(value) { if (field != value) { field = value; pictures.evictAll(); invalidate() } }
    /** Whether the map keeps the car in the middle. */
    var follow = true
        private set(value) { if (field != value) { field = value; changed() } }
    /** While following: the map turns with the car, or keeps north up. */
    var headingUp = true
        set(value) {
            if (field == value) return
            field = value
            if (follow) bearing = if (value) carHeading else 0.0
            invalidate(); changed()
        }
    /** The name of the street the car is on, empty off any named road. */
    var street = ""
        private set
    /** On the main thread, whenever [street], [follow], [headingUp] or [bearing] changed. */
    var onChange: (() -> Unit)? = null
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
        if (follow) { this.lon = lon; this.lat = lat; bearing = if (headingUp) carHeading else 0.0 }
        invalidate()
        changed()
        if (SystemClock.elapsedRealtime() - streetAt >= Street.EVERY_MS) lookUpStreet()
    }

    /** The zoom buttons: a whole step in or out. */
    fun zoomBy(steps: Double) {
        zoom = (zoom + steps).coerceIn(MIN_ZOOM, MAX_ZOOM)
        invalidate()
        lookUpStreet()
    }

    /** The location button: back on the car at the driving zoom. */
    fun recentre() {
        follow = true
        zoom = DRIVE_ZOOM
        setCar(carLon, carLat, null)
        lookUpStreet()
    }

    private fun changed() = onChange?.invoke()

    // the tiles the street is looked up in, decoded on the worker and kept only there
    private val streetTiles = LruCache<String, Any>(STREET_TILES)
    private var streetAt = 0L

    /** Finds the street under the car on the worker, from the pack, as the emulator does from its loaded tiles. */
    private fun lookUpStreet() {
        streetAt = SystemClock.elapsedRealtime()
        val at = Triple(carLon, carLat, zoom)
        workerHandler.post {
            val name = Street.at(at.first, at.second, at.third, TileRenderer.Tiles { z, x, y ->
                val key = "$z/$x/$y"
                (streetTiles.get(key) ?: (packs.tile(z, x, y)?.let { Mvt.decode(it) } ?: NO_TILE).also { streetTiles.put(key, it) }) as? VectorTile
            })
            main.post { if (name != street) { street = name; Log.i(TAG, "street: $name"); changed() } }
        }
    }

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

    private val carPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeJoin = Paint.Join.ROUND }
    /** The navigation arrow seen from above, 44 units tall, pointing up and centred. */
    private val arrow = Path().apply { moveTo(0f, -22f); lineTo(18f, 22f); lineTo(0f, 11.5f); lineTo(-18f, 22f); close() }

    private fun stroke(canvas: Canvas, path: Path, color: Int, width: Float) {
        carPaint.shader = null
        carPaint.style = Paint.Style.STROKE
        carPaint.color = color
        carPaint.strokeWidth = width
        canvas.drawPath(path, carPaint)
    }

    /**
     * The car where the GPS puts it: the navigation arrow seen from above in the location button's blue,
     * #0a84ff, flat, with a white rim and a fine dark edge and a soft shadow kept below it on the screen.
     * Same layers as the emulator.
     */
    private fun drawCar(canvas: Canvas, v: MapViewState) {
        val dx = TileMath.worldX(carLon, v.zoom) - TileMath.worldX(v.lon, v.zoom)
        val dy = TileMath.worldY(carLat, v.zoom) - TileMath.worldY(v.lat, v.zoom)
        val r = Math.toRadians(-v.bearing)
        val x = (logicalW / 2 + dx * cos(r) - dy * sin(r)).toFloat()
        val y = (logicalH / 2 + dx * sin(r) + dy * cos(r)).toFloat()
        val turn = (carHeading - v.bearing).toFloat()
        // the shadow, 3 px below on the screen
        canvas.save()
        canvas.translate(x, y + 3f * CAR_SCALE)
        canvas.scale(CAR_SCALE, CAR_SCALE)
        canvas.rotate(turn)
        stroke(canvas, arrow, 0x0D000000, 13f)
        stroke(canvas, arrow, 0x12000000, 9f)
        stroke(canvas, arrow, 0x1A000000, 5.5f)
        carPaint.style = Paint.Style.FILL
        carPaint.color = 0x33000000
        canvas.drawPath(arrow, carPaint)
        canvas.restore()
        // the arrow: a fine dark edge, the white rim, the blue body
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(CAR_SCALE, CAR_SCALE)
        canvas.rotate(turn)
        stroke(canvas, arrow, 0x2E000000, 6.5f)
        stroke(canvas, arrow, 0xFFFFFFFF.toInt(), 5f)
        carPaint.style = Paint.Style.FILL
        canvas.drawPath(arrow, carPaint)
        // the location button's blue, flat
        carPaint.color = 0xFF0A84FF.toInt()
        canvas.drawPath(arrow, carPaint)
        carPaint.style = Paint.Style.STROKE
        carPaint.strokeWidth = 1.2f
        canvas.drawPath(arrow, carPaint)
        carPaint.style = Paint.Style.FILL
        carPaint.shader = null
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
        override fun onScaleEnd(d: ScaleGestureDetector) = lookUpStreet()
    })

    // two fingers also turn the map, as on the emulator; while following, the next position turns it back
    private var twoFingerAngle = Double.NaN

    private fun angle(e: MotionEvent) = Math.toDegrees(atan2((e.getY(1) - e.getY(0)).toDouble(), (e.getX(1) - e.getX(0)).toDouble()))

    override fun onTouchEvent(event: MotionEvent): Boolean {
        pinch.onTouchEvent(event)
        if (event.pointerCount == 2 && event.actionMasked == MotionEvent.ACTION_MOVE) {
            val a = angle(event)
            if (!twoFingerAngle.isNaN()) {
                bearing = ((bearing - (a - twoFingerAngle)) % 360 + 360) % 360
                invalidate(); changed()
            }
            twoFingerAngle = a
        } else if (event.pointerCount != 2 || event.actionMasked == MotionEvent.ACTION_POINTER_UP) twoFingerAngle = Double.NaN
        if (!pinch.isInProgress) gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) lookUpStreet()
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
        /** Where the location button brings the map back to, as in the emulator. */
        const val DRIVE_ZOOM = 15.5
        /** Decoded tiles kept for the street lookup: the car's tile and its neighbours. */
        const val STREET_TILES = 4
        private val NO_TILE = Any()
        /** Bitmaps of finished pictures kept: about 36 at the unit's size, enough for the screen and some around it. */
        const val PICTURE_BUDGET = 12 * 1024 * 1024
        const val CANDIDATE_TILES = 48
        const val LOG_EVERY_MS = 3000L
        /** The car's arrow is 44 units tall, drawn at this size, as in the emulator. */
        const val CAR_SCALE = 1.2f
    }
}

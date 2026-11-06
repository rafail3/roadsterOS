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
import android.view.Choreographer
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import ro.rdstr.map.CameraEase
import ro.rdstr.map.Easing
import ro.rdstr.map.GridTile
import ro.rdstr.map.LabelCandidate
import ro.rdstr.map.MapStyle
import ro.rdstr.map.Mvt
import ro.rdstr.map.PackMask
import ro.rdstr.map.SpeedLimit
import ro.rdstr.map.Street
import ro.rdstr.map.TileMath
import ro.rdstr.map.TileRenderer
import ro.rdstr.map.VectorTile
import ro.rdstr.map.VisibleTile
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin
import ro.rdstr.map.View as MapViewState
import ro.rdstr.ui.Emulates

/**
 * The map on the unit. Each picture of the map grid is drawn once, off the main thread, into a small bitmap kept in a
 * bounded cache; a frame only moves and turns the finished pictures and sets the names on top, so panning, zooming
 * and turning with the car stay cheap on the Mali-450. The car follows at the centre, heading up (or north up), until a
 * finger moves the map; the screen's controls read [street], [roadLimit], [follow] and [headingUp] on [onChange] and
 * the compass reads [bearing] on [onRotate].
 *
 * The camera eases as the emulator's MapLibre map does (`easeTo`, MapsApp.tsx): one ease at a time, stepped on the
 * display's frames by [Choreographer]; a new ease takes over from wherever the running one got to, and a finger moving
 * the map stops it.
 */
class MapView(context: Context, private val packs: MapPacks) : View(context) {
    private val style = MapStyle.load()
    private val renderer = TileRenderer(style)

    var lon = 25.5887
    var lat = 45.6427
    var zoom = 15.5
    var bearing = 0.0
    /** The night palette, else the day one (MapHost sets it from the shell's theme). */
    var night = true
        set(value) { if (field != value) { field = value; pictures.evictAll(); candidates.evictAll(); invalidate() } }
    /**
     * Whether the map keeps the car in the middle (MapsApp.tsx `follow`): a finger dragging the map turns it off
     * (`dragstart` -> `setFollow(false)`); turned on, the map eases onto the car as on a new position.
     */
    var follow = true
        set(value) {
            if (field == value) return
            field = value
            if (value && seen) followCar()
            changed()
        }
    /**
     * The compass button (MapsApp.tsx `setHeadingUp(!headingUp)`): while following, the map turns with the car or keeps
     * north up, eased by the follow ease right away; not following, the map keeps its bearing. The emulator has no
     * other bearing reset.
     */
    var headingUp = true
        set(value) {
            if (field == value) return
            field = value
            if (follow && seen) followCar()
            changed()
        }
    /**
     * Whether the map is on a screen (MapsApp.tsx `seen = open || inCard`; MapHost sets it). Unseen, it does no work;
     * seen again it jumps onto the car (`jumpTo({ center })`, no long glide) and goes on as on a new position.
     */
    var seen = false
        set(value) {
            if (field == value) return
            field = value
            camera.stop()
            if (!value) return
            lon = carLon; lat = carLat
            setCar(carLon, carLat, movingHeading, carDriving)
        }
    /** The name of the street the car is on, empty off any named road. */
    var street = ""
        private set
    /** The posted limit of the car's road for its direction, in km/h; null when the road has none in the pack. */
    var roadLimit: Int? = null
        private set
    /** The countries whose pack is not on the unit (MapHost's [PackMask]): covered, as the unit has no tiles there. */
    var missing: List<PackMask.Polygon> = emptyList()
        set(value) { field = value; invalidate() }
    /** On the main thread, on every position seen and whenever [street], [roadLimit], [follow] or [headingUp] changed. */
    var onChange: (() -> Unit)? = null

    /** The map's bearing changed, by an ease or by two fingers (MapLibre's `rotate` event, MapsApp.tsx `setBearing`). */
    fun interface OnRotate { fun rotated(bearing: Double) }
    /** The compass's listener; called on the main thread, on each frame the bearing moves. */
    var onRotate: OnRotate? = null
    private var carLon = lon
    private var carLat = lat
    private var carHeading = 0.0
    private var carDriving = false
    /** The car's heading while it moves; the road it is on is then looked for along it. */
    private var movingHeading: Double? = null

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

    /**
     * A new position of the car (MapsApp.tsx, the effect on each `gps` fix): the arrow moves there, turned [heading]
     * (null while the car stands: the last heading stays and the street is looked for in every direction); following,
     * `easeTo({ center, bearing: headingUp ? heading : 0, duration: driving ? 260 : 600, easing: t => t })`. Unseen,
     * the map only keeps the position.
     */
    fun setCar(lon: Double, lat: Double, heading: Double?, driving: Boolean) {
        carLon = lon; carLat = lat
        if (heading != null) carHeading = heading
        movingHeading = heading
        carDriving = driving
        if (!seen) return
        if (follow) followCar()
        invalidate()
        changed()
        if (SystemClock.elapsedRealtime() - streetAt >= Street.EVERY_MS) lookUpStreet()
    }

    /**
     * The zoom the buttons or the locate button asked for, until the map is there (MapsApp.tsx `zoomTarget`): each
     * follow ease carries it, so a new position does not cut the zoom short; a finger on the map drops it.
     */
    private var zoomTarget: Double? = null

    /** MapsApp.tsx `followCar`: one ease onto the car, its heading (or north) and the zoom asked for while not reached. */
    private fun followCar() {
        zoomTarget?.let { if (abs(zoom - it) < 1e-6) zoomTarget = null }
        ease(carLon, carLat, zoomTarget ?: zoom, if (headingUp) carHeading else 0.0, if (carDriving) FOLLOW_DRIVING_MS else FOLLOW_MS, Easing.LINEAR)
    }

    /**
     * The zoom buttons (MapsApp.tsx `zoom(+-1)`): a step goes on from the step under way, so quick presses add up to
     * whole steps; `easeTo({ zoom: target, duration: 250 })`, within 4..18.
     */
    fun zoomBy(steps: Double) {
        val from = zoomTarget?.takeIf { camera.running } ?: zoom
        val to = (from + steps).coerceIn(MIN_ZOOM, MAX_ZOOM)
        zoomTarget = to
        ease(lon, lat, to, bearing, ZOOM_MS, Easing.DEFAULT)
    }

    /**
     * The locate button (MapsApp.tsx `recentre`): back onto the car at the driving zoom in the follow ease itself, so
     * nothing cancels the zoom; centre, bearing and zoom move together, from wherever a finger left the map.
     */
    fun recentre() {
        zoomTarget = DRIVE_ZOOM
        if (!follow) follow = true else if (seen) followCar()
    }

    private val camera = CameraEase()
    private var framePosted = false
    // the street is looked up again once an ease that zooms has ended (MapLibre's `idle`)
    private var lookUpAfterEase = false

    private fun ease(toLon: Double, toLat: Double, toZoom: Double, toBearing: Double, ms: Double, easing: Easing) {
        camera.start(System.nanoTime() / 1e6, lon, lat, zoom, bearing, toLon, toLat, toZoom, toBearing, ms, easing, MIN_ZOOM, MAX_ZOOM)
        lookUpAfterEase = toZoom.coerceIn(MIN_ZOOM, MAX_ZOOM) != zoom
        postFrame()
    }

    private fun postFrame() {
        if (framePosted) return
        framePosted = true
        Choreographer.getInstance().postFrameCallback(frame)
    }

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            framePosted = false
            if (!camera.running) return
            val more = camera.step(frameTimeNanos / 1e6)
            val turned = camera.bearing != bearing
            lon = camera.lon; lat = camera.lat; zoom = camera.zoom; bearing = camera.bearing
            invalidate()
            if (turned) onRotate?.rotated(bearing)
            if (more) postFrame() else if (lookUpAfterEase) lookUpStreet()
        }
    }

    private fun changed() = onChange?.invoke()

    // the tiles the street is looked up in, decoded on the worker and kept only there
    private val streetTiles = LruCache<String, Any>(STREET_TILES)
    private var streetAt = 0L

    /**
     * Finds the street and the speed limit of the car's road on the worker, from the pack, as the emulator does from its
     * loaded tiles: along the car's heading while it moves. Once a second; the decoded tiles stay in a small cache.
     */
    private fun lookUpStreet() {
        streetAt = SystemClock.elapsedRealtime()
        val lon = carLon
        val lat = carLat
        val z = zoom
        val heading = movingHeading
        workerHandler.post {
            val tiles = TileRenderer.Tiles { tz, x, y ->
                val key = "$tz/$x/$y"
                (streetTiles.get(key) ?: (packs.tile(tz, x, y)?.let { Mvt.decode(it) } ?: NO_TILE).also { streetTiles.put(key, it) }) as? VectorTile
            }
            val name = streetAt(tiles, lon, lat, z, heading)
            val limit = limitAt(tiles, lon, lat, heading)
            main.post {
                if (name == street && limit == roadLimit) return@post
                if (name != street) Log.i(TAG, "street: $name")
                street = name
                roadLimit = limit
                changed()
            }
        }
    }

    /**
     * The name of the street the car is on at [zoom] (core-map Street): the nearest named road of the pack's tiles,
     * along [heading] while the car moves. The emulator runs the same rule over the tiles MapLibre has loaded.
     */
    @Emulates("roads.streetAt")
    private fun streetAt(tiles: TileRenderer.Tiles, lon: Double, lat: Double, zoom: Double, heading: Double?): String =
        Street.at(lon, lat, zoom, tiles, heading)

    /** The posted limit of the car's road for its direction, in km/h, from the pack's maxspeed layer (core-map SpeedLimit). */
    @Emulates("roads.limitAt")
    private fun limitAt(tiles: TileRenderer.Tiles, lon: Double, lat: Double, heading: Double?): Int? =
        SpeedLimit.at(lon, lat, heading, tiles)

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
        drawMissing(canvas, v)
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
        val drawnWith = packsDrawn
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
                if (drawNight != night || drawnWith != packsDrawn) { bitmap.recycle(); return@post }
                pictures.put(g.key, bitmap)
                if (candidates.get(g.srcKey) == null) candidates.put(g.srcKey, names)
                pictureCount++
                pictureMs += ms
                invalidate()
            }
        }
    }

    private val maskPath = Path().apply { fillType = Path.FillType.EVEN_ODD }
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * The missing countries in the style's mask colour at 0.92, over the names as in the emulator, where the mask is the
     * last layer. Only the polygons that reach the screen are drawn.
     */
    private fun drawMissing(canvas: Canvas, v: MapViewState) {
        if (missing.isEmpty()) return
        val cx = TileMath.worldX(v.lon, v.zoom)
        val cy = TileMath.worldY(v.lat, v.zoom)
        val reach = hypot(logicalW.toDouble(), logicalH.toDouble()) / 2
        maskPath.rewind()
        for (p in missing) {
            if (!p.reaches(cx, cy, v.zoom, reach)) continue
            for (ring in p.rings) {
                for (i in ring.indices step 2) {
                    val x = (TileMath.worldX(ring[i], v.zoom) - cx + logicalW / 2).toFloat()
                    val y = (TileMath.worldY(ring[i + 1], v.zoom) - cy + logicalH / 2).toFloat()
                    if (i == 0) maskPath.moveTo(x, y) else maskPath.lineTo(x, y)
                }
                maskPath.close()
            }
        }
        if (maskPath.isEmpty) return
        maskPaint.color = (style.color(night, "mask") and 0xFFFFFF) or (MASK_ALPHA shl 24)
        canvas.save()
        canvas.rotate(-bearing.toFloat(), logicalW / 2f, logicalH / 2f)
        canvas.drawPath(maskPath, maskPaint)
        canvas.restore()
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
            camera.stop()
            zoomTarget = null
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
        /** MapLibre's tap zoom: `easeTo({ duration: 300, zoom: getZoom() + 1, around })`, the tapped point staying put. */
        override fun onDoubleTap(e: MotionEvent): Boolean {
            val r = Math.toRadians(bearing)
            val sx = e.x / scale - logicalW / 2.0
            val sy = e.y / scale - logicalH / 2.0
            val wx = TileMath.worldX(lon, zoom) + sx * cos(r) - sy * sin(r)
            val wy = TileMath.worldY(lat, zoom) + sx * sin(r) + sy * cos(r)
            camera.startAround(System.nanoTime() / 1e6, lon, lat, zoom, bearing, zoom + 1, TileMath.lon(wx, zoom), TileMath.lat(wy, zoom),
                TAP_ZOOM_MS, Easing.DEFAULT, MIN_ZOOM, MAX_ZOOM)
            zoomTarget = null
            lookUpAfterEase = true
            postFrame()
            return true
        }
    })

    private val pinch = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            camera.stop()
            zoomTarget = null
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
                camera.stop()
                bearing = CameraEase.wrap(bearing - (a - twoFingerAngle))
                invalidate()
                onRotate?.rotated(bearing)
            }
            twoFingerAngle = a
        } else if (event.pointerCount != 2 || event.actionMasked == MotionEvent.ACTION_POINTER_UP) twoFingerAngle = Double.NaN
        if (!pinch.isInProgress) gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) lookUpStreet()
        return true
    }

    /**
     * The one map moves between the Maps screen and the card on home (MapHost), so leaving a parent keeps its worker
     * and its pictures; the host drops the pictures when the map is shown nowhere.
     */
    fun dropPictures() = pictures.evictAll()

    // bumped on each pack change: pictures drawn before it are thrown away when they arrive
    private var packsDrawn = 0

    /** A pack was downloaded or removed: what was drawn and looked up without it, or from it, is done again. */
    fun packsChanged() {
        packsDrawn++
        pictures.evictAll()
        candidates.evictAll()
        workerHandler.post { streetTiles.evictAll() }
        invalidate()
        lookUpStreet()
    }

    companion object {
        const val TAG = "rdstr.map"
        const val MIN_ZOOM = 4.0
        const val MAX_ZOOM = 18.0
        /** Where the location button brings the map back to, as in the emulator. */
        const val DRIVE_ZOOM = 15.5
        /** The unit's GPS gives a position this often while the car drives (the Maps screen). */
        const val GPS_EVERY_MS = 500L
        /**
         * MapsApp.tsx follow ease: `duration: driving ? 260 : 600`, linear. The emulator's 260 ms is its 250 ms drive tick
         * and a little more; on the unit it is the GPS interval and the same little more, so each ease is still running
         * when the next position takes it over and the map moves on without a stop.
         */
        const val FOLLOW_DRIVING_MS = GPS_EVERY_MS + 10.0
        const val FOLLOW_MS = 600.0
        /** MapsApp.tsx `zoom(+-1)`: 250 ms; MapLibre's tap zoom: 300 ms. Both on MapLibre's default curve. */
        const val ZOOM_MS = 250.0
        const val TAP_ZOOM_MS = 300.0
        /** Decoded tiles kept for the street and limit lookups: the car's tiles and their neighbours, at two zooms. */
        const val STREET_TILES = 6
        private val NO_TILE = Any()
        /** The mask's opacity, 0.92 as in the emulator's style. */
        const val MASK_ALPHA = 235
        /** Bitmaps of finished pictures kept: about 36 at the unit's size, enough for the screen and some around it. */
        const val PICTURE_BUDGET = 12 * 1024 * 1024
        const val CANDIDATE_TILES = 48
        const val LOG_EVERY_MS = 3000L
        /** The car's arrow is 44 units tall, drawn at this size, as in the emulator. */
        const val CAR_SCALE = 1.2f
    }
}

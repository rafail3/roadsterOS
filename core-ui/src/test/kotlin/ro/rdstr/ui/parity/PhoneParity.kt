package ro.rdstr.ui.parity

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.cash.paparazzi.Snapshot
import app.cash.paparazzi.SnapshotHandler
import com.android.ide.common.rendering.api.SessionParams.RenderingMode
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import ro.rdstr.car.CallRecord
import ro.rdstr.car.CallType
import ro.rdstr.car.CameraSettings
import ro.rdstr.car.CarCommand
import ro.rdstr.car.CarEvent
import ro.rdstr.car.CarEvent.Call.State
import ro.rdstr.car.Contact
import ro.rdstr.car.PhoneGate
import ro.rdstr.car.PhoneNumber
import ro.rdstr.car.ShellAction
import ro.rdstr.ui.kit.Backdrop
import ro.rdstr.ui.kit.IconView
import ro.rdstr.ui.kit.UiTheme
import ro.rdstr.ui.overlays.CallBarView
import ro.rdstr.ui.overlays.CallOverlayView
import ro.rdstr.ui.overlays.CameraView
import ro.rdstr.ui.overlays.MessageBannerView
import ro.rdstr.ui.overlays.OverlayActions
import ro.rdstr.ui.phone.Box
import ro.rdstr.ui.phone.CallInfo
import ro.rdstr.ui.phone.Lp
import ro.rdstr.ui.phone.PhoneAppView
import ro.rdstr.ui.phone.PhoneState
import ro.rdstr.ui.phone.Txt
import ro.rdstr.ui.uiKey
import java.awt.image.BufferedImage
import java.io.File
import java.util.TimeZone
import javax.imageio.ImageIO

/**
 * Visual parity of the phone screens against the browser's scenes (capture-layout.mjs, the phone block), in the four
 * looks. The views are drawn at their screen size from the emulator's sample phone (emulator/car/phone.ts, store.ts
 * fromUrl) at the capture's frozen moment. Boxes are the layout's unrounded ones (a box's pixels are its rounded
 * edges, as the browser paints them); as in the browser, elements outside the screen are not listed.
 */
class PhoneParity {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.NEXUS_5.copy(
            screenWidth = 1024, screenHeight = 600, xdpi = 128, ydpi = 128, density = Density.create(128),
            orientation = ScreenOrientation.LANDSCAPE, softButtons = false,
        ),
        theme = "android:Theme.Translucent.NoTitleBar.Fullscreen",
        renderingMode = RenderingMode.NORMAL,
        snapshotHandler = Png,
        useDeviceResolution = true,
    )

    private val ctx: Context get() = paparazzi.context

    @Test fun phoneRecents() = looks { phoneScene("phone-recents-$it") { } }
    @Test fun phoneContacts() = looks { phoneScene("phone-contacts-$it") { v -> tab(v, 1) } }
    @Test fun phoneKeypad() = looks { phoneScene("phone-keypad-$it") { v -> for (k in listOf(10, 6, 1)) (find(v, "pad-grid") as ViewGroup).getChildAt(k).performClick() } }
    @Test fun phoneMessages() = looks { phoneScene("phone-messages-$it") { v -> tab(v, 2) } }
    @Test fun phoneThread() = looks {
        phoneScene("phone-thread-$it") { v -> tab(v, 2); find(v, "phone-row")!!.performClick() }
    }

    @Test fun callIncoming() = looks { overlay("call-incoming-$it", "overlay.call") { CallOverlayView(ctx, NoOverlay).apply { clock = { NOW }; bind(call(State.RINGING), CONTACTS, "RO") } } }
    @Test fun callActive() = looks { overlay("call-active-$it", "overlay.call") { CallOverlayView(ctx, NoOverlay).apply { clock = { NOW }; bind(call(State.ACTIVE), CONTACTS, "RO") } } }
    @Test fun callBarRinging() = looks { overlay("callbar-ringing-$it", "call-banner") { CallBarView(ctx, NoOverlay).apply { clock = { NOW }; bind(call(State.RINGING), "RO") } } }
    @Test fun callBar() = looks { overlay("callbar-$it", "call-banner") { CallBarView(ctx, NoOverlay).apply { clock = { NOW }; bind(call(State.ACTIVE), "RO") } } }
    @Test fun messageBanner() = looks {
        overlay("message-banner-$it", "message-banner") {
            MessageBannerView(ctx, NoOverlay).apply { bind(CarEvent.Message("m-9", "+40721000101", "Are you close?", atMs = NOW), CONTACTS, "RO", stopped = true) }
        }
    }
    @Test fun camera() = looks { overlay("camera-$it", "overlay.camera") { CameraView(ctx, NoOverlay, Feed(ctx)).apply { bind(true, CameraSettings(), 0) } } }

    // ---- the sample phone -------------------------------------------------------------------------------------------

    private var state = PhoneState()

    private fun phoneScene(scene: String, act: (PhoneAppView) -> Unit) {
        lateinit var view: PhoneAppView
        view = PhoneAppView(ctx, object : PhoneAppView.Actions {
            override fun shellAction(action: ShellAction) = Unit
            override fun carCommand(command: CarCommand) = Unit
            override fun turnOnBluetooth() = Unit
            // store.ts openThread and the markRead the thread makes on open
            override fun openThread(number: String?) {
                val ids = state.messages.filter { it.number == number && !it.sent }.map { it.id }
                state = state.copy(thread = number, read = state.read + ids); view.bind(state)
            }
            override fun markRead(ids: List<String>) = Unit
        })
        view.clock = { NOW }
        state = PhoneState(
            open = true, screen = PhoneGate.Screen.READY, device = "Pixel 8", operator = "Vela Mobile", signal = 3, battery = 4,
            contacts = CONTACTS, calls = calls(NOW), messages = MESSAGES, read = setOf("m-1", "m-3"),
        )
        view.bind(state)
        render(scene, "music-app.phone-app") { act(view); view }
    }

    private fun tab(v: View, i: Int) = (find(v, "phone-tabs") as ViewGroup).getChildAt(i).performClick()

    private fun call(s: State) = CallInfo(s, "+40721000101", "Ana Petrescu", "mobile", since = if (s == State.ACTIVE) NOW - 83_000 else null)

    private object NoOverlay : OverlayActions {
        override fun carCommand(command: CarCommand) = Unit
        override fun shellAction(action: ShellAction) = Unit
        override fun openThread(number: String) = Unit
        override fun dismissMessage() = Unit
    }

    /** The emulator's synthetic camera frame (screen.css `.camera-feed`, `.camera-scene`): what the AV_IN video is on the unit. */
    private class Feed(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        init { uiKey = "camera-scene" }
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            paint.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(0xFF1A1D22.toInt(), 0xFF2A2D33.toInt(), 0xFF15171B.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, paint); paint.shader = null
            val pl = 0.14f * w; val pw = 0.034f * w; val pb = h * 0.66f; val ph = 0.30f * h
            for (i in 0 until 6) {
                paint.color = if (i % 2 == 0) 0xFFE8B730.toInt() else 0xFF2A2A2A.toInt()
                val t = pb - ph + ph * 0.18f * i
                c.drawRect(pl, t, pl + pw, minOf(pb, t + ph * 0.18f), paint)
            }
            c.save(); c.rotate(-10f, -0.1f * w, h * 0.86f - 0.012f * h)
            paint.color = 0xFF5B5F66.toInt(); c.drawRect(-0.1f * w, h * 0.86f - 0.024f * h, 0.32f * w, h * 0.86f, paint)
            c.restore()
        }
    }

    // ---- capture ----------------------------------------------------------------------------------------------------

    private fun looks(block: (String) -> Unit) {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Bucharest"))
        for (glassy in listOf(false, true)) for (light in listOf(false, true)) {
            UiTheme.set(light, glassy, true, 0f)
            block("${if (light) "light" else "dark"}-${if (glassy) "glassy" else "solid"}")
        }
    }

    private fun overlay(scene: String, root: String, build: () -> View) {
        // the browser's isolated component has nothing behind its glass: an empty frost
        Backdrop.capture(View(ctx).apply { measure(exact(1024), exact(600)); layout(0, 0, 1024, 600) }, UiTheme.glass.glassyFrost)
        render(scene, root, build)
        Backdrop.clear()
    }

    private fun exact(px: Int) = View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.EXACTLY)

    private fun render(scene: String, rootKey: String, build: () -> View) {
        val web = File(WEB, "$scene.json")
        assumeTrue("no browser capture $web; run npm run parity:capture", web.isFile)
        val screen = FrameLayout(ctx)
        val view = build()
        screen.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        Png.target = File(ANDROID, "$scene.png").apply { parentFile.mkdirs() }
        Png.root = rootKey
        Png.view = view
        paparazzi.snapshot(screen, scene)
        val root = find(view, rootKey) ?: error("$scene: no $rootKey")
        val out = mutableListOf<String>()
        collect(root, rootKey, out)
        File(ANDROID, "$scene.json").writeText(
            """{"scene":${q(scene)},"scale":0.8,"canvas":{"w":1280.0,"h":750.0},"root":${q(rootKey)},"elements":[""" + out.joinToString(",\n") + "]}\n",
        )
    }

    /** [v]'s box on the screen in canvas px: the layout's unrounded one inside a [Box], else its pixels. */
    private fun box(v: View): FloatArray {
        val d = v.resources.displayMetrics.density
        var x = 0f; var y = 0f
        var cur: View = v
        while (true) {
            val p = cur.parent as? View ?: break
            val lp = cur.layoutParams
            if (lp is Lp) { x += lp.x; y += lp.y } else { x += cur.left / d; y += cur.top / d }
            x += cur.translationX / d; y += cur.translationY / d
            x -= p.scrollX / d; y -= p.scrollY / d
            if (p.parent !is View) break
            cur = p
        }
        val lp = v.layoutParams
        return if (lp is Lp) floatArrayOf(x, y, lp.cw, lp.ch) else floatArrayOf(x, y, v.width / d, v.height / d)
    }

    private fun shown(v: View): Boolean {
        var cur: View? = v
        var alpha = 1f
        while (cur != null) {
            if (cur.visibility != View.VISIBLE) return false
            alpha *= cur.alpha
            cur = cur.parent as? View
        }
        return alpha > 0f
    }

    private fun collect(view: View, key: String, out: MutableList<String>) {
        val b = box(view)
        val inside = b[0] + b[2] > 0f && b[0] < 1280f && b[1] + b[3] > 0f && b[1] < 750f
        if (shown(view) && b[2] > 0f && b[3] > 0f && inside) {
            val color = when (view) { is Txt -> view.color; is IconView -> view.color; else -> null }
            val bg = (view as? IconView)?.color
            out += buildString {
                append("""{"key":${q(key)},"tag":${q(view.javaClass.simpleName)},""")
                append(""""box":{"x":${b[0]},"y":${b[1]},"w":${b[2]},"h":${b[3]}},""")
                append(""""style":{"background":${hex(bg)},"color":${hex(color)},""")
                if (view is Txt) append(""""fontSize":${view.style.size},"fontWeight":${view.style.weight},""")
                append(""""opacity":${view.alpha}},"text":${(view as? Txt)?.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let(::q) ?: "null"}}""")
            }
        }
        val kids = keyed(view)
        val counts = kids.groupingBy { it.uiKey!! }.eachCount()
        val index = mutableMapOf<String, Int>()
        for (k in kids) {
            val s = k.uiKey!!
            val i = index.merge(s, 1, Int::plus)!! - 1
            collect(k, "$key/$s" + if (counts.getValue(s) > 1) ":$i" else "", out)
        }
    }

    private fun keyed(view: View): List<View> =
        if (view !is ViewGroup) emptyList()
        else (0 until view.childCount).map(view::getChildAt).filter { it.visibility != View.GONE }
            .flatMap { if (it.uiKey != null) listOf(it) else keyed(it) }

    private fun find(v: View, key: String): View? {
        if (v.uiKey == key) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i), key)?.let { return it }
        return null
    }

    private fun hex(c: Int?) = c?.let { q("#%08X".format(it)) } ?: "null"
    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private object Png : SnapshotHandler {
        var target: File? = null
        var root: String = ""
        var view: View? = null
        override fun newFrameHandler(snapshot: Snapshot, frameCount: Int, fps: Int) = object : SnapshotHandler.FrameHandler {
            override fun handle(image: BufferedImage) { ImageIO.write(image, "png", target!!) }
            override fun close() {}
        }
        override fun close() {}
    }

    private companion object {
        /** The capture's frozen moment: Saturday 31 October 2026, 12:00 in Bucharest. */
        const val NOW = 1793440800000L
        const val MIN = 60_000L
        const val HOUR = 60 * MIN
        const val DAY = 24 * HOUR
        val WEB = File(System.getProperty("rdstr.parity.web") ?: "../build/parity/web")
        val ANDROID = File(System.getProperty("rdstr.parity.android") ?: "../build/parity/android")

        fun c(name: String, vararg n: Pair<String, String>) = Contact(name, n.map { PhoneNumber(it.first, it.second) })
        val CONTACTS = listOf(
            c("Ana Petrescu", "+40 721 000 101" to "mobile"), c("Andrei Popa", "0722 000 202" to "mobile"),
            c("Victor Stan", "0744 000 303" to "mobile", "021 000 3030" to "work"), c("Bogdan Ionescu", "0723 000 404" to "mobile"),
            c("Cristina Dima", "0755 000 505" to "mobile"), c("Daniel Marin", "0766 000 606" to "mobile"),
            c("Elena Radu", "0745 000 707" to "mobile", "0264 000 770" to "home"), c("Florin Tudor", "0731 000 808" to "mobile"),
            c("Ioana Vlad", "0727 000 909" to "mobile"), c("Mama", "0721 000 111" to "mobile"),
            c("Mihai Georgescu", "0740 000 121" to "mobile", "021 000 1212" to "work"), c("Radu Constantin", "0757 000 131" to "mobile"),
            c("Service auto", "021 000 1414" to "work"), c("Ștefan Matei", "0733 000 151" to "mobile"), c("Teodora Iliescu", "0768 000 161" to "mobile"),
        )
        fun calls(now: Long) = listOf(
            CallRecord("0744 000 303", type = CallType.MISSED, atMs = now - 25 * MIN), CallRecord("+40744000303", type = CallType.MISSED, atMs = now - 31 * MIN),
            CallRecord("0721 000 111", type = CallType.OUTGOING, atMs = now - 2 * HOUR), CallRecord("021 000 1414", type = CallType.INCOMING, atMs = now - DAY - 3 * HOUR),
            CallRecord("0722 000 202", type = CallType.OUTGOING, atMs = now - 2 * DAY), CallRecord("0799 000 444", type = CallType.MISSED, atMs = now - 4 * DAY),
            CallRecord("0745 000 707", type = CallType.INCOMING, atMs = now - 9 * DAY),
        )
        val MESSAGES = listOf(
            CarEvent.Message("m-0", "0744 000 303", "Are you coming tonight?", atMs = NOW - 20 * MIN),
            CarEvent.Message("m-1", "0721 000 111", "Did you eat? Call me when you get home.", atMs = NOW - 3 * HOUR),
            CarEvent.Message("m-2", "0721 000 111", "On my way, see you at 8.", atMs = NOW - 150 * MIN, sent = true),
            CarEvent.Message("m-3", "021 000 1414", "Your car is ready for pickup. We are open until 18:00.", atMs = NOW - 26 * HOUR),
            CarEvent.Message("m-4", "021 000 1414", "Thanks, I'll come tomorrow morning.", atMs = NOW - 25 * HOUR, sent = true),
        )
    }
}

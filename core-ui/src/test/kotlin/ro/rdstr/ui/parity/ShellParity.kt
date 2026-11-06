package ro.rdstr.ui.parity

import ro.rdstr.ui.shell.Ambient
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
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
import ro.rdstr.car.App
import ro.rdstr.car.AudioSource
import ro.rdstr.car.CardPage
import ro.rdstr.car.CarEvent
import ro.rdstr.car.Design
import ro.rdstr.car.HomeApps
import ro.rdstr.car.HomeCard
import ro.rdstr.car.RulesState
import ro.rdstr.car.ShellState
import ro.rdstr.car.ThemeMode
import ro.rdstr.ui.home.CardActions
import ro.rdstr.ui.home.CardState
import ro.rdstr.ui.home.HomeCardView
import ro.rdstr.ui.kit.UiTheme
import ro.rdstr.ui.shell.ConnectionNote
import ro.rdstr.ui.shell.GestureActions
import ro.rdstr.ui.shell.GesturesView
import ro.rdstr.ui.shell.Layer
import ro.rdstr.ui.shell.NoticeView
import ro.rdstr.ui.shell.Rail
import ro.rdstr.ui.shell.RecentsActions
import ro.rdstr.ui.shell.RecentsState
import ro.rdstr.ui.shell.RecentsView
import ro.rdstr.ui.shell.ShellActions
import ro.rdstr.ui.shell.ShellModel
import ro.rdstr.ui.shell.ShellView
import ro.rdstr.ui.shell.StatusBar
import ro.rdstr.ui.uiKey
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Visual parity of the shell (Screen.tsx and its home), in the browser capture's state (`?bare&noboot&fresh&freeze`,
 * the sample phone connected and playing, noon): the status bar, the dock, the card without a phone, the open apps,
 * the connection notice and the whole home screen, in the four looks. Written as ParityCapture writes its scenes.
 */
class ShellParity {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.NEXUS_5.copy(
            screenWidth = W, screenHeight = H, xdpi = DPI, ydpi = DPI, density = Density.create(DPI),
            orientation = ScreenOrientation.LANDSCAPE, softButtons = false,
        ),
        theme = "android:Theme.Translucent.NoTitleBar.Fullscreen",
        renderingMode = RenderingMode.NORMAL,
        snapshotHandler = Png,
        useDeviceResolution = true,
    )

    private val ctx get() = paparazzi.context

    // the browser's sample state (store.ts fromUrl)
    private val car = RulesState(source = AudioSource.BT, playing = true, btConnected = true, hfpConnected = true)
    private fun shell(light: Boolean, glassy: Boolean) =
        ShellState(theme = if (light) ThemeMode.LIGHT else ThemeMode.DARK, design = if (glassy) Design.GLASSY else Design.SOLID)
    private fun model(light: Boolean, glassy: Boolean) = ShellModel(
        shell = shell(light, glassy), car = car, now = NOON, btOn = true, wifiOn = true, wifiSsid = "Home", wifiLevel = 3,
        phoneStatus = CarEvent.PhoneStatus(4, 3, "Vela Mobile", false), homeApps = HomeApps.shown(HomeApps.DEFAULT, emptyList()), unread = 1,
    )
    private val track = CarEvent.Track("Night Transit", "Vela Orsini", durationMs = 228000)
    private val radio = CarEvent.Radio(101900, CarEvent.Radio.Band.FM, "NORD FM", pty = 9, stereo = true)

    @Test
    fun bar() = looks { name, light, glassy ->
        scene("bar-$name", "bar", 0f, 0f, 1280f, 56f) { StatusBar(ctx, NoShell).apply { bind(model(light, glassy)) } }
    }

    @Test
    fun rail() = looks { name, _, _ -> scene("rail-$name", "rail", 0f, 56f, 112f, 694f) { Rail(ctx) {} } }

    @Test
    fun cardNoPhone() = looks { name, _, _ ->
        scene("card-nophone-$name", "panel", 880f, 56f, 376f, 670f) {
            FrameLayout(ctx).apply {
                uiKey = "panel"
                addView(HomeCardView(ctx, NoCard).apply {
                    val off = RulesState(source = AudioSource.NONE)
                    bind(CardState(HomeCard.pages(off, true), HomeCard.shown(off, true, null), HomeCard.strip(off), off, radio = radio,
                        saved = listOf("Pixel 8", "Galaxy S23", "Galaxy Tab S9")))
                })
            }
        }
    }

    @Test
    fun recents() = looks { name, _, _ ->
        scene("recents-$name", "recents", 0f, 0f, 1280f, 750f) {
            RecentsView(ctx, NoRecents).apply { bind(RecentsState(true, listOf(App.SETTINGS, App.RADIO, App.MAPS), track, radio = radio)); animate().cancel(); alpha = 1f }
        }
    }

    @Test
    fun notice() = looks { name, _, _ ->
        scene("notice-$name", "notice", 0f, 0f, 1280f, 750f) { NoticeView(ctx).apply { bind(ConnectionNote(1, false, true, "Pixel 8"), hidden = false, mapOffer = false) } }
    }

    @Test
    fun home() = looks { name, light, glassy ->
        scene("home-$name", null, 0f, 0f, 1280f, 750f) {
            ShellView(ctx, NoShell).apply {
                put(Layer.PANEL, HomeCardView(ctx, NoCard).apply {
                    bind(CardState(HomeCard.pages(car, true), HomeCard.shown(car, true, null), HomeCard.strip(car), car, track, null, 72000, System.currentTimeMillis(), radio, true))
                })
                put(Layer.GESTURES, GesturesView(ctx, NoGestures))
                bind(model(light, glassy))
            }
        }
    }

    private fun looks(block: (name: String, light: Boolean, glassy: Boolean) -> Unit) {
        for (glassy in listOf(false, true)) for (light in listOf(false, true)) {
            UiTheme.set(light, glassy, geist = true, speed = 0f)
            block("${if (light) "light" else "dark"}-${if (glassy) "glassy" else "solid"}", light, glassy)
        }
    }

    /**
     * [build]'s view at canvas box ([x], [y], [w], [h]) on a transparent screen; the elements written are the subtree
     * of the view keyed [root] (the whole screen when null), keyed from the screen as the browser keys them.
     */
    private fun scene(scene: String, root: String?, x: Float, y: Float, w: Float, h: Float, build: () -> View) {
        val web = File(WEB, "$scene.json")
        assumeTrue("no browser capture $web; run npm run parity:capture", web.isFile)
        val k = DPI / 160f
        val screen = FrameLayout(ctx)
        val view = build()
        screen.addView(view, FrameLayout.LayoutParams((w * k + 0.5f).toInt(), (h * k + 0.5f).toInt()).apply { leftMargin = (x * k + 0.5f).toInt(); topMargin = (y * k + 0.5f).toInt() })
        // Glassy: the browser captures a component with every other element hidden, so its glass frosts nothing; the
        // backdrop is an empty, transparent screen
        val ambient = UiTheme.glassy && !ro.rdstr.ui.kit.Backdrop.captured
        if (ambient) {
            val ground = View(ctx)
            ground.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY))
            ground.layout(0, 0, W, H)
            ro.rdstr.ui.kit.Backdrop.capture(ground, UiTheme.glass.glassyFrost)
        }
        Png.target = File(ANDROID, "$scene.png").apply { parentFile.mkdirs() }
        paparazzi.snapshot(screen, scene)
        if (ambient) ro.rdstr.ui.kit.Backdrop.clear()
        val out = mutableListOf<String>()
        if (root == null) for ((key, kid) in keyed(view)) collect(kid, key, screen, k, out)
        else find(view, root)?.let { collect(it, root, screen, k, out) }
        File(ANDROID, "$scene.json").writeText(
            """{"scene":${q(scene)},"scale":$k,"canvas":{"w":${W / k},"h":${H / k}},"root":${root?.let(::q) ?: "null"},"elements":[""" + out.joinToString(",\n") + "]}\n",
        )
    }

    private fun find(v: View, key: String): View? {
        if (v.uiKey == key) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i), key)?.let { return it }
        return null
    }

    private fun collect(view: View, key: String, screen: View, k: Float, out: MutableList<String>) {
        var x = 0f; var y = 0f; var alpha = 1f
        var v: View = view
        while (v !== screen) { x += v.left + v.translationX - v.scrollX; y += v.top + v.translationY; alpha *= v.alpha; v = v.parent as View }
        if (view.visibility == View.VISIBLE && alpha > 0f && view.width > 0 && view.height > 0) {
            val text = (view as? TextView)?.text?.toString()?.takeIf { it.isNotBlank() }
            val color = (view as? TextView)?.currentTextColor
            out += buildString {
                append("""{"key":${q(key)},"tag":${q(view.javaClass.simpleName)},""")
                append(""""box":{"x":${x / k},"y":${y / k},"w":${view.width / k},"h":${view.height / k}},""")
                append(""""style":{"background":null,"color":${color?.let { q("#%08X".format(it)) } ?: "null"},""")
                if (view is TextView) append(""""fontSize":${view.textSize / k},""")
                append(""""opacity":${view.alpha}},"text":${text?.let(::q) ?: "null"}}""")
            }
        }
        for ((s, kid) in keyed(view)) collect(kid, "$key/$s", screen, k, out)
    }

    /** The keyed children of [view] with their segments (":i" among same segments), through views without a key. */
    private fun keyed(view: View): List<Pair<String, View>> {
        fun near(v: View): List<View> = if (v !is ViewGroup) emptyList()
        else (0 until v.childCount).map(v::getChildAt).filter { it.visibility != View.GONE }.flatMap { if (it.uiKey != null) listOf(it) else near(it) }
        val kids = near(view)
        val counts = kids.groupingBy { it.uiKey!! }.eachCount()
        val index = mutableMapOf<String, Int>()
        return kids.map { kid -> val s = kid.uiKey!!; val i = index.merge(s, 1, Int::plus)!! - 1; (s + if (counts.getValue(s) > 1) ":$i" else "") to kid }
    }

    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private object Png : SnapshotHandler {
        var target: File? = null
        override fun newFrameHandler(snapshot: Snapshot, frameCount: Int, fps: Int) = object : SnapshotHandler.FrameHandler {
            override fun handle(image: BufferedImage) { ImageIO.write(image, "png", target!!) }
            override fun close() {}
        }
        override fun close() {}
    }

    private object NoShell : ShellActions {
        override fun userActivity() = Unit
        override fun pressRail(id: String) = Unit
        override fun launchApp(id: String) = Unit
        override fun openDrawer() = Unit
        override fun closeDrawer() = Unit
        override fun openVolume() = Unit
        override fun moveHomeApp(id: String, by: Int) = Unit
        override fun toggleHomeApp(id: String) = Unit
        override fun wake() = Unit
        override fun barDrag(e: MotionEvent) = false
    }

    private object NoCard : CardActions {
        override fun showCard(page: CardPage) = Unit
        override fun openApp(app: App) = Unit
        override fun pressSound(page: CardPage, press: HomeCard.Press) = Unit
        override fun toggleFavorite(khz: Int) = Unit
        override fun connect(device: String) = Unit
        override fun openDevices() = Unit
        override fun turnOnBluetooth() = Unit
    }

    private object NoRecents : RecentsActions {
        override fun openApp(app: App) = Unit
        override fun closeApp(app: App) = Unit
        override fun closeAll() = Unit
        override fun close() = Unit
    }

    private object NoGestures : GestureActions {
        override fun home() = Unit
        override fun openRecents() = Unit
        override fun back() = Unit
    }

    private companion object {
        const val W = 1024
        const val H = 600
        const val DPI = 128
        /** Saturday 31 October 2026, 12:00 in Bucharest, as the browser capture pins its clock. */
        const val NOON = 1_793_440_800_000L
        val WEB = File(System.getProperty("rdstr.parity.web") ?: "../build/parity/web")
        val ANDROID = File(System.getProperty("rdstr.parity.android") ?: "../build/parity/android")
    }
}

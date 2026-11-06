package ro.rdstr.ui.parity

import android.graphics.Bitmap
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
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
import ro.rdstr.car.CarCommand
import ro.rdstr.car.CardPage
import ro.rdstr.car.CarEvent.Radio.Band
import ro.rdstr.car.HomeCard
import ro.rdstr.car.ShellAction
import ro.rdstr.ui.kit.IconView
import ro.rdstr.ui.kit.UiTheme
import ro.rdstr.ui.media.MediaAppFrame
import ro.rdstr.ui.media.MusicActions
import ro.rdstr.ui.media.MusicAppView
import ro.rdstr.ui.media.MusicState
import ro.rdstr.ui.media.RadioActions
import ro.rdstr.ui.media.RadioAppView
import ro.rdstr.ui.media.RadioState
import ro.rdstr.ui.media.SavedPhone
import ro.rdstr.ui.media.Station
import ro.rdstr.ui.uiKey
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Visual parity of the media screens (music playing, paused, without a phone; radio on FM and AM), in the four looks,
 * against the browser's scenes of capture-layout.mjs ("media" block). The app is a component scene rooted at its
 * section, drawn alone. The capture works as ParityCapture's, with boxes through each view's transform (a scaled
 * cover reports its scaled box, as getBoundingClientRect does) and only elements inside the screen, as the browser
 * lists them.
 */
class MediaParity {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.NEXUS_5.copy(
            screenWidth = SCREEN_W, screenHeight = SCREEN_H, xdpi = DPI, ydpi = DPI, density = Density.create(DPI),
            orientation = ScreenOrientation.LANDSCAPE, softButtons = false,
        ),
        theme = "android:Theme.Translucent.NoTitleBar.Fullscreen",
        renderingMode = RenderingMode.NORMAL,
        snapshotHandler = Png,
        useDeviceResolution = true,
    )

    private val noop = object : MusicActions, RadioActions {
        override fun carCommand(command: CarCommand) = Unit
        override fun shellAction(action: ShellAction) = Unit
        override fun setVolume(level: Int) = Unit
        override fun stepVolume(delta: Int) = Unit
        override fun pressSound(page: CardPage, press: HomeCard.Press) = Unit
        override fun toggleFavorite(khz: Int) = Unit
    }

    /** The emulator's ?app=music: the sample phone, its first track 1:12 in. */
    private fun music(playing: Boolean, connected: Boolean = true) = MusicState(
        connected = connected, playing = playing, muted = false, volume = 16,
        title = if (connected) "Night Transit" else "", artist = if (connected) "Vela Orsini" else "", durationMs = 228_000,
        positionMs = 72_000, positionAt = NOW, device = "Pixel 8",
        cover = if (connected) cover() else null, coverKey = "night-transit",
        saved = listOf(SavedPhone("Pixel 8", 0x0200), SavedPhone("Galaxy S23", 0x0200), SavedPhone("Galaxy Tab S9", 0x0100)),
    )

    @Test fun musicPlaying() = looks { name, light, glassy -> app("music-playing-$name", light, glassy) { musicView(music(playing = true)) } }
    @Test fun musicPaused() = looks { name, light, glassy -> app("music-paused-$name", light, glassy) { musicView(music(playing = false)) } }
    @Test fun musicNoPhone() = looks { name, light, glassy -> app("music-nophone-$name", light, glassy) { musicView(music(playing = false, connected = false)) } }

    /** The emulator's simulated air (car/radio.ts STATIONS). */
    private val air = listOf(
        Station(Band.FM, 88_600, "CITYWAVE", 10), Station(Band.FM, 89_200, "POP HITS", 10), Station(Band.FM, 91_400, "NEWS 24", 1),
        Station(Band.FM, 93_700, "ROCK 93", 11), Station(Band.FM, 95_350, "JAZZ BAR", 24), Station(Band.FM, 97_800, "CLASSIC", 14),
        Station(Band.FM, 100_400, "RETRO", 27), Station(Band.FM, 101_900, "NORD FM", 9), Station(Band.FM, 104_500, "SPORT FM", 4),
        Station(Band.FM, 106_800, "FOLK", 28), Station(Band.FM, 107_500, "", 0),
        Station(Band.AM, 558, "", 0), Station(Band.AM, 1_404, "", 0), Station(Band.AM, 1_593, "", 0),
    )

    private fun radio(am: Boolean) = RadioState(
        band = if (am) Band.AM else Band.FM, khz = if (am) 1_404 else 101_900,
        rds = if (am) "" else "NORD FM", pty = if (am) 0 else 9, stereo = !am,
        stations = air, favorites = if (am) listOf(558, 89_200, 91_400, 101_900) else listOf(89_200, 91_400, 101_900),
        scan = null, radioOn = true, own = true, muted = false,
    )

    @Test fun radioFm() = looks { name, light, glassy -> app("radio-fm-$name", light, glassy) { RadioAppView(paparazzi.context, noop).apply { bind(radio(am = false), animate = false) } } }
    @Test fun radioAm() = looks { name, light, glassy -> app("radio-am-$name", light, glassy) { RadioAppView(paparazzi.context, noop).apply { bind(radio(am = true), animate = false) } } }

    private fun musicView(s: MusicState) = MusicAppView(paparazzi.context, noop).apply { clock = { NOW }; bind(s, animate = false) }

    /** The cover as the browser drew it in its playing scene (the emulator's SVG, rasterised there). */
    private fun cover(): Bitmap {
        val png = File(WEB, "music-playing-dark-solid.png")
        assumeTrue("no browser capture $png", png.isFile)
        val json = File(WEB, "music-playing-dark-solid.json").readText()
        val box = Regex(""""key":\s*"music-app/music-cover/img",\s*"tag":\s*"[^"]*",\s*"box":\s*\{\s*"x":\s*([-\d.]+),\s*"y":\s*([-\d.]+),\s*"w":\s*([-\d.]+),\s*"h":\s*([-\d.]+)""").find(json)!!
        val k = DPI / 160f
        val (x, y, w, h) = box.groupValues.drop(1).map { Math.round(it.toFloat() * k) }
        val img = ImageIO.read(png).getSubimage(x, y, w, h)
        val px = IntArray(w * h)
        img.getRGB(0, 0, w, h, px, 0, w)
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun looks(block: (name: String, light: Boolean, glassy: Boolean) -> Unit) {
        for (glassy in listOf(false, true)) for (light in listOf(false, true)) {
            block("${if (light) "light" else "dark"}-${if (glassy) "glassy" else "solid"}", light, glassy)
        }
    }

    /** One app scene: the look set, the app open and laid out at the screen's size, its frost made, then captured. */
    private fun app(scene: String, light: Boolean, glassy: Boolean, build: () -> MediaAppFrame) {
        val web = File(WEB, "$scene.json")
        assumeTrue("no browser capture $web; run npm run parity:capture", web.isFile)
        val json = web.readText()
        val root = Regex(""""root":\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: error("$scene is not a component scene")
        UiTheme.set(light, glassy, geist = true, speed = 0f)
        val density = DPI / 160f
        val screen = FrameLayout(paparazzi.context)
        val view = build()
        check(view.uiKey == root) { "$scene: ${view.uiKey} is not $root" }
        view.open(true, animate = false)
        screen.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        screen.measure(View.MeasureSpec.makeMeasureSpec(SCREEN_W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(SCREEN_H, View.MeasureSpec.EXACTLY))
        screen.layout(0, 0, SCREEN_W, SCREEN_H)
        view.refreshFrost()
        Png.target = File(ANDROID, "$scene.png").apply { parentFile.mkdirs() }
        paparazzi.snapshot(screen, scene)
        val elements = mutableListOf<String>()
        collect(view, root, screen, density, elements)
        File(ANDROID, "$scene.json").writeText(
            """{"scene":${q(scene)},"scale":$density,"canvas":{"w":${SCREEN_W / density},"h":${SCREEN_H / density}},"root":${q(root)},"elements":[""" +
                elements.joinToString(",\n") + "]}\n",
        )
    }

    private fun collect(view: View, key: String, screen: View, density: Float, out: MutableList<String>) {
        val r = RectF(0f, 0f, view.width.toFloat(), view.height.toFloat())
        var alpha = 1f
        var shown = true
        var v: View = view
        while (v !== screen) {
            v.matrix.mapRect(r)
            r.offset(v.left.toFloat(), v.top.toFloat())
            val p = v.parent as View
            r.offset(-p.scrollX.toFloat(), -p.scrollY.toFloat())
            alpha *= v.alpha
            if (v.visibility != View.VISIBLE) shown = false
            v = p
        }
        val inside = r.right > 0 && r.left < SCREEN_W && r.bottom > 0 && r.top < SCREEN_H
        if (shown && alpha > 0f && r.width() > 0 && r.height() > 0 && inside) {
            val text = (view as? TextView)?.text?.toString()?.takeIf { it.isNotBlank() }
            val color = when (view) {
                is TextView -> view.currentTextColor
                is IconView -> view.color
                else -> null
            }
            val background = (view.background as? ColorDrawable)?.color
            out += buildString {
                append("""{"key":${q(key)},"tag":${q(view.javaClass.simpleName)},""")
                append(""""box":{"x":${r.left / density},"y":${r.top / density},"w":${r.width() / density},"h":${r.height() / density}},""")
                append(""""style":{"background":${hex(background)},"color":${hex(color)},""")
                if (view is TextView) append(""""fontSize":${view.textSize / density},"fontWeight":${weight(view)},""")
                append(""""opacity":${view.alpha}},"text":${text?.let(::q) ?: "null"}}""")
            }
        }
        val kids = keyedChildren(view)
        val counts = kids.groupingBy { it.uiKey!! }.eachCount()
        val index = mutableMapOf<String, Int>()
        for (kid in kids) {
            val s = kid.uiKey!!
            val i = index.merge(s, 1, Int::plus)!! - 1
            collect(kid, "$key/$s" + if (counts.getValue(s) > 1) ":$i" else "", screen, density, out)
        }
    }

    /** The weight the text is drawn at: its font's `wght` axis (the kit's texts set it), else the typeface's. */
    private fun weight(t: TextView): Int =
        Regex("""'wght'\s+(\d+)""").find(t.fontVariationSettings.orEmpty())?.groupValues?.get(1)?.toInt() ?: t.typeface?.weight ?: 400

    private fun keyedChildren(view: View): List<View> =
        if (view !is ViewGroup) emptyList()
        else (0 until view.childCount).map(view::getChildAt).filter { it.visibility != View.GONE }
            .flatMap { if (it.uiKey != null) listOf(it) else keyedChildren(it) }

    private fun hex(c: Int?) = c?.let { q("#%08X".format(it)) } ?: "null"
    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private object Png : SnapshotHandler {
        var target: File? = null
        override fun newFrameHandler(snapshot: Snapshot, frameCount: Int, fps: Int) = object : SnapshotHandler.FrameHandler {
            override fun handle(image: BufferedImage) { ImageIO.write(image, "png", target!!) }
            override fun close() {}
        }
        override fun close() {}
    }

    private companion object {
        const val SCREEN_W = 1024
        const val SCREEN_H = 600
        const val DPI = 128
        /** The capture's frozen moment: what the phone's position was reported at. */
        const val NOW = 1_793_444_400_000L
        val WEB = File(System.getProperty("rdstr.parity.web") ?: "../build/parity/web")
        val ANDROID = File(System.getProperty("rdstr.parity.android") ?: "../build/parity/android")
    }
}

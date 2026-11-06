package ro.rdstr.ui.parity

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
import ro.rdstr.car.SpeedWarning
import ro.rdstr.ui.kit.UiTheme
import ro.rdstr.ui.maps.MapStatus
import ro.rdstr.ui.maps.MapsAppView
import ro.rdstr.ui.uiKey
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Visual parity of the maps screen (MapsApp.tsx), in the four looks, against the browser scenes `maps-follow-*` and
 * `maps-panned-*` of capture-layout.mjs: the screen with its controls and status, the map itself left out on both
 * sides. The state is the one the browser shows at the car's start position: the street from the laptop's pack,
 * stopped, the road's 30 km/h sign.
 *
 * Same rendering and JSON shape as ParityCapture; the text weight is read from the font's `wght` axis, which is how
 * the kit sets it.
 */
class MapsParity {
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

    @Test
    fun follow() = looks { name -> scene("maps-follow-$name", panned = false) }

    @Test
    fun panned() = looks { name -> scene("maps-panned-$name", panned = true) }

    private fun looks(block: (String) -> Unit) {
        for (glassy in listOf(false, true)) for (light in listOf(false, true)) {
            UiTheme.set(light, glassy, geist = true, speed = 0f)
            block("${if (light) "light" else "dark"}-${if (glassy) "glassy" else "solid"}")
        }
    }

    private fun scene(scene: String, panned: Boolean) {
        val web = File(WEB, "$scene.json")
        assumeTrue("no browser capture $web; run npm run parity:capture", web.isFile)
        val street = Regex(""""text":\s*"([^"]+)"""").findAll(web.readText()).map { it.groupValues[1] }.first { it.length > 3 && it != "km/h" }
        val view = MapsAppView(paparazzi.context)
        view.status.bind(MapStatus(street = street, kmh = 0, limit = 30, over = false, lit = 0, tone = SpeedWarning.Tone.WHITE, sign = true))
        view.bind(follow = !panned, headingUp = true)
        val screen = FrameLayout(paparazzi.context)
        screen.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (panned) {
            // the credits button, as the browser scene clicks it
            screen.measure(View.MeasureSpec.makeMeasureSpec(SCREEN_W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(SCREEN_H, View.MeasureSpec.EXACTLY))
            find(view, "glass-button") { it.contentDescription == "Map data credits" }!!.performClick()
        }
        Png.target = File(ANDROID, "$scene.png").apply { parentFile.mkdirs() }
        paparazzi.snapshot(screen, scene)
        val density = DPI / 160f
        val elements = mutableListOf<String>()
        collect(view, ROOT, screen, density, elements)
        File(ANDROID, "$scene.json").writeText(
            """{"scene":${q(scene)},"scale":$density,"canvas":{"w":${SCREEN_W / density},"h":${SCREEN_H / density}},"root":${q(ROOT)},"elements":[""" +
                elements.joinToString(",\n") + "]}\n",
        )
    }

    private fun find(v: View, key: String, test: (View) -> Boolean): View? {
        if (v.uiKey == key && test(v)) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i), key, test)?.let { return it }
        return null
    }

    private fun collect(view: View, key: String, screen: View, density: Float, out: MutableList<String>) {
        var x = 0f
        var y = 0f
        var alpha = 1f
        var v: View = view
        while (v !== screen) {
            x += v.left + v.translationX - v.scrollX
            y += v.top + v.translationY
            alpha *= v.alpha
            v = v.parent as View
        }
        if (view.visibility == View.VISIBLE && alpha > 0f && view.width > 0 && view.height > 0) {
            val text = (view as? TextView)?.text?.toString()?.takeIf { it.isNotBlank() }
            val color = (view as? TextView)?.currentTextColor
            out += buildString {
                append("""{"key":${q(key)},"tag":${q(view.javaClass.simpleName)},""")
                append(""""box":{"x":${x / density},"y":${y / density},"w":${view.width / density},"h":${view.height / density}},""")
                append(""""style":{"background":null,"color":${color?.let { q("#%08X".format(it)) } ?: "null"},""")
                if (view is TextView) append(""""fontSize":${view.textSize / density},"fontWeight":${weight(view)},""")
                append(""""opacity":${view.alpha}},"text":${text?.let(::q) ?: "null"}}""")
            }
        }
        val kids = keyed(view)
        val counts = kids.groupingBy { it.uiKey!! }.eachCount()
        val index = mutableMapOf<String, Int>()
        for (kid in kids) {
            val s = kid.uiKey!!
            val i = index.merge(s, 1, Int::plus)!! - 1
            collect(kid, "$key/$s" + if (counts.getValue(s) > 1) ":$i" else "", screen, density, out)
        }
    }

    private fun weight(t: TextView) =
        Regex("""'wght'\s+(\d+)""").find(t.fontVariationSettings ?: "")?.groupValues?.get(1)?.toInt() ?: t.typeface?.weight ?: 400

    private fun keyed(view: View): List<View> =
        if (view !is ViewGroup) emptyList()
        else (0 until view.childCount).map(view::getChildAt).filter { it.visibility != View.GONE }
            .flatMap { if (it.uiKey != null) listOf(it) else keyed(it) }

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
        const val ROOT = "music-app.maps-app"
        const val SCREEN_W = 1024
        const val SCREEN_H = 600
        const val DPI = 128
        val WEB = File(System.getProperty("rdstr.parity.web") ?: "../build/parity/web")
        val ANDROID = File(System.getProperty("rdstr.parity.android") ?: "../build/parity/android")
    }
}

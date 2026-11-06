package ro.rdstr.ui.parity

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
import ro.rdstr.car.Design
import ro.rdstr.car.Setup
import ro.rdstr.car.ThemeMode
import ro.rdstr.ui.UiPart
import ro.rdstr.ui.UiParts
import ro.rdstr.ui.WifiBars
import ro.rdstr.ui.kit.UiTheme
import ro.rdstr.ui.setup.SetupFile
import ro.rdstr.ui.setup.SetupPlace
import ro.rdstr.ui.setup.SetupState
import ro.rdstr.ui.setup.SetupView
import ro.rdstr.ui.setup.SetupWifi
import ro.rdstr.ui.setup.UnitPicture
import ro.rdstr.ui.uiKey
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/**
 * Visual parity of the setup's screens (SetupScreens.tsx) and About's picture of the unit (UnitPicture.tsx), each in
 * the browser scene's state (capture-layout.mjs, the setup block). As ParityCapture, plus the elements views draw
 * themselves ([UiParts]) and the weight of a variable font read from its `wght` axis.
 */
class SetupParity {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.NEXUS_5.copy(
            screenWidth = 1024, screenHeight = 600, xdpi = DPI, ydpi = DPI, density = Density.create(DPI),
            orientation = ScreenOrientation.LANDSCAPE, softButtons = false,
        ),
        theme = "android:Theme.Translucent.NoTitleBar.Fullscreen",
        renderingMode = RenderingMode.NORMAL,
        snapshotHandler = Png,
        useDeviceResolution = true,
    )

    private val wifi = SetupWifi(
        networks = listOf(Setup.Network("Phone hotspot", 3, true), Setup.Network("Home", 2, true), Setup.Network("Cafe Free Wi-Fi", 2, false), Setup.Network("Office 5G", 1, true)),
        connected = "Phone hotspot", saved = listOf("Phone hotspot"),
    )
    private val done = Setup.Prepare(Setup.Task.entries.associateWith { Setup.TaskState.DONE })

    private fun state(step: Setup.Step, light: Boolean, glassy: Boolean) = SetupState(
        step = step, prepare = done, wifi = wifi, release = "1.19.0", place = SetupPlace("", "Europe/Bucharest", true, "RO"),
        design = if (glassy) Design.GLASSY else Design.SOLID, theme = if (light) ThemeMode.LIGHT else ThemeMode.DARK,
        files = listOf(SetupFile("Map of Romania", "363 MB", Setup.TaskState.DONE), SetupFile("Fonts", "1.8 MB", Setup.TaskState.DONE)),
    )

    @Test
    fun steps() = looks { name, light, glassy ->
        for ((scene, step) in SCENES) setup("setup-$scene-$name", light, glassy) { it.bind(state(step, light, glassy)) }
    }

    @Test
    fun unitPicture() = looks { name, light, glassy ->
        UiTheme.set(light, glassy, true, 0f)
        component("unitpicture-$name") { UnitPicture(paparazzi.context) }
    }

    private fun setup(scene: String, light: Boolean, glassy: Boolean, bind: (SetupView) -> Unit) {
        UiTheme.set(light, glassy, true, 0f)
        component(scene) { SetupView(paparazzi.context, NoActions).also(bind) }
    }

    private fun looks(block: (name: String, light: Boolean, glassy: Boolean) -> Unit) {
        for (glassy in listOf(false, true)) for (light in listOf(false, true)) {
            block("${if (light) "light" else "dark"}-${if (glassy) "glassy" else "solid"}", light, glassy)
        }
    }

    private fun component(scene: String, build: () -> View) {
        val web = File(WEB, "$scene.json")
        assumeTrue("no browser capture $web; run npm run parity:capture", web.isFile)
        val json = web.readText()
        val root = Regex(""""root":\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: error("$scene is not a component scene")
        val at = Regex(""""key":\s*"${Regex.escape(root)}",\s*"tag":\s*"[^"]*",\s*"box":\s*\{\s*"x":\s*([-\d.]+),\s*"y":\s*([-\d.]+),\s*"w":\s*([-\d.]+),\s*"h":\s*([-\d.]+)""").find(json)
            ?: error("$scene: no box for $root")
        val density = DPI / 160f
        val screen = FrameLayout(paparazzi.context)
        val view = build()
        val (x, y, w, h) = at.groupValues.drop(1).map { (it.toFloat() * density).roundToInt() }
        view.translationX = x.toFloat()
        view.translationY = y.toFloat()
        screen.addView(view, FrameLayout.LayoutParams(w, h))
        Png.target = File(ANDROID, "$scene.png").apply { parentFile.mkdirs() }
        paparazzi.snapshot(screen, scene)
        val elements = mutableListOf<String>()
        collect(view, root, screen, density, elements)
        File(ANDROID, "$scene.json").writeText(
            """{"scene":${q(scene)},"scale":$density,"canvas":{"w":${1024 / density},"h":${600 / density}},"root":${q(root)},"elements":[""" +
                elements.joinToString(",\n") + "]}\n",
        )
    }

    private fun collect(view: View, key: String, screen: View, density: Float, out: MutableList<String>) {
        var x = 0f
        var y = 0f
        var alpha = 1f
        var v: View = view
        while (v !== screen) {
            x += v.left + v.translationX - v.scrollX
            y += v.top + v.translationY - if (v !== view) v.scrollY else 0
            alpha *= v.alpha
            v = v.parent as View
        }
        if (view.visibility == View.VISIBLE && alpha > 0f && view.width > 0 && view.height > 0) {
            val text = (view as? TextView)?.text?.toString()?.takeIf { it.isNotBlank() }
            val color = when (view) {
                is UiParts -> view.uiColor ?: (view as? TextView)?.currentTextColor
                is TextView -> view.currentTextColor
                is WifiBars -> view.color
                else -> null
            }
            val background = when {
                view is UiParts && view.uiBackground != null -> view.uiBackground
                view.background == null -> if (view is UiParts) null else 0
                view.background is ColorDrawable -> (view.background as ColorDrawable).color
                else -> null
            }
            out += element(key, view.javaClass.simpleName, x / density, y / density, view.width / density, view.height / density, background, color, view.alpha, text,
                (view as? TextView)?.let { it.textSize / density }, (view as? TextView)?.let { weight(it) })
            if (view is UiParts) {
                val parts = mutableListOf<UiPart>().also(view::uiParts)
                for (p in parts) out += element("$key/${p.key}", "part", (x + p.x) / density, (y + p.y) / density, p.w / density, p.h / density,
                    p.background, p.color, p.opacity, p.text, null, null)
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

    private fun element(key: String, tag: String, x: Float, y: Float, w: Float, h: Float, background: Int?, color: Int?, opacity: Float, text: String?, size: Float?, weight: Int?) =
        """{"key":${q(key)},"tag":${q(tag)},"box":{"x":$x,"y":$y,"w":$w,"h":$h},"style":{"background":${hex(background)},"color":${hex(color)},""" +
            (if (size != null) """"fontSize":$size,"fontWeight":$weight,""" else "") + """"opacity":$opacity},"text":${text?.let(::q) ?: "null"}}"""

    /** The weight of the variable font's `wght` axis, else the typeface's. */
    private fun weight(t: TextView): Int =
        t.fontVariationSettings?.let { Regex("""'wght'\s+(\d+)""").find(it)?.groupValues?.get(1)?.toInt() } ?: (t.typeface?.weight ?: 400)

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

    private object NoActions : SetupView.Actions {
        override fun next() = Unit
        override fun back() = Unit
        override fun setCarName(name: String) = Unit
        override fun retryPrepare() = Unit
        override fun wifiScan() = Unit
        override fun wifiConnect(ssid: String, password: String?) = Unit
        override fun turnOnWifi() = Unit
        override fun install() = Unit
        override fun setPhoneRegion(region: String) = Unit
        override fun setDesign(design: Design) = Unit
        override fun setTheme(mode: ThemeMode) = Unit
    }

    private companion object {
        const val DPI = 128
        val WEB = File(System.getProperty("rdstr.parity.web") ?: "../build/parity/web")
        val ANDROID = File(System.getProperty("rdstr.parity.android") ?: "../build/parity/android")
        val SCENES = listOf(
            "carname" to Setup.Step.CAR_NAME, "prepare" to Setup.Step.PREPARE, "wifi" to Setup.Step.WIFI, "update" to Setup.Step.UPDATE,
            "location" to Setup.Step.LOCATION, "look" to Setup.Step.LOOK, "files" to Setup.Step.FILES, "welcome" to Setup.Step.WELCOME,
        )
    }
}

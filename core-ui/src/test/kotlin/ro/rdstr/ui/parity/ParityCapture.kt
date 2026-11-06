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
import ro.rdstr.ui.WifiBars
import ro.rdstr.ui.tokens.Base
import ro.rdstr.ui.uiKey
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/**
 * Visual parity, Android side: renders core-ui views on the JVM and writes, per scene, `<scene>.png` and
 * `<scene>.json` into build/parity/android/ at the repository root, in the shape the browser capture writes
 * (emulator/scripts/capture-layout.mjs); `:core-ui:compareParity` then compares the two.
 *
 * Rendering is Paparazzi (layoutlib, the renderer of Android Studio's layout preview): the real framework View, Canvas
 * and Skia code run on the JVM, at the unit's 1024x600 and density 128, with no device or emulator, so the check runs
 * in a plain `./gradlew` build. Paparazzi 2.0.0-alpha05.1 supports AGP 9; an instrumented test on the AVD would also
 * work but needs an emulator started for every run.
 *
 * A component scene (a web scene with `root`) places the component where the browser drew it: its position is the
 * parent's layout, which is not built yet; its size, drawing and inner layout are the component's own. Scenes are
 * skipped until the browser capture exists (`npm run parity:capture`).
 */
class ParityCapture {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.NEXUS_5.copy(
            screenWidth = SCREEN_W, screenHeight = SCREEN_H, xdpi = DPI, ydpi = DPI, density = Density.create(DPI),
            orientation = ScreenOrientation.LANDSCAPE, softButtons = false,
        ),
        // no window background: the picture holds only what the views draw, like the browser's isolated component
        theme = "android:Theme.Translucent.NoTitleBar.Fullscreen",
        renderingMode = RenderingMode.NORMAL,
        snapshotHandler = Png,
        useDeviceResolution = true,
    )

    @Test
    fun wifiBars() = looks { name, light, glassy ->
        component("wifibars-$name") {
            WifiBars(paparazzi.context).apply {
                uiKey = "wifi-bars.bar-wifi"
                level = 3
                // the status bar's `color: var(--ink)`
                color = Base.of(light, glassy).ink
            }
        }
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
        val at = Regex(""""key":\s*"${Regex.escape(root)}",\s*"tag":\s*"[^"]*",\s*"box":\s*\{\s*"x":\s*([-\d.]+),\s*"y":\s*([-\d.]+)""").find(json)
            ?: error("$scene: no box for $root")
        val density = DPI / 160f
        val screen = FrameLayout(paparazzi.context)
        val view = build()
        check(view.uiKey == root.substringAfterLast('/').substringBefore(':')) { "$scene: ${view.uiKey} is not $root" }
        // on whole pixels, as a layout places views and as the browser snaps an <svg> or <img>
        view.translationX = (at.groupValues[1].toFloat() * density).roundToInt().toFloat()
        view.translationY = (at.groupValues[2].toFloat() * density).roundToInt().toFloat()
        screen.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        Png.target = File(ANDROID, "$scene.png").apply { parentFile.mkdirs() }
        paparazzi.snapshot(screen, scene)
        val elements = mutableListOf<String>()
        collect(view, root, screen, density, elements)
        File(ANDROID, "$scene.json").writeText(
            """{"scene":${q(scene)},"scale":${density},"canvas":{"w":${SCREEN_W / density},"h":${SCREEN_H / density}},"root":${q(root)},"elements":[""" +
                elements.joinToString(",\n") + "]}\n",
        )
    }

    /** Writes [view] under [key] and its keyed descendants, the way capture-layout.mjs keys the DOM. */
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
            val color = when (view) {
                is TextView -> view.currentTextColor
                is WifiBars -> view.color
                // ponytail: one case per view that draws with a colour; an interface once there are several
                else -> null
            }
            val background = when (val b = view.background) {
                null -> 0
                is ColorDrawable -> b.color
                else -> null
            }
            out += buildString {
                append("""{"key":${q(key)},"tag":${q(view.javaClass.simpleName)},""")
                append(""""box":{"x":${x / density},"y":${y / density},"w":${view.width / density},"h":${view.height / density}},""")
                append(""""style":{"background":${hex(background)},"color":${hex(color)},""")
                if (view is TextView) append(""""fontSize":${view.textSize / density},"fontWeight":${view.typeface?.weight ?: 400},""")
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

    /** The nearest keyed descendants, through views without a key; GONE views and their subtrees are not there. */
    private fun keyedChildren(view: View): List<View> =
        if (view !is ViewGroup) emptyList()
        else (0 until view.childCount).map(view::getChildAt).filter { it.visibility != View.GONE }
            .flatMap { if (it.uiKey != null) listOf(it) else keyedChildren(it) }

    private fun hex(c: Int?) = c?.let { q("#%08X".format(it)) } ?: "null"
    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    /** Writes the rendered frame to [target]. */
    private object Png : SnapshotHandler {
        var target: File? = null
        override fun newFrameHandler(snapshot: Snapshot, frameCount: Int, fps: Int) = object : SnapshotHandler.FrameHandler {
            override fun handle(image: BufferedImage) {
                ImageIO.write(image, "png", target!!)
            }
            override fun close() {}
        }
        override fun close() {}
    }

    private companion object {
        const val SCREEN_W = 1024
        const val SCREEN_H = 600
        const val DPI = 128
        val WEB = File(System.getProperty("rdstr.parity.web") ?: "../build/parity/web")
        val ANDROID = File(System.getProperty("rdstr.parity.android") ?: "../build/parity/android")
    }
}

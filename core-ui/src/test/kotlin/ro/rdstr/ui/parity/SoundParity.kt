package ro.rdstr.ui.parity

import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
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
import ro.rdstr.car.EqPreset
import ro.rdstr.car.ListeningSeat
import ro.rdstr.car.Sound
import ro.rdstr.car.SoundSettings
import ro.rdstr.ui.kit.Backdrop
import ro.rdstr.ui.kit.KitText
import ro.rdstr.ui.kit.UiTheme
import ro.rdstr.ui.sound.SoundSection
import ro.rdstr.ui.sound.SoundView
import ro.rdstr.ui.uiKey
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Visual parity of the sound window (the browser scenes `sound-*` of capture-layout.mjs). Each scene lays the whole
 * window out at the screen's size, as the shell shows it, then keeps only the scene's element and its subtree on a
 * transparent screen, as the browser capture does: every other view invisible, the window's ground off, the Glassy
 * frost over a transparent backdrop (the browser's hidden ancestors leave nothing behind the glass either).
 */
class SoundParity {
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

    private val window = "music-app.settings-app.sound-app"
    private val rock = Sound.withPreset(SoundSettings(), EqPreset.ROCK)

    @Test fun nav() = looks { scene("sound-nav-$it", SoundSection.EQ, SoundSettings(), "$window/set-nav") }
    @Test fun curve() = looks { scene("sound-curve-$it", SoundSection.EQ, rock, "$window/set-detail/snd-eq/snd-curve") }
    @Test fun bands() = looks { scene("sound-bands-$it", SoundSection.EQ, rock, "$window/set-detail/snd-eq/snd-bands") }
    @Test fun balance() = looks { scene("sound-balance-$it", SoundSection.BALANCE, SoundSettings(), "$window/set-detail") }
    @Test fun field() = looks { scene("sound-field-$it", SoundSection.FIELD, SoundSettings(seat = ListeningSeat.FRONT_LEFT), "$window/set-detail/snd-stage") }
    @Test fun loudness() = looks { scene("sound-loudness-$it", SoundSection.LOUDNESS, SoundSettings(), "$window/set-detail/set-group:1") }
    @Test fun bass() = looks { scene("sound-bass-$it", SoundSection.BASS, SoundSettings(), "$window/set-detail/set-group:1") }
    @Test fun filters() = looks {
        val s = SoundSettings().let { it.copy(front = it.front.copy(highPass = true, highPassHz = Sound.HPF_HZ[3])) }
        scene("sound-filters-$it", SoundSection.FILTERS, s, "$window/set-detail/set-group:0/set-rows/snd-response")
    }

    private fun looks(block: (String) -> Unit) {
        for (glassy in listOf(false, true)) for (light in listOf(false, true)) {
            // speed 0: every transition and spring at its end, as ?freeze stops them in the browser
            UiTheme.set(light, glassy, geist = true, speed = 0f)
            block("${if (light) "light" else "dark"}-${if (glassy) "glassy" else "solid"}")
        }
    }

    private fun scene(scene: String, section: SoundSection, s: SoundSettings, root: String) {
        val web = File(WEB, "$scene.json")
        assumeTrue("no browser capture $web; run npm run parity:capture", web.isFile)
        val density = DPI / 160f
        // the frost over nothing: a transparent backdrop, as behind the browser's isolated element
        val empty = View(paparazzi.context).apply { layout(0, 0, SCREEN_W, SCREEN_H) }
        Backdrop.capture(empty, UiTheme.glass.glassyFrost)
        val view = SoundView(paparazzi.context)
        view.show(section, fade = false)
        view.bind(true, s)
        view.scrollbar = false
        view.measure(View.MeasureSpec.makeMeasureSpec(SCREEN_W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(SCREEN_H, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, SCREEN_W, SCREEN_H)
        val keys = LinkedHashMap<View, String>()
        keyAll(view, window, keys)
        val target = keys.entries.firstOrNull { it.value == root }?.key ?: error("$scene: no view keyed $root")
        isolate(target, view)
        Png.target = File(ANDROID, "$scene.png").apply { parentFile.mkdirs() }
        paparazzi.snapshot(view, scene)
        Backdrop.clear()
        val elements = keys.filter { (_, k) -> k == root || k.startsWith("$root/") }.map { (v, k) -> element(v, k, view, density) }.filterNotNull()
        File(ANDROID, "$scene.json").writeText(
            """{"scene":${q(scene)},"scale":$density,"canvas":{"w":${SCREEN_W / density},"h":${SCREEN_H / density}},"root":${q(root)},"elements":[""" +
                elements.joinToString(",\n") + "]}\n",
        )
    }

    /** Only [target] and its subtree drawn: the other views on the way up invisible, the window's ground off. */
    private fun isolate(target: View, top: View) {
        var v = target
        while (v !== top) {
            val p = v.parent as ViewGroup
            for (i in 0 until p.childCount) p.getChildAt(i).let { if (it !== v && it.visibility == View.VISIBLE) it.visibility = View.INVISIBLE }
            v = p
            // the ancestors' own grounds are not drawn either (the browser hides them with the rest)
            if (p !== top) { p.background = null; p.setWillNotDraw(true) }
        }
        (top as SoundView).ground = false
    }

    /** Every keyed view under [view] with its key, the way capture-layout.mjs keys the DOM. */
    private fun keyAll(view: View, key: String, out: MutableMap<View, String>) {
        out[view] = key
        val kids = keyedChildren(view)
        val counts = kids.groupingBy { it.uiKey!! }.eachCount()
        val index = mutableMapOf<String, Int>()
        for (kid in kids) {
            val s = kid.uiKey!!
            val i = index.merge(s, 1, Int::plus)!! - 1
            keyAll(kid, "$key/$s" + if (counts.getValue(s) > 1) ":$i" else "", out)
        }
    }

    private fun keyedChildren(view: View): List<View> =
        if (view !is ViewGroup) emptyList()
        else (0 until view.childCount).map(view::getChildAt).filter { it.visibility != View.GONE }
            .flatMap { if (it.uiKey != null) listOf(it) else keyedChildren(it) }

    private fun element(view: View, key: String, screen: View, density: Float): String? {
        var x = 0f
        var y = 0f
        var alpha = 1f
        var v: View = view
        while (true) {
            x += v.left + v.translationX - v.scrollX
            y += v.top + v.translationY - (if (v === view) 0 else v.scrollY)
            alpha *= v.alpha
            if (v === screen) break
            v = v.parent as View
        }
        if (view.visibility != View.VISIBLE || alpha <= 0f || view.width <= 0 || view.height <= 0) return null
        val text = (view as? TextView)?.text?.toString()?.takeIf { it.isNotBlank() }
        val color = (view as? TextView)?.currentTextColor
        val background = when (val b = view.background) {
            // a view that paints itself (a box, a curve) has no background colour to report; a plain container is transparent
            null -> if (view.willNotDraw()) 0 else null
            is ColorDrawable -> b.color
            else -> null
        }
        // the weight the text is drawn at: the variable font's wght axis
        val weight = (view as? KitText)?.style?.weight ?: (view as? TextView)?.typeface?.weight
        return buildString {
            append("""{"key":${q(key)},"tag":${q(view.javaClass.simpleName)},""")
            append(""""box":{"x":${x / density},"y":${y / density},"w":${view.width / density},"h":${view.height / density}},""")
            append(""""style":{"background":${hex(background)},"color":${hex(color)},""")
            if (view is TextView) append(""""fontSize":${view.textSize / density},"fontWeight":$weight,""")
            append(""""opacity":${view.alpha}},"text":${text?.let(::q) ?: "null"}}""")
        }
    }

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
        val WEB = File(System.getProperty("rdstr.parity.web") ?: "../build/parity/web")
        val ANDROID = File(System.getProperty("rdstr.parity.android") ?: "../build/parity/android")
    }
}

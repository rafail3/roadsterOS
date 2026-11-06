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
import ro.rdstr.car.App
import ro.rdstr.car.RulesState
import ro.rdstr.car.ShellState
import ro.rdstr.ui.kit.UiTheme
import ro.rdstr.ui.settings.About
import ro.rdstr.ui.settings.RestartPromptView
import ro.rdstr.ui.settings.SettingsState
import ro.rdstr.ui.settings.SettingsView
import ro.rdstr.ui.settings.Update
import ro.rdstr.ui.uiKey
import java.awt.image.BufferedImage
import java.io.File
import java.util.Calendar
import java.util.TimeZone
import javax.imageio.ImageIO

/**
 * Visual parity of the settings app (SettingsApp.tsx) and the restart question (RestartPrompt.tsx), in the browser
 * capture's state (`?bare&noboot&fresh&freeze&app=settings`, the sample phone and network connected, noon of
 * 31 October 2026): every section, Software Update before and after a check, and the prompt, in the four looks.
 * The Wi-Fi networks and the unit's picture are other screens' views and are not in these scenes.
 */
class SettingsParity {
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
    private val zone = "Europe/Bucharest"
    private val noon = Calendar.getInstance(TimeZone.getTimeZone(zone)).apply { clear(); set(2026, Calendar.OCTOBER, 31, 12, 0) }.timeInMillis

    // the browser's sample state (store.ts fromUrl)
    private val rules = RulesState(btConnected = true, hfpConnected = true, playing = true)
    private val state = SettingsState(
        wifiOn = true, locationOn = true, wifiConnected = "Phone hotspot", btOn = true, device = "Pixel 8", savedDevices = 3,
        now = noon, timeZone = zone, packs = listOf("RO"),
        about = About(version = File("../version.txt").readText().trim(), appMemory = "27 MB", freeMemory = "214 MB", totalMemory = "1003 MB",
            freeStorage = "8.6 GB", totalStorage = "11.9 GB"),
        update = Update(installedAt = noon),
        adbAddress = "192.168.43.120:5555",
    )

    private var light = false
    private var glassy = false
    private fun page(id: String) = ShellState(app = App.SETTINGS, settingsPage = id,
        theme = if (light) ro.rdstr.car.ThemeMode.LIGHT else ro.rdstr.car.ThemeMode.DARK, design = if (glassy) ro.rdstr.car.Design.GLASSY else ro.rdstr.car.Design.SOLID)

    @Test
    fun sections() {
        for (id in listOf("wifi", "bluetooth", "location", "display", "datetime", "sound", "phone", "dash", "camera", "home", "maps", "about")) {
            looks { name -> settings("settings-$id-$name", page(id), state) }
        }
        looks { name -> settings("settings-developer-$name", page("developer").copy(developer = true), state) }
    }

    @Test
    fun softwareUpdate() {
        looks { name -> settings("settings-update-$name", page("about"), state) { it.press("roadsterOS") } }
        looks { name ->
            settings("settings-update-checked-$name", page("about"), state.copy(update = state.update.copy(check = Update.Check.LATEST, checkedAt = noon))) { it.press("roadsterOS") }
        }
    }

    @Test
    fun restartPrompt() = looks { name ->
        scene("restart-$name", "alert:1") { RestartPromptView(ctx).apply { open = true } }
    }

    private fun settings(scene: String, shell: ShellState, s: SettingsState, after: (View) -> Unit = {}) =
        scene(scene, "music-app.settings-app") {
            SettingsView(ctx, Slots).apply { open = true; bind(shell, rules, s); after(this) }
        }

    /** Taps the row labelled [label] (the first one), as a click in the browser scene. */
    private fun View.press(label: String) {
        fun find(v: View): View? {
            if (v is TextView && v.text.toString() == label) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
            return null
        }
        (find(this)!!.parent as View).performClick()
    }

    private fun looks(block: (name: String) -> Unit) {
        for (g in listOf(false, true)) for (l in listOf(false, true)) {
            light = l; glassy = g
            UiTheme.set(light, glassy, geist = true, speed = 0f)
            block("${if (light) "light" else "dark"}-${if (glassy) "glassy" else "solid"}")
        }
    }

    /** [build]'s view over the whole screen; the elements written are the subtree keyed [root], keyed from the screen. */
    private fun scene(scene: String, root: String, build: () -> View) {
        val web = File(WEB, "$scene.json")
        assumeTrue("no browser capture $web; run npm run parity:capture", web.isFile)
        val k = DPI / 160f
        val screen = FrameLayout(ctx)
        val view = build()
        screen.addView(view, FrameLayout.LayoutParams(W, H))
        Png.target = File(ANDROID, "$scene.png").apply { parentFile.mkdirs() }
        paparazzi.snapshot(screen, scene)
        val out = mutableListOf<String>()
        collect(view, root, screen, k, out)
        File(ANDROID, "$scene.json").writeText(
            """{"scene":${q(scene)},"scale":$k,"canvas":{"w":${W / k},"h":${H / k}},"root":${q(root)},"elements":[""" + out.joinToString(",\n") + "]}\n",
        )
    }

    private fun collect(view: View, key: String, screen: View, k: Float, out: MutableList<String>) {
        var x = 0f; var y = 0f; var alpha = 1f
        var v: View = view
        while (v !== screen) { x += v.left + v.translationX - v.scrollX; y += v.top + v.translationY - (v.parent as View).scrollY; alpha *= v.alpha; v = v.parent as View }
        // as the browser: only what is on the screen
        val inside = x < W && x + view.width > 0 && y < H && y + view.height > 0
        if (view.visibility == View.VISIBLE && alpha > 0f && view.width > 0 && view.height > 0 && inside) {
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

    private companion object {
        const val W = 1024
        const val H = 600
        const val DPI = 128
        val WEB = File(System.getProperty("rdstr.parity.web") ?: "../build/parity/web")
        val ANDROID = File(System.getProperty("rdstr.parity.android") ?: "../build/parity/android")
    }
}

/** The unit picture as the display app places it in About (HostSlots); the Wi-Fi list has its own scenes. */
private object Slots : ro.rdstr.ui.settings.SettingsSlots {
    override fun wifiNetworks(context: android.content.Context): android.view.View = android.view.View(context)
    override fun unitPicture(context: android.content.Context, width: Float): android.view.View = ro.rdstr.ui.setup.UnitPicture(context, width)
}

package ro.rdstr.os

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Bundle
import android.view.View
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import ro.rdstr.car.Setup
import ro.rdstr.car.ShellAction
import ro.rdstr.os.boot.BootView
import ro.rdstr.os.screens.Screen
import ro.rdstr.os.screens.ShellScreen
import ro.rdstr.ui.Mirrors
import ro.rdstr.os.screens.Screens
import ro.rdstr.ui.kit.UiDensity
import ro.rdstr.ui.kit.UiTheme
import ro.rdstr.ui.shell.Layer
import ro.rdstr.ui.shell.ShellView
import java.util.Calendar

/** The only HOME screen: the shell (Screen.tsx's root, its layers and its startup screens). */
@Mirrors("Screen.tsx")
class HomeActivity : Activity() {

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var bound: Job? = null
    private lateinit var shell: ShellView
    private val screens = ArrayList<Screen>()

    /** Our density: the 1280 px canvas across the panel, 1 canvas px = 1 dp. */
    override fun attachBaseContext(base: Context) = super.attachBaseContext(UiDensity.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // the screens read the runtime as they are made; the service starts it too, later, on this same thread
        CarRuntime.start(applicationContext)
        CarRuntime.ensureService(this)
        val root = ShellScreen(this)
        screens += root
        shell = root.view
        setContentView(shell)
        UiTheme.fadeRoot = shell
        for ((layer, make) in Screens.ALL) make(this).also { screens += it; shell.put(layer, it.view) }
        startup()
    }

    /**
     * A cold start shows the boot screen; a first start (setup at the logo) then moves on to the setup's start screen,
     * where a touch begins the setup. The other setup screens come with the shell's views.
     */
    private fun startup() {
        val shell = CarRuntime.shell.value
        when {
            !booted || (!shell.setupDone && shell.setupStep == Setup.Step.LOGO) -> overlay(BootView(this, BootView.Mode.BOOT) {
                booted = true
                val now = CarRuntime.shell.value
                if (!now.setupDone && now.setupStep == Setup.Step.LOGO) CarRuntime.shellAction(ShellAction.SetupNext())
                startup()
            })
            !shell.setupDone && shell.setupStep == Setup.Step.START -> overlay(BootView(this, BootView.Mode.START, versionName()) {
                CarRuntime.shellAction(ShellAction.SetupNext())
                startup()
            })
            else -> overlay(null)
        }
    }

    private var shown: View? = null

    /** One startup screen over the shell at a time, or none. */
    private fun overlay(view: View?) {
        shown?.let(shell::remove)
        shown = view
        if (view != null) shell.put(Layer.STARTUP, view)
    }

    private fun versionName() = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty()

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        lockIfOwner()
        bound = uiScope.launch {
            // the look every view draws with: the shell's theme (the auto one decided at the hour), design, font, speed
            launch { combine(CarRuntime.shell, CarRuntime.time.now()) { s, t -> s to hourOf(t) }.collect { (s, h) -> UiTheme.follow(s, h) } }
            for (s in screens) s.bind(this)
        }
    }

    // a keyboard's Escape closes the drawer and the camera, as on the emulator's screen
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.keyCode == android.view.KeyEvent.KEYCODE_ESCAPE && event.action == android.view.KeyEvent.ACTION_UP) {
            CarRuntime.shellAction(ShellAction.CloseDrawer)
            CarRuntime.shellAction(ShellAction.CloseCamera)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        CarRuntime.userActivity()
    }

    override fun onPause() {
        bound?.cancel()
        super.onPause()
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    private companion object {
        /** The boot screen shows once per start of the app's process, as the unit starts. */
        var booted = false

        fun hourOf(ms: Long) = Calendar.getInstance().apply { timeInMillis = ms }.get(Calendar.HOUR_OF_DAY)
    }

    @Deprecated("Back is disabled on the home screen")
    override fun onBackPressed() = Unit

    /** API 27 target: the WindowInsetsController replacement needs API 30. */
    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    private fun lockIfOwner() {
        val dpm = getSystemService(DevicePolicyManager::class.java)
        if (dpm.isDeviceOwnerApp(packageName) && dpm.isLockTaskPermitted(packageName)) startLockTask()
    }
}

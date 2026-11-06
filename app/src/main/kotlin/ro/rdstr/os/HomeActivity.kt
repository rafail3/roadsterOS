package ro.rdstr.os

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.os.Bundle
import android.view.View
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class HomeActivity : Activity() {

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var devJob: Job? = null
    // Show Frame Rate: the main thread's frames a second, to the log while the screens are drawn by the new shell
    private val frames = ro.rdstr.os.settings.FrameMeter { android.util.Log.i("rdstr.dev", "fps=$it") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        CarRuntime.ensureService(this)
        addContentView(castView(), android.widget.FrameLayout.LayoutParams(-1, -1))
    }

    private lateinit var castFrame: android.widget.FrameLayout

    /**
     * The phone's screen over everything while it plays: black around a 16:9 picture (the stream is 1280x720),
     * its surface handed to the receiver. ponytail: test screen only; the shell's phone screen view replaces it.
     */
    private fun castView(): View {
        val picture = android.view.SurfaceView(this)
        picture.holder.addCallback(object : android.view.SurfaceHolder.Callback {
            override fun surfaceCreated(holder: android.view.SurfaceHolder) = CarRuntime.castSurface(holder.surface)
            override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, width: Int, height: Int) = Unit
            override fun surfaceDestroyed(holder: android.view.SurfaceHolder) = CarRuntime.castSurface(null)
        })
        val width = resources.displayMetrics.widthPixels
        castFrame = android.widget.FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            visibility = View.GONE
            addView(picture, android.widget.FrameLayout.LayoutParams(width, width * 9 / 16, android.view.Gravity.CENTER))
        }
        return castFrame
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        lockIfOwner()
        val status = findViewById<TextView>(R.id.status)
        job = uiScope.launch {
            CarRuntime.ui.collect { ui ->
                castFrame.visibility = if (ui.state.cast == ro.rdstr.car.CarEvent.PhoneCast.State.LIVE) View.VISIBLE else View.GONE
                status.text = buildString {
                    appendLine("source: ${ui.state.source}  playing: ${ui.state.playing}  muted: ${ui.state.muted}")
                    appendLine("bt: ${ui.state.btConnected}  call: ${ui.state.call}  night: ${ui.night}")
                    appendLine("camera: ${ui.camera}  overlay call: ${ui.call?.number ?: "-"}")
                    appendLine("track: ${ui.track?.title ?: "-"}  cover: ${CarRuntime.cover.value?.from ?: "-"}")
                    append("last: ${ui.lastEvent}")
                }
            }
        }
        devJob = uiScope.launch {
            CarRuntime.shell.collect { if (it.showFrameRate) frames.start() else frames.stop() }
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        CarRuntime.userActivity()
    }

    override fun onPause() {
        job?.cancel()
        devJob?.cancel()
        frames.stop()
        super.onPause()
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
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

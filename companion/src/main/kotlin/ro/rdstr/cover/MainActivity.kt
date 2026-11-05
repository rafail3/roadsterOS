package ro.rdstr.cover

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.widget.ImageView
import android.widget.TextView

/** One screen that follows the app live: what plays, whether its cover reached the car, and what setup is missing. */
class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private lateinit var cover: ImageView
    private lateinit var state: TextView
    private val bluetoothManager by lazy { getSystemService(BluetoothManager::class.java) }
    private var a2dp: BluetoothA2dp? = null
    private var coverShownAt = -1L

    private val changed = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> render() }
    private val bluetoothChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = render()
    }

    // keeps "5 s ago" current while the screen is visible
    private val tick = object : Runnable {
        override fun run() {
            renderDelivery()
            main.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main)
        prefs = Status.prefs(this)
        cover = findViewById(R.id.cover)
        state = findViewById(R.id.state)
        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        findViewById<View>(R.id.allow_media).setOnClickListener { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        findViewById<View>(R.id.allow_bluetooth).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
        }
    }

    override fun onStart() {
        super.onStart()
        prefs.registerOnSharedPreferenceChangeListener(changed)
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED).apply { addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) registerReceiver(bluetoothChanged, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(bluetoothChanged, filter)
        bluetoothManager?.adapter?.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) { a2dp = proxy as BluetoothA2dp; renderBluetooth() }
            override fun onServiceDisconnected(profile: Int) { a2dp = null }
        }, BluetoothProfile.A2DP)
        render()
        main.post(tick)
    }

    override fun onResume() {
        super.onResume()
        renderSetup() // the user may come back from the access settings
    }

    override fun onStop() {
        super.onStop()
        prefs.unregisterOnSharedPreferenceChangeListener(changed)
        unregisterReceiver(bluetoothChanged)
        a2dp?.let { bluetoothManager?.adapter?.closeProfileProxy(BluetoothProfile.A2DP, it) }
        a2dp = null
        main.removeCallbacks(tick)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) = render()

    private fun render() {
        // the listener is stopped while Bluetooth is off, so what it saved last is stale
        if (bluetoothManager?.adapter?.isEnabled != true && prefs.contains(Status.TITLE)) return Status.clear(this)
        renderSetup()
        renderTrack()
        renderCover()
        renderDelivery()
        renderBluetooth()
    }

    private fun mediaAccess() = Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()
        .contains(ComponentName(this, CoverListener::class.java).flattenToString())

    private fun bluetoothAllowed() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun renderSetup() {
        val media = mediaAccess()
        val bluetooth = bluetoothAllowed()
        findViewById<View>(R.id.setup).visibility = if (media && bluetooth) View.GONE else View.VISIBLE
        findViewById<View>(R.id.setup_media).visibility = if (media) View.GONE else View.VISIBLE
        findViewById<View>(R.id.setup_bluetooth).visibility = if (bluetooth) View.GONE else View.VISIBLE
    }

    private fun renderTrack() {
        val title = prefs.getString(Status.TITLE, null)
        findViewById<TextView>(R.id.title).text = title ?: getString(R.string.nothing_playing)
        findViewById<TextView>(R.id.artist).text = when {
            title == null -> getString(R.string.nothing_playing_hint)
            else -> prefs.getString(Status.ARTIST, null)?.takeIf { it.isNotBlank() } ?: getString(R.string.unknown_artist)
        }
        val source = findViewById<TextView>(R.id.source)
        val app = prefs.getString(Status.APP, null)
        source.visibility = if (title != null && app != null) View.VISIBLE else View.GONE
        if (app != null) {
            val name = appName(app)
            source.text = getString(if (prefs.getBoolean(Status.PLAYING, false)) R.string.from_app_playing else R.string.from_app_paused, name)
        }
        // a paused song stays on screen, faded
        cover.alpha = if (title == null || prefs.getBoolean(Status.PLAYING, false)) 1f else 0.45f
    }

    private fun appName(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    // decoded off the main thread, and only when a new cover was saved
    private fun renderCover() {
        val at = prefs.getLong(Status.COVER_AT, 0L)
        if (at == coverShownAt) return
        coverShownAt = at
        val file = Status.coverFile(this)
        if (!file.exists()) return showEmptyCover()
        Thread {
            val bitmap = BitmapFactory.decodeFile(file.path)
            main.post {
                if (isDestroyed) return@post
                if (bitmap == null) showEmptyCover()
                else {
                    cover.scaleType = ImageView.ScaleType.CENTER_CROP
                    cover.setImageBitmap(bitmap)
                }
            }
        }.start()
    }

    private fun showEmptyCover() {
        cover.scaleType = ImageView.ScaleType.CENTER
        cover.setImageResource(R.drawable.ic_note)
    }

    private fun renderDelivery() {
        val device = prefs.getString(Status.DEVICE, null) ?: ""
        val delivery = Status.delivery(prefs)
        val (text, color) = when (delivery) {
            null -> getString(R.string.state_idle) to R.color.ink_subtle
            Status.Delivery.SENDING -> getString(R.string.state_sending, device) to R.color.accent
            Status.Delivery.SENT -> getString(R.string.state_sent, device, ago(prefs.getLong(Status.AT, 0L))) to R.color.accent
            Status.Delivery.NO_DEVICE -> getString(R.string.state_no_device) to R.color.ink_subtle
            Status.Delivery.NO_DISPLAY -> getString(R.string.state_no_display, device) to R.color.danger
            Status.Delivery.NEED_PERMISSION -> getString(R.string.state_permission) to R.color.danger
            Status.Delivery.PAUSED -> getString(R.string.state_paused) to R.color.ink_subtle
        }
        state.text = text
        findViewById<View>(R.id.state_dot).backgroundTintList = getColorStateList(color)
    }

    private fun ago(at: Long): String {
        val seconds = ((System.currentTimeMillis() - at) / 1000).coerceAtLeast(0)
        return when {
            seconds < 5 -> getString(R.string.just_now)
            seconds < 60 -> getString(R.string.seconds_ago, seconds.toInt())
            else -> getString(R.string.minutes_ago, (seconds / 60).toInt())
        }
    }

    @SuppressLint("MissingPermission") // device names are read only with the permission granted
    private fun renderBluetooth() {
        val adapter = bluetoothManager?.adapter
        val text = when {
            adapter?.isEnabled != true -> getString(R.string.bluetooth_off)
            !bluetoothAllowed() -> getString(R.string.bluetooth_alone).substringBefore(" ·")
            else -> a2dp?.connectedDevices.orEmpty().map { it.name ?: it.address }
                .takeIf { it.isNotEmpty() }?.let { getString(R.string.bluetooth_with, it.joinToString()) }
                ?: getString(R.string.bluetooth_alone)
        }
        findViewById<TextView>(R.id.bluetooth).text = text
    }
}

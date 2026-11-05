package ro.rdstr.cover

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService

/**
 * Follows what the phone plays, through the media sessions of its music apps, and sends each new cover to the car.
 * Android lets an app read other apps' sessions once it has notification access; no notification is read.
 * Runs only while Bluetooth is on: it lets the system unbind it when Bluetooth turns off, and [BluetoothWake]
 * brings it back when a device connects.
 */
class CoverListener : NotificationListenerService() {
    private lateinit var sender: CoverSender
    private val main = Handler(Looper.getMainLooper())
    private val callbacks = HashMap<MediaController, MediaController.Callback>()
    private var lastKey: String? = null
    private var lastTrack: String? = null
    private var lastApp: String? = null
    private var running = false

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { watch(it.orEmpty()) }

    // a car that connects gets the current cover after it had a moment to open its channel
    // Bluetooth turning off stops the service until the next connection
    private val bluetoothChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_TURNING_OFF) stop()
            } else if (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) == BluetoothProfile.STATE_CONNECTED) {
                main.postDelayed({ sender.resend() }, CAR_READY_MS)
            }
        }
    }

    override fun onListenerConnected() {
        if (getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled != true) return stop()
        running = true
        sender = CoverSender(this)
        val sessions = getSystemService(MediaSessionManager::class.java)
        val me = ComponentName(this, CoverListener::class.java)
        sessions.addOnActiveSessionsChangedListener(sessionsChanged, me, main)
        watch(sessions.getActiveSessions(me))
        val filter = IntentFilter(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED).apply { addAction(BluetoothAdapter.ACTION_STATE_CHANGED) }
        // system broadcasts; from Android 13 a registered receiver says whether other apps may reach it
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) registerReceiver(bluetoothChanged, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(bluetoothChanged, filter)
    }

    private fun stop() {
        Status.delivery(this, Status.Delivery.PAUSED)
        forget()
        requestUnbind()
    }

    override fun onListenerDisconnected() {
        if (!running) return
        running = false
        getSystemService(MediaSessionManager::class.java).removeOnActiveSessionsChangedListener(sessionsChanged)
        runCatching { unregisterReceiver(bluetoothChanged) }
        callbacks.forEach { (controller, callback) -> controller.unregisterCallback(callback) }
        callbacks.clear()
        sender.close()
    }

    private fun watch(controllers: List<MediaController>) {
        callbacks.forEach { (controller, callback) -> controller.unregisterCallback(callback) }
        callbacks.clear()
        for (controller in controllers) {
            val callback = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) { if (isPlaying(controller)) offer(controller, metadata) }
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    when {
                        isPlaying(controller) -> offer(controller, controller.metadata)
                        controller.packageName != lastApp -> Unit
                        state == null || state.state == PlaybackState.STATE_STOPPED || state.state == PlaybackState.STATE_NONE -> forget()
                        else -> Status.playing(this@CoverListener, false)
                    }
                }

                override fun onSessionDestroyed() { if (controller.packageName == lastApp) forget() }
            }
            controller.registerCallback(callback, main)
            callbacks[controller] = callback
        }
        controllers.firstOrNull(::isPlaying)?.let { offer(it, it.metadata) }
        // the app that was playing closed
        if (lastApp != null && controllers.none { it.packageName == lastApp }) forget()
    }

    private fun forget() {
        lastKey = null
        lastTrack = null
        lastApp = null
        Status.clear(this)
    }

    private fun isPlaying(controller: MediaController) = controller.playbackState?.state == PlaybackState.STATE_PLAYING

    /** Sends the cover when the track or its artwork changed; music apps often set the artwork a moment late. */
    private fun offer(controller: MediaController, metadata: MediaMetadata?) {
        metadata ?: return
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: return
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE).orEmpty()
        if ("$title|$artist" != lastTrack || controller.packageName != lastApp) {
            lastTrack = "$title|$artist"
            lastApp = controller.packageName
            Status.track(this, title, artist, controller.packageName)
        } else {
            Status.playing(this, true)
        }
        val art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            ?: return
        val key = "$title|$artist|${signature(art)}"
        if (key == lastKey) return
        lastKey = key
        sender.send(title, artist, art)
    }

    /** A cheap fingerprint of the artwork: its size and a grid of 16 pixels, enough to tell a new image from a repeat. */
    private fun signature(art: Bitmap): String {
        var sum = 0L
        for (i in 1..4) for (j in 1..4) sum = sum * 31 + art.getPixel(art.width * i / 5, art.height * j / 5)
        return "${art.width}x${art.height}:$sum"
    }

    private companion object {
        const val CAR_READY_MS = 3000L
    }
}

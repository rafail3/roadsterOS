package ro.rdstr.cover

import android.bluetooth.BluetoothA2dp
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
 */
class CoverListener : NotificationListenerService() {
    private lateinit var sender: CoverSender
    private val main = Handler(Looper.getMainLooper())
    private val callbacks = HashMap<MediaController, MediaController.Callback>()
    private var lastKey: String? = null

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { watch(it.orEmpty()) }

    // a car that connects gets the current cover after it had a moment to open its channel
    private val carConnected = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) == BluetoothProfile.STATE_CONNECTED) main.postDelayed({ sender.resend() }, CAR_READY_MS)
        }
    }

    override fun onListenerConnected() {
        sender = CoverSender(this)
        val sessions = getSystemService(MediaSessionManager::class.java)
        val me = ComponentName(this, CoverListener::class.java)
        sessions.addOnActiveSessionsChangedListener(sessionsChanged, me, main)
        watch(sessions.getActiveSessions(me))
        val filter = IntentFilter(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
        // a system broadcast; from Android 13 a registered receiver says whether other apps may reach it
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) registerReceiver(carConnected, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(carConnected, filter)
    }

    override fun onListenerDisconnected() {
        getSystemService(MediaSessionManager::class.java).removeOnActiveSessionsChangedListener(sessionsChanged)
        runCatching { unregisterReceiver(carConnected) }
        callbacks.forEach { (controller, callback) -> controller.unregisterCallback(callback) }
        callbacks.clear()
        sender.close()
    }

    private fun watch(controllers: List<MediaController>) {
        callbacks.forEach { (controller, callback) -> controller.unregisterCallback(callback) }
        callbacks.clear()
        for (controller in controllers) {
            val callback = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) { if (isPlaying(controller)) offer(metadata) }
                override fun onPlaybackStateChanged(state: PlaybackState?) { if (isPlaying(controller)) offer(controller.metadata) }
            }
            controller.registerCallback(callback, main)
            callbacks[controller] = callback
        }
        controllers.firstOrNull(::isPlaying)?.let { offer(it.metadata) }
    }

    private fun isPlaying(controller: MediaController) = controller.playbackState?.state == PlaybackState.STATE_PLAYING

    /** Sends the cover when the track or its artwork changed; music apps often set the artwork a moment late. */
    private fun offer(metadata: MediaMetadata?) {
        metadata ?: return
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: return
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE).orEmpty()
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

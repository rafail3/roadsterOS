package ro.rdstr.os.cover

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ro.rdstr.car.CarEvent
import ro.rdstr.car.CoverFetch
import ro.rdstr.car.CoverFrame
import ro.rdstr.car.TrackMatch
import java.io.File

/** The cover of the playing track and where it came from; [at] changes when the same file got a better image. */
data class Cover(val title: String, val artist: String, val file: File, val from: From, val at: Long) {
    enum class From { PHONE, DISK, CATALOG }
}

/**
 * Finds the cover of the playing Bluetooth track, best source first: the phone app over the cover channel, a cover
 * kept on disk, then, when no phone cover arrived shortly after the track and the unit is online, the 1000 px cover
 * from the online catalogs. Every cover is kept on disk, so the music screen shows it at once whenever it opens.
 * All state is touched on [serial] only.
 */
class CoverDesk(
    private val cache: CoverCache,
    private val online: () -> Boolean,
    private val scope: CoroutineScope,
    private val serial: CoroutineDispatcher,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val fetch: (String, String) -> ByteArray? = CoverFetch::large,
) {
    private val _cover = MutableStateFlow<Cover?>(null)
    val cover: StateFlow<Cover?> = _cover

    private var current: CarEvent.Track? = null
    private var lookup: Job? = null

    fun follow(tracks: Flow<CarEvent.Track?>) = scope.launch(serial) { tracks.collect(::onTrack) }

    private fun onTrack(track: CarEvent.Track?) {
        if (track == current) return
        current = track
        lookup?.cancel()
        if (track == null) { _cover.value = null; return }
        val kept = cache.find(track.title, track.artist)
        if (kept != null) return show(track, kept, Cover.From.DISK)
        _cover.value = null // the previous track's cover must not stay up
        lookup = scope.launch(serial) {
            delay(PHONE_GRACE_MS)
            fromCatalog(track)
        }
    }

    fun fromPhone(frame: CoverFrame) {
        scope.launch(serial) {
            val file = cache.put(frame.title, frame.artist, frame.jpeg)
            val track = current ?: return@launch
            if (!TrackMatch.same(track.title, track.artist, frame.title, frame.artist)) return@launch
            lookup?.cancel()
            show(track, file, Cover.From.PHONE)
        }
    }

    /** The unit just went online: look up the playing track if it still has no cover. */
    fun networkAvailable() {
        scope.launch(serial) {
            val track = current ?: return@launch
            if (_cover.value != null || lookup?.isActive == true) return@launch
            lookup = scope.launch(serial) { fromCatalog(track) }
        }
    }

    private suspend fun fromCatalog(track: CarEvent.Track) {
        if (!online()) return // networkAvailable() tries again
        val jpeg = withContext(io) { fetch(track.title, track.artist) } ?: return
        val file = cache.put(track.title, track.artist, jpeg)
        if (track == current && _cover.value == null) show(track, file, Cover.From.CATALOG)
    }

    private fun show(track: CarEvent.Track, file: File, from: Cover.From) {
        _cover.value = Cover(track.title, track.artist, file, from, System.nanoTime())
    }

    companion object {
        /** The phone app sends within a second of the track change; after this the unit looks the cover up itself. */
        const val PHONE_GRACE_MS = 1500L

        /** The desk wired to the unit: cover channel, Bluetooth state, network state, disk in the app's files. */
        fun create(context: Context, scope: CoroutineScope): CoverDesk {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            @Suppress("DEPRECATION") // the synchronous check on API 27; the callback below covers changes
            val online = { connectivity?.activeNetworkInfo?.isConnected == true }
            val desk = CoverDesk(CoverCache(File(context.filesDir, "covers")), online, scope, Dispatchers.Default.limitedParallelism(1))
            val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
            val server = CoverServer(adapter, desk::fromPhone)
            server.start(scope)
            context.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_ON) server.bluetoothTurnedOn()
                }
            }, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
            connectivity?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = desk.networkAvailable()
            })
            return desk
        }
    }
}

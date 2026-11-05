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
import ro.rdstr.car.CoverIndex
import ro.rdstr.car.TrackMatch
import ro.rdstr.os.R
import java.io.File

/**
 * The cover of the playing track and where it came from. A track with no cover anywhere gets one of the stock
 * covers, [stock], and no [file]. [at] changes when the same file got a better image.
 */
data class Cover(val title: String, val artist: String, val file: File?, val from: From, val stock: Int, val at: Long) {
    enum class From { PHONE, DISK, CATALOG, STOCK }
}

/**
 * Finds the cover of the playing Bluetooth track. The disk comes first: a track heard before shows its kept cover
 * at once. Otherwise the phone app's cover over the cover channel; when none arrived shortly after the track and the
 * unit is online, the 1000 px cover from the online catalogs; failing all, a stock cover. Every real cover is kept
 * on disk, one per track, a small one upgraded when a larger arrives. All state is touched on [serial] only.
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
        if (kept != null) {
            show(track, kept, Cover.From.DISK)
            // a small kept cover is upgraded from the catalogs, unless the phone sends a larger one first
            if (cache.small(kept)) lookup = scope.launch(serial) { delay(PHONE_GRACE_MS); fromCatalog(track) }
            return
        }
        _cover.value = null // the previous track's cover must not stay up
        lookup = scope.launch(serial) {
            delay(PHONE_GRACE_MS)
            fromCatalog(track)
            if (!hasReal() && track == current) showStock(track)
        }
    }

    fun fromPhone(frame: CoverFrame) {
        scope.launch(serial) {
            val stored = cache.put(frame.title, frame.artist, frame.jpeg)
            val track = current ?: return@launch
            if (!TrackMatch.same(track.title, track.artist, frame.title, frame.artist)) return@launch
            if (!cache.small(stored.file)) lookup?.cancel()
            if (stored.written || !hasReal()) show(track, stored.file, Cover.From.PHONE)
        }
    }

    /** The unit just went online: look up the playing track if it still has no cover, or only a small one. */
    fun networkAvailable() {
        scope.launch(serial) {
            val track = current ?: return@launch
            if (lookup?.isActive == true) return@launch
            val file = _cover.value?.file
            if (file != null && !cache.small(file)) return@launch
            lookup = scope.launch(serial) { fromCatalog(track) }
        }
    }

    private suspend fun fromCatalog(track: CarEvent.Track) {
        if (!online()) return // networkAvailable() tries again
        val jpeg = withContext(io) { fetch(track.title, track.artist) } ?: return
        val stored = cache.put(track.title, track.artist, jpeg)
        if (track == current && (stored.written || !hasReal())) show(track, stored.file, Cover.From.CATALOG)
    }

    private fun hasReal() = _cover.value?.file != null

    private fun show(track: CarEvent.Track, file: File, from: Cover.From) {
        _cover.value = Cover(track.title, track.artist, file, from, -1, System.nanoTime())
    }

    private fun showStock(track: CarEvent.Track) {
        val stock = CoverIndex.stockCover(track.title, track.artist, STOCK.size)
        _cover.value = Cover(track.title, track.artist, null, Cover.From.STOCK, stock, System.nanoTime())
    }

    companion object {
        /** The phone app sends within a second of the track change; after this the unit looks the cover up itself. */
        const val PHONE_GRACE_MS = 1500L

        /** The stock covers, the same set as the emulator's; [Cover.stock] indexes it. */
        val STOCK = intArrayOf(R.drawable.cover_stock_0, R.drawable.cover_stock_1, R.drawable.cover_stock_2)

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

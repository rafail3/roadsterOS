package ro.rdstr.os.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import ro.rdstr.car.CoverColors
import ro.rdstr.os.CarRuntime
import java.io.File

/**
 * The playing track's cover for the screens: decoded once per file (at most 640 px), with its two edge colours
 * (core-car CoverColors on a 48 x 48 copy, as media.tsx `useCoverColors` samples it).
 */
object Covers {
    class Decoded(val bitmap: Bitmap, val top: Int, val bottom: Int)

    private var file: File? = null
    private var decoded: Decoded? = null

    val flow: Flow<Decoded?> = CarRuntime.cover.map { it?.file }.distinctUntilChanged().map { f -> f?.let(::decode) }.flowOn(Dispatchers.IO)

    @Synchronized
    private fun decode(f: File): Decoded? {
        if (f == file) return decoded
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_PX) sample *= 2
        val bitmap = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val small = Bitmap.createScaledBitmap(bitmap, SAMPLE, SAMPLE, true)
        val px = IntArray(SAMPLE * SAMPLE)
        small.getPixels(px, 0, SAMPLE, 0, 0, SAMPLE, SAMPLE)
        if (small !== bitmap) small.recycle()
        val (top, bottom) = CoverColors.from(px, SAMPLE)
        file = f
        decoded = Decoded(bitmap, top, bottom)
        return decoded
    }

    private const val MAX_PX = 640
    private const val SAMPLE = 48
}

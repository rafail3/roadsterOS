package ro.rdstr.carvendor

import android.util.Log
import ro.rdstr.car.DspWrite

/**
 * The sound processor, through the calls MainUI makes on com.yyw.ts70xhw.Iop and FtSet (libts50xhw.so). The library
 * runs in the vendor's process, so for now each call is logged; it goes through once the unit shows which way in works.
 */
class VendorSound {
    fun write(w: DspWrite) {
        Log.i(TAG, "${w.call}(${w.args.joinToString()})")
    }

    private companion object { const val TAG = "rdstr.sound" }
}

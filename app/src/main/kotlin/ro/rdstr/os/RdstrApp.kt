package ro.rdstr.os

import android.app.Application

class RdstrApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CarRuntime.ensureService(this)
    }
}

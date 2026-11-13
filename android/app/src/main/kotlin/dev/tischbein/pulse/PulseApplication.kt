package dev.tischbein.pulse

import android.app.Application

class PulseApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.onAppStart(appStartEpochMs = System.currentTimeMillis())
    }
}

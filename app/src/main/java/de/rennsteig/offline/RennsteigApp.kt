package de.rennsteig.offline

import android.app.Application
import org.maplibre.android.MapLibre

class RennsteigApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        TrackStore.init(this)
    }
}

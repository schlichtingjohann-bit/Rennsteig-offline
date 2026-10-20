package de.rennsteig.offline

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock

/**
 * Standort über GPS (funktioniert ohne Internet). Netzwerkortung wird nur genutzt,
 * solange kein frischer GPS-Fix vorliegt.
 */
class LocationTracker(
    private val context: Context,
    private val listener: (Location) -> Unit,
) : LocationListener {
    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var lastGpsFix = 0L
    private var running = false

    val gpsEnabled: Boolean
        get() = try {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        } catch (e: Exception) {
            false
        }

    @SuppressLint("MissingPermission")
    fun start(intervalMs: Long = 1000L): Boolean {
        if (running) return true
        if (!hasPermission(context)) return false
        val providers = manager.allProviders
        try {
            if (LocationManager.GPS_PROVIDER in providers) {
                manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, intervalMs, 0f, this, Looper.getMainLooper())
            }
            if (LocationManager.NETWORK_PROVIDER in providers) {
                manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 0f, this, Looper.getMainLooper())
            }
        } catch (e: SecurityException) {
            return false
        } catch (e: IllegalArgumentException) {
            // Provider auf diesem Gerät nicht vorhanden
        }
        running = true
        lastKnown()?.let { listener(it) }
        return true
    }

    fun stop() {
        if (!running) return
        manager.removeUpdates(this)
        running = false
    }

    @SuppressLint("MissingPermission")
    fun lastKnown(): Location? {
        if (!hasPermission(context)) return null
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { p ->
                try {
                    manager.getLastKnownLocation(p)
                } catch (e: Exception) {
                    null
                }
            }
            .maxByOrNull { it.time }
    }

    override fun onLocationChanged(location: Location) {
        val now = SystemClock.elapsedRealtime()
        if (location.provider == LocationManager.GPS_PROVIDER) {
            lastGpsFix = now
        } else if (now - lastGpsFix < 20_000) {
            return
        }
        listener(location)
    }

    // Auf Android < 11 sind diese Methoden abstrakt und werden aufgerufen – daher überschreiben.
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    override fun onProviderEnabled(provider: String) {}

    override fun onProviderDisabled(provider: String) {}

    companion object {
        fun hasPermission(context: Context): Boolean =
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        /** Alter eines Fixes in Millisekunden. */
        fun ageMs(location: Location): Long =
            (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000
    }
}

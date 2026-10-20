package de.rennsteig.offline

import java.util.Locale
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    private const val EARTH_RADIUS = 6371008.8

    /** Meter pro Pixel bei Zoom 0 am Äquator (MapLibre rechnet mit 512-px-Kacheln). */
    const val METERS_PER_PIXEL_Z0 = 78271.517

    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_RADIUS * asin(sqrt(min(1.0, h)))
    }

    /** Peilung von Punkt 1 zu Punkt 2 in Grad (0 = Nord, im Uhrzeigersinn). */
    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return normalize(Math.toDegrees(atan2(y, x)))
    }

    fun normalize(deg: Double): Double = ((deg % 360.0) + 360.0) % 360.0

    /** Kleinster Winkel von a nach b im Bereich -180..180. */
    fun angleDiff(a: Double, b: Double): Double {
        var d = (b - a) % 360.0
        if (d > 180) d -= 360.0
        if (d < -180) d += 360.0
        return d
    }

    fun cardinal(deg: Double): String {
        val dirs = arrayOf("N", "NO", "O", "SO", "S", "SW", "W", "NW")
        return dirs[(((normalize(deg) + 22.5) / 45.0).toInt()) % 8]
    }

    fun formatDistance(m: Double): String = when {
        m < 20 -> "${m.roundToInt()} m"
        m < 950 -> "${(m / 10).roundToInt() * 10} m"
        m < 9950 -> String.format(Locale.GERMANY, "%.1f km", m / 1000)
        else -> "${(m / 1000).roundToInt()} km"
    }

    fun formatKm(km: Double): String = String.format(Locale.GERMANY, "%.1f", km)
}

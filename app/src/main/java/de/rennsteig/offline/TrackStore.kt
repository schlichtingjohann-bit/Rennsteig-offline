package de.rennsteig.offline

import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Aufgezeichneter eigener Weg (bleibt über App-Neustarts erhalten). */
object TrackStore {
    private val points = ArrayList<DoubleArray>() // lat, lon, zeit(ms)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var file: File? = null
    var lengthM = 0.0
        private set

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.filesDir, "track.csv")
        file = f
        if (!f.exists()) return
        try {
            f.forEachLine { line ->
                val parts = line.split(';')
                if (parts.size >= 3) {
                    val p = doubleArrayOf(parts[0].toDouble(), parts[1].toDouble(), parts[2].toDouble())
                    points.lastOrNull()?.let { lengthM += Geo.distance(it[0], it[1], p[0], p[1]) }
                    points.add(p)
                }
            }
        } catch (e: Exception) {
            // beschädigte Datei: mit dem bisher Gelesenen weitermachen
        }
    }

    /** Fügt einen Punkt hinzu, wenn er genau genug ist und sich die Position merklich geändert hat. */
    @Synchronized
    fun add(location: Location): Boolean {
        if (location.hasAccuracy() && location.accuracy > 35f) return false
        val last = points.lastOrNull()
        if (last != null) {
            val d = Geo.distance(last[0], last[1], location.latitude, location.longitude)
            if (d < 8.0) return false
            lengthM += d
        }
        val p = doubleArrayOf(location.latitude, location.longitude, location.time.toDouble())
        points.add(p)
        try {
            file?.appendText("${p[0]};${p[1]};${location.time}\n")
        } catch (e: Exception) {
            // Speicher voll o. Ä. – Punkt bleibt im Speicher
        }
        notifyListeners()
        return true
    }

    @Synchronized
    fun clear() {
        points.clear()
        lengthM = 0.0
        file?.delete()
        notifyListeners()
    }

    @Synchronized
    fun size(): Int = points.size

    @Synchronized
    fun toGeoJson(): String {
        if (points.size < 2) return """{"type":"FeatureCollection","features":[]}"""
        val sb = StringBuilder(points.size * 24 + 120)
        sb.append("""{"type":"Feature","properties":{},"geometry":{"type":"LineString","coordinates":[""")
        points.forEachIndexed { i, p ->
            if (i > 0) sb.append(',')
            sb.append('[').append(p[1]).append(',').append(p[0]).append(']')
        }
        sb.append("]}}")
        return sb.toString()
    }

    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)

    private fun notifyListeners() {
        mainHandler.post { listeners.forEach { it() } }
    }
}

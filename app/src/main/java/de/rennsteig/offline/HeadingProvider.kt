package de.rennsteig.offline

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.view.Surface
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Blickrichtung des Geräts bezogen auf geografisch Nord.
 *
 * Liegt das Handy flach, zählt die Richtung der Oberkante; wird es aufrecht gehalten
 * (wie beim Fotografieren), die Richtung, in die die Rückseite zeigt – also wohin man schaut.
 * Funktioniert komplett ohne Internet (Magnetfeld- und Lagesensoren, Deklination aus dem
 * im System eingebauten Erdmagnetfeldmodell).
 */
class HeadingProvider(
    context: Context,
    private val listener: (headingDeg: Double) -> Unit,
) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    @Suppress("DEPRECATION")
    private val display = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
    private val rotationVector: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnetometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private val rot = FloatArray(9)
    private val remapped = FloatArray(9)
    private val upright = FloatArray(9)
    private val orientation = FloatArray(3)
    private var gravity: FloatArray? = null
    private var geomagnetic: FloatArray? = null
    private var uprightMode = false
    private var sinSmooth = 0.0
    private var cosSmooth = 1.0
    private var hasValue = false
    private var lastEmit = 0L

    /** Deklination (Ost positiv) in Grad, wird aus der GPS-Position berechnet. */
    var declination = 0.0
        private set

    /** Genauigkeit des Magnetfeldsensors (SensorManager.SENSOR_STATUS_*). */
    var accuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH
        private set

    val available: Boolean get() = rotationVector != null || (accelerometer != null && magnetometer != null)

    fun start() {
        if (rotationVector != null) {
            sensorManager.registerListener(this, rotationVector, SensorManager.SENSOR_DELAY_GAME)
        } else if (accelerometer != null && magnetometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME)
        }
        if (magnetometer != null) {
            // auch für die Genauigkeitsmeldung (Kalibrierhinweis)
            sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        hasValue = false
    }

    fun updateDeclination(lat: Double, lon: Double, altitude: Double) {
        declination = GeomagneticField(lat.toFloat(), lon.toFloat(), altitude.toFloat(),
            System.currentTimeMillis()).declination.toDouble()
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        if (sensor.type == Sensor.TYPE_MAGNETIC_FIELD) this.accuracy = accuracy
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                val v = if (event.values.size > 4) event.values.copyOf(4) else event.values
                SensorManager.getRotationMatrixFromVector(rot, v)
                compute()
            }
            Sensor.TYPE_ACCELEROMETER -> {
                gravity = lowPass(event.values, gravity)
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                accuracy = event.accuracy
                if (rotationVector == null) {
                    geomagnetic = lowPass(event.values, geomagnetic)
                    val g = gravity ?: return
                    val m = geomagnetic ?: return
                    if (SensorManager.getRotationMatrix(rot, null, g, m)) compute()
                }
            }
        }
    }

    private fun lowPass(input: FloatArray, prev: FloatArray?): FloatArray {
        if (prev == null) return input.copyOf()
        for (i in prev.indices) prev[i] += 0.2f * (input[i] - prev[i])
        return prev
    }

    private fun compute() {
        // Bildschirmdrehung berücksichtigen
        val (ax, ay) = when (display.rotation) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
        SensorManager.remapCoordinateSystem(rot, ax, ay, remapped)
        // remapped[8] = Anteil der Bildschirmnormalen nach oben: 1 = flach, 0 = senkrecht
        val flatness = abs(remapped[8])
        uprightMode = if (uprightMode) flatness < 0.6f else flatness < 0.45f
        val matrix = if (uprightMode) {
            SensorManager.remapCoordinateSystem(remapped, SensorManager.AXIS_X, SensorManager.AXIS_Z, upright)
            upright
        } else {
            remapped
        }
        SensorManager.getOrientation(matrix, orientation)
        val azimuth = Math.toDegrees(orientation[0].toDouble()) + declination
        val rad = Math.toRadians(azimuth)
        if (!hasValue) {
            sinSmooth = sin(rad)
            cosSmooth = cos(rad)
            hasValue = true
        } else {
            val a = 0.18
            sinSmooth += a * (sin(rad) - sinSmooth)
            cosSmooth += a * (cos(rad) - cosSmooth)
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastEmit >= 30) {
            lastEmit = now
            listener(Geo.normalize(Math.toDegrees(atan2(sinSmooth, cosSmooth))))
        }
    }
}

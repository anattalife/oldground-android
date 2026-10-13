package com.brandonthomas.oldground.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.view.Surface
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.brandonthomas.oldground.geo.LngLat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs

data class Fix(val geo: LngLat, val accuracy: Float?, val heading: Float?, val timeMs: Long)

/**
 * The phone's position and compass heading while the app is on screen. Uses Android's
 * own LocationManager (GPS, plus network for a quick first fix), so it works offline and
 * without Google Play services.
 */
object LocationHub {
    private val _fix = MutableStateFlow<Fix?>(null)
    val fix: StateFlow<Fix?> = _fix

    private var lm: LocationManager? = null
    private var sm: SensorManager? = null
    private var appContext: Context? = null
    private var users = 0
    private var heading: Float? = null
    private var declination = 0f
    private var lastHeadingEmit = 0L

    fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = accept(location)
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private fun accept(l: Location) {
        val prev = _fix.value
        // Ignore a coarse network fix once a good GPS fix is recent.
        if (prev != null && l.provider == LocationManager.NETWORK_PROVIDER &&
            (prev.accuracy ?: 999f) < (l.accuracy) && l.time - prev.timeMs < 15_000
        ) return
        declination = GeomagneticField(l.latitude.toFloat(), l.longitude.toFloat(), l.altitude.toFloat(), l.time).declination
        _fix.value = Fix(LngLat(l.longitude, l.latitude), if (l.hasAccuracy()) l.accuracy else null, heading ?: (if (l.hasBearing()) l.bearing else null), l.time)
    }

    private val sensorListener = object : SensorEventListener {
        private val rot = FloatArray(9)
        private val remapped = FloatArray(9)
        private val orient = FloatArray(3)
        override fun onSensorChanged(e: SensorEvent) {
            SensorManager.getRotationMatrixFromVector(rot, e.values)
            val (ax, ay) = when (displayRotation()) {
                Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
            }
            SensorManager.remapCoordinateSystem(rot, ax, ay, remapped)
            SensorManager.getOrientation(remapped, orient)
            val deg = ((Math.toDegrees(orient[0].toDouble()).toFloat() + declination) + 360f) % 360f
            val prev = heading
            heading = if (prev == null) deg else smooth(prev, deg)
            val now = System.currentTimeMillis()
            if (now - lastHeadingEmit > 200) {
                lastHeadingEmit = now
                _fix.value?.let { _fix.value = it.copy(heading = heading) }
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun smooth(prev: Float, next: Float): Float {
        var d = next - prev
        if (d > 180) d -= 360
        if (d < -180) d += 360
        return (prev + d * 0.25f + 360f) % 360f
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int {
        val c = appContext ?: return Surface.ROTATION_0
        return if (Build.VERSION.SDK_INT >= 30) c.display?.rotation ?: Surface.ROTATION_0
        else (c.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
    }

    /** Start (or keep) updates. Call [release] when done. */
    @SuppressLint("MissingPermission")
    fun acquire(context: Context) {
        appContext = context
        users++
        if (users > 1 || !hasPermission(context)) return
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        this.lm = lm
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (lm.allProviders.contains(p)) {
                runCatching { lm.getLastKnownLocation(p)?.let { if (_fix.value == null) accept(it) } }
                runCatching { lm.requestLocationUpdates(p, 1000L, 0f, listener, Looper.getMainLooper()) }
            }
        }
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        this.sm = sm
        sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { sm.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI) }
    }

    fun release() {
        users = (users - 1).coerceAtLeast(0)
        if (users > 0) return
        lm?.removeUpdates(listener)
        sm?.unregisterListener(sensorListener)
        lm = null
        sm = null
    }

    /** Restart after permission is granted. */
    fun restart(context: Context) {
        val n = users
        if (n == 0) return
        users = 1
        release()
        users = 0
        repeat(n) { acquire(context) }
    }

    fun headingDelta(a: Float, b: Float) = abs(((a - b + 540) % 360) - 180)
}

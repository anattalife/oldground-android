package com.brandonthomas.oldground.location

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.brandonthomas.oldground.MainActivity
import com.brandonthomas.oldground.R
import com.brandonthomas.oldground.data.Repo
import com.brandonthomas.oldground.data.TrackPt
import com.brandonthomas.oldground.geo.LngLat
import com.brandonthomas.oldground.geo.Mercator

/**
 * Records the hunt path as a foreground service, so it keeps going with the screen off
 * or the phone in a pocket. Android shows a "Recording your track" notification while it
 * runs, with a Stop button. Points go straight to the track's file.
 */
class TrackService : Service() {
    private var lm: LocationManager? = null
    private var wake: PowerManager.WakeLock? = null
    private var trackId: String? = null
    private var filter: TrackFilter? = null

    private val listener = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            val id = trackId ?: return
            val f = filter ?: TrackFilter(Repo.lastTrackPoint(id)).also { filter = it }
            val pt = f.offer(
                l.longitude, l.latitude, l.time,
                if (l.hasAccuracy()) l.accuracy else null,
                if (l.hasSpeed()) l.speed else null,
            ) ?: return
            Repo.appendTrackPoint(id, pt)
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Repo.init(applicationContext)
        if (intent?.action == ACTION_STOP) {
            stopRecordingAndSelf()
            return START_NOT_STICKY
        }
        val id = Repo.current.recording ?: Repo.startTrack().id
        if (trackId != id) filter = null
        trackId = id

        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)

        if (lm == null && LocationHub.hasPermission(this)) {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            this.lm = lm
            runCatching { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, MIN_STEP.toFloat(), listener, Looper.getMainLooper()) }
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "oldground:track").apply { acquire(12 * 60 * 60 * 1000L) }
        }
        return START_STICKY
    }

    private fun stopRecordingAndSelf() {
        lm?.removeUpdates(listener)
        lm = null
        wake?.let { if (it.isHeld) it.release() }
        wake = null
        Repo.stopRecording()
        // Stopping from the notification ends the visit too.
        Repo.finishVisit()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        lm?.removeUpdates(listener)
        wake?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Track recording", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TrackService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Visit in progress")
            .setContentText("OldGround is recording your path for this visit.")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "End visit", stop)
            .build()
    }

    companion object {
        private const val CHANNEL = "track"
        private const val NOTIFICATION_ID = 7
        private const val ACTION_STOP = "stop"
        const val MIN_STEP = 2.0

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TrackService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, TrackService::class.java).setAction(ACTION_STOP))
        }
    }
}

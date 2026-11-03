package com.pulso.esportes

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

class LocationTrackingService : Service(), LocationListener {
    companion object {
        const val ACTION_START = "com.pulso.esportes.location.START"
        const val ACTION_PAUSE = "com.pulso.esportes.location.PAUSE"
        const val ACTION_STOP = "com.pulso.esportes.location.STOP"
        const val ACTION_SNAPSHOT = "com.pulso.esportes.location.SNAPSHOT"
        const val EXTRA_ID = "activityId"
        const val EXTRA_SPORT = "sport"
        const val EXTRA_ELAPSED_MS = "elapsedMs"
        const val PREFS = "pulso_gps_tracking"
        const val KEY_SNAPSHOT = "snapshot"
        private const val CHANNEL = "pulso_location"
        private const val NOTIFICATION_ID = 1601
    }

    private lateinit var locations: LocationManager
    private var activityId = ""
    private var sport = "run"
    private var distance = 0.0
    private var points = JSONArray()
    private var last: Location? = null
    private var tracking = false
    private var elapsedBaseMs = 0L
    private var trackingStartedAtElapsed = 0L
    private var lastNotificationAt = 0L

    override fun onCreate() {
        super.onCreate()
        locations = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startTracking(intent)
            ACTION_PAUSE -> pauseTracking()
            ACTION_STOP -> stopTracking()
            ACTION_SNAPSHOT -> persistSnapshot()
            null -> {
                val saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_SNAPSHOT, null)
                    ?.let { runCatching { JSONObject(it) }.getOrNull() }
                if (saved?.optBoolean("tracking") == true) {
                    val restart = Intent(this, LocationTrackingService::class.java)
                        .putExtra(EXTRA_ID, saved.optString("activityId"))
                        .putExtra(EXTRA_SPORT, saved.optString("sport"))
                        .putExtra(EXTRA_ELAPSED_MS, saved.optLong("elapsedMs"))
                    startTracking(restart)
                } else stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startTracking(intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID).orEmpty()
        val selectedSport = intent.getStringExtra(EXTRA_SPORT).orEmpty()
        if (id.isNotBlank() && id != activityId) {
            val saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_SNAPSHOT, null)
                ?.let { runCatching { JSONObject(it) }.getOrNull() }
            val resumeSaved = saved?.optString("activityId") == id
            activityId = id
            sport = selectedSport
            distance = if (resumeSaved) saved?.optDouble("distanceMeters", 0.0) ?: 0.0 else 0.0
            points = if (resumeSaved) saved?.optJSONArray("points") ?: JSONArray() else JSONArray()
            elapsedBaseMs = if (resumeSaved) saved?.optLong("elapsedMs", 0L) ?: 0L else intent.getLongExtra(EXTRA_ELAPSED_MS, 0L)
            last = null
        }
        if (!hasLocationPermission()) {
            stopSelf()
            return
        }
        startForeground(NOTIFICATION_ID, notification())
        tracking = true
        trackingStartedAtElapsed = SystemClock.elapsedRealtime()
        persistSnapshot()
        var requested = false
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
                requested = true
            } catch (_: SecurityException) { }
            catch (_: IllegalArgumentException) { }
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                locations.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000L, 0f, this, Looper.getMainLooper())
                requested = true
            } catch (_: SecurityException) { }
            catch (_: IllegalArgumentException) { }
        }
        if (!requested) {
            tracking = false
            persistSnapshot()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun pauseTracking() {
        elapsedBaseMs = currentElapsedMs()
        tracking = false
        last = null
        locations.removeUpdates(this)
        persistSnapshot()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopTracking() {
        elapsedBaseMs = currentElapsedMs()
        tracking = false
        last = null
        locations.removeUpdates(this)
        persistSnapshot()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onLocationChanged(location: Location) {
        if (!tracking || !location.latitude.isFinite() || !location.longitude.isFinite()) return
        val accuracyLimit = if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) 60f else 150f
        if (location.accuracy > accuracyLimit) return
        val prior = last
        if (prior != null) {
            val dt = (location.time - prior.time) / 1000.0
            val meters = prior.distanceTo(location).toDouble()
            val maxSpeed = if (sport in setOf("bike", "skate", "rowing")) 35.0 else 18.0
            if (dt <= 0.0 || meters / max(.1, dt) > maxSpeed) {
                last = location
                return
            }
            if (dt <= 90.0 && meters >= 3.0) distance += meters
            if (dt > 90.0) {
                last = location
                return
            }
        }
        last = location
        points.put(JSONArray().put(location.latitude).put(location.longitude).put(location.time))
        if (points.length() > 7000) {
            val compact = JSONArray()
            for (i in 0 until points.length() step 2) compact.put(points.get(i))
            points = compact
        }
        persistSnapshot()
        val now = System.currentTimeMillis()
        if (now - lastNotificationAt > 10_000L) {
            lastNotificationAt = now
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification())
        }
    }

    private fun persistSnapshot() {
        val snapshot = JSONObject().put("activityId", activityId).put("sport", sport)
            .put("distanceMeters", distance).put("points", points).put("tracking", tracking)
            .put("elapsedMs", currentElapsedMs())
            .put("updatedAt", System.currentTimeMillis())
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_SNAPSHOT, snapshot.toString()).apply()
    }

    private fun currentElapsedMs(): Long = elapsedBaseMs +
        if (tracking && trackingStartedAtElapsed > 0L) SystemClock.elapsedRealtime() - trackingStartedAtElapsed else 0L

    private fun notification(): Notification {
        val openApp = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        val distanceKm = String.format(java.util.Locale.getDefault(), "%.2f", distance / 1000.0)
        return builder.setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("PULSO • treino em andamento")
            .setContentText("${distanceKm} km registrados • GPS ativo")
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(openApp)
            .setCategory(Notification.CATEGORY_SERVICE).build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL, "Rastreamento de treino", NotificationManager.IMPORTANCE_LOW)
            channel.description = "Notificação persistente durante o rastreamento GPS."
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit
    @Deprecated("Deprecated by Android") override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        tracking = false
        try { locations.removeUpdates(this) } catch (_: Exception) { }
        super.onDestroy()
    }
}

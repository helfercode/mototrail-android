package com.terratrack

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileInputStream

class TrackNavigationService : Service() {
    private val preferences by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var locationManager: LocationManager? = null
    private var locationListener: LocationListener? = null
    private var trackPoints: List<TrackPoint> = emptyList()
    private var lastAlertElapsed = 0L
    private var wasOffTrack = false
    private var lastTurnIndex = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val path = intent.getStringExtra(EXTRA_TRACK_PATH)
                if (path.isNullOrBlank()) {
                    preferences.edit().putString(KEY_STATUS, "No GPX track was supplied.").putBoolean(KEY_ACTIVE, false).apply()
                    stopSelf()
                } else {
                    startForegroundCompat(buildOngoingNotification("Loading original GPX track…"))
                    loadTrack(path, reversed = intent.getBooleanExtra(EXTRA_REVERSED, false), resetTurns = true)
                }
            }
            ACTION_STOP -> stopNavigation()
            null -> {
                val path = preferences.getString(KEY_TRACK_PATH, null)
                if (preferences.getBoolean(KEY_ACTIVE, false) && !path.isNullOrBlank()) {
                    startForegroundCompat(buildOngoingNotification("Restoring GPX navigation…"))
                    loadTrack(path, reversed = preferences.getBoolean(KEY_REVERSED, false), resetTurns = false)
                } else {
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private fun loadTrack(path: String, reversed: Boolean, resetTurns: Boolean) {
        removeGpsUpdates()
        wasOffTrack = false
        lastAlertElapsed = 0L
        getSystemService(NotificationManager::class.java).cancel(OFF_TRACK_NOTIFICATION_ID)
        getSystemService(NotificationManager::class.java).cancel(TURN_NOTIFICATION_ID)
        val previousPath = preferences.getString(KEY_TRACK_PATH, null)
        if (resetTurns || previousPath != path) {
            lastTurnIndex = -1
            preferences.edit().putInt(KEY_LAST_TURN_INDEX, -1).apply()
        }
        preferences.edit()
            .putString(KEY_TRACK_PATH, path)
            .putBoolean(KEY_REVERSED, reversed)
            .putBoolean(KEY_ACTIVE, true)
            .apply()
        Thread {
            try {
                val file = File(path)
                require(file.exists()) { "The selected GPX file is missing." }
                val parsed = FileInputStream(file).use { parseGpx(it, file.name) }
                mainHandler.post {
                    trackPoints = if (reversed) parsed.points.asReversed() else parsed.points
                    lastTurnIndex = preferences.getInt(KEY_LAST_TURN_INDEX, -1)
                    preferences.edit()
                        .putString(KEY_TRACK_NAME, parsed.name)
                        .putString(KEY_STATUS, "Following ${parsed.name}")
                        .putBoolean(KEY_ACTIVE, true)
                        .apply()
                    updateOngoingNotification("Following ${parsed.name} · no route recalculation")
                    startGpsUpdates()
                }
            } catch (error: Exception) {
                mainHandler.post {
                    preferences.edit()
                        .putBoolean(KEY_ACTIVE, false)
                        .putString(KEY_STATUS, "Navigation could not start: ${error.message}")
                        .apply()
                    stopForegroundCompat()
                    stopSelf()
                }
            }
        }.start()
    }

    private fun startGpsUpdates() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            preferences.edit().putBoolean(KEY_ACTIVE, false).putString(KEY_STATUS, "Location permission is required for background GPX navigation.").apply()
            stopForegroundCompat()
            stopSelf()
            return
        }
        val manager = locationManager ?: return
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (trackPoints.size < 2) return
                val progress = trackProgress(location.latitude, location.longitude, trackPoints) ?: return
                preferences.edit()
                    .putFloat(KEY_DISTANCE_METERS, progress.distanceMeters.toFloat())
                    .putLong(KEY_LAST_FIX_TIME, System.currentTimeMillis())
                    .apply()
                handleOffTrack(progress.distanceMeters)
                if (progress.distanceMeters <= OFF_TRACK_LIMIT_METERS) {
                    nextRouteTurn(trackPoints, progress, TURN_LOOKAHEAD_METERS)?.let { turn ->
                        if (turn.distanceAlongTrackMeters <= TURN_ALERT_METERS && turn.segmentIndex != lastTurnIndex) {
                            lastTurnIndex = turn.segmentIndex
                            preferences.edit().putInt(KEY_LAST_TURN_INDEX, lastTurnIndex).apply()
                            showTurnAlert(turn.direction, turn.distanceAlongTrackMeters)
                        }
                    }
                }
            }
        }
        locationListener = listener
        try {
            if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 3f, listener, Looper.getMainLooper())
                manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { listener.onLocationChanged(it) }
            } else if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 4000L, 5f, listener, Looper.getMainLooper())
                manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)?.let { listener.onLocationChanged(it) }
            } else {
                throw IllegalStateException("Location services are disabled.")
            }
        } catch (error: Exception) {
            preferences.edit().putBoolean(KEY_ACTIVE, false).putString(KEY_STATUS, "GPS navigation unavailable: ${error.message}").apply()
            stopForegroundCompat()
            stopSelf()
        }
    }

    private fun handleOffTrack(distanceMeters: Double) {
        val now = SystemClock.elapsedRealtime()
        if (distanceMeters > OFF_TRACK_LIMIT_METERS) {
            preferences.edit()
                .putBoolean(KEY_OFF_TRACK, true)
                .putString(KEY_STATUS, "OFF TRACK · ${distanceMeters.toInt()} m from GPX")
                .apply()
            if (!wasOffTrack || now - lastAlertElapsed >= ALERT_INTERVAL_MS) {
                lastAlertElapsed = now
                showOffTrackAlert(distanceMeters)
            }
            wasOffTrack = true
        } else if (distanceMeters < ON_TRACK_RESET_METERS) {
            preferences.edit()
                .putBoolean(KEY_OFF_TRACK, false)
                .putString(KEY_STATUS, "On original GPX track")
                .apply()
            if (wasOffTrack) {
                updateOngoingNotification("Back on the original GPX track")
                wasOffTrack = false
            }
        }
    }

    private fun showTurnAlert(direction: String, distanceMeters: Double) {
        val message = "$direction turn in ${distanceMeters.toInt()} metres."
        val notification = NotificationCompat.Builder(this, OFF_TRACK_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentTitle("TERRATRACK · TURN AHEAD")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$message Direction derived from the geometry of the selected GPX track."))
            .setContentIntent(notificationIntent())
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(TURN_NOTIFICATION_ID, notification)
        preferences.edit().putString(KEY_STATUS, message).apply()
    }

    private fun stopNavigation() {
        removeGpsUpdates()
        preferences.edit()
            .putBoolean(KEY_ACTIVE, false)
            .putBoolean(KEY_OFF_TRACK, false)
            .putString(KEY_STATUS, "GPX navigation stopped")
            .apply()
        getSystemService(NotificationManager::class.java).cancel(OFF_TRACK_NOTIFICATION_ID)
        getSystemService(NotificationManager::class.java).cancel(TURN_NOTIFICATION_ID)
        stopForegroundCompat()
        stopSelf()
    }

    private fun removeGpsUpdates() {
        val listener = locationListener ?: return
        try { locationManager?.removeUpdates(listener) } catch (_: Exception) { }
        locationListener = null
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(ONGOING_CHANNEL, "GPX navigation", NotificationManager.IMPORTANCE_LOW)
            )
            manager.createNotificationChannel(
                NotificationChannel(OFF_TRACK_CHANNEL, "Off-track warnings", NotificationManager.IMPORTANCE_HIGH).apply {
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 350, 180, 350)
                    description = "Alerts when the GPS position is more than 50 metres from the selected GPX track."
                }
            )
        }
    }

    private fun notificationIntent(): PendingIntent {
        val openApp = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this, 51, openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildOngoingNotification(message: String): Notification =
        NotificationCompat.Builder(this, ONGOING_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("TerraTrack · GPX navigation")
            .setContentText(message)
            .setContentIntent(notificationIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    private fun updateOngoingNotification(message: String) {
        getSystemService(NotificationManager::class.java)
            .notify(ONGOING_NOTIFICATION_ID, buildOngoingNotification(message))
    }

    private fun showOffTrackAlert(distanceMeters: Double) {
        val message = "You are ${distanceMeters.toInt()} metres from the original GPX line."
        val notification = NotificationCompat.Builder(this, OFF_TRACK_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("TERRATRACK · OFF TRACK")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$message No automatic recalculation has been performed."))
            .setContentIntent(notificationIntent())
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setAutoCancel(false)
            .setOnlyAlertOnce(false)
            .build()
        getSystemService(NotificationManager::class.java).notify(OFF_TRACK_NOTIFICATION_ID, notification)
        updateOngoingNotification("OFF TRACK · ${distanceMeters.toInt()} m from GPX")
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        removeGpsUpdates()
        super.onDestroy()
    }

    companion object {
        const val PREFS = "terratrack_navigation"
        const val KEY_ACTIVE = "navigation_active"
        const val KEY_TRACK_PATH = "navigation_track_path"
        const val KEY_REVERSED = "navigation_reversed"
        const val KEY_TRACK_NAME = "navigation_track_name"
        const val KEY_OFF_TRACK = "navigation_off_track"
        const val KEY_DISTANCE_METERS = "navigation_distance_m"
        const val KEY_LAST_FIX_TIME = "navigation_last_fix_time"
        const val KEY_STATUS = "navigation_status"
        const val KEY_LAST_TURN_INDEX = "navigation_last_turn_index"

        private const val ACTION_START = "com.terratrack.navigation.START"
        private const val ACTION_STOP = "com.terratrack.navigation.STOP"
        private const val EXTRA_TRACK_PATH = "track_path"
        private const val EXTRA_REVERSED = "track_reversed"
        private const val ONGOING_CHANNEL = "terratrack_gpx_navigation"
        private const val OFF_TRACK_CHANNEL = "terratrack_off_track_alerts"
        private const val NOTIFICATION_ID = 4413
        private const val ONGOING_NOTIFICATION_ID = 4413
        private const val OFF_TRACK_NOTIFICATION_ID = 4414
        private const val TURN_NOTIFICATION_ID = 4415
        private const val OFF_TRACK_LIMIT_METERS = 50.0
        private const val ON_TRACK_RESET_METERS = 40.0
        private const val TURN_LOOKAHEAD_METERS = 250.0
        private const val TURN_ALERT_METERS = 120.0
        private const val ALERT_INTERVAL_MS = 90000L

        fun start(context: Context, trackPath: String, reversed: Boolean = false) {
            val intent = Intent(context, TrackNavigationService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_TRACK_PATH, trackPath)
                .putExtra(EXTRA_REVERSED, reversed)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, TrackNavigationService::class.java).setAction(ACTION_STOP))
        }
    }
}

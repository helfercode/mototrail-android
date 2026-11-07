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
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max

class RecordingService : Service() {
    private val preferences by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private var locationManager: LocationManager? = null
    private var locationListener: LocationListener? = null
    private var recordingFile: File? = null
    private var lastLocation: Location? = null
    private var segmentOpen = false
    private var elapsedBeforeSegment = 0L
    private var segmentStartElapsed = 0L
    private var totalDistanceMeters = 0.0
    private var pointCount = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startNewRecording(intent.getStringExtra(EXTRA_NAME).orEmpty())
            ACTION_PAUSE -> pauseRecording()
            ACTION_RESUME -> resumeRecording()
            ACTION_STOP -> stopRecording()
            null -> restoreAfterSystemRestart()
        }
        return START_STICKY
    }

    private fun startNewRecording(requestedName: String) {
        if (preferences.getString(KEY_STATE, STATE_IDLE) == STATE_RECORDING) return
        try {
            val directory = recordingDirectory()
            if (!directory.exists() && !directory.mkdirs()) throw IllegalStateException("Cannot create recordings folder")
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val safeName = requestedName.trim()
                .ifBlank { "Off-road ride" }
                .replace(Regex("[^A-Za-z0-9 _-]"), "")
                .replace(Regex("\\s+"), "_")
                .take(40)
            recordingFile = File(directory, "TerraTrack_${safeName}_$stamp.gpx")
            totalDistanceMeters = 0.0
            pointCount = 0
            lastLocation = null
            elapsedBeforeSegment = 0L
            writeText("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"TerraTrack\" xmlns=\"http://www.topografix.com/GPX/1/1\"><trk><name>${xmlEscape(requestedName.ifBlank { "Off-road ride" })}</name>\n")
            openSegment()
            preferences.edit()
                .putString(KEY_FILE, recordingFile?.absolutePath)
                .putString(KEY_LAST_FILE, null)
                .putString(KEY_STATE, STATE_RECORDING)
                .putString(KEY_NAME, requestedName.ifBlank { "Off-road ride" })
                .putInt(KEY_POINTS, 0)
                .putLong(KEY_DISTANCE_METERS, 0L)
                .putLong(KEY_ELAPSED, 0L)
                .putString(KEY_STATUS, "Recording GPX track")
                .apply()
            startForegroundCompat(buildNotification("Recording GPX track"))
            requestGpsUpdates()
        } catch (error: Exception) {
            preferences.edit().putString(KEY_STATUS, "Recording could not start: ${error.message}").putString(KEY_STATE, STATE_IDLE).apply()
            stopSelf()
        }
    }

    private fun restoreAfterSystemRestart() {
        val state = preferences.getString(KEY_STATE, STATE_IDLE)
        val path = preferences.getString(KEY_FILE, null)
        if ((state == STATE_RECORDING || state == STATE_PAUSED) && path != null) {
            recordingFile = File(path)
            if (recordingFile?.exists() == true) {
                totalDistanceMeters = preferences.getLong(KEY_DISTANCE_METERS, 0L).toDouble()
                pointCount = preferences.getInt(KEY_POINTS, 0)
                elapsedBeforeSegment = preferences.getLong(KEY_ELAPSED, 0L)
                startForegroundCompat(buildNotification(if (state == STATE_RECORDING) "Recording GPX track" else "GPX recording paused"))
                if (state == STATE_RECORDING) {
                    openSegment()
                    requestGpsUpdates()
                }
            } else {
                preferences.edit().putString(KEY_STATE, STATE_IDLE).putString(KEY_STATUS, "The active GPX recording file is missing.").apply()
                stopSelf()
            }
        } else {
            stopSelf()
        }
    }

    private fun pauseRecording() {
        if (preferences.getString(KEY_STATE, STATE_IDLE) != STATE_RECORDING) return
        updateElapsed()
        closeSegment()
        removeGpsUpdates()
        preferences.edit()
            .putString(KEY_STATE, STATE_PAUSED)
            .putString(KEY_STATUS, "Recording paused")
            .apply()
        updateNotification("GPX recording paused")
    }

    private fun resumeRecording() {
        if (preferences.getString(KEY_STATE, STATE_IDLE) != STATE_PAUSED) return
        openSegment()
        preferences.edit()
            .putString(KEY_STATE, STATE_RECORDING)
            .putString(KEY_STATUS, "Recording GPX track")
            .apply()
        updateNotification("Recording GPX track")
        requestGpsUpdates()
    }

    private fun stopRecording() {
        val state = preferences.getString(KEY_STATE, STATE_IDLE)
        if (state != STATE_RECORDING && state != STATE_PAUSED) {
            stopForegroundCompat()
            stopSelf()
            return
        }
        updateElapsed()
        removeGpsUpdates()
        closeSegment()
        try {
            writeText("</trk></gpx>\n")
            recordingFile?.let { file ->
                preferences.edit()
                    .putString(KEY_LAST_FILE, file.absolutePath)
                    .putString(KEY_FILE, file.absolutePath)
                    .putString(KEY_STATE, STATE_STOPPED)
                    .putString(KEY_STATUS, "GPX recording saved locally")
                    .apply()
            }
        } catch (error: Exception) {
            preferences.edit().putString(KEY_STATUS, "Could not finalise GPX: ${error.message}").apply()
        }
        stopForegroundCompat()
        stopSelf()
    }

    private fun requestGpsUpdates() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            preferences.edit().putString(KEY_STATUS, "Location permission is required to record GPX.").apply()
            stopRecording()
            return
        }
        val manager = locationManager ?: return
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) = acceptLocation(location)
        }
        locationListener = listener
        try {
            if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1500L, 2f, listener, Looper.getMainLooper())
            } else {
                manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000L, 5f, listener, Looper.getMainLooper())
                preferences.edit().putString(KEY_STATUS, "GPS is unavailable. Using network location until GPS is available.").apply()
            }
        } catch (error: SecurityException) {
            preferences.edit().putString(KEY_STATUS, "Location permission was revoked.").apply()
            stopRecording()
        }
    }

    private fun acceptLocation(location: Location) {
        if (preferences.getString(KEY_STATE, STATE_IDLE) != STATE_RECORDING) return
        if (location.hasAccuracy() && location.accuracy > 60f) return
        val previous = lastLocation
        if (previous != null) {
            val moved = previous.distanceTo(location)
            val elapsed = location.time - previous.time
            if (moved < 3f && elapsed < 12000L) return
            if (moved > 250f && elapsed < 3000L) return
            totalDistanceMeters += moved
        }
        lastLocation = Location(location)
        val elevation = if (location.hasAltitude()) String.format(Locale.US, "<ele>%.1f</ele>", location.altitude) else ""
        val time = gpxTime(location.time)
        val point = String.format(Locale.US,
            "<trkpt lat=\"%.7f\" lon=\"%.7f\">%s<time>%s</time></trkpt>\n",
            location.latitude, location.longitude, elevation, time)
        try {
            writeText(point)
            pointCount++
            updateElapsed()
            preferences.edit()
                .putInt(KEY_POINTS, pointCount)
                .putLong(KEY_DISTANCE_METERS, totalDistanceMeters.toLong())
                .putString(KEY_STATUS, "Recording GPX track")
                .apply()
        } catch (error: Exception) {
            preferences.edit().putString(KEY_STATUS, "GPX write error: ${error.message}").apply()
            pauseRecording()
        }
    }

    private fun openSegment() {
        if (segmentOpen) return
        writeText("<trkseg>\n")
        segmentOpen = true
        segmentStartElapsed = SystemClock.elapsedRealtime()
        preferences.edit().putString(KEY_STATE, STATE_RECORDING).apply()
    }

    private fun closeSegment() {
        if (!segmentOpen) return
        writeText("</trkseg>\n")
        segmentOpen = false
        updateElapsed()
    }

    private fun updateElapsed() {
        if (segmentOpen) {
            val elapsed = elapsedBeforeSegment + max(0L, SystemClock.elapsedRealtime() - segmentStartElapsed)
            preferences.edit().putLong(KEY_ELAPSED, elapsed).apply()
        } else {
            elapsedBeforeSegment = preferences.getLong(KEY_ELAPSED, elapsedBeforeSegment)
        }
    }

    private fun writeText(text: String) {
        val file = recordingFile ?: return
        FileOutputStream(file, true).bufferedWriter(Charsets.UTF_8).use { it.write(text) }
    }

    private fun recordingDirectory(): File {
        val removable = getExternalFilesDirs(null).filterNotNull()
            .firstOrNull { android.os.Environment.isExternalStorageRemovable(it) }
        val root = removable ?: getExternalFilesDir(null) ?: filesDir
        return File(root, "recordings")
    }

    private fun gpxTime(millis: Long): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        return formatter.format(Date(millis))
    }

    private fun xmlEscape(value: String): String = value
        .replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "GPX recording", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(message: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 20, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("TerraTrack")
            .setContentText(message)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(message: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(message))
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
    }

    private fun removeGpsUpdates() {
        val listener = locationListener ?: return
        try { locationManager?.removeUpdates(listener) } catch (_: Exception) { }
        locationListener = null
    }

    override fun onDestroy() {
        removeGpsUpdates()
        if (segmentOpen) {
            try { closeSegment() } catch (_: Exception) { }
        }
        super.onDestroy()
    }

    companion object {
        const val PREFS = "terratrack_state"
        const val KEY_STATE = "recording_state"
        const val KEY_FILE = "recording_file"
        const val KEY_LAST_FILE = "last_recording_file"
        const val KEY_NAME = "recording_name"
        const val KEY_POINTS = "recording_points"
        const val KEY_DISTANCE_METERS = "recording_distance_m"
        const val KEY_ELAPSED = "recording_elapsed_ms"
        const val KEY_STATUS = "recording_status"
        const val STATE_IDLE = "IDLE"
        const val STATE_RECORDING = "RECORDING"
        const val STATE_PAUSED = "PAUSED"
        const val STATE_STOPPED = "STOPPED"

        private const val CHANNEL_ID = "terratrack_gpx_recording"
        private const val NOTIFICATION_ID = 4412
        private const val ACTION_START = "com.terratrack.action.START"
        private const val ACTION_PAUSE = "com.terratrack.action.PAUSE"
        private const val ACTION_RESUME = "com.terratrack.action.RESUME"
        private const val ACTION_STOP = "com.terratrack.action.STOP"
        private const val EXTRA_NAME = "recording_name"

        fun start(context: Context, name: String) {
            val intent = Intent(context, RecordingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_NAME, name)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun pause(context: Context) = context.startService(Intent(context, RecordingService::class.java).setAction(ACTION_PAUSE))
        fun resume(context: Context) = context.startService(Intent(context, RecordingService::class.java).setAction(ACTION_RESUME))
        fun stop(context: Context) = context.startService(Intent(context, RecordingService::class.java).setAction(ACTION_STOP))
    }
}

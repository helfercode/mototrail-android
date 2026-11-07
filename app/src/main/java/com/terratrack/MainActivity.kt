package com.terratrack

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mapsforge.core.graphics.Style
import org.mapsforge.core.model.LatLong
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.util.AndroidUtil
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.layer.overlay.FixedPixelCircle
import org.mapsforge.map.layer.overlay.Polyline
import org.mapsforge.map.layer.renderer.TileRendererLayer
import org.mapsforge.map.reader.MapFile
import org.mapsforge.map.rendertheme.internal.MapsforgeThemes
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale

private val Night = Color(0xFF050806)
private val Panel = Color(0xFF151E19)
private val Panel2 = Color(0xFF202C25)
private val Signal = Color(0xFFD7FF35)
private val TrackColor = Color(0xFFFF3030)
private val TextMain = Color(0xFFF7FAF7)
private val TextMuted = Color(0xFFB7C2BB)

private const val STATE_PREFS = "terratrack_state"
private const val ACTIVE_TRACK = "active_track_path"
private const val ACTIVE_MAP = "active_map_path"

private data class MapOverlayState(
    var mapDataStore: MapFile? = null,
    var route: Polyline? = null,
    var marker: FixedPixelCircle? = null,
    var destinationMarker: FixedPixelCircle? = null,
    var destinationLine: Polyline? = null,
    val directionArrows: MutableList<Polyline> = mutableListOf(),
    var downX: Float = 0f,
    var downY: Float = 0f,
    var trackSignature: Int = Int.MIN_VALUE,
    var zoomRequest: Int = 0,
    var recenterRequest: Int = 0,
    val waypointMarkers: MutableList<FixedPixelCircle> = mutableListOf()
)

class TerraTrackUiState {
    var selectedTab by mutableStateOf("MAP")
    var zoomRequest by mutableIntStateOf(0)
    var recenterRequest by mutableIntStateOf(0)
}

class MainActivity : ComponentActivity() {
    private val uiState = TerraTrackUiState()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidGraphicFactory.createInstance(application)
        setContent { TerraTrackApp(uiState) }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> { uiState.zoomRequest++; return true }
                KeyEvent.KEYCODE_VOLUME_DOWN -> { uiState.zoomRequest--; return true }
                KeyEvent.KEYCODE_MEDIA_NEXT -> { uiState.recenterRequest++; return true }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                    uiState.selectedTab = if (uiState.selectedTab == "MAP") "RECORD" else "MAP"
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        AndroidGraphicFactory.clearResourceMemoryCache()
        super.onDestroy()
    }
}

@Composable
private fun TerraTrackApp(ui: TerraTrackUiState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferences = remember { context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE) }
    val navigationPreferences = remember { context.getSharedPreferences(TrackNavigationService.PREFS, Context.MODE_PRIVATE) }

    var activeTrack by remember { mutableStateOf<GpxTrack?>(null) }
    var activeTrackPath by remember { mutableStateOf(preferences.getString(ACTIVE_TRACK, null)) }
    var activeMapPath by remember { mutableStateOf(preferences.getString(ACTIVE_MAP, null)) }
    var currentLocation by remember { mutableStateOf<Location?>(null) }
    var compassHeading by remember { mutableFloatStateOf(Float.NaN) }
    var locationAllowed by remember { mutableStateOf(hasLocationPermission(context)) }
    var followTrack by remember { mutableStateOf(navigationPreferences.getBoolean(TrackNavigationService.KEY_ACTIVE, false)) }
    var navigationDistance by remember { mutableFloatStateOf(navigationPreferences.getFloat(TrackNavigationService.KEY_DISTANCE_METERS, Float.NaN)) }
    var pendingTrackFollow by remember { mutableStateOf(false) }
    var showPoiLayer by remember { mutableStateOf(true) }
    var navigationMode by remember { mutableStateOf("TRACK") }
    var destination by remember { mutableStateOf<TrackPoint?>(null) }
    var controlsVisible by remember { mutableStateOf(true) }
    var interactionToken by remember { mutableIntStateOf(0) }
    var touchLocked by remember { mutableStateOf(false) }
    var reversed by remember { mutableStateOf(false) }
    var importBusy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Ready for the next off-road route.") }
    var recordingState by remember { mutableStateOf(preferences.getString(RecordingService.KEY_STATE, RecordingService.STATE_IDLE) ?: RecordingService.STATE_IDLE) }
    var recordingName by remember { mutableStateOf("Off-road ride") }
    var recordingStatus by remember { mutableStateOf(preferences.getString(RecordingService.KEY_STATUS, "No active recording") ?: "No active recording") }
    var recordingPoints by remember { mutableIntStateOf(preferences.getInt(RecordingService.KEY_POINTS, 0)) }
    var recordingDistance by remember { mutableLongStateOf(preferences.getLong(RecordingService.KEY_DISTANCE_METERS, 0L)) }
    var recordingElapsed by remember { mutableLongStateOf(preferences.getLong(RecordingService.KEY_ELAPSED, 0L)) }
    var lastRecordingPath by remember { mutableStateOf(preferences.getString(RecordingService.KEY_LAST_FILE, null)) }
    var savedRecordings by remember { mutableStateOf<List<File>>(emptyList()) }
    var savedTracks by remember { mutableStateOf<List<File>>(emptyList()) }
    var savedMaps by remember { mutableStateOf<List<File>>(emptyList()) }
    var offlinePois by remember { mutableStateOf<List<OfflinePoi>>(emptyList()) }
    var poiCategories by remember { mutableStateOf(setOf("fuel", "water", "repair", "camping")) }
    var poiRadius by remember { mutableIntStateOf(25000) }
    var mapDownloadUrl by remember { mutableStateOf("") }
    var pendingRecordName by remember { mutableStateOf<String?>(null) }
    var pendingExportPath by remember { mutableStateOf<String?>(null) }
    var mapZoomRequest by remember { mutableIntStateOf(0) }
    var recenterRequest by remember { mutableIntStateOf(0) }
    var gpsRequested by remember { mutableStateOf(false) }

    fun showControls() {
        controlsVisible = true
        interactionToken++
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        locationAllowed = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (locationAllowed) {
            status = "Precise location permission granted."
            pendingRecordName?.let { name ->
                RecordingService.start(context, name)
                pendingRecordName = null
                ui.selectedTab = "RECORD"
            }
            if (pendingTrackFollow) {
                val path = activeTrackPath
                if (path != null) {
                    TrackNavigationService.start(context, path)
                    followTrack = true
                } else {
                    status = "Select a GPX file before starting navigation."
                }
                pendingTrackFollow = false
            }
        } else {
            status = "Location permission is required for GPS navigation and GPX recording."
            pendingRecordName = null
        }
    }

    val gpxPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            importBusy = true
            scope.launch {
                try {
                    val pair = withContext(Dispatchers.IO) {
                        val file = copyUriIntoAppFolder(context, uri, "tracks", safeFileName(displayName(context, uri)))
                        file to FileInputStream(file).use { parseGpx(it, file.name) }
                    }
                    activeTrackPath = pair.first.absolutePath
                    activeTrack = pair.second
                    savedTracks = withContext(Dispatchers.IO) { listTrackFiles(context) }
                    preferences.edit().putString(ACTIVE_TRACK, activeTrackPath).apply()
                    reversed = false
                    followTrack = false
                    status = "GPX imported and stored on this device."
                    ui.selectedTab = "MAP"
                    showControls()
                } catch (error: Exception) {
                    status = "GPX import failed: ${error.message ?: "invalid file"}"
                } finally {
                    importBusy = false
                }
            }
        }
    }

    val mapPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            importBusy = true
            scope.launch {
                try {
                    val file = withContext(Dispatchers.IO) {
                        copyUriIntoAppFolder(context, uri, "maps", safeFileName(displayName(context, uri)))
                    }
                    require(file.name.endsWith(".map", true)) { "Select a Mapsforge .map file, such as an OpenAndroMaps regional map." }
                    activeMapPath = file.absolutePath
                    preferences.edit().putString(ACTIVE_MAP, activeMapPath).apply()
                    status = "Offline map imported: ${file.name}"
                    savedMaps = listMapFiles(context)
                } catch (error: Exception) {
                    status = "Map import failed: ${error.message ?: "unsupported map file"}"
                } finally {
                    importBusy = false
                }
            }
        }
    }

    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri: Uri? ->
        val source = pendingExportPath
        pendingExportPath = null
        if (uri != null && source != null) {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { output ->
                            FileInputStream(File(source)).use { input -> input.copyTo(output) }
                        } ?: throw IllegalStateException("Could not create the destination file.")
                    }
                    recordingStatus = "GPX exported successfully."
                } catch (error: Exception) {
                    recordingStatus = "GPX export failed: ${error.message}"
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        activeTrackPath?.let { path ->
            val file = File(path)
            if (file.exists()) {
                try {
                    activeTrack = withContext(Dispatchers.IO) { FileInputStream(file).use { parseGpx(it, file.name) } }
                } catch (_: Exception) { status = "The previously selected GPX file could not be restored." }
            }
        }
        savedRecordings = withContext(Dispatchers.IO) { listRecordingFiles(context) }
        savedTracks = withContext(Dispatchers.IO) { listTrackFiles(context) }
        savedMaps = withContext(Dispatchers.IO) { listMapFiles(context) }
        offlinePois = withContext(Dispatchers.IO) { OfflinePoiStore.load(context) }
    }

    LaunchedEffect(Unit) {
        while (true) {
            recordingState = preferences.getString(RecordingService.KEY_STATE, RecordingService.STATE_IDLE) ?: RecordingService.STATE_IDLE
            recordingStatus = preferences.getString(RecordingService.KEY_STATUS, "No active recording") ?: "No active recording"
            recordingPoints = preferences.getInt(RecordingService.KEY_POINTS, 0)
            recordingDistance = preferences.getLong(RecordingService.KEY_DISTANCE_METERS, 0L)
            recordingElapsed = preferences.getLong(RecordingService.KEY_ELAPSED, 0L)
            lastRecordingPath = preferences.getString(RecordingService.KEY_LAST_FILE, null)
            followTrack = navigationPreferences.getBoolean(TrackNavigationService.KEY_ACTIVE, false)
            navigationDistance = navigationPreferences.getFloat(TrackNavigationService.KEY_DISTANCE_METERS, Float.NaN)
            if (recordingState == RecordingService.STATE_STOPPED) {
                savedRecordings = withContext(Dispatchers.IO) { listRecordingFiles(context) }
            }
            delay(1000)
        }
    }

    LaunchedEffect(controlsVisible, interactionToken) {
        if (controlsVisible && ui.selectedTab == "MAP" && !touchLocked) {
            delay(5000)
            controlsVisible = false
        }
    }

    DisposableEffect(locationAllowed, ui.selectedTab) {
        if (!locationAllowed || (ui.selectedTab != "MAP" && ui.selectedTab != "POI")) {
            onDispose { }
        } else {
            val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) { currentLocation = location }
            }
            try {
                val permission = hasLocationPermission(context)
                if (permission && manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { currentLocation = it }
                    manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1500L, 2f, listener)
                }
            } catch (_: SecurityException) {
                status = "Location permission was revoked."
            }
            onDispose { try { manager.removeUpdates(listener) } catch (_: Exception) { } }
        }
    }

    DisposableEffect(ui.selectedTab) {
        if (ui.selectedTab != "MAP") {
            onDispose { }
        } else {
            val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            val rotationMatrix = FloatArray(9)
            val orientation = FloatArray(3)
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    SensorManager.getOrientation(rotationMatrix, orientation)
                    val heading = ((Math.toDegrees(orientation[0].toDouble()) + 360.0) % 360.0).toFloat()
                    val previous = compassHeading
                    val delta = if (previous.isNaN()) 360f else ((heading - previous + 540f) % 360f) - 180f
                    if (previous.isNaN() || kotlin.math.abs(delta) >= 2f) compassHeading = heading
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            if (rotationSensor != null) {
                sensorManager.registerListener(listener, rotationSensor, SensorManager.SENSOR_DELAY_UI)
            }
            onDispose { sensorManager.unregisterListener(listener) }
        }
    }

    val visibleTrackPoints = if (reversed) activeTrack?.points?.asReversed().orEmpty() else activeTrack?.points.orEmpty()
    val distanceFromTrack = remember(currentLocation, visibleTrackPoints) {
        currentLocation?.let { distanceToTrackMeters(it.latitude, it.longitude, visibleTrackPoints) }
    }
    val destinationDistance = remember(currentLocation, destination) {
        val location = currentLocation
        val target = destination
        if (location == null || target == null) null
        else haversineMeters(location.latitude, location.longitude, target.latitude, target.longitude)
    }
    val destinationBearing = remember(currentLocation, destination) {
        val location = currentLocation
        val target = destination
        if (location == null || target == null) null
        else bearingDegrees(location.latitude, location.longitude, target.latitude, target.longitude)
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Signal,
            onPrimary = Night,
            secondary = TrackColor,
            background = Night,
            surface = Panel,
            onSurface = TextMain
        )
    ) {
        when (ui.selectedTab) {
            "MAP" -> Box(Modifier.fillMaxSize().background(Night)) {
                OfflineMapCanvas(
                    mapPath = activeMapPath,
                    trackPoints = visibleTrackPoints,
                    waypoints = activeTrack?.waypoints.orEmpty() + if (showPoiLayer) offlinePois.map { it.toTrackWaypoint() } else emptyList(),
                    currentLocation = currentLocation,
                    destination = destination,
                    followGps = followTrack,
                    zoomRequest = ui.zoomRequest + mapZoomRequest,
                    recenterRequest = ui.recenterRequest + recenterRequest,
                    onMapTouched = { showControls() },
                    onMapTap = { point ->
                        if (navigationMode == "POINT") {
                            destination = point
                            status = "Destination set. Guidance is straight-line bearing and distance, not a calculated route."
                        }
                        showControls()
                    },
                    onMapError = { status = it },
                    modifier = Modifier.fillMaxSize()
                )

                if (!controlsVisible && !touchLocked) {
                    Box(
                        Modifier.fillMaxSize().pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                showControls()
                            }
                        }
                    )
                }

                if (controlsVisible && !touchLocked) {
                    Column(
                        Modifier.align(Alignment.TopCenter).fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("TRACKS", "RECORD", "MAPS", "POI", "SETTINGS").forEach { tab ->
                                FilledTonalButton(
                                    onClick = { ui.selectedTab = tab; showControls() },
                                    modifier = Modifier.weight(1f).height(48.dp),
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = if (ui.selectedTab == tab) Signal else Panel,
                                        contentColor = if (ui.selectedTab == tab) Night else TextMain
                                    ),
                                    contentPadding = PaddingValues(horizontal = 4.dp)
                                ) {
                                    Text(when (tab) { "TRACKS" -> "TRACK"; "RECORD" -> "REC"; "MAPS" -> "MAP"; else -> tab }, fontSize = 10.sp, fontWeight = FontWeight.Black)
                                }
                            }
                            FilledTonalButton(
                                onClick = { touchLocked = true },
                                modifier = Modifier.size(52.dp),
                                shape = CircleShape,
                                colors = ButtonDefaults.filledTonalButtonColors(containerColor = TrackColor, contentColor = TextMain),
                                contentPadding = PaddingValues(0.dp)
                            ) { Text("LOCK", fontSize = 10.sp, fontWeight = FontWeight.Black) }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RallyMetric("TRACK", activeTrack?.let { formatDistance(it.distanceKm) } ?: "—", Modifier.weight(1f))
                            if (navigationMode == "TRACK") {
                                val shownOffTrackDistance = distanceFromTrack ?: navigationDistance.takeIf { it.isFinite() }?.toDouble()
                                RallyPrimaryMetric("OFF TRACK", shownOffTrackDistance?.let { if (it < 1000) "${it.toInt()} m" else "${"%.1f".format(Locale.US, it / 1000)} km" } ?: "—", Modifier.weight(1f))
                            } else {
                                RallyPrimaryMetric("TO DESTINATION", destinationDistance?.let { if (it < 1000) "${it.toInt()} m" else "${"%.1f".format(Locale.US, it / 1000)} km" } ?: "TAP MAP", Modifier.weight(1f))
                            }
                            RallyMetric("GPS SPEED", currentLocation?.let { "${(it.speed * 3.6f).toInt()} km/h" } ?: "—", Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RallyButton(if (showPoiLayer) "POI ON" else "POI OFF", Modifier.weight(1f).height(44.dp), active = showPoiLayer) {
                                showPoiLayer = !showPoiLayer
                                showControls()
                            }
                            RallyButton("TRACK FOLLOW", Modifier.weight(1f).height(44.dp), active = navigationMode == "TRACK") {
                                navigationMode = "TRACK"
                                destination = null
                                showControls()
                            }
                            RallyButton("POINT TO POINT", Modifier.weight(1f).height(44.dp), active = navigationMode == "POINT") {
                                navigationMode = "POINT"
                                followTrack = false
                                status = "Tap the map to select a destination point."
                                showControls()
                            }
                            if (navigationMode == "POINT" && destination != null) {
                                RallyButton("CLEAR", Modifier.height(44.dp)) { destination = null; showControls() }
                            }
                        }
                        if (navigationMode == "POINT" && destination != null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                RallyPrimaryMetric("BEARING", destinationBearing?.let { "${it.toInt()}°" } ?: "—", Modifier.weight(1f))
                                RallyMetric("DISTANCE", destinationDistance?.let { "${it.toInt()} m" } ?: "—", Modifier.weight(1f))
                                RallyMetric("ALTITUDE", currentLocation?.takeIf { it.hasAltitude() }?.let { "${it.altitude.toInt()} m" } ?: "—", Modifier.weight(1f))
                            }
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                RallyMetric("ALTITUDE", currentLocation?.takeIf { it.hasAltitude() }?.let { "${it.altitude.toInt()} m" } ?: "—", Modifier.weight(1f))
                                RallyPrimaryMetric("COMPASS", if (!compassHeading.isNaN()) "${compassHeading.toInt()}°" else currentLocation?.takeIf { it.hasBearing() && it.speed > 1.5f }?.let { "${it.bearing.toInt()}°" } ?: "—", Modifier.weight(1f))
                            }
                        }
                    }

                    Row(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        RallyButton("−", Modifier.size(64.dp)) { mapZoomRequest--; showControls() }
                        RallyButton("GPS", Modifier.weight(1f).height(64.dp)) {
                            if (!locationAllowed) permissionLauncher.launch(locationPermissions())
                            else { recenterRequest++; showControls() }
                        }
                        RallyButton("+", Modifier.size(64.dp)) { mapZoomRequest++; showControls() }
                        RallyButton(if (followTrack) "PAUSE" else "FOLLOW", Modifier.weight(1f).height(64.dp), active = followTrack) {
                            if (navigationMode != "TRACK") {
                                status = "Switch to Track Follow mode to follow an imported GPX track."
                            } else if (activeTrack == null || activeTrackPath == null) {
                                status = "Import a GPX track before following it."
                            } else if (followTrack) {
                                TrackNavigationService.stop(context)
                                followTrack = false
                                status = "Background GPX navigation stopped."
                            } else if (!hasLocationPermission(context)) {
                                pendingTrackFollow = true
                                permissionLauncher.launch(locationPermissions())
                            } else {
                                try {
                                    TrackNavigationService.start(context, activeTrackPath!!)
                                    followTrack = true
                                    status = "Background GPX navigation started. Off-track warnings begin at 50 metres."
                                } catch (error: Exception) {
                                    followTrack = false
                                    status = "Could not start background navigation: ${error.message}"
                                }
                            }
                            showControls()
                        }
                    }
                }

                if (touchLocked) {
                    Box(
                        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.93f))
                            .pointerInput(touchLocked) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val started = SystemClock.elapsedRealtime()
                                    var released = false
                                    while (!released) {
                                        val event = awaitPointerEvent()
                                        event.changes.forEach { it.consume() }
                                        released = event.changes.none { it.pressed }
                                    }
                                    if (SystemClock.elapsedRealtime() - started >= 3000L) touchLocked = false
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Text("TOUCHSCREEN LOCKED", color = Signal, fontSize = 24.sp, fontWeight = FontWeight.Black)
                            Text("Press and hold anywhere for 3 seconds to unlock.", color = TextMain, fontSize = 16.sp)
                            Text("Android system navigation remains available.", color = TextMuted, fontSize = 12.sp)
                        }
                    }
                }

                Surface(
                    Modifier.align(Alignment.BottomEnd).padding(8.dp),
                    color = Panel.copy(alpha = 0.88f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        "© OpenStreetMap contributors",
                        Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        color = TextMuted,
                        fontSize = 9.sp
                    )
                }

                if (status.isNotBlank()) {
                    Surface(
                        Modifier.align(Alignment.BottomStart).padding(8.dp),
                        color = Panel.copy(alpha = 0.9f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(status, Modifier.padding(horizontal = 9.dp, vertical = 6.dp), color = TextMuted, fontSize = 10.sp)
                    }
                }
                if (importBusy) {
                    CircularProgressIndicator(
                        Modifier.align(Alignment.Center).size(48.dp),
                        color = Signal,
                        strokeWidth = 4.dp
                    )
                }
            }

            "TRACKS" -> TracksScreen(
                activeTrack = activeTrack,
                trackFiles = savedTracks,
                activeTrackPath = activeTrackPath,
                reversed = reversed,
                status = status,
                busy = importBusy,
                onImport = { gpxPicker.launch(arrayOf("application/gpx+xml", "application/xml", "text/xml", "*/*")) },
                onSelectTrack = { file ->
                    scope.launch {
                        try {
                            val selected = withContext(Dispatchers.IO) {
                                FileInputStream(file).use { parseGpx(it, file.name) }
                            }
                            if (followTrack) TrackNavigationService.stop(context)
                            activeTrackPath = file.absolutePath
                            activeTrack = selected
                            preferences.edit().putString(ACTIVE_TRACK, activeTrackPath).apply()
                            reversed = false
                            followTrack = false
                            status = "Selected ${file.name}"
                        } catch (error: Exception) {
                            status = "Could not open track: ${error.message}"
                        }
                    }
                },
                onReverse = {
                    reversed = !reversed
                    status = if (reversed) "Track direction reversed. Original GPX remains unchanged." else "Original GPX direction restored."
                },
                onExport = {
                    val path = activeTrackPath
                    if (path != null) {
                        pendingExportPath = path
                        exportPicker.launch(File(path).name)
                    }
                },
                onBack = { ui.selectedTab = "MAP" }
            )

            "RECORD" -> RecordingScreen(
                recordingName = recordingName,
                onNameChange = { recordingName = it },
                state = recordingState,
                status = recordingStatus,
                points = recordingPoints,
                distanceMeters = recordingDistance,
                elapsedMillis = recordingElapsed,
                recordings = savedRecordings,
                lastPath = lastRecordingPath,
                onStart = {
                    if (hasLocationPermission(context)) {
                        RecordingService.start(context, recordingName)
                    } else {
                        pendingRecordName = recordingName
                        permissionLauncher.launch(locationPermissions())
                    }
                },
                onPause = { RecordingService.pause(context) },
                onResume = { RecordingService.resume(context) },
                onStop = {
                    RecordingService.stop(context)
                    scope.launch { delay(800); savedRecordings = withContext(Dispatchers.IO) { listRecordingFiles(context) } }
                },
                onExport = { file ->
                    pendingExportPath = file.absolutePath
                    exportPicker.launch(file.name)
                },
                onBack = { ui.selectedTab = "MAP" }
            )

            "POI" -> OfflinePoiScreen(
                pois = offlinePois,
                categories = poiCategories,
                radiusMeters = poiRadius,
                currentLocation = currentLocation,
                busy = importBusy,
                status = status,
                onCategoryChange = { category, enabled ->
                    poiCategories = if (enabled) poiCategories + category else poiCategories - category
                },
                onRadiusChange = { poiRadius = it },
                onSelectPoi = { poi ->
                    destination = TrackPoint(poi.latitude, poi.longitude)
                    navigationMode = "POINT"
                    followTrack = false
                    ui.selectedTab = "MAP"
                    status = "Destination set to ${poi.name}. Straight-line guidance only."
                    showControls()
                },
                onDownload = {
                    val location = currentLocation
                    if (location == null) {
                        status = "Waiting for a GPS fix. Enable location and try again."
                        if (!locationAllowed) permissionLauncher.launch(locationPermissions())
                    } else if (poiCategories.isEmpty()) {
                        status = "Select at least one POI category."
                    } else {
                        importBusy = true
                        scope.launch {
                            try {
                                val downloaded = withContext(Dispatchers.IO) {
                                    OfflinePoiStore.downloadAround(
                                        location.latitude,
                                        location.longitude,
                                        poiRadius,
                                        poiCategories
                                    )
                                }
                                val merged = (offlinePois.associateBy { it.id } + downloaded.associateBy { it.id })
                                    .values
                                    .take(20000)
                                    .toList()
                                withContext(Dispatchers.IO) { OfflinePoiStore.save(context, merged) }
                                offlinePois = merged
                                status = "${downloaded.size} POIs downloaded. ${merged.size} are stored locally for offline use."
                            } catch (error: Exception) {
                                status = "POI download failed: ${error.message ?: "Overpass error"}"
                            } finally {
                                importBusy = false
                            }
                        }
                    }
                },
                onBack = { ui.selectedTab = "MAP" }
            )

            "MAPS" -> MapsScreen(
                currentMap = activeMapPath?.let { File(it) },
                maps = savedMaps,
                busy = importBusy,
                status = status,
                downloadUrl = mapDownloadUrl,
                onDownloadUrlChange = { mapDownloadUrl = it },
                onImport = { mapPicker.launch(arrayOf("*/*")) },
                onDownload = {
                    if (mapDownloadUrl.isBlank()) {
                        status = "Paste a direct HTTPS link to a Mapsforge .map file or a ZIP containing one."
                    } else {
                        importBusy = true
                        scope.launch {
                            try {
                                val downloaded = withContext(Dispatchers.IO) { downloadMapFile(context, mapDownloadUrl) }
                                activeMapPath = downloaded.absolutePath
                                preferences.edit().putString(ACTIVE_MAP, activeMapPath).apply()
                                savedMaps = listMapFiles(context)
                                status = "Offline map downloaded: ${downloaded.name}"
                                ui.selectedTab = "MAP"
                                showControls()
                            } catch (error: Exception) {
                                status = "Map download failed: ${error.message ?: "download error"}"
                            } finally {
                                importBusy = false
                            }
                        }
                    }
                },
                onSelect = { file ->
                    activeMapPath = file.absolutePath
                    preferences.edit().putString(ACTIVE_MAP, activeMapPath).apply()
                    status = "Active map: ${file.name}"
                    ui.selectedTab = "MAP"
                },
                onBack = { ui.selectedTab = "MAP" }
            )

            else -> SettingsScreen(
                onBack = { ui.selectedTab = "MAP" },
                onImportMap = { ui.selectedTab = "MAPS" },
                onImportTrack = { ui.selectedTab = "TRACKS" }
            )
        }
    }
}

@Composable
private fun OfflineMapCanvas(
    mapPath: String?,
    trackPoints: List<TrackPoint>,
    waypoints: List<TrackWaypoint>,
    currentLocation: Location?,
    destination: TrackPoint?,
    followGps: Boolean,
    zoomRequest: Int,
    recenterRequest: Int,
    onMapTouched: () -> Unit,
    onMapTap: (TrackPoint) -> Unit,
    onMapError: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val latestTouch by rememberUpdatedState(onMapTouched)
    val latestError by rememberUpdatedState(onMapError)
    val latestTrack by rememberUpdatedState(trackPoints)
    val latestWaypoints by rememberUpdatedState(waypoints)
    val latestLocation by rememberUpdatedState(currentLocation)
    val latestDestination by rememberUpdatedState(destination)
    val latestMapTap by rememberUpdatedState(onMapTap)
    val latestFollow by rememberUpdatedState(followGps)
    val lastZoom by rememberUpdatedState(zoomRequest)
    val lastRecenter by rememberUpdatedState(recenterRequest)

    key(mapPath) {
        AndroidView(
            modifier = modifier,
            factory = { context ->
                MapView(context).apply {
                    tag = MapOverlayState()
                    setBuiltInZoomControls(false)
                    mapScaleBar.setVisible(true)
                    if (mapPath != null && File(mapPath).exists()) {
                        try {
                            val file = File(mapPath)
                            val cache = AndroidUtil.createTileCache(
                                context,
                                "terratrack_${file.name.hashCode()}",
                                model.displayModel.tileSize,
                                1f,
                                model.frameBufferModel.overdrawFactor
                            )
                            val dataStore = MapFile(file)
                            (tag as MapOverlayState).mapDataStore = dataStore
                            val layer = TileRendererLayer(cache, dataStore, model.mapViewPosition, AndroidGraphicFactory.INSTANCE)
                            layer.setXmlRenderTheme(MapsforgeThemes.MOTORIDER)
                            layerManager.layers.add(layer)
                            setCenter(dataStore.startPosition())
                            setZoomLevel(dataStore.startZoomLevel())
                        } catch (error: Exception) {
                            latestError("Unable to open offline map: ${error.message ?: "invalid Mapsforge map"}")
                            setCenter(LatLong(39.6, -8.0))
                            setZoomLevel(6.toByte())
                        }
                    } else {
                        setCenter(LatLong(39.6, -8.0))
                        setZoomLevel(6.toByte())
                    }
                    val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop.toFloat()
                    setOnTouchListener { map, event ->
                        val state = map.tag as? MapOverlayState
                        when (event.actionMasked) {
                            android.view.MotionEvent.ACTION_DOWN -> {
                                latestTouch()
                                state?.downX = event.x
                                state?.downY = event.y
                            }
                            android.view.MotionEvent.ACTION_UP -> {
                                val dx = event.x - (state?.downX ?: event.x)
                                val dy = event.y - (state?.downY ?: event.y)
                                val isTap = event.pointerCount == 1 &&
                                    event.eventTime - event.downTime < 450L &&
                                    dx * dx + dy * dy <= touchSlop * touchSlop
                                if (isTap) {
                                    try {
                                        val tapped = mapViewProjection.fromPixels(event.x.toDouble(), event.y.toDouble())
                                        latestMapTap(TrackPoint(tapped.latitude, tapped.longitude))
                                    } catch (_: Exception) { }
                                }
                            }
                        }
                        false
                    }
                }
            },
            update = { view ->
                val overlayState = (view.tag as? MapOverlayState) ?: MapOverlayState().also { view.tag = it }
                val signature = 31 * (31 * latestTrack.hashCode() + latestWaypoints.hashCode()) + (latestDestination?.hashCode() ?: 0)
                if (overlayState.trackSignature != signature) {
                    overlayState.route?.let { view.layerManager.layers.remove(it) }
                    overlayState.route = null
                    overlayState.directionArrows.forEach { view.layerManager.layers.remove(it) }
                    overlayState.directionArrows.clear()
                    if (latestTrack.size > 1) {
                        val paint = AndroidGraphicFactory.INSTANCE.createPaint().apply {
                            setColor(AndroidGraphicFactory.INSTANCE.createColor(org.mapsforge.core.graphics.Color.RED))
                            setStrokeWidth(8f * view.model.displayModel.scaleFactor)
                            setStyle(Style.STROKE)
                        }
                        val line = Polyline(paint, AndroidGraphicFactory.INSTANCE)
                        line.setPoints(latestTrack.map { LatLong(it.latitude, it.longitude) })
                        view.layerManager.layers.add(line)
                        overlayState.route = line

                        val arrowPaint = AndroidGraphicFactory.INSTANCE.createPaint().apply {
                            setColor(AndroidGraphicFactory.INSTANCE.createColor(255, 215, 255, 53))
                            setStrokeWidth(3.5f * view.model.displayModel.scaleFactor)
                            setStyle(Style.STROKE)
                        }
                        buildDirectionArrows(latestTrack).forEach { arrowPoints ->
                            val arrow = Polyline(arrowPaint, AndroidGraphicFactory.INSTANCE)
                            arrow.setPoints(arrowPoints.map { LatLong(it.latitude, it.longitude) })
                            view.layerManager.layers.add(arrow)
                            overlayState.directionArrows.add(arrow)
                        }
                    }
                    overlayState.waypointMarkers.forEach { view.layerManager.layers.remove(it) }
                    overlayState.waypointMarkers.clear()
                    if (latestWaypoints.isNotEmpty()) {
                        val poiFill = AndroidGraphicFactory.INSTANCE.createPaint().apply {
                            setColor(AndroidGraphicFactory.INSTANCE.createColor(255, 215, 255, 53))
                            setStyle(Style.FILL)
                        }
                        val poiStroke = AndroidGraphicFactory.INSTANCE.createPaint().apply {
                            setColor(AndroidGraphicFactory.INSTANCE.createColor(org.mapsforge.core.graphics.Color.BLACK))
                            setStrokeWidth(1.5f * view.model.displayModel.scaleFactor)
                            setStyle(Style.STROKE)
                        }
                        latestWaypoints.take(1200).forEach { waypoint ->
                            val poi = FixedPixelCircle(LatLong(waypoint.latitude, waypoint.longitude), 8f, poiFill, poiStroke)
                            view.layerManager.layers.add(poi)
                            overlayState.waypointMarkers.add(poi)
                        }
                    }
                    overlayState.trackSignature = signature
                }

                overlayState.destinationMarker?.let { view.layerManager.layers.remove(it) }
                overlayState.destinationMarker = null
                overlayState.destinationLine?.let { view.layerManager.layers.remove(it) }
                overlayState.destinationLine = null
                latestDestination?.let { target ->
                    val targetFill = AndroidGraphicFactory.INSTANCE.createPaint().apply {
                        setColor(AndroidGraphicFactory.INSTANCE.createColor(255, 215, 255, 53))
                        setStyle(Style.FILL)
                    }
                    val targetOutline = AndroidGraphicFactory.INSTANCE.createPaint().apply {
                        setColor(AndroidGraphicFactory.INSTANCE.createColor(org.mapsforge.core.graphics.Color.BLACK))
                        setStrokeWidth(2f * view.model.displayModel.scaleFactor)
                        setStyle(Style.STROKE)
                    }
                    val targetMarker = FixedPixelCircle(LatLong(target.latitude, target.longitude), 12f, targetFill, targetOutline)
                    view.layerManager.layers.add(targetMarker)
                    overlayState.destinationMarker = targetMarker
                    latestLocation?.let { location ->
                        val bearingPaint = AndroidGraphicFactory.INSTANCE.createPaint().apply {
                            setColor(AndroidGraphicFactory.INSTANCE.createColor(255, 0, 255, 255))
                            setStrokeWidth(4f * view.model.displayModel.scaleFactor)
                            setStyle(Style.STROKE)
                        }
                        val bearingLine = Polyline(bearingPaint, AndroidGraphicFactory.INSTANCE)
                        bearingLine.setPoints(listOf(
                            LatLong(location.latitude, location.longitude),
                            LatLong(target.latitude, target.longitude)
                        ))
                        view.layerManager.layers.add(bearingLine)
                        overlayState.destinationLine = bearingLine
                    }
                }

                overlayState.marker?.let { view.layerManager.layers.remove(it) }
                overlayState.marker = null
                latestLocation?.let { location ->
                    val paint = AndroidGraphicFactory.INSTANCE.createPaint().apply {
                        setColor(AndroidGraphicFactory.INSTANCE.createColor(org.mapsforge.core.graphics.Color.BLUE))
                        setStyle(Style.FILL)
                    }
                    val outline = AndroidGraphicFactory.INSTANCE.createPaint().apply {
                        setColor(AndroidGraphicFactory.INSTANCE.createColor(org.mapsforge.core.graphics.Color.WHITE))
                        setStrokeWidth(2f * view.model.displayModel.scaleFactor)
                        setStyle(Style.STROKE)
                    }
                    val circle = FixedPixelCircle(LatLong(location.latitude, location.longitude), 11f, paint, outline)
                    view.layerManager.layers.add(circle)
                    overlayState.marker = circle
                    if (latestFollow) view.setCenter(LatLong(location.latitude, location.longitude))
                }

                if (lastZoom != overlayState.zoomRequest) {
                    val delta = (lastZoom - overlayState.zoomRequest).coerceIn(-8, 8)
                    repeat(kotlin.math.abs(delta)) {
                        if (delta > 0) view.model.mapViewPosition.zoomIn(false)
                        else view.model.mapViewPosition.zoomOut(false)
                    }
                    overlayState.zoomRequest = lastZoom
                }
                if (lastRecenter != overlayState.recenterRequest) {
                    latestLocation?.let { view.setCenter(LatLong(it.latitude, it.longitude)) }
                    overlayState.recenterRequest = lastRecenter
                }
            },
            onRelease = { view ->
                view.destroyAll()
                (view.tag as? MapOverlayState)?.mapDataStore?.close()
                view.tag = null
            }
        )
    }
}

@Composable
private fun TracksScreen(
    activeTrack: GpxTrack?,
    trackFiles: List<File>,
    activeTrackPath: String?,
    reversed: Boolean,
    status: String,
    busy: Boolean,
    onImport: () -> Unit,
    onSelectTrack: (File) -> Unit,
    onReverse: () -> Unit,
    onExport: () -> Unit,
    onBack: () -> Unit
) {
    Column(Modifier.fillMaxSize().background(Night).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ScreenHeader("TRACK LIBRARY", onBack)
        Button(onClick = onImport, modifier = Modifier.fillMaxWidth().height(58.dp), enabled = !busy) {
            Text(if (busy) "IMPORTING…" else "IMPORT GPX FILE", fontWeight = FontWeight.Black)
        }
        if (activeTrack == null) {
            Text("No active GPX track. Import a track from your device.", color = TextMuted)
        } else {
            Surface(color = Panel, shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(activeTrack.name, color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(formatDistance(activeTrack.distanceKm), color = Signal, fontSize = 30.sp, fontWeight = FontWeight.Black)
                    Text("${activeTrack.points.size} track points · ${activeTrack.waypoints.size} waypoints", color = TextMuted)
                    if (activeTrack.waypoints.isNotEmpty()) {
                        Text("OFFLINE WAYPOINTS", color = Signal, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        activeTrack.waypoints.take(12).forEach { waypoint ->
                            Text(
                                "• ${waypoint.name}" + (waypoint.category?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                                color = TextMuted,
                                fontSize = 12.sp
                            )
                        }
                        if (activeTrack.waypoints.size > 12) Text("+ ${activeTrack.waypoints.size - 12} more", color = TextMuted, fontSize = 11.sp)
                    }
                    Text(if (reversed) "Direction: REVERSED" else "Direction: ORIGINAL", color = TextMain, fontWeight = FontWeight.Bold)
                    OutlinedButton(onClick = onReverse, modifier = Modifier.fillMaxWidth().height(54.dp)) {
                        Text(if (reversed) "RESTORE ORIGINAL DIRECTION" else "REVERSE TRACK DIRECTION")
                    }
                    OutlinedButton(onClick = onExport, modifier = Modifier.fillMaxWidth().height(54.dp)) {
                        Text("EXPORT GPX")
                    }
                    Text("The original imported GPX remains unchanged. No automatic rerouting is used in track-following mode.", color = TextMuted, fontSize = 12.sp)
                }
            }
        }
        Text("SAVED GPX FILES", color = Signal, fontSize = 12.sp, fontWeight = FontWeight.Black)
        if (trackFiles.isEmpty()) {
            Text("No imported GPX files stored yet.", color = TextMuted, fontSize = 12.sp)
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                items(trackFiles, key = { it.absolutePath }) { file ->
                    Surface(color = if (file.absolutePath == activeTrackPath) Panel2 else Panel, shape = RoundedCornerShape(8.dp)) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(file.name, Modifier.weight(1f), color = TextMain, fontSize = 11.sp)
                            TextButton(onClick = { onSelectTrack(file) }) {
                                Text(if (file.absolutePath == activeTrackPath) "ACTIVE" else "OPEN", color = Signal)
                            }
                        }
                    }
                }
            }
        }
        Text(status, color = TextMuted, fontSize = 11.sp)
    }
}

@Composable
private fun RecordingScreen(
    recordingName: String,
    onNameChange: (String) -> Unit,
    state: String,
    status: String,
    points: Int,
    distanceMeters: Long,
    elapsedMillis: Long,
    recordings: List<File>,
    lastPath: String?,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onExport: (File) -> Unit,
    onBack: () -> Unit
) {
    val active = state == RecordingService.STATE_RECORDING
    val paused = state == RecordingService.STATE_PAUSED
    val elapsedSeconds = elapsedMillis / 1000L
    val elapsed = "%02d:%02d:%02d".format(elapsedSeconds / 3600, (elapsedSeconds % 3600) / 60, elapsedSeconds % 60)
    Column(Modifier.fillMaxSize().background(Night).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeader("GPX RECORDER", onBack)
        Text("Record your own off-road tracks", color = TextMuted, fontSize = 14.sp)
        OutlinedTextField(
            value = recordingName,
            onValueChange = onNameChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Track name") },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextMain,
                unfocusedTextColor = TextMain,
                focusedBorderColor = Signal,
                unfocusedBorderColor = TextMuted,
                focusedLabelColor = Signal,
                unfocusedLabelColor = TextMuted
            )
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RallyMetric("DISTANCE", "${"%.2f".format(distanceMeters / 1000.0)} km", Modifier.weight(1f))
            RallyMetric("POINTS", points.toString(), Modifier.weight(1f))
            RallyMetric("DURATION", elapsed, Modifier.weight(1f))
        }
        Surface(color = Panel, shape = RoundedCornerShape(10.dp)) {
            Text(status, Modifier.fillMaxWidth().padding(12.dp), color = if (active) Signal else TextMuted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        when {
            active -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onPause, modifier = Modifier.weight(1f).height(64.dp)) { Text("PAUSE", fontWeight = FontWeight.Black) }
                Button(onClick = onStop, modifier = Modifier.weight(1f).height(64.dp), colors = ButtonDefaults.buttonColors(containerColor = TrackColor, contentColor = TextMain)) { Text("STOP & SAVE", fontWeight = FontWeight.Black) }
            }
            paused -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onResume, modifier = Modifier.weight(1f).height(64.dp)) { Text("RESUME", fontWeight = FontWeight.Black) }
                Button(onClick = onStop, modifier = Modifier.weight(1f).height(64.dp), colors = ButtonDefaults.buttonColors(containerColor = TrackColor, contentColor = TextMain)) { Text("STOP & SAVE", fontWeight = FontWeight.Black) }
            }
            else -> Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(68.dp)) {
                Text("●  START GPX RECORDING", fontSize = 17.sp, fontWeight = FontWeight.Black)
            }
        }
        Text("Recording continues with the screen off while the foreground service is active. Android displays a persistent notification.", color = TextMuted, fontSize = 12.sp)
        Text("SAVED RECORDINGS", color = Signal, fontSize = 14.sp, fontWeight = FontWeight.Black)
        if (recordings.isEmpty()) {
            Text("No saved recordings yet.", color = TextMuted, fontSize = 13.sp)
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(recordings, key = { it.absolutePath }) { file ->
                    Surface(color = Panel, shape = RoundedCornerShape(8.dp)) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(file.name, Modifier.weight(1f), color = TextMain, fontSize = 12.sp)
                            TextButton(onClick = { onExport(file) }) { Text("EXPORT", color = Signal, fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }
        }
        if (lastPath != null && File(lastPath).exists()) {
            TextButton(onClick = { onExport(File(lastPath)) }) { Text("EXPORT LAST RECORDING", color = Signal) }
        }
    }
}

@Composable
private fun OfflinePoiScreen(
    pois: List<OfflinePoi>,
    categories: Set<String>,
    radiusMeters: Int,
    currentLocation: Location?,
    busy: Boolean,
    status: String,
    onCategoryChange: (String, Boolean) -> Unit,
    onRadiusChange: (Int) -> Unit,
    onDownload: () -> Unit,
    onSelectPoi: (OfflinePoi) -> Unit,
    onBack: () -> Unit
) {
    val categoryOptions = listOf(
        "fuel" to "FUEL STATIONS",
        "water" to "DRINKING WATER & SPRINGS",
        "repair" to "WORKSHOPS",
        "camping" to "CAMPSITES & CARAVAN SITES"
    )
    val visiblePois = pois.filter { it.category in categories }
    Column(
        Modifier.fillMaxSize().background(Night).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ScreenHeader("OFFLINE POI", onBack)
        Text(
            "Download useful places around your current GPS position. After downloading, these locations remain stored on this device and appear on the map without mobile data.",
            color = TextMuted,
            fontSize = 13.sp
        )
        Surface(color = Panel, shape = RoundedCornerShape(10.dp)) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("CURRENT GPS POSITION", color = Signal, fontSize = 11.sp, fontWeight = FontWeight.Black)
                Text(
                    currentLocation?.let { "${"%.5f".format(Locale.US, it.latitude)}, ${"%.5f".format(Locale.US, it.longitude)}" }
                        ?: "No GPS fix yet. Enable location on the map screen.",
                    color = TextMain,
                    fontSize = 14.sp
                )
            }
        }
        Text("CATEGORIES TO DOWNLOAD", color = Signal, fontSize = 12.sp, fontWeight = FontWeight.Black)
        categoryOptions.forEach { (key, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = key in categories,
                    onCheckedChange = { onCategoryChange(key, it) },
                    colors = CheckboxDefaults.colors(checkedColor = Signal, checkmarkColor = Night)
                )
                Text(label, color = TextMain, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text("SEARCH RADIUS", color = Signal, fontSize = 12.sp, fontWeight = FontWeight.Black)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(10000, 25000, 50000).forEach { radius ->
                OutlinedButton(
                    onClick = { onRadiusChange(radius) },
                    modifier = Modifier.weight(1f).height(46.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (radiusMeters == radius) Signal else Panel,
                        contentColor = if (radiusMeters == radius) Night else TextMain
                    )
                ) {
                    Text("${radius / 1000} KM", fontWeight = FontWeight.Black)
                }
            }
        }
        Button(
            onClick = onDownload,
            modifier = Modifier.fillMaxWidth().height(58.dp),
            enabled = !busy && categories.isNotEmpty()
        ) {
            Text(if (busy) "DOWNLOADING POI…" else "DOWNLOAD AROUND GPS", fontWeight = FontWeight.Black)
        }
        Text(
            "OpenStreetMap data via Overpass API. A download sends your current coordinates and search radius to Overpass. The saved POIs are then available offline.",
            color = TextMuted,
            fontSize = 10.sp
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("SAVED LOCATIONS", Modifier.weight(1f), color = Signal, fontSize = 12.sp, fontWeight = FontWeight.Black)
            Text("${visiblePois.size} shown / ${pois.size} stored", color = TextMuted, fontSize = 10.sp)
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            items(visiblePois, key = { it.id }) { poi ->
                Surface(color = Panel, shape = RoundedCornerShape(8.dp)) {
                    Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            when (poi.category) {
                                "fuel" -> "F"
                                "water" -> "W"
                                "repair" -> "R"
                                else -> "C"
                            },
                            color = Signal,
                            fontWeight = FontWeight.Black,
                            fontSize = 17.sp
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(poi.name, color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text(poi.category.uppercase(Locale.US), color = TextMuted, fontSize = 9.sp)
                        }
                        TextButton(onClick = { onSelectPoi(poi) }) { Text("GO", color = Signal, fontWeight = FontWeight.Black) }
                    }
                }
            }
        }
        Text(status, color = TextMuted, fontSize = 10.sp)
    }
}

@Composable
private fun MapsScreen(
    currentMap: File?,
    maps: List<File>,
    busy: Boolean,
    status: String,
    downloadUrl: String,
    onDownloadUrlChange: (String) -> Unit,
    onImport: () -> Unit,
    onDownload: () -> Unit,
    onSelect: (File) -> Unit,
    onBack: () -> Unit
) {
    Column(Modifier.fillMaxSize().background(Night).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeader("OFFLINE MAPS", onBack)
        Text("Import Mapsforge .map files, including compatible OpenAndroMaps regional maps. Map data is stored locally and can be read without mobile coverage.", color = TextMuted, fontSize = 13.sp)
        Button(onClick = onImport, modifier = Modifier.fillMaxWidth().height(58.dp), enabled = !busy) {
            Text(if (busy) "IMPORTING MAP…" else "IMPORT OFFLINE MAP FILE", fontWeight = FontWeight.Black)
        }
        OutlinedTextField(
            value = downloadUrl,
            onValueChange = onDownloadUrlChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Direct HTTPS map or ZIP URL") },
            placeholder = { Text("https://…/region.map") },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextMain,
                unfocusedTextColor = TextMain,
                focusedBorderColor = Signal,
                unfocusedBorderColor = TextMuted,
                focusedLabelColor = Signal,
                unfocusedLabelColor = TextMuted
            )
        )
        OutlinedButton(onClick = onDownload, modifier = Modifier.fillMaxWidth().height(54.dp), enabled = !busy) {
            Text("DOWNLOAD FOR OFFLINE USE", fontWeight = FontWeight.Bold)
        }
        Text("Use a direct download link from a map provider. TerraTrack does not host or mirror map data.", color = TextMuted, fontSize = 11.sp)
        Text("ACTIVE MAP", color = Signal, fontSize = 13.sp, fontWeight = FontWeight.Black)
        Text(currentMap?.name ?: "No offline map selected", color = TextMain, fontSize = 15.sp)
        Text("MAPS ON THIS DEVICE", color = Signal, fontSize = 13.sp, fontWeight = FontWeight.Black)
        if (maps.isEmpty()) Text("No map files imported yet.", color = TextMuted)
        else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(maps, key = { it.absolutePath }) { file ->
                Surface(color = Panel, shape = RoundedCornerShape(8.dp)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(file.name, Modifier.weight(1f), color = TextMain, fontSize = 13.sp)
                        TextButton(onClick = { onSelect(file) }) { Text(if (currentMap?.absolutePath == file.absolutePath) "ACTIVE" else "USE MAP", color = Signal) }
                    }
                }
            }
        }
        Text(status, color = TextMuted, fontSize = 11.sp)
    }
}

@Composable
private fun SettingsScreen(
    onBack: () -> Unit,
    onImportMap: () -> Unit,
    onImportTrack: () -> Unit
) {
    Column(Modifier.fillMaxSize().background(Night).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeader("SETTINGS", onBack)
        SettingRow("Supported vehicles", "Adventure motorcycles, SSV and UTV")
        SettingRow("Navigation mode", "Original GPX track following. No automatic recalculation.")
        SettingRow("Maps", "Offline Mapsforge vector maps")
        SettingRow("Rally controls", "Large controls, zoom keys and GPS recenter")
        SettingRow("Touch protection", "Rain lock with 3-second hold to unlock")
        SettingRow("Bluetooth remotes", "Volume keys: zoom. Media next: recenter. Media previous: switch Map / Recorder.")
        OutlinedButton(onClick = onImportMap, modifier = Modifier.fillMaxWidth()) { Text("MANAGE OFFLINE MAPS") }
        OutlinedButton(onClick = onImportTrack, modifier = Modifier.fillMaxWidth()) { Text("OPEN GPX TRACK LIBRARY") }
        Text("Offline POI packages, turn-point warnings, richer compass presentation and community sharing are not yet included in this build.", color = TextMuted, fontSize = 12.sp)
    }
}

@Composable
private fun ScreenHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), color = Signal, fontSize = 22.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
        TextButton(onClick = onBack) { Text("MAP", color = TextMain, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun RallyMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier, color = Panel, shape = RoundedCornerShape(10.dp)) {
        Column(Modifier.padding(9.dp)) {
            Text(label, color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(value, color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.Black, maxLines = 1)
        }
    }
}

@Composable
private fun RallyPrimaryMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier, color = Panel, shape = RoundedCornerShape(10.dp)) {
        Column(Modifier.padding(9.dp)) {
            Text(label, color = Signal, fontSize = 11.sp, fontWeight = FontWeight.Black)
            Text(value, color = TextMain, fontSize = 32.sp, fontWeight = FontWeight.Black, maxLines = 1)
        }
    }
}

@Composable
private fun RallyButton(label: String, modifier: Modifier = Modifier, active: Boolean = false, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (active) TrackColor else Signal,
            contentColor = Night
        ),
        contentPadding = PaddingValues(4.dp)
    ) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun SettingRow(title: String, value: String) {
    Surface(color = Panel, shape = RoundedCornerShape(9.dp)) {
        Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(value, color = TextMain, fontSize = 14.sp)
        }
    }
}

private fun buildDirectionArrows(points: List<TrackPoint>): List<List<TrackPoint>> {
    if (points.size < 4) return emptyList()
    val stride = maxOf(2, points.size / 90)
    val arrows = ArrayList<List<TrackPoint>>()

    fun offset(origin: TrackPoint, eastMeters: Double, northMeters: Double): TrackPoint {
        val cosLatitude = cos(Math.toRadians(origin.latitude)).coerceAtLeast(0.01)
        return TrackPoint(
            origin.latitude + northMeters / 111320.0,
            origin.longitude + eastMeters / (111320.0 * cosLatitude)
        )
    }

    for (index in stride until points.lastIndex step stride) {
        val before = points[index - 1]
        val tip = points[index]
        val after = points[index + 1]
        val cosLatitude = cos(Math.toRadians(tip.latitude)).coerceAtLeast(0.01)
        val east = (after.longitude - before.longitude) * 111320.0 * cosLatitude
        val north = (after.latitude - before.latitude) * 111320.0
        val length = kotlin.math.sqrt(east * east + north * north)
        if (!length.isFinite() || length < 0.5) continue

        val ux = east / length
        val uy = north / length
        val baseEast = -ux * 13.0
        val baseNorth = -uy * 13.0
        val sideEast = -uy * 5.5
        val sideNorth = ux * 5.5
        val left = offset(tip, baseEast + sideEast, baseNorth + sideNorth)
        val right = offset(tip, baseEast - sideEast, baseNorth - sideNorth)
        arrows += listOf(left, tip)
        arrows += listOf(right, tip)
    }
    return arrows
}

private fun bearingDegrees(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
    val lat1 = Math.toRadians(aLat)
    val lat2 = Math.toRadians(bLat)
    val dLon = Math.toRadians(bLon - aLon)
    val y = kotlin.math.sin(dLon) * kotlin.math.cos(lat2)
    val x = kotlin.math.cos(lat1) * kotlin.math.sin(lat2) -
        kotlin.math.sin(lat1) * kotlin.math.cos(lat2) * kotlin.math.cos(dLon)
    return (Math.toDegrees(kotlin.math.atan2(y, x)) + 360.0) % 360.0
}

private fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

private fun locationPermissions(): Array<String> =
    if (android.os.Build.VERSION.SDK_INT >= 33) {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS
        )
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    }

private fun displayName(context: Context, uri: Uri): String {
    var name = "Imported file"
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (cursor.moveToFirst() && index >= 0) name = cursor.getString(index) ?: name
    }
    return name
}

private fun safeFileName(input: String): String =
    input.replace(Regex("[^A-Za-z0-9._ -]"), "_").replace(Regex("\\s+"), "_").take(100).ifBlank { "imported_file" }

private fun appFolder(context: Context, folderName: String): File {
    val external = context.getExternalFilesDirs(null).filterNotNull()
    val removable = external.firstOrNull { android.os.Environment.isExternalStorageRemovable(it) && it.canWrite() }
    val base = removable ?: context.getExternalFilesDir(null) ?: context.filesDir
    return File(base, folderName).apply { if (!exists()) mkdirs() }
}

private fun copyUriIntoAppFolder(context: Context, uri: Uri, folder: String, fileName: String): File {
    val directory = appFolder(context, folder)
    val clean = if (fileName.contains('.')) fileName else "$fileName.gpx"
    val destination = File(directory, clean)
    context.contentResolver.openInputStream(uri)?.use { input ->
        FileOutputStream(destination).use { output -> input.copyTo(output, 1024 * 1024) }
    } ?: throw IllegalStateException("Could not open selected file.")
    require(destination.length() > 0L) { "Selected file is empty." }
    return destination
}

private fun downloadMapFile(context: Context, address: String): File {
    val url = java.net.URL(address.trim())
    require(url.protocol.equals("https", true)) { "Only HTTPS map downloads are accepted." }
    val connection = (url.openConnection() as java.net.HttpURLConnection).apply {
        connectTimeout = 20000
        readTimeout = 60000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "TerraTrack-Android/0.2.0")
    }
    val directory = appFolder(context, "maps")
    val downloadedFile = try {
        connection.connect()
        require(connection.responseCode in 200..299) { "Map server returned HTTP ${connection.responseCode}." }
        val sourceName = url.path.substringAfterLast('/').ifBlank { "regional_map.map" }
        val isZip = sourceName.endsWith(".zip", true) ||
            connection.contentType?.contains("zip", true) == true
        if (connection.contentLengthLong > 2_000_000_000L) {
            throw IllegalStateException("Map download exceeds the 2 GB safety limit.")
        }

        if (isZip) {
            var extracted: File? = null
            java.util.zip.ZipInputStream(connection.inputStream.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null && extracted == null) {
                    if (!entry.isDirectory && entry.name.endsWith(".map", true)) {
                        val target = File(directory, safeFileName(entry.name.substringAfterLast('/')))
                        var copied = 0L
                        FileOutputStream(target).use { output ->
                            val buffer = ByteArray(1024 * 256)
                            while (true) {
                                val count = zip.read(buffer)
                                if (count < 0) break
                                copied += count
                                if (copied > 2_000_000_000L) throw IllegalStateException("Extracted map exceeds the 2 GB safety limit.")
                                output.write(buffer, 0, count)
                            }
                        }
                        extracted = target
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            extracted ?: throw IllegalStateException("The ZIP archive does not contain a Mapsforge .map file.")
        } else {
            require(sourceName.endsWith(".map", true)) { "The download URL must point to a .map file or ZIP archive." }
            val target = File(directory, safeFileName(sourceName))
            var copied = 0L
            connection.inputStream.use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(1024 * 256)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count
                        if (copied > 2_000_000_000L) throw IllegalStateException("Map download exceeds the 2 GB safety limit.")
                        output.write(buffer, 0, count)
                    }
                }
            }
            target
        }
    } finally {
        connection.disconnect()
    }
    return downloadedFile.also { file ->
        require(file.exists() && file.length() > 1024L) { "Downloaded file is empty or too small to be a map." }
        try {
            val mapData = MapFile(file)
            try { mapData.startPosition() } finally { mapData.close() }
        } catch (error: Exception) {
            file.delete()
            throw IllegalStateException("Downloaded file is not a valid Mapsforge map: ${error.message}")
        }
    }
}

private fun listTrackFiles(context: Context): List<File> =
    context.getExternalFilesDirs(null).filterNotNull()
        .map { File(it, "tracks") }
        .flatMap { it.listFiles()?.filter { file -> file.isFile && file.extension.equals("gpx", true) }.orEmpty() }
        .distinctBy { it.absolutePath }
        .sortedByDescending { it.lastModified() }

private fun listRecordingFiles(context: Context): List<File> =
    context.getExternalFilesDirs(null).filterNotNull()
        .map { File(it, "recordings") }
        .flatMap { it.listFiles()?.filter { file -> file.isFile && file.extension.equals("gpx", true) }.orEmpty() }
        .distinctBy { it.absolutePath }
        .sortedByDescending { it.lastModified() }

private fun listMapFiles(context: Context): List<File> =
    context.getExternalFilesDirs(null).filterNotNull()
        .map { File(it, "maps") }
        .flatMap { it.listFiles()?.filter { file -> file.isFile && file.extension.equals("map", true) }.orEmpty() }
        .distinctBy { it.absolutePath }
        .sortedBy { it.name.lowercase(Locale.ROOT) }

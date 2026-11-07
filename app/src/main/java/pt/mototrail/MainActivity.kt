package com.terratrack

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
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
    var route: Polyline? = null,
    var marker: FixedPixelCircle? = null,
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

    var activeTrack by remember { mutableStateOf<GpxTrack?>(null) }
    var activeTrackPath by remember { mutableStateOf(preferences.getString(ACTIVE_TRACK, null)) }
    var activeMapPath by remember { mutableStateOf(preferences.getString(ACTIVE_MAP, null)) }
    var currentLocation by remember { mutableStateOf<Location?>(null) }
    var locationAllowed by remember { mutableStateOf(hasLocationPermission(context)) }
    var followTrack by remember { mutableStateOf(false) }
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
    var savedMaps by remember { mutableStateOf<List<File>>(emptyList()) }
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
        savedMaps = withContext(Dispatchers.IO) { listMapFiles(context) }
    }

    LaunchedEffect(Unit) {
        while (true) {
            recordingState = preferences.getString(RecordingService.KEY_STATE, RecordingService.STATE_IDLE) ?: RecordingService.STATE_IDLE
            recordingStatus = preferences.getString(RecordingService.KEY_STATUS, "No active recording") ?: "No active recording"
            recordingPoints = preferences.getInt(RecordingService.KEY_POINTS, 0)
            recordingDistance = preferences.getLong(RecordingService.KEY_DISTANCE_METERS, 0L)
            recordingElapsed = preferences.getLong(RecordingService.KEY_ELAPSED, 0L)
            lastRecordingPath = preferences.getString(RecordingService.KEY_LAST_FILE, null)
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

    DisposableEffect(locationAllowed) {
        if (!locationAllowed) {
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

    val visibleTrackPoints = if (reversed) activeTrack?.points?.asReversed().orEmpty() else activeTrack?.points.orEmpty()
    val distanceFromTrack = remember(currentLocation, visibleTrackPoints) {
        currentLocation?.let { distanceToTrackMeters(it.latitude, it.longitude, visibleTrackPoints) }
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
                    waypoints = activeTrack?.waypoints.orEmpty(),
                    currentLocation = currentLocation,
                    followGps = followTrack,
                    zoomRequest = ui.zoomRequest + mapZoomRequest,
                    recenterRequest = ui.recenterRequest + recenterRequest,
                    onMapTouched = { showControls() },
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
                            listOf("TRACKS", "RECORD", "MAPS", "SETTINGS").forEach { tab ->
                                FilledTonalButton(
                                    onClick = { ui.selectedTab = tab; showControls() },
                                    modifier = Modifier.weight(1f).height(48.dp),
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = if (ui.selectedTab == tab) Signal else Panel,
                                        contentColor = if (ui.selectedTab == tab) Night else TextMain
                                    ),
                                    contentPadding = PaddingValues(horizontal = 4.dp)
                                ) {
                                    Text(tab, fontSize = 11.sp, fontWeight = FontWeight.Black)
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
                            RallyMetric("OFF TRACK", distanceFromTrack?.let { if (it < 1000) "${it.toInt()} m" else "${"%.1f".format(it / 1000)} km" } ?: "—", Modifier.weight(1f))
                            RallyMetric("GPS SPEED", currentLocation?.let { "${(it.speed * 3.6f).toInt()} km/h" } ?: "—", Modifier.weight(1f))
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
                            if (activeTrack == null) status = "Import a GPX track before following it."
                            else followTrack = !followTrack
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
                reversed = reversed,
                status = status,
                busy = importBusy,
                onImport = { gpxPicker.launch(arrayOf("application/gpx+xml", "application/xml", "text/xml", "*/*")) },
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

            "MAPS" -> MapsScreen(
                currentMap = activeMapPath?.let { File(it) },
                maps = savedMaps,
                busy = importBusy,
                status = status,
                onImport = { mapPicker.launch(arrayOf("*/*")) },
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
    followGps: Boolean,
    zoomRequest: Int,
    recenterRequest: Int,
    onMapTouched: () -> Unit,
    onMapError: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val latestTouch by rememberUpdatedState(onMapTouched)
    val latestError by rememberUpdatedState(onMapError)
    val latestTrack by rememberUpdatedState(trackPoints)
    val latestWaypoints by rememberUpdatedState(waypoints)
    val latestLocation by rememberUpdatedState(currentLocation)
    val latestFollow by rememberUpdatedState(followGps)
    val lastZoom by rememberUpdatedState(zoomRequest)
    val lastRecenter by rememberUpdatedState(recenterRequest)

    key(mapPath) {
        AndroidView(
            modifier = modifier,
            factory = { context ->
                MapView(context).apply {
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
                            val dataStore = MapFile(FileInputStream(file))
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
                    setOnTouchListener { _, event ->
                        if (event.action == android.view.MotionEvent.ACTION_DOWN) latestTouch()
                        false
                    }
                }
            },
            update = { view ->
                val overlayState = (view.tag as? MapOverlayState) ?: MapOverlayState().also { view.tag = it }
                val signature = 31 * latestTrack.hashCode() + latestWaypoints.hashCode()
                if (overlayState.trackSignature != signature) {
                    overlayState.route?.let { view.layerManager.layers.remove(it) }
                    overlayState.route = null
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
                    }
                    overlayState.waypointMarkers.forEach { view.layerManager.layers.remove(it) }
                    overlayState.waypointMarkers.clear()
                    if (latestWaypoints.isNotEmpty()) {
                        val poiFill = AndroidGraphicFactory.INSTANCE.createPaint().apply {
                            setColor(AndroidGraphicFactory.INSTANCE.createColor(org.mapsforge.core.graphics.Color.YELLOW))
                            setStyle(Style.FILL)
                        }
                        val poiStroke = AndroidGraphicFactory.INSTANCE.createPaint().apply {
                            setColor(AndroidGraphicFactory.INSTANCE.createColor(org.mapsforge.core.graphics.Color.BLACK))
                            setStrokeWidth(1.5f * view.model.displayModel.scaleFactor)
                            setStyle(Style.STROKE)
                        }
                        latestWaypoints.forEach { waypoint ->
                            val poi = FixedPixelCircle(LatLong(waypoint.latitude, waypoint.longitude), 8, poiFill, poiStroke)
                            view.layerManager.layers.add(poi)
                            overlayState.waypointMarkers.add(poi)
                        }
                    }
                    overlayState.trackSignature = signature
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
                    val circle = FixedPixelCircle(LatLong(location.latitude, location.longitude), 11, paint, outline)
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
            onRelease = { view -> view.destroyAll() }
        )
    }
}

@Composable
private fun TracksScreen(
    activeTrack: GpxTrack?,
    reversed: Boolean,
    status: String,
    busy: Boolean,
    onImport: () -> Unit,
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
        Text(status, color = TextMuted, fontSize = 12.sp)
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
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
private fun MapsScreen(
    currentMap: File?,
    maps: List<File>,
    busy: Boolean,
    status: String,
    onImport: () -> Unit,
    onSelect: (File) -> Unit,
    onBack: () -> Unit
) {
    Column(Modifier.fillMaxSize().background(Night).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeader("OFFLINE MAPS", onBack)
        Text("Import Mapsforge .map files, including compatible OpenAndroMaps regional maps. Map data is stored locally and can be read without mobile coverage.", color = TextMuted, fontSize = 13.sp)
        Button(onClick = onImport, modifier = Modifier.fillMaxWidth().height(58.dp), enabled = !busy) {
            Text(if (busy) "IMPORTING MAP…" else "IMPORT OFFLINE MAP FILE", fontWeight = FontWeight.Black)
        }
        Text("ACTIVE MAP", color = Signal, fontSize = 13.sp, fontWeight = FontWeight.Black)
        Text(currentMap?.name ?: "No offline map selected", color = TextMain, fontSize = 15.sp)
        Text("MAPS ON THIS DEVICE", color = Signal, fontSize = 13.sp, fontWeight = FontWeight.Black)
        if (maps.isEmpty()) Text("No map files imported yet.", color = TextMuted)
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            Text(label, color = TextMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text(value, color = TextMain, fontSize = 19.sp, fontWeight = FontWeight.Black, maxLines = 1)
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

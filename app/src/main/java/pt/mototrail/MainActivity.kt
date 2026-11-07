package com.terratrack

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Xml
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import kotlin.math.*

private val Night = Color(0xFF080D0B)
private val Panel = Color(0xFF17221D)
private val Signal = Color(0xFFD7FF35)
private val TrackRed = Color(0xFFFF3030)
private val TextMain = Color(0xFFF7FAF7)
private val TextMuted = Color(0xFFB7C2BB)

data class TrackPoint(val latitude: Double, val longitude: Double)
data class GpxTrack(
    val name: String,
    val points: List<TrackPoint>,
    val distanceKm: Double
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TerraTrackApp() }
    }
}

@Composable
private fun TerraTrackApp() {
    var selectedTab by remember { mutableStateOf("MAP") }
    var track by remember { mutableStateOf<GpxTrack?>(null) }
    var status by remember { mutableStateOf("Ready for the next off-road route.") }
    var currentLocation by remember { mutableStateOf<Location?>(null) }
    var locationAllowed by remember { mutableStateOf(false) }
    var following by remember { mutableStateOf(false) }
    var reversed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        locationAllowed = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        status = if (locationAllowed) "Location permission granted." else "Location permission was not granted."
    }

    val gpxPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            busy = true
            scope.launch {
                try {
                    val imported = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use {
                            parseGpx(it, displayName(context, uri))
                        } ?: throw IllegalStateException("The selected file could not be opened.")
                    }
                    track = imported
                    reversed = false
                    following = false
                    status = "GPX loaded locally. No route recalculation will be performed."
                    selectedTab = "MAP"
                } catch (error: Exception) {
                    status = "Could not read GPX: ${error.message ?: "invalid file"}"
                } finally {
                    busy = false
                }
            }
        }
    }

    DisposableEffect(locationAllowed) {
        if (!locationAllowed) {
            onDispose { }
        } else {
            val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    currentLocation = location
                }
            }
            try {
                if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                        manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { currentLocation = it }
                        manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 3f, listener)
                    }
                }
            } catch (_: SecurityException) {
                status = "Location access is unavailable."
            }
            onDispose { manager.removeUpdates(listener) }
        }
    }

    val activePoints = if (reversed) track?.points?.asReversed().orEmpty() else track?.points.orEmpty()
    val distanceToTrack = remember(currentLocation, activePoints) {
        val location = currentLocation
        if (location == null || activePoints.isEmpty()) null
        else activePoints.minOf { point ->
            haversine(location.latitude, location.longitude, point.latitude, point.longitude)
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Signal,
            onPrimary = Night,
            secondary = TrackRed,
            background = Night,
            surface = Panel,
            onSurface = TextMain
        )
    ) {
        Scaffold(
            containerColor = Night,
            topBar = {
                Row(
                    Modifier.fillMaxWidth().background(Night).padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("TERRATRACK", color = Signal, fontSize = 23.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                        Text("OFFROAD NAVIGATION", color = TextMuted, fontSize = 11.sp, letterSpacing = 2.sp)
                    }
                    Text("BETA", color = Signal, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            },
            bottomBar = {
                NavigationBar(containerColor = Panel, contentColor = TextMain) {
                    listOf("MAP", "TRACKS", "SETTINGS").forEach { tab ->
                        NavigationBarItem(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            icon = { Text(when (tab) { "MAP" -> "⌖"; "TRACKS" -> "↗"; else -> "⚙" }, fontSize = 20.sp) },
                            label = { Text(tab, fontWeight = FontWeight.Bold) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Signal,
                                selectedTextColor = Signal,
                                indicatorColor = Color(0xFF34452B),
                                unselectedIconColor = TextMuted,
                                unselectedTextColor = TextMuted
                            )
                        )
                    }
                }
            }
        ) { padding ->
            when (selectedTab) {
                "MAP" -> MapHome(
                    Modifier.fillMaxSize().padding(padding),
                    track,
                    activePoints.size,
                    following,
                    distanceToTrack,
                    status,
                    busy,
                    onImport = { gpxPicker.launch(arrayOf("application/gpx+xml", "application/xml", "text/xml", "*/*")) },
                    onFollow = {
                        if (track == null) status = "Import a GPX track before starting track following."
                        else {
                            following = !following
                            status = if (following) "Track following enabled. The original GPX geometry is unchanged." else "Track following paused."
                        }
                    },
                    onLocation = {
                        if (locationAllowed) status = if (currentLocation == null) "Waiting for a GPS fix…" else "GPS position updated."
                        else permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    }
                )
                "TRACKS" -> TracksScreen(
                    Modifier.fillMaxSize().padding(padding),
                    track,
                    reversed,
                    status,
                    onImport = { gpxPicker.launch(arrayOf("application/gpx+xml", "application/xml", "text/xml", "*/*")) },
                    onReverse = {
                        reversed = !reversed
                        status = if (reversed) "Track direction reversed." else "Original track direction restored."
                    }
                )
                else -> SettingsScreen(Modifier.fillMaxSize().padding(padding))
            }
        }
    }
}

@Composable
private fun MapHome(
    modifier: Modifier,
    track: GpxTrack?,
    pointCount: Int,
    following: Boolean,
    distanceToTrack: Double?,
    status: String,
    busy: Boolean,
    onImport: () -> Unit,
    onFollow: () -> Unit,
    onLocation: () -> Unit
) {
    Column(modifier.background(Night).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier.weight(1f).fillMaxWidth().background(Color(0xFF101A15), RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("OFFLINE MAP", color = Signal, fontSize = 22.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                Text(
                    if (track == null) "Import a GPX track to get started" else track.name,
                    color = TextMain,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Offline map engine is the next build step",
                    color = TextMuted,
                    fontSize = 13.sp
                )
                if (track != null) {
                    Text("${"%.1f".format(track.distanceKm)} km  •  $pointCount points", color = Signal, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RallyMetric("TRACK DISTANCE", track?.let { "${"%.1f".format(it.distanceKm)} km" } ?: "—", Modifier.weight(1f))
            RallyMetric("DISTANCE TO TRACK", distanceToTrack?.let { "${"%.0f".format(it * 1000)} m" } ?: "—", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onImport, modifier = Modifier.weight(1f).height(58.dp), enabled = !busy) {
                Text(if (busy) "LOADING…" else "IMPORT GPX", fontWeight = FontWeight.Black)
            }
            Button(
                onClick = onFollow,
                modifier = Modifier.weight(1f).height(58.dp),
                enabled = track != null,
                colors = ButtonDefaults.buttonColors(containerColor = if (following) TrackRed else Signal, contentColor = Night)
            ) {
                Text(if (following) "STOP FOLLOWING" else "FOLLOW TRACK", fontWeight = FontWeight.Black)
            }
        }
        OutlinedButton(onClick = onLocation, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("ENABLE / UPDATE GPS", fontWeight = FontWeight.Bold)
        }
        Text(status, color = TextMuted, fontSize = 12.sp)
    }
}

@Composable
private fun RallyMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier, color = Panel, shape = RoundedCornerShape(10.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(label, color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text(value, color = TextMain, fontSize = 22.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
private fun TracksScreen(
    modifier: Modifier,
    track: GpxTrack?,
    reversed: Boolean,
    status: String,
    onImport: () -> Unit,
    onReverse: () -> Unit
) {
    Column(modifier.background(Night).padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("YOUR TRACKS", color = Signal, fontSize = 23.sp, fontWeight = FontWeight.Black)
        Button(onClick = onImport, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text("IMPORT GPX FILE", fontWeight = FontWeight.Black)
        }
        if (track == null) {
            Text("No GPX tracks imported yet.", color = TextMuted)
        } else {
            Surface(color = Panel, shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(track.name, color = TextMain, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text("${"%.2f".format(track.distanceKm)} km", color = Signal, fontSize = 26.sp, fontWeight = FontWeight.Black)
                    Text("${track.points.size} track points", color = TextMuted)
                    Text(if (reversed) "Direction: REVERSED" else "Direction: ORIGINAL", color = TextMain, fontWeight = FontWeight.Bold)
                    OutlinedButton(onClick = onReverse, modifier = Modifier.fillMaxWidth().height(54.dp)) {
                        Text(if (reversed) "RESTORE ORIGINAL DIRECTION" else "REVERSE TRACK DIRECTION")
                    }
                    Text("Track following never recalculates or replaces the imported GPX line.", color = TextMuted, fontSize = 12.sp)
                }
            }
        }
        Text(status, color = TextMuted, fontSize = 12.sp)
    }
}

@Composable
private fun SettingsScreen(modifier: Modifier) {
    Column(modifier.background(Night).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("SETTINGS", color = Signal, fontSize = 23.sp, fontWeight = FontWeight.Black)
        SettingRow("Navigation focus", "Off-road GPX track following")
        SettingRow("Connectivity", "Designed for offline use")
        SettingRow("Route calculation", "Disabled for imported GPX tracks")
        SettingRow("Supported vehicles", "Adventure motorcycles, SSV and UTV")
        Text("Offline map storage, Rally Mode, rain lock, Bluetooth controls and background GPS service will be added in subsequent milestones.", color = TextMuted, fontSize = 13.sp)
    }
}

@Composable
private fun SettingRow(title: String, value: String) {
    Surface(color = Panel, shape = RoundedCornerShape(10.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(value, color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

private fun displayName(context: Context, uri: Uri): String {
    var name = "Off-road track.gpx"
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (cursor.moveToFirst() && index >= 0) name = cursor.getString(index) ?: name
    }
    return name
}

private fun parseGpx(input: InputStream, fileName: String): GpxTrack {
    val parser = Xml.newPullParser()
    parser.setInput(input, null)
    var event = parser.eventType
    var name = fileName.substringBeforeLast('.')
    val points = mutableListOf<TrackPoint>()
    var distance = 0.0
    var previous: TrackPoint? = null
    while (event != XmlPullParser.END_DOCUMENT) {
        if (event == XmlPullParser.START_TAG) {
            when (parser.name.substringAfter(':').lowercase()) {
                "name" -> if (points.isEmpty()) {
                    val candidate = try { parser.nextText() } catch (_: Exception) { "" }
                    if (candidate.isNotBlank()) name = candidate.trim()
                }
                "trkpt", "rtept" -> {
                    val lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                    val lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                    if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) {
                        val point = TrackPoint(lat, lon)
                        previous?.let { distance += haversine(it.latitude, it.longitude, lat, lon) }
                        points.add(point)
                        previous = point
                    }
                }
            }
        }
        event = parser.next()
    }
    require(points.size >= 2) { "The GPX file contains fewer than two route points." }
    return GpxTrack(name, points, distance)
}

private fun haversine(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
    val radiusKm = 6371.0
    val dLat = Math.toRadians(bLat - aLat)
    val dLon = Math.toRadians(bLon - aLon)
    val a = sin(dLat / 2).pow(2.0) +
        cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLon / 2).pow(2.0)
    return 2 * radiusKm * asin(sqrt(a.coerceIn(0.0, 1.0)))
}

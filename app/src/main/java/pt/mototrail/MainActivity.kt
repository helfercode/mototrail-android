package pt.mototrail

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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.Polyline
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import kotlin.math.*

private val Forest = Color(0xFF183B32)
private val Lime = Color(0xFFC5E56A)
private val Paper = Color(0xFFF3F5F0)

data class GpxSummary(val name: String, val points: Int, val distanceKm: Double, val coordinates: List<LatLng>)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(applicationContext)
        setContent { MotoTrailApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MotoTrailApp() {
    var page by remember { mutableStateOf("Mapa") }
    var gpx by remember { mutableStateOf<GpxSummary?>(null) }
    var status by remember { mutableStateOf("Pronto para explorar.") }
    var locationEnabled by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current

    val permissionPicker = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        locationEnabled = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (!locationEnabled) status = "A localização não foi autorizada."
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) try {
            context.contentResolver.openInputStream(uri)?.use { gpx = parseGpx(it, displayName(context, uri)) }
            status = if (gpx != null) "GPX importado e lido localmente." else "Não foi possível abrir o ficheiro."
            page = "Mapa"
        } catch (e: Exception) {
            status = "Erro ao ler GPX: " + (e.message ?: "formato inválido")
        }
    }

    fun requestLocation() {
        val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fine || coarse) locationEnabled = true
        else permissionPicker.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    MaterialTheme(colorScheme = lightColorScheme(primary = Forest, secondary = Lime, background = Paper)) {
        Scaffold(
            containerColor = Paper,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                if (page != "Mapa") TopAppBar(
                    title = { Text(page, fontWeight = FontWeight.Bold, color = Forest) },
                    navigationIcon = { TextButton(onClick = { page = "Mapa" }) { Text("Mapa") } }
                )
            },
            bottomBar = {
                if (page != "Mapa") NavigationBar {
                    listOf("Percursos", "Comunidade", "Perfil").forEach { item ->
                        NavigationBarItem(selected = page == item, onClick = { page = item },
                            icon = { Text(when (item) { "Percursos" -> "↗"; "Comunidade" -> "◎"; else -> "●" }) },
                            label = { Text(item) })
                    }
                }
            }
        ) { padding ->
            if (page == "Mapa") {
                MapPanel(locationEnabled, gpx, { requestLocation() },
                    { filePicker.launch(arrayOf("application/gpx+xml", "application/xml", "text/xml", "*/*")) },
                    { page = it }, Modifier.fillMaxSize())
            } else Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (page) {
                    "Percursos" -> {
                        Text("Os teus percursos", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(status, color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { filePicker.launch(arrayOf("application/gpx+xml", "application/xml", "text/xml", "*/*")) }, modifier = Modifier.fillMaxWidth()) { Text("Escolher ficheiro GPX") }
                        val track = gpx
                        if (track == null) InfoCard("Ainda não importaste nenhum percurso.", "Importa um ficheiro GPX para veres o traçado no mapa.")
                        else Card { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(track.name, fontWeight = FontWeight.Bold)
                            Text("%.1f km · %d pontos".format(track.distanceKm, track.points))
                            Text("O percurso pode ser seguido no modo Navegação livre.")
                        } }
                    }
                    "Comunidade" -> { Text("Comunidade", style = MaterialTheme.typography.headlineSmall); InfoCard("Em preparação.", "Grupos, viagens, pontos de encontro e entreajuda serão desenvolvidos mais tarde.") }
                    else -> { Text("Perfil", style = MaterialTheme.typography.headlineSmall); InfoCard("Privacidade.", "A localização é apresentada localmente e só é ativada com a tua autorização. Esta versão não envia percursos nem localização para servidores."); InfoCard("Versão 0.3.2", "Mapa em ecrã inteiro, controlos retrácteis e visualização de percursos GPX.") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MapPanel(
    locationEnabled: Boolean,
    gpx: GpxSummary?,
    onRequestLocation: () -> Unit,
    onImportGpx: () -> Unit,
    onOpenPage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var currentLocation by remember { mutableStateOf<LatLng?>(null) }
    var currentBearing by remember { mutableFloatStateOf(0f) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var userMarker by remember { mutableStateOf<Marker?>(null) }
    var targetMarker by remember { mutableStateOf<Marker?>(null) }
    var trackLine by remember { mutableStateOf<Polyline?>(null) }
    var firstCenter by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf("Destino") }
    var destination by remember { mutableStateOf<LatLng?>(null) }
    var distance by remember { mutableStateOf<Double?>(null) }
    var routeLine by remember { mutableStateOf<Polyline?>(null) }
    var navigating by remember { mutableStateOf(false) }
    var routeStatus by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var visible by remember { mutableStateOf(true) }
    var touchCount by remember { mutableIntStateOf(0) }
    fun showControls() { visible = true; touchCount++ }
    LaunchedEffect(visible, touchCount) { if (visible) { delay(5000); visible = false } }

    DisposableEffect(locationEnabled) {
        if (!locationEnabled) onDispose { } else {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val allowed = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    currentLocation = LatLng(location.latitude, location.longitude)
                    if (location.hasBearing() && location.speed > 1.5f) currentBearing = location.bearing
                }
            }
            try {
                if (allowed) listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { provider ->
                    try {
                        if (lm.isProviderEnabled(provider)) {
                            lm.getLastKnownLocation(provider)?.let { if (currentLocation == null) currentLocation = LatLng(it.latitude, it.longitude) }
                            lm.requestLocationUpdates(provider, 2000L, 5f, listener)
                        }
                    } catch (_: Exception) { }
                }
            } catch (_: SecurityException) { }
            onDispose { lm.removeUpdates(listener) }
        }
    }
    LaunchedEffect(map, currentLocation, currentBearing) {
        val m = map ?: return@LaunchedEffect
        val p = currentLocation ?: return@LaunchedEffect
        if (userMarker == null) userMarker = m.addMarker(MarkerOptions().position(p).title("A tua localização"))
        else { userMarker?.position = p; userMarker?.let { m.updateMarker(it) } }
        destination?.let { distance = haversine(p.latitude, p.longitude, it.latitude, it.longitude) }
        if (!firstCenter) { m.cameraPosition = CameraPosition.Builder().target(p).zoom(15.5).bearing(0.0).tilt(0.0).build(); firstCenter = true }
        else if (navigating) { m.cameraPosition = CameraPosition.Builder().target(p).zoom(16.5).bearing(0.0).tilt(0.0).build() }
    }
    LaunchedEffect(map, gpx) {
        val m = map ?: return@LaunchedEffect
        trackLine?.let { m.removePolyline(it) }
        trackLine = null
        val pts = gpx?.coordinates.orEmpty()
        if (pts.size > 1) {
            trackLine = m.addPolyline(PolylineOptions().addAll(pts).color(android.graphics.Color.rgb(197, 229, 106)).width(5f))
            m.cameraPosition = CameraPosition.Builder().target(pts.first()).zoom(11.5).bearing(0.0).tilt(0.0).build()
            firstCenter = true
        }
    }
    LaunchedEffect(map, destination, mode) {
        val m = map ?: return@LaunchedEffect
        targetMarker?.let { m.removeMarker(it) }
        targetMarker = null
        if (mode == "Destino") destination?.let { targetMarker = m.addMarker(MarkerOptions().position(it).title("Destino escolhido")) }
        else { destination = null; distance = null }
    }

    Box(modifier) {
        val mapView = remember {
            MapView(context).apply {
                onCreate(Bundle())
                getMapAsync { m ->
                    val json = """{"version":8,"sources":{"topo":{"type":"raster","tiles":["https://tile.openmaps.fr/opentopomap/{z}/{x}/{y}.png"],"tileSize":256,"attribution":"© OpenStreetMap contributors · OpenTopoMap · openmaps.fr"}},"layers":[{"id":"topo","type":"raster","source":"topo"}]}"""
                    m.setStyle(Style.Builder().fromJson(json)) {
                        map = m
                        m.cameraPosition = CameraPosition.Builder().target(LatLng(39.6, -8.0)).zoom(6.2).build()
                        m.addOnMapClickListener { point ->
                            showControls()
                            if (mode == "Destino") {
                                destination = point
                                currentLocation?.let { distance = haversine(it.latitude, it.longitude, point.latitude, point.longitude) }
                            }
                            true
                        }
                    }
                }
            }
        }
        DisposableEffect(mapView) {
            mapView.onStart(); mapView.onResume()
            onDispose { mapView.onPause(); mapView.onStop(); mapView.onDestroy() }
        }
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        if (visible) {
            Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    FilledTonalButton(onClick = { onOpenPage("Percursos"); showControls() }) { Text("Percursos") }
                    FilledTonalButton(onClick = { onOpenPage("Comunidade"); showControls() }) { Text("Comunidade") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    SingleChoiceSegmentedButtonRow {
                        SegmentedButton(mode == "Destino", { mode = "Destino"; showControls() }, SegmentedButtonDefaults.itemShape(index = 0, count = 2)) { Text("Navegar até destino") }
                        SegmentedButton(mode == "GPX", { mode = "GPX"; showControls() }, SegmentedButtonDefaults.itemShape(index = 1, count = 2)) { Text("Navegação livre") }
                    }
                }
                if (mode == "GPX") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        Button(onClick = { onImportGpx(); showControls() }) { Text("Importar GPX") }
                    }
                    gpx?.let { Surface(color = Color.White.copy(alpha = 0.94f), shape = RoundedCornerShape(8.dp)) {
                        Text("${it.name} · %.1f km · ${it.points} pontos".format(it.distanceKm), Modifier.padding(8.dp), color = Forest)
                    } }
                } else destination?.let {
                    Surface(color = Color.White.copy(alpha = 0.94f), shape = RoundedCornerShape(8.dp)) {
                        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Destino escolhido", fontWeight = FontWeight.Bold, color = Forest)
                                Text(distance?.let { d -> "Distância em linha reta: %.1f km".format(d) } ?: "A aguardar localização.", style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { destination = null; distance = null; navigating = false; routeStatus = null; routeLine?.let { map?.removePolyline(it) }; routeLine = null; showControls() }) { Text("Limpar") }
                        }
                        Spacer(Modifier.height(6.dp))
                        Button(onClick = {
                            val origin = currentLocation
                            val target = destination
                            if (origin == null) { routeStatus = "A obter a tua localização…"; onRequestLocation() }
                            else if (target != null) {
                                routeStatus = "A calcular percurso…"
                                navigating = false
                                scope.launch {
                                    try {
                                        val points = withContext(Dispatchers.IO) { fetchRoadRoute(origin, target) }
                                        routeLine?.let { map?.removePolyline(it) }
                                        routeLine = map?.addPolyline(PolylineOptions().addAll(points).color(android.graphics.Color.rgb(24, 59, 50)).width(6f))
                                        navigating = true
                                        routeStatus = "Percurso calculado. Navegação iniciada."
                                        map?.cameraPosition = CameraPosition.Builder().target(origin).zoom(16.5).bearing(0.0).tilt(0.0).build()
                                    } catch (_: Exception) {
                                        routeStatus = "Não foi possível calcular a rota. Verifica a ligação à Internet e tenta novamente."
                                    }
                                }
                            }
                            showControls()
                        }, modifier = Modifier.fillMaxWidth()) { Text(if (navigating) "Recalcular percurso" else "Calcular rota e iniciar navegação") }
                        routeStatus?.let { Text(it, Modifier.padding(8.dp), color = Forest, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
        Row(Modifier.align(Alignment.TopEnd).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalIconButton(onClick = { visible = !visible; if (visible) touchCount++ }) { Text(if (visible) "◉" else "◎") }
            FilledTonalIconButton(onClick = {
                currentLocation?.let { map?.cameraPosition = CameraPosition.Builder().target(it).zoom(16.0).bearing(0.0).tilt(0.0).build() } ?: onRequestLocation()
                showControls()
            }) { Text("⌖") }
        }
        Surface(Modifier.align(Alignment.BottomStart).padding(8.dp), color = Color.White.copy(alpha = 0.88f), shape = RoundedCornerShape(8.dp)) {
            Text("© OpenStreetMap · OpenTopoMap · openmaps.fr", Modifier.padding(6.dp), style = MaterialTheme.typography.labelSmall, color = Color.DarkGray)
        }
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(body, color = Color.DarkGray, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Forest)
    }
}

private fun displayName(context: Context, uri: Uri): String {
    var name = "Percurso GPX"
    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (c.moveToFirst() && i >= 0) name = c.getString(i) ?: name
    }
    return name
}

private fun parseGpx(input: InputStream, fileName: String): GpxSummary {
    val parser = Xml.newPullParser()
    parser.setInput(input, null)
    var event = parser.eventType
    var name = fileName.removeSuffix(".gpx").removeSuffix(".GPX")
    var count = 0
    var distance = 0.0
    var previous: LatLng? = null
    val coordinates = mutableListOf<LatLng>()
    while (event != XmlPullParser.END_DOCUMENT) {
        if (event == XmlPullParser.START_TAG) when (parser.name.lowercase()) {
            "name" -> if (count == 0) {
                val candidate = try { parser.nextText() } catch (_: Exception) { "" }
                if (candidate.isNotBlank()) name = candidate
            }
            "trkpt", "rtept" -> {
                val lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                val lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) {
                    val point = LatLng(lat, lon)
                    count++
                    previous?.let { distance += haversine(it.latitude, it.longitude, lat, lon) }
                    coordinates.add(point)
                    previous = point
                }
            }
        }
        event = parser.next()
    }
    return GpxSummary(name, count, distance, coordinates)
}

private fun haversine(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
    val r = 6371.0
    val dLat = Math.toRadians(bLat - aLat)
    val dLon = Math.toRadians(bLon - aLon)
    val a = sin(dLat / 2).pow(2.0) + cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLon / 2).pow(2.0)
    return 2 * r * asin(sqrt(a.coerceIn(0.0, 1.0)))
}

private fun fetchRoadRoute(origin: LatLng, destination: LatLng): List<LatLng> {
    val url = URL("https://router.project-osrm.org/route/v1/driving/${origin.longitude},${origin.latitude};${destination.longitude},${destination.latitude}?overview=full&geometries=geojson&steps=true")
    val connection = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 15000
        readTimeout = 20000
        setRequestProperty("User-Agent", "MotoTrail-Android/0.3.3")
    }
    try {
        val response = connection.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(response)
        if (json.optString("code") != "Ok") throw IllegalStateException("No route")
        val routes = json.getJSONArray("routes")
        if (routes.length() == 0) throw IllegalStateException("No route")
        val coords = routes.getJSONObject(0).getJSONObject("geometry").getJSONArray("coordinates")
        return (0 until coords.length()).map { i ->
            val pair = coords.getJSONArray(i)
            LatLng(pair.getDouble(1), pair.getDouble(0))
        }
    } finally { connection.disconnect() }
}

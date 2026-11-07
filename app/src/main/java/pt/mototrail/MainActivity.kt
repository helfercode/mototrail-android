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
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
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

data class GpxSummary(val name: String, val points: Int, val distanceKm: Double)

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
            page = "Percursos"
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
            topBar = {
                TopAppBar(title = {
                    Column {
                        Text("MOTOTRAIL", fontWeight = FontWeight.Black, color = Forest)
                        Text("EXPLORE BEYOND THE ROUTE", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    }
                }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Paper))
            },
            bottomBar = {
                NavigationBar(containerColor = Color.White) {
                    listOf("Mapa", "Percursos", "Comunidade", "Perfil").forEach { item ->
                        NavigationBarItem(
                            selected = page == item,
                            onClick = { page = item },
                            icon = { Text(when (item) { "Mapa" -> "⌖"; "Percursos" -> "↗"; "Comunidade" -> "◎"; else -> "●" }) },
                            label = { Text(item) }
                        )
                    }
                }
            }
        ) { padding ->
            BoxWithConstraints(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp, vertical = 8.dp)) {
                val landscape = maxWidth > maxHeight
                when (page) {
                    "Mapa" -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(if (landscape) 6.dp else 10.dp)) {
                        if (!landscape) Text("O teu próximo caminho começa aqui.", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        MapPanel(
                            locationEnabled = locationEnabled,
                            onRequestLocation = { requestLocation() },
                            modifier = Modifier.weight(1f).fillMaxWidth()
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { requestLocation() }, modifier = Modifier.weight(1f)) {
                                Text(if (locationEnabled) "Localização ativa" else "Mostrar localização")
                            }
                            OutlinedButton(
                                onClick = { filePicker.launch(arrayOf("application/gpx+xml", "application/xml", "text/xml", "*/*")) },
                                modifier = Modifier.weight(1f)
                            ) { Text("Importar GPX") }
                        }
                        if (!landscape) Text(
                            "A posição é usada apenas no telefone. A cartografia necessita de Internet.",
                            style = MaterialTheme.typography.bodySmall, color = Color.Gray
                        )
                    }
                    "Percursos" -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Os teus percursos", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(status, color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { filePicker.launch(arrayOf("application/gpx+xml", "application/xml", "text/xml", "*/*")) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Escolher ficheiro GPX")
                        }
                        val track = gpx
                        if (track == null) InfoCard("Ainda não importaste nenhum percurso.", "Seleciona um ficheiro GPX para consultar o nome, número de pontos e distância.")
                        else Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(track.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Metric("DISTÂNCIA", "%.1f km".format(track.distanceKm))
                                    Metric("PONTOS", track.points.toString())
                                }
                                Text("O ficheiro foi lido no telefone. A representação do traçado será acrescentada numa etapa seguinte.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    "Comunidade" -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Comunidade", style = MaterialTheme.typography.headlineSmall)
                        InfoCard("Em preparação.", "Grupos, viagens, pontos de encontro e entreajuda serão desenvolvidos mais tarde.")
                    }
                    else -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("O teu MotoTrail", style = MaterialTheme.typography.headlineSmall)
                        InfoCard("Privacidade desde o início.", "A localização é apresentada localmente e só é ativada com a tua autorização. Esta versão não envia percursos nem localização para servidores.")
                        InfoCard("Versão 0.3.1", "Cartografia topográfica, localização GPS e adaptação a ecrãs verticais e horizontais.")
                    }
                }
            }
        }
    }
}

@Composable
private fun MapPanel(locationEnabled: Boolean, onRequestLocation: () -> Unit, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var currentLocation by remember { mutableStateOf<LatLng?>(null) }
    var mapInstance by remember { mutableStateOf<MapLibreMap?>(null) }
    var marker by remember { mutableStateOf<Marker?>(null) }
    var centered by remember { mutableStateOf(false) }

    DisposableEffect(locationEnabled) {
        if (!locationEnabled) {
            onDispose { }
        } else {
            val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val coarse = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    currentLocation = LatLng(location.latitude, location.longitude)
                }
            }
            try {
                if (fine || coarse) {
                    listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { provider ->
                        try {
                            if (manager.isProviderEnabled(provider)) {
                                manager.getLastKnownLocation(provider)?.let {
                                    if (currentLocation == null) currentLocation = LatLng(it.latitude, it.longitude)
                                }
                                manager.requestLocationUpdates(provider, 2000L, 5f, listener)
                            }
                        } catch (_: Exception) { }
                    }
                }
            } catch (_: SecurityException) { }
            onDispose { manager.removeUpdates(listener) }
        }
    }

    LaunchedEffect(mapInstance, currentLocation) {
        val map = mapInstance
        val position = currentLocation
        if (map != null && position != null) {
            if (marker == null) marker = map.addMarker(MarkerOptions().position(position).title("A tua localização"))
            else {
                marker?.position = position
                marker?.let { map.updateMarker(it) }
            }
            if (!centered) {
                map.cameraPosition = CameraPosition.Builder().target(position).zoom(14.5).build()
                centered = true
            }
        }
    }

    Card(modifier = modifier, shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Box(Modifier.fillMaxSize()) {
            val mapView = remember {
                MapView(context).apply {
                    onCreate(Bundle())
                    getMapAsync { map ->
                        val topoStyle = """
                            {
                              "version": 8,
                              "sources": {
                                "mototrail-topographic": {
                                  "type": "raster",
                                  "tiles": ["https://tile.openmaps.fr/opentopomap/{z}/{x}/{y}.png"],
                                  "tileSize": 256,
                                  "attribution": "© OpenStreetMap contributors · OpenTopoMap · openmaps.fr"
                                }
                              },
                              "layers": [
                                {"id": "mototrail-topographic", "type": "raster", "source": "mototrail-topographic"}
                              ]
                            }
                        """.trimIndent()
                        map.setStyle(Style.Builder().fromJson(topoStyle)) {
                            mapInstance = map
                            map.cameraPosition = CameraPosition.Builder().target(LatLng(39.6, -8.0)).zoom(6.2).build()
                        }
                    }
                }
            }
            DisposableEffect(mapView) {
                mapView.onStart()
                mapView.onResume()
                onDispose { mapView.onPause(); mapView.onStop(); mapView.onDestroy() }
            }
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
            Column(Modifier.align(Alignment.TopEnd).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FilledTonalIconButton(onClick = onRequestLocation) { Text("⌖", style = MaterialTheme.typography.titleLarge) }
                FilledTonalIconButton(onClick = {
                    val pos = currentLocation
                    if (pos != null) mapInstance?.cameraPosition = CameraPosition.Builder().target(pos).zoom(14.5).build()
                    else onRequestLocation()
                }) { Text("◎", style = MaterialTheme.typography.titleLarge) }
            }
            Surface(
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                color = Color.White.copy(alpha = 0.92f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("© OpenStreetMap · OpenTopoMap · openmaps.fr", modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall, color = Color.DarkGray)
            }
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
    var previous: Pair<Double, Double>? = null
    while (event != XmlPullParser.END_DOCUMENT) {
        if (event == XmlPullParser.START_TAG) when (parser.name.lowercase()) {
            "name" -> if (count == 0) {
                val candidate = try { parser.nextText() } catch (_: Exception) { "" }
                if (candidate.isNotBlank()) name = candidate
            }
            "trkpt", "rtept" -> {
                val lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                val lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                if (lat != null && lon != null) {
                    count++
                    previous?.let { distance += haversine(it.first, it.second, lat, lon) }
                    previous = lat to lon
                }
            }
        }
        event = parser.next()
    }
    return GpxSummary(name, count, distance)
}

private fun haversine(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
    val r = 6371.0
    val dLat = Math.toRadians(bLat - aLat)
    val dLon = Math.toRadians(bLon - aLon)
    val a = sin(dLat / 2).pow(2.0) + cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLon / 2).pow(2.0)
    return 2 * r * asin(sqrt(a.coerceIn(0.0, 1.0)))
}

package pt.mototrail

import android.Manifest
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Xml
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import kotlin.math.*

private val Forest = Color(0xFF183B32)
private val Lime = Color(0xFFC5E56A)
private val Paper = Color(0xFFF3F5F0)
private val Ink = Color(0xFF18231F)

data class GpxSummary(
    val name: String,
    val points: Int,
    val distanceKm: Double
)

class MainActivity : ComponentActivity() {
    private var locationManager: LocationManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        setContent {
            MotoTrailApp(
                onReadLocation = { callback -> readLocation(callback) }
            )
        }
    }

    private fun readLocation(callback: (String) -> Unit) {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            callback("Permissão de localização ainda não concedida.")
            return
        }
        try {
            val providers = locationManager?.getProviders(true).orEmpty()
            val last = providers.mapNotNull { provider ->
                try { locationManager?.getLastKnownLocation(provider) } catch (_: SecurityException) { null }
            }.maxByOrNull { it.time }

            callback(
                if (last != null) "Latitude: %.5f\nLongitude: %.5f".format(last.latitude, last.longitude)
                else "Ainda não existe uma posição GPS disponível. Experimenta no exterior."
            )
        } catch (_: Exception) {
            callback("Não foi possível obter a localização.")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MotoTrailApp(onReadLocation: ((String) -> Unit) -> Unit) {
    var page by remember { mutableStateOf("Mapa") }
    var gpx by remember { mutableStateOf<GpxSummary?>(null) }
    var status by remember { mutableStateOf("Pronto para explorar.") }
    var locationText by remember { mutableStateOf("A localização ainda não foi consultada.") }

    val context = androidx.compose.ui.platform.LocalContext.current
    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.any { it }) onReadLocation { locationText = it }
        else locationText = "Permissão recusada. A localização permanece desligada."
    }

    val gpxPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    gpx = parseGpx(stream, queryDisplayName(context, uri))
                    status = "GPX importado com sucesso."
                    page = "Percursos"
                } ?: run { status = "Não foi possível abrir o ficheiro." }
            } catch (e: Exception) {
                status = "Erro ao ler GPX: ${e.message ?: "formato inválido"}"
            }
        }
    }

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Forest,
            secondary = Lime,
            background = Paper,
            surface = Color.White,
            onPrimary = Color.White,
            onBackground = Ink
        )
    ) {
        Scaffold(
            containerColor = Paper,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("MOTOTRAIL", fontWeight = FontWeight.Black, color = Forest)
                            Text("EXPLORE BEYOND THE ROUTE", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Paper)
                )
            },
            bottomBar = {
                NavigationBar(containerColor = Color.White) {
                    listOf("Mapa", "Percursos", "Comunidade", "Perfil").forEach { item ->
                        NavigationBarItem(
                            selected = page == item,
                            onClick = { page = item },
                            icon = {
                                Text(
                                    when (item) {
                                        "Mapa" -> "⌖"
                                        "Percursos" -> "↗"
                                        "Comunidade" -> "◎"
                                        else -> "●"
                                    },
                                    style = MaterialTheme.typography.titleLarge
                                )
                            },
                            label = { Text(item) }
                        )
                    }
                }
            }
        ) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (page) {
                    "Mapa" -> MapPage(
                        locationText = locationText,
                        onLocation = {
                            val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                            val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                            if (fine || coarse) onReadLocation { locationText = it }
                            else locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        },
                        gpx = gpx,
                        onOpenGpx = { gpxPicker.launch(arrayOf("*/*")) }
                    )
                    "Percursos" -> RoutesPage(gpx, status, onOpenGpx = { gpxPicker.launch(arrayOf("*/*")) })
                    "Comunidade" -> CommunityPage()
                    else -> ProfilePage()
                }
            }
        }
    }
}

@Composable
fun MapPage(
    locationText: String,
    onLocation: () -> Unit,
    gpx: GpxSummary?,
    onOpenGpx: () -> Unit
) {
    Text("O teu próximo caminho começa aqui.", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Box(
        modifier = Modifier.fillMaxWidth().weight(300.dp).background(Color(0xFFE0E7DF), RoundedCornerShape(24.dp)).padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("⌖", style = MaterialTheme.typography.displayLarge, color = Forest)
            Text("ÁREA DE MAPA", fontWeight = FontWeight.Black, color = Forest)
            Text("A cartografia MapLibre será integrada na próxima etapa.", color = Color.DarkGray)
            Text("Esta versão ainda não apresenta mapas reais.", style = MaterialTheme.typography.bodySmall, color = Color.DarkGray)
        }
    }
    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Localização", fontWeight = FontWeight.Bold)
            Text(locationText, style = MaterialTheme.typography.bodySmall)
            Button(onClick = onLocation, modifier = Modifier.fillMaxWidth()) {
                Text("Consultar localização GPS")
            }
        }
    }
    OutlinedButton(onClick = onOpenGpx, modifier = Modifier.fillMaxWidth()) {
        Text(if (gpx == null) "Importar percurso GPX" else "Importar outro GPX")
    }
}

@Composable
fun RoutesPage(gpx: GpxSummary?, status: String, onOpenGpx: () -> Unit) {
    Text("Os teus percursos", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Text(status, color = Color.Gray, style = MaterialTheme.typography.bodySmall)
    Button(onClick = onOpenGpx, modifier = Modifier.fillMaxWidth()) { Text("Escolher ficheiro GPX") }
    if (gpx == null) {
        EmptyCard("Ainda não importaste nenhum percurso.", "Seleciona um ficheiro GPX para consultar o nome, número de pontos e distância.")
    } else {
        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(gpx.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Metric("DISTÂNCIA", "%.1f km".format(gpx.distanceKm))
                    Metric("PONTOS", gpx.points.toString())
                }
                Text("O percurso já foi lido localmente. A representação sobre o mapa será acrescentada na próxima etapa.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun CommunityPage() {
    Text("Comunidade", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    EmptyCard("A comunidade está em preparação.", "Grupos, partilha de viagens, pontos de encontro e entreajuda serão desenvolvidos numa fase posterior.")
}

@Composable
fun ProfilePage() {
    Text("O teu MotoTrail", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    EmptyCard("Privacidade desde o início.", "A localização desta versão é consultada apenas quando carregas no botão. Não é transmitida para nenhum servidor.")
    EmptyCard("Versão 0.2.0", "Protótipo inicial de navegação. Ainda não inclui mapas reais, sincronização ou encaminhamento.")
}

@Composable
fun EmptyCard(title: String, body: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(body, color = Color.DarkGray, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun Metric(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Forest)
    }
}

private fun queryDisplayName(context: Context, uri: Uri): String {
    var result = "Percurso GPX"
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (cursor.moveToFirst() && index >= 0) result = cursor.getString(index) ?: result
    }
    return result
}

private fun parseGpx(input: InputStream, fileName: String): GpxSummary {
    val parser = Xml.newPullParser()
    parser.setInput(input, null)
    var event = parser.eventType
    var name = fileName.removeSuffix(".gpx").removeSuffix(".GPX")
    var points = 0
    var distance = 0.0
    var previous: Pair<Double, Double>? = null

    while (event != XmlPullParser.END_DOCUMENT) {
        if (event == XmlPullParser.START_TAG) {
            when (parser.name.lowercase()) {
                "name" -> {
                    if (points == 0) {
                        val candidate = try { parser.nextText() } catch (_: Exception) { "" }
                        if (candidate.isNotBlank()) name = candidate
                    }
                }
                "trkpt", "rtept", "wpt" -> {
                    val lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                    val lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                    if (lat != null && lon != null) {
                        points++
                        if (parser.name.lowercase() != "wpt") {
                            val current = lat to lon
                            previous?.let { distance += haversineKm(it.first, it.second, lat, lon) }
                            previous = current
                        }
                    }
                }
            }
        }
        event = parser.next()
    }
    return GpxSummary(name, points, distance)
}

private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2.0) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2.0)
    return 2 * r * asin(sqrt(a.coerceIn(0.0, 1.0)))
}

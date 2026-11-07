package pt.mototrail

import android.content.Context
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.maplibre.android.MapLibre
import com.maplibre.android.maps.MapView
import com.maplibre.android.maps.Style
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) try {
            context.contentResolver.openInputStream(uri)?.use { gpx = parseGpx(it, displayName(context, uri)) }
            status = if (gpx != null) "GPX importado e lido localmente." else "Não foi possível abrir o ficheiro."
            page = "Percursos"
        } catch (e: Exception) { status = "Erro ao ler GPX: ${e.message ?: "formato inválido"}" }
    }
    MaterialTheme(colorScheme = lightColorScheme(primary = Forest, secondary = Lime, background = Paper)) {
        Scaffold(
            containerColor = Paper,
            topBar = { TopAppBar(title = { Column {
                Text("MOTOTRAIL", fontWeight = FontWeight.Black, color = Forest)
                Text("EXPLORE BEYOND THE ROUTE", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Paper)) },
            bottomBar = { NavigationBar(containerColor = Color.White) {
                listOf("Mapa", "Percursos", "Comunidade", "Perfil").forEach { item ->
                    NavigationBarItem(selected = page == item, onClick = { page = item },
                        icon = { Text(when (item) { "Mapa" -> "⌖"; "Percursos" -> "↗"; "Comunidade" -> "◎"; else -> "●" }) },
                        label = { Text(item) })
                }
            } }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (page) {
                    "Mapa" -> {
                        Text("O teu próximo caminho começa aqui.", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        MapPanel()
                        OutlinedButton(onClick = { picker.launch(arrayOf("application/gpx+xml", "application/xml", "text/xml", "*/*")) }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (gpx == null) "Importar percurso GPX" else "Importar outro GPX")
                        }
                        Text("A cartografia necessita de Internet. A localização não é partilhada.", style = MaterialTheme.typography.bodySmall)
                    }
                    "Percursos" -> {
                        Text("Os teus percursos", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(status, color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { picker.launch(arrayOf("application/gpx+xml", "application/xml", "text/xml", "*/*")) }, modifier = Modifier.fillMaxWidth()) { Text("Escolher ficheiro GPX") }
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
                    "Comunidade" -> { Text("Comunidade", style = MaterialTheme.typography.headlineSmall); InfoCard("Em preparação.", "Grupos, viagens, pontos de encontro e entreajuda serão desenvolvidos mais tarde.") }
                    else -> { Text("O teu MotoTrail", style = MaterialTheme.typography.headlineSmall); InfoCard("Privacidade desde o início.", "Os ficheiros GPX são lidos localmente. Esta versão não envia percursos nem localização para servidores."); InfoCard("Versão 0.3.0", "Mapa cartográfico real e importação GPX básica.") }
                }
            }
        }
    }
}

@Composable
private fun MapPanel() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val mapView = remember { MapView(context).apply {
        onCreate(Bundle())
        getMapAsync { map -> map.setStyle(Style.Builder().fromUri("https://tiles.openfreemap.org/styles/liberty")) }
    } }
    DisposableEffect(mapView) {
        mapView.onStart()
        mapView.onResume()
        onDispose { mapView.onPause(); mapView.onStop(); mapView.onDestroy() }
    }
    Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxWidth().height(330.dp))
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
    Column { Text(label, style = MaterialTheme.typography.labelSmall, color = Color.Gray); Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Forest) }
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

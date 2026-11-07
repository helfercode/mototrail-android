package com.terratrack

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import kotlin.math.*

data class TrackPoint(
    val latitude: Double,
    val longitude: Double,
    val elevation: Double? = null,
    val time: String? = null
)

data class GpxTrack(
    val name: String,
    val points: List<TrackPoint>,
    val distanceKm: Double
)

fun parseGpx(input: InputStream, fileName: String): GpxTrack {
    val parser = Xml.newPullParser()
    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
    parser.setInput(input, null)

    var event = parser.eventType
    var name = fileName.substringBeforeLast('.').ifBlank { "Off-road track" }
    var currentLat: Double? = null
    var currentLon: Double? = null
    var currentElevation: Double? = null
    var currentTime: String? = null
    val points = ArrayList<TrackPoint>()
    var distance = 0.0
    var previous: TrackPoint? = null

    while (event != XmlPullParser.END_DOCUMENT) {
        when (event) {
            XmlPullParser.START_TAG -> when (parser.name.substringAfter(':').lowercase()) {
                "name" -> if (points.isEmpty()) {
                    val candidate = try { parser.nextText() } catch (_: Exception) { "" }
                    if (candidate.isNotBlank()) name = candidate.trim()
                }
                "trkpt", "rtept" -> {
                    currentLat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                    currentLon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                    currentElevation = null
                    currentTime = null
                }
                "ele" -> if (currentLat != null) {
                    currentElevation = try { parser.nextText().toDoubleOrNull() } catch (_: Exception) { null }
                }
                "time" -> if (currentLat != null) {
                    currentTime = try { parser.nextText() } catch (_: Exception) { null }
                }
            }
            XmlPullParser.END_TAG -> when (parser.name.substringAfter(':').lowercase()) {
                "trkpt", "rtept" -> {
                    val lat = currentLat
                    val lon = currentLon
                    if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) {
                        val point = TrackPoint(lat, lon, currentElevation, currentTime)
                        previous?.let { distance += haversineMeters(it.latitude, it.longitude, lat, lon) / 1000.0 }
                        points.add(point)
                        previous = point
                    }
                    currentLat = null
                    currentLon = null
                    currentElevation = null
                    currentTime = null
                }
            }
        }
        event = parser.next()
    }

    require(points.size >= 2) { "The GPX file contains fewer than two valid track or route points." }
    return GpxTrack(name, points, distance)
}

fun haversineMeters(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
    val radius = 6371000.0
    val dLat = Math.toRadians(bLat - aLat)
    val dLon = Math.toRadians(bLon - aLon)
    val a = sin(dLat / 2).pow(2.0) +
        cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLon / 2).pow(2.0)
    return 2.0 * radius * asin(sqrt(a.coerceIn(0.0, 1.0)))
}

fun distanceToTrackMeters(latitude: Double, longitude: Double, points: List<TrackPoint>): Double? {
    if (points.isEmpty()) return null
    if (points.size == 1) return haversineMeters(latitude, longitude, points.first().latitude, points.first().longitude)

    val cosLat = cos(Math.toRadians(latitude)).coerceAtLeast(0.000001)
    fun x(lon: Double) = Math.toRadians(lon - longitude) * 6371000.0 * cosLat
    fun y(lat: Double) = Math.toRadians(lat - latitude) * 6371000.0

    var minimumSquared = Double.POSITIVE_INFINITY
    for (index in 0 until points.lastIndex) {
        val a = points[index]
        val b = points[index + 1]
        val ax = x(a.longitude)
        val ay = y(a.latitude)
        val bx = x(b.longitude)
        val by = y(b.latitude)
        val dx = bx - ax
        val dy = by - ay
        val denominator = dx * dx + dy * dy
        val t = if (denominator == 0.0) 0.0 else ((-ax * dx - ay * dy) / denominator).coerceIn(0.0, 1.0)
        val px = ax + t * dx
        val py = ay + t * dy
        minimumSquared = min(minimumSquared, px * px + py * py)
    }
    return sqrt(minimumSquared)
}

fun formatDistance(kilometres: Double): String = when {
    kilometres >= 100.0 -> "${"%.0f".format(kilometres)} km"
    kilometres >= 10.0 -> "${"%.1f".format(kilometres)} km"
    else -> "${"%.2f".format(kilometres)} km"
}

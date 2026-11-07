package com.terratrack

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.io.File

data class OfflinePoi(
    val id: String,
    val name: String,
    val category: String,
    val latitude: Double,
    val longitude: Double
) {
    fun toTrackWaypoint() = TrackWaypoint(name, latitude, longitude, category)
}

object OfflinePoiStore {
    private const val FILE_NAME = "offline_pois.json"
    private const val ENDPOINT = "https://overpass-api.de/api/interpreter"

    fun load(context: Context): List<OfflinePoi> {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return emptyList()
        return try {
            val array = JSONArray(file.readText(Charsets.UTF_8))
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val lat = item.optDouble("lat", Double.NaN)
                val lon = item.optDouble("lon", Double.NaN)
                if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) null
                else OfflinePoi(
                    item.optString("id"),
                    item.optString("name", "Unnamed place"),
                    item.optString("category", "other"),
                    lat,
                    lon
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(context: Context, pois: List<OfflinePoi>) {
        val array = JSONArray()
        pois.forEach { poi ->
            array.put(JSONObject()
                .put("id", poi.id)
                .put("name", poi.name)
                .put("category", poi.category)
                .put("lat", poi.latitude)
                .put("lon", poi.longitude))
        }
        File(context.filesDir, FILE_NAME).writeText(array.toString(), Charsets.UTF_8)
    }

    fun downloadAround(
        latitude: Double,
        longitude: Double,
        radiusMeters: Int,
        categories: Set<String>
    ): List<OfflinePoi> {
        require(latitude in -90.0..90.0 && longitude in -180.0..180.0) { "Invalid GPS coordinates." }
        require(radiusMeters in 1000..50000) { "Search radius must be between 1 and 50 km." }
        require(categories.isNotEmpty()) { "Select at least one POI category." }

        val filters = mutableListOf<String>()
        if ("fuel" in categories) {
            filters += listOf(
                "node(around:$radiusMeters,$latitude,$longitude)[\"amenity\"=\"fuel\"];",
                "way(around:$radiusMeters,$latitude,$longitude)[\"amenity\"=\"fuel\"];",
                "relation(around:$radiusMeters,$latitude,$longitude)[\"amenity\"=\"fuel\"];"
            )
        }
        if ("water" in categories) {
            filters += listOf(
                "node(around:$radiusMeters,$latitude,$longitude)[\"amenity\"=\"drinking_water\"];",
                "way(around:$radiusMeters,$latitude,$longitude)[\"amenity\"=\"drinking_water\"];",
                "node(around:$radiusMeters,$latitude,$longitude)[\"natural\"=\"spring\"];",
                "way(around:$radiusMeters,$latitude,$longitude)[\"natural\"=\"spring\"];"
            )
        }
        if ("repair" in categories) {
            filters += listOf(
                "node(around:$radiusMeters,$latitude,$longitude)[\"shop\"=\"motorcycle\"];",
                "way(around:$radiusMeters,$latitude,$longitude)[\"shop\"=\"motorcycle\"];",
                "node(around:$radiusMeters,$latitude,$longitude)[\"shop\"=\"car_repair\"];",
                "way(around:$radiusMeters,$latitude,$longitude)[\"shop\"=\"car_repair\"];",
                "node(around:$radiusMeters,$latitude,$longitude)[\"craft\"=\"car_repair\"];",
                "way(around:$radiusMeters,$latitude,$longitude)[\"craft\"=\"car_repair\"];"
            )
        }
        if ("camping" in categories) {
            filters += listOf(
                "node(around:$radiusMeters,$latitude,$longitude)[\"tourism\"=\"camp_site\"];",
                "way(around:$radiusMeters,$latitude,$longitude)[\"tourism\"=\"camp_site\"];",
                "relation(around:$radiusMeters,$latitude,$longitude)[\"tourism\"=\"camp_site\"];",
                "node(around:$radiusMeters,$latitude,$longitude)[\"tourism\"=\"caravan_site\"];",
                "way(around:$radiusMeters,$latitude,$longitude)[\"tourism\"=\"caravan_site\"];",
                "relation(around:$radiusMeters,$latitude,$longitude)[\"tourism\"=\"caravan_site\"];"
            )
        }

        val query = "[out:json][timeout:45];(\n${filters.joinToString("\n")}\n);out center tags;"
        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20000
            readTimeout = 60000
            doOutput = true
            setRequestProperty("User-Agent", "TerraTrack-Android/0.3.0 (offline travel POI)")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val body = "data=" + URLEncoder.encode(query, StandardCharsets.UTF_8.name())
            connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            if (code !in 200..299) throw IllegalStateException("Overpass returned HTTP $code.")
            parseOverpass(response)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseOverpass(response: String): List<OfflinePoi> {
        val elements = JSONObject(response).optJSONArray("elements") ?: JSONArray()
        val result = LinkedHashMap<String, OfflinePoi>()
        for (index in 0 until elements.length()) {
            val item = elements.optJSONObject(index) ?: continue
            val type = item.optString("type")
            val id = item.optLong("id", -1L)
            if (id < 0 || type.isBlank()) continue
            val tags = item.optJSONObject("tags") ?: continue
            val location = item.optJSONObject("center") ?: item
            val lat = location.optDouble("lat", Double.NaN)
            val lon = location.optDouble("lon", Double.NaN)
            if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) continue

            val amenity = tags.optString("amenity")
            val natural = tags.optString("natural")
            val shop = tags.optString("shop")
            val craft = tags.optString("craft")
            val tourism = tags.optString("tourism")
            val category = when {
                amenity == "fuel" -> "fuel"
                amenity == "drinking_water" || natural == "spring" -> "water"
                shop == "motorcycle" || shop == "car_repair" || craft == "car_repair" -> "repair"
                tourism == "camp_site" || tourism == "caravan_site" -> "camping"
                else -> continue
            }
            val fallback = when (category) {
                "fuel" -> "Fuel station"
                "water" -> if (natural == "spring") "Spring" else "Drinking water"
                "repair" -> if (shop == "motorcycle") "Motorcycle workshop" else "Vehicle workshop"
                else -> "Camping"
            }
            val name = tags.optString("name").takeIf { it.isNotBlank() } ?: fallback
            val key = "$type/$id"
            result[key] = OfflinePoi(key, name, category, lat, lon)
        }
        return result.values.toList()
    }
}

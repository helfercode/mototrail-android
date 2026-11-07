package com.terratrack

import android.location.Location
import android.view.MotionEvent
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.mapsforge.core.graphics.Style
import org.mapsforge.core.model.LatLong
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.util.AndroidUtil
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.layer.overlay.FixedPixelCircle
import org.mapsforge.map.layer.overlay.Polyline
import org.mapsforge.map.reader.MapFile
import org.mapsforge.map.rendertheme.internal.MapsforgeThemes
import java.io.File

@Composable
fun OfflineMapView(
    mapPath: String?,
    trackPoints: List<TrackPoint>,
    trackSignature: Int,
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
    val mapViewRef = remember(mapPath) { mutableStateOf<MapView?>(null) }
    val routeLayer = remember(mapPath) { mutableStateOf<Polyline?>(null) }
    val userCircle = remember(mapPath) { mutableStateOf<FixedPixelCircle?>(null) }
    val renderedSignature = remember(mapPath) { mutableIntStateOf(Int.MIN_VALUE) }
    val appliedZoomRequest = remember(mapPath) { mutableIntStateOf(zoomRequest) }
    val appliedRecenterRequest = remember(mapPath) { mutableIntStateOf(recenterRequest) }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            MapView(context).apply {
                setBuiltInZoomControls(false)
                mapScaleBar.isVisible = true
                setZoomLevelMin(3.toByte())
                setZoomLevelMax(20.toByte())
                setOnTouchListener { _, event ->
                    if (event.action == MotionEvent.ACTION_DOWN) latestTouch()
                    false
                }

                val file = mapPath?.let(::File)
                if (file != null && file.exists() && file.length() > 0L) {
                    try {
                        val cache = AndroidUtil.createTileCache(
                            context,
                            "terratrack_${file.name.hashCode()}",
                            model.displayModel.tileSize,
                            1f,
                            model.frameBufferModel.overdrawFactor
                        )
                        val dataStore = MapFile(file)
                        val renderer = org.mapsforge.map.layer.renderer.TileRendererLayer(
                            cache,
                            dataStore,
                            model.mapViewPosition,
                            AndroidGraphicFactory.INSTANCE
                        )
                        renderer.setXmlRenderTheme(MapsforgeThemes.MOTORIDER)
                        layerManager.layers.add(renderer)
                        setCenter(dataStore.startPosition() ?: LatLong(38.7223, -9.1393))
                        setZoomLevel(dataStore.startZoomLevel() ?: 7.toByte())
                    } catch (error: Exception) {
                        latestError("Map could not be opened: ${error.message ?: "unsupported map file"}")
                        setCenter(LatLong(38.7223, -9.1393))
                        setZoomLevel(7.toByte())
                    }
                } else {
                    setCenter(LatLong(38.7223, -9.1393))
                    setZoomLevel(7.toByte())
                }
                mapViewRef.value = this
            }
        },
        update = { view ->
            if (renderedSignature.intValue != trackSignature) {
                routeLayer.value?.let { view.layerManager.layers.remove(it) }
                routeLayer.value = null
                if (trackPoints.size > 1) {
                    val paint = AndroidGraphicFactory.INSTANCE.createPaint().apply {
                        color = AndroidGraphicFactory.INSTANCE.createColor(org.mapsforge.core.graphics.Color.RED)
                        strokeWidth = 8f * view.model.displayModel.scaleFactor
                        style = Style.STROKE
                    }
                    val polyline = Polyline(paint, AndroidGraphicFactory.INSTANCE)
                    polyline.setPoints(trackPoints.map { LatLong(it.latitude, it.longitude) })
                    view.layerManager.layers.add(polyline)
                    routeLayer.value = polyline
                }
                renderedSignature.intValue = trackSignature
            }

            userCircle.value?.let { view.layerManager.layers.remove(it) }
            userCircle.value = null
            currentLocation?.let { location ->
                val fill = AndroidGraphicFactory.INSTANCE.createPaint().apply {
                    color = AndroidGraphicFactory.INSTANCE.createColor(org.mapsforge.core.graphics.Color.BLUE)
                    style = Style.FILL
                }
                val outline = AndroidGraphicFactory.INSTANCE.createPaint().apply {
                    color = AndroidGraphicFactory.INSTANCE.createColor(org.mapsforge.core.graphics.Color.WHITE)
                    strokeWidth = 2f * view.model.displayModel.scaleFactor
                    style = Style.STROKE
                }
                val dot = FixedPixelCircle(
                    LatLong(location.latitude, location.longitude),
                    10,
                    fill,
                    outline
                )
                view.layerManager.layers.add(dot)
                userCircle.value = dot
                if (followGps) view.setCenter(LatLong(location.latitude, location.longitude))
            }

            if (zoomRequest != appliedZoomRequest.intValue) {
                val delta = zoomRequest - appliedZoomRequest.intValue
                repeat(kotlin.math.abs(delta).coerceAtMost(8)) {
                    if (delta > 0) view.model.mapViewPosition.zoomIn(false)
                    else view.model.mapViewPosition.zoomOut(false)
                }
                appliedZoomRequest.intValue = zoomRequest
            }
            if (recenterRequest != appliedRecenterRequest.intValue) {
                currentLocation?.let { view.setCenter(LatLong(it.latitude, it.longitude)) }
                appliedRecenterRequest.intValue = recenterRequest
            }
        }
    )

    DisposableEffect(mapPath) {
        onDispose {
            mapViewRef.value?.destroyAll()
            mapViewRef.value = null
            routeLayer.value = null
            userCircle.value = null
        }
    }
}

package com.myassistant.app.ui

import android.graphics.Color as AColor
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.myassistant.app.health.ActivityStore
import com.myassistant.app.health.GeoPoint
import com.myassistant.app.health.MapRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import org.osmdroid.util.GeoPoint as OsmPoint

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    var request by remember { mutableStateOf(vm.mapRequest ?: todayRequest()) }
    var points by remember { mutableStateOf<List<GeoPoint>>(emptyList()) }
    var map by remember { mutableStateOf<MapView?>(null) }

    LaunchedEffect(request) {
        points = withContext(Dispatchers.IO) {
            val store = ActivityStore.get(ctx)
            if (request.walkId != null) store.points(0, 0, request.walkId) else store.points(request.from, request.to)
        }.filter { it.accuracy <= 100f }
    }
    LaunchedEffect(points, map) { map?.let { draw(it, points) } }

    val km = ActivityStore.distance(points) / 1000
    val fmt = SimpleDateFormat("HH:mm", Locale.US)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("🗺️ ${request.title}", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { vm.screen = Screen.Chat }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = request.title == "النهارده", onClick = { request = todayRequest() }, label = { Text("النهارده") })
                FilterChip(selected = request.title == "امبارح", onClick = { request = dayRequest(1, "امبارح") }, label = { Text("امبارح") })
                FilterChip(selected = request.walkId != null, onClick = {
                    ActivityStore.get(ctx).walks(1).firstOrNull()?.let { w ->
                        request = MapRequest(w.start, w.end ?: System.currentTimeMillis(), w.id, "آخر مشوار")
                    }
                }, label = { Text("آخر مشوار") })
            }
            Text(
                if (points.isEmpty()) "مفيش أماكن متسجّلة للوقت ده. فعّل \"الموقع طول الوقت\" من الصلاحيات، أو قول \"ابدأ تتبّع المشي\"."
                else String.format(Locale.US, "%.2f كم", km) + " · من ${fmt.format(Date(points.first().time))} لـ ${fmt.format(Date(points.last().time))} · ${points.size} نقطة",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { c ->
                    Configuration.getInstance().apply {
                        userAgentValue = c.packageName
                        osmdroidBasePath = File(c.filesDir, "osmdroid")
                        osmdroidTileCache = File(c.cacheDir, "osm-tiles")
                    }
                    MapView(c).apply {
                        setTileSource(TileSourceFactory.MAPNIK)
                        setMultiTouchControls(true)
                        controller.setZoom(15.0)
                        map = this
                    }
                },
                onRelease = { it.onDetach() },
            )
        }
    }
}

private fun draw(map: MapView, points: List<GeoPoint>) {
    map.overlays.clear()
    if (points.isEmpty()) {
        map.invalidate()
        return
    }
    val geo = points.map { OsmPoint(it.lat, it.lng) }
    val line = Polyline(map).apply {
        setPoints(geo)
        outlinePaint.color = AColor.parseColor("#4F46E5")
        outlinePaint.strokeWidth = 12f
    }
    map.overlays.add(line)
    map.overlays.add(Marker(map).apply { position = geo.first(); title = "البداية"; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM) })
    if (geo.size > 1) map.overlays.add(Marker(map).apply { position = geo.last(); title = "النهاية"; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM) })
    map.post {
        if (geo.size == 1) {
            map.controller.setZoom(16.0); map.controller.setCenter(geo.first())
        } else {
            map.zoomToBoundingBox(BoundingBox.fromGeoPoints(geo).increaseByScale(1.3f), false)
        }
    }
    map.invalidate()
}

private fun dayRequest(daysAgo: Int, title: String): MapRequest {
    val start = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_MONTH, -daysAgo)
    }.timeInMillis
    return MapRequest(start, if (daysAgo == 0) System.currentTimeMillis() else start + 86_400_000L, null, title)
}

private fun todayRequest() = dayRequest(0, "النهارده")

package com.bayra.customer

import android.os.Bundle
import android.preference.PreferenceManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight // 🛡️ THE MISSING WELD
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.firebase.database.FirebaseDatabase
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.net.URL
import kotlin.concurrent.thread
import kotlin.math.*

// --- THE GAMO LAWS (FUEL PHYSICS 275 ETB/L) ---
enum class ServiceTier(val label: String, val base: Int, val fuelCost: Double, val extra: Int) {
    POOL("Pool", 50, 11.0, 30),
    COMFORT("Comfort", 50, 11.0, 0),
    CODE_3("Code 3", 50, 27.5, 60)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        Configuration.getInstance().userAgentValue = "BayraSovereignV1"
        setContent { MaterialTheme { SovereignRoadApp() } }
    }
}

@Composable
fun SovereignRoadApp() {
    var step by remember { mutableStateOf("PICKUP") }
    var pickupPt by remember { mutableStateOf<GeoPoint?>(null) }
    var destPt by remember { mutableStateOf<GeoPoint?>(null) }
    var roadDistance by remember { mutableStateOf(0.0) }
    var routePoints by remember { mutableStateOf<List<GeoPoint>>(listOf()) }
    var isCalculating by remember { mutableStateOf(false) }
    var selectedTier by remember { mutableStateOf(ServiceTier.COMFORT) }
    var mapView: MapView? by remember { mutableStateOf(null) }

    fun fetchRoadRoute(start: GeoPoint, end: GeoPoint) {
        isCalculating = true
        thread {
            try {
                val url = "https://router.project-osrm.org/route/v1/driving/${start.longitude},${start.latitude};${end.longitude},${end.latitude}?overview=full&geometries=geojson"
                val response = URL(url).readText()
                val json = JSONObject(response)
                val route = json.getJSONArray("routes").getJSONObject(0)
                val dist = route.getDouble("distance") / 1000.0
                val geometry = route.getJSONObject("geometry").getJSONArray("coordinates")
                val points = mutableListOf<GeoPoint>()
                for (i in 0 until geometry.length()) {
                    val coord = geometry.getJSONArray(i)
                    points.add(GeoPoint(coord.getDouble(1), coord.getDouble(0)))
                }
                roadDistance = dist
                routePoints = points
                isCalculating = false
            } catch (e: Exception) { isCalculating = false }
        }
    }

    val fare = remember(selectedTier, roadDistance) {
        val total = selectedTier.base + (roadDistance * selectedTier.fuelCost) + selectedTier.extra
        (total * 1.15).toInt()
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                MapView(ctx).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    controller.setZoom(15.0)
                    controller.setCenter(GeoPoint(6.0238, 37.5532))
                    mapView = this
                }
            },
            update = { view ->
                view.overlays.clear()
                pickupPt?.let { pt ->
                    Marker(view).apply { position = pt; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM) }.also { view.overlays.add(it) }
                }
                destPt?.let { pt ->
                    Marker(view).apply { position = pt; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM) }.also { view.overlays.add(it) }
                    if (routePoints.isNotEmpty()) {
                        Polyline().apply { setPoints(routePoints); color = android.graphics.Color.BLACK; width = 10f }.also { view.overlays.add(it) }
                    }
                }
                view.invalidate()
            }
        )

        if (step != "BOOK") {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if(step == "PICKUP") "PICKUP" else "DESTINATION", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                    Canvas(Modifier.size(30.dp)) {
                        val path = Path().apply {
                            moveTo(size.width/2, size.height)
                            cubicTo(0f, size.height/2, size.width/4, 0f, size.width/2, 0f)
                            cubicTo(3*size.width/4, 0f, size.width, size.height/2, size.width/2, size.height)
                        }
                        drawPath(path, Color.Black)
                    }
                }
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.White, RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)).padding(24.dp)) {
            if (step == "PICKUP") {
                Button(onClick = { pickupPt = mapView?.mapCenter as GeoPoint; step = "DEST" }, Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Black)) {
                    Text("SET PICKUP", fontWeight = FontWeight.Bold)
                }
            } else if (step == "DEST") {
                Button(onClick = { 
                    val end = mapView?.mapCenter as GeoPoint
                    destPt = end
                    fetchRoadRoute(pickupPt!!, end)
                    step = "BOOK" 
                }, Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5E4E92))) {
                    Text("SET DESTINATION", fontWeight = FontWeight.Bold)
                }
            } else {
                if (isCalculating) {
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = Color(0xFF5E4E92))
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(ServiceTier.values()) { tier ->
                            Surface(Modifier.clickable { selectedTier = tier }, color = if(selectedTier == tier) Color(0xFF4CAF50) else Color(0xFFF5F5F5), shape = RoundedCornerShape(12.dp)) {
                                Text(tier.label, Modifier.padding(16.dp, 10.dp), fontWeight = FontWeight.Bold, color = if(selectedTier == tier) Color.White else Color.Black)
                            }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        Text("${"%.2f".format(roadDistance)} KM", fontWeight = FontWeight.Bold)
                        Text("$fare ETB", fontSize = 32.sp, fontWeight = FontWeight.Black, color = Color.Red)
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { 
                        val ref = FirebaseDatabase.getInstance().getReference("rides")
                        val id = "R_${System.currentTimeMillis()}"
                        ref.child(id).setValue(mapOf("id" to id, "status" to "REQUESTED", "price" to fare.toString(), "pLat" to pickupPt!!.latitude, "pLon" to pickupPt!!.longitude, "dLat" to destPt!!.latitude, "dLon" to destPt!!.longitude))
                    }, Modifier.fillMaxWidth().height(65.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5E4E92))) {
                        Text("BOOK NOW", fontWeight = FontWeight.Bold)
                    }
                    TextButton({ step = "PICKUP"; pickupPt = null; destPt = null; routePoints = listOf() }, Modifier.fillMaxWidth()) { Text("RESET") }
                }
            }
        }
    }
}

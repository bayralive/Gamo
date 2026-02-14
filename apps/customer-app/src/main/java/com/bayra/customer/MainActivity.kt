package com.bayra.customer

import android.os.Bundle
import android.preference.PreferenceManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.firebase.database.FirebaseDatabase
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import kotlin.math.*

// --- THE GAMO LAWS (PILLAR 1: FUEL PHYSICS) ---
// Benzine: 275 ETB/L | Bajaj: 25km/L (11 ETB/km) | Code 3: 10km/L (27.5 ETB/km)
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
        setContent { MaterialTheme { SovereignRadar() } }
    }
}

@Composable
fun SovereignRadar() {
    var step by remember { mutableStateOf("PICKUP") } // PICKUP -> DEST -> BOOK
    var pickupPt by remember { mutableStateOf<GeoPoint?>(null) }
    var destPt by remember { mutableStateOf<GeoPoint?>(null) }
    var selectedTier by remember { mutableStateOf(ServiceTier.COMFORT) }
    
    // Map Control Reference
    var mapView: MapView? by remember { mutableStateOf(null) }

    // THE DISTANCE CALCULATOR (Haversine)
    val distance = remember(pickupPt, destPt) {
        if (pickupPt != null && destPt != null) {
            val lat1 = pickupPt!!.latitude; val lon1 = pickupPt!!.longitude
            val lat2 = destPt!!.latitude; val lon2 = destPt!!.longitude
            val a = sin(Math.toRadians(lat2-lat1)/2).pow(2) + 
                    cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * 
                    sin(Math.toRadians(lon2-lon1)/2).pow(2)
            6371.0 * 2 * asin(sqrt(a))
        } else 0.0
    }

    // THE PRICE WELD (+15% Bayra Commission)
    val fare = remember(selectedTier, distance) {
        val total = selectedTier.base + (distance * selectedTier.fuelCost) + selectedTier.extra
        (total * 1.15).toInt()
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                MapView(ctx).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    // --- 🛰️ HORN OF AFRICA LIMITS ---
                    minZoomLevel = 4.0 
                    controller.setZoom(15.0)
                    controller.setCenter(GeoPoint(6.0238, 37.5532)) // Arba Minch
                    mapView = this
                }
            },
            update = { view ->
                view.overlays.clear()
                pickupPt?.let {
                    val m = Marker(view)
                    m.position = it
                    m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    m.icon = view.context.getDrawable(android.R.drawable.ic_menu_myplaces)
                    view.overlays.add(m)
                }
                destPt?.let {
                    val m = Marker(view)
                    m.position = it
                    m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    m.icon = view.context.getDrawable(android.R.drawable.ic_menu_directions)
                    view.overlays.add(m)
                    
                    val line = Polyline()
                    line.addPoint(pickupPt); line.addPoint(it)
                    line.color = android.graphics.Color.BLACK
                    line.width = 8f
                    view.overlays.add(line)
                }
                view.invalidate()
            }
        )

        // --- THE CENTER COMMAND PIN (BLACK WATER DROP) ---
        if (step != "BOOK") {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if(step == "PICKUP") "START" else "FINISH", color = Color.Black, fontSize = 10.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    Canvas(Modifier.size(30.dp)) {
                        val path = Path().apply {
                            moveTo(size.width/2, size.height)
                            cubicTo(0f, size.height/2, size.width/4, 0f, size.width/2, 0f)
                            cubicTo(3*size.width/4, 0f, size.width, size.height/2, size.width/2, size.height)
                        }
                        drawPath(path, Color.Black)
                        drawCircle(Color.White, radius = 5.dp.toPx(), center = center.copy(y = center.y - 5.dp.toPx()))
                    }
                }
            }
        }

        // --- CONTROL PANEL ---
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.White, RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)).padding(24.dp)) {
            if (step == "PICKUP") {
                Text("Select Pickup Point", color = Color.Gray)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { 
                    pickupPt = mapView?.mapCenter as GeoPoint
                    step = "DEST" 
                }, Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Black)) {
                    Text("CONFIRM PICKUP")
                }
            } else if (step == "DEST") {
                Text("Select Destination", color = Color(0xFF5E4E92), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { 
                    destPt = mapView?.mapCenter as GeoPoint
                    step = "BOOK" 
                }, Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5E4E92))) {
                    Text("CONFIRM DESTINATION")
                }
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(ServiceTier.values()) { tier ->
                        val sel = selectedTier == tier
                        Surface(Modifier.clickable { selectedTier = tier }, color = if(sel) Color(0xFF4CAF50) else Color(0xFFF5F5F5), shape = RoundedCornerShape(12.dp)) {
                            Text(tier.label, Modifier.padding(16.dp, 10.dp), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = if(sel) Color.White else Color.Black)
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                    Text("DISTANCE: ${"%.2f".format(distance)} KM", color = Color.Gray)
                    Text("$fare ETB", fontSize = 28.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Black, color = Color.Red)
                }
                Spacer(Modifier.height(16.dp))
                Button(onClick = {
                    val ref = FirebaseDatabase.getInstance().getReference("rides")
                    val id = "R_${System.currentTimeMillis()}"
                    ref.child(id).setValue(mapOf("id" to id, "status" to "REQUESTED", "price" to fare.toString(), "tier" to selectedTier.label, "pLat" to pickupPt!!.latitude, "pLon" to pickupPt!!.longitude, "dLat" to destPt!!.latitude, "dLon" to destPt!!.longitude))
                }, Modifier.fillMaxWidth().height(65.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5E4E92))) { Text("BOOK RIDE") }
                TextButton({ step = "PICKUP"; pickupPt = null; destPt = null }, Modifier.fillMaxWidth()) { Text("RESET POINTS") }
            }
        }
    }
}

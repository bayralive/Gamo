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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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

enum class ServiceTier(val label: String, val base: Int, val rate: Double) {
    POOL("Pool", 80, 15.0), COMFORT("Comfort", 120, 20.0), CODE_3("Code 3", 280, 35.0)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        Configuration.getInstance().userAgentValue = "BayraV1"
        setContent { MaterialTheme { NativeRouterApp() } }
    }
}

@Composable
fun NativeRouterApp() {
    var step by remember { mutableStateOf("PICKUP") }
    var mapCenter by remember { mutableStateOf(GeoPoint(6.0333, 37.5500)) }
    var pickupPt by remember { mutableStateOf<GeoPoint?>(null) }
    var dropoffPt by remember { mutableStateOf<GeoPoint?>(null) }
    var selectedTier by remember { mutableStateOf(ServiceTier.COMFORT) }

    val distanceKm = if (pickupPt != null && dropoffPt != null) {
        val lat1 = pickupPt!!.latitude; val lon1 = pickupPt!!.longitude
        val lat2 = dropoffPt!!.latitude; val lon2 = dropoffPt!!.longitude
        val a = sin(Math.toRadians(lat2-lat1)/2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(Math.toRadians(lon2-lon1)/2).pow(2)
        6371.0 * 2 * asin(sqrt(a))
    } else 0.0

    val totalFare = (selectedTier.base + (distanceKm * selectedTier.rate * 1.15)).toInt()

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx -> MapView(ctx).apply { 
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                controller.setZoom(16.0)
                controller.setCenter(mapCenter)
            } },
            update = { view ->
                view.overlays.clear()
                pickupPt?.let { pt -> Marker(view).apply { position = pt; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM); title = "Pickup" }.also { view.overlays.add(it) } }
                dropoffPt?.let { pt -> 
                    Marker(view).apply { position = pt; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM); title = "Drop-off" }.also { view.overlays.add(it) }
                    Polyline().apply { addPoint(pickupPt); addPoint(dropoffPt); color = android.graphics.Color.BLACK; width = 8f }.also { view.overlays.add(it) }
                }
                mapCenter = view.mapCenter as GeoPoint
                view.invalidate()
            }
        )

        if (step != "CONFIRM") {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if(step == "PICKUP") "📍" else "🏁", fontSize = 40.sp, modifier = Modifier.padding(bottom = 40.dp))
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.White, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).padding(24.dp)) {
            if (step == "PICKUP") {
                Text("LOCK PICKUP", fontWeight = FontWeight.Bold, color = Color(0xFF5E4E92))
                Button(onClick = { pickupPt = mapCenter; step = "DROPOFF" }, Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Black)) { Text("CONFIRM PICKUP") }
            } else if (step == "DROPOFF") {
                Text("LOCK DESTINATION", fontWeight = FontWeight.Bold, color = Color.Red)
                Button(onClick = { dropoffPt = mapCenter; step = "CONFIRM" }, Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5E4E92))) { Text("CALCULATE FARE") }
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ServiceTier.values()) { tier ->
                        Surface(Modifier.clickable { selectedTier = tier }, color = if(selectedTier == tier) Color(0xFF4CAF50) else Color(0xFFF0F0F0), shape = RoundedCornerShape(8.dp)) {
                            Text(tier.label, Modifier.padding(12.dp, 8.dp), fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                    Text("${"%.1f".format(distanceKm)} KM", color = Color.Gray)
                    Text("$totalFare ETB", fontSize = 24.sp, fontWeight = FontWeight.Black, color = Color.Red)
                }
                Button(onClick = {
                    val id = "R_${System.currentTimeMillis()}"
                    FirebaseDatabase.getInstance().getReference("rides/$id").setValue(mapOf("id" to id, "status" to "REQUESTED", "price" to totalFare.toString(), "tier" to selectedTier.label, "pLat" to pickupPt!!.latitude, "pLon" to pickupPt!!.longitude, "dLat" to dropoffPt!!.latitude, "dLon" to dropoffPt!!.longitude))
                }, Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5E4E92))) { Text("BOOK NOW") }
                TextButton({ step = "PICKUP"; pickupPt = null; dropoffPt = null }, Modifier.fillMaxWidth()) { Text("RESET MAP") }
            }
        }
    }
}

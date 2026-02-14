package com.bayra.customer

import android.os.Bundle
import android.preference.PreferenceManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.net.URL
import kotlin.concurrent.thread
import kotlinx.coroutines.delay
import java.util.*

// --- THE GAMO LAWS (FUEL PHYSICS: 275 ETB/L) ---
enum class ServiceTier(val label: String, val base: Int, val fuelKm: Double, val extra: Int, val isHr: Boolean) {
    POOL("Pool", 50, 11.0, 30, false),      // 25km/L
    COMFORT("Comfort", 50, 11.0, 0, false), // 25km/L
    CODE_3("Code 3", 50, 27.5, 60, false),  // 10km/L
    BAJAJ_HR("Bajaj Hr", 350, 0.0, 0, true),
    C3_HR("C3 Hr", 550, 0.0, 0, true)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        Configuration.getInstance().userAgentValue = "BayraSovereign"
        setContent { MaterialTheme { BookingEngine() } }
    }
}

@Composable
fun BookingEngine() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("bayra_vFinal", Context.MODE_PRIVATE)
    var name by remember { mutableStateOf(prefs.getString("n", "") ?: "") }
    var isAuth by remember { mutableStateOf(name.isNotEmpty()) }

    if (!isAuth) {
        Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
            Text("BAYRA LOGIN", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color(0xFF5E4E92))
            Spacer(Modifier.height(30.dp))
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
            Button({ if(name.isNotEmpty()){ prefs.edit().putString("n", name).apply(); isAuth = true } }, Modifier.fillMaxWidth().height(60.dp)) { Text("ENTER") }
        }
    } else {
        SovereignMapHub(name)
    }
}

@Composable
fun SovereignMapHub(pName: String) {
    var step by remember { mutableStateOf("PICKUP") }
    var pickupPt by remember { mutableStateOf<GeoPoint?>(null) }
    var destPt by remember { mutableStateOf<GeoPoint?>(null) }
    var roadDistance by remember { mutableStateOf(0.0) }
    var routePoints by remember { mutableStateOf<List<GeoPoint>>(listOf()) }
    var selectedTier by remember { mutableStateOf(ServiceTier.COMFORT) }
    var hrCount by remember { mutableStateOf(1) }
    var mapView: MapView? by remember { mutableStateOf(null) }

    // --- REACTIVE PRICE CALCULATION ---
    val isNight = Calendar.getInstance().get(Calendar.HOUR_OF_DAY).let { it >= 20 || it < 6 }
    val currentPrice = remember(selectedTier, roadDistance, hrCount, isNight) {
        if (selectedTier.isHr) {
            ((selectedTier.base + (if(isNight) 100 else 0)) * hrCount * 1.15).toInt()
        } else {
            val distCost = roadDistance * selectedTier.fuelKm
            ((selectedTier.base + distCost + selectedTier.extra + (if(isNight) 200 else 0)) * 1.15).toInt()
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx -> MapView(ctx).apply { setTileSource(TileSourceFactory.MAPNIK); setMultiTouchControls(true); controller.setZoom(16.0); controller.setCenter(GeoPoint(6.0238, 37.5532)); mapView = this } },
            update = { view ->
                view.overlays.clear()
                pickupPt?.let { pt -> Marker(view).apply { position = pt; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM) }.also { view.overlays.add(it) } }
                destPt?.let { pt -> 
                    Marker(view).apply { position = pt; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM) }.also { view.overlays.add(it) }
                    if (routePoints.isNotEmpty()) Polyline().apply { setPoints(routePoints); color = android.graphics.Color.BLACK; width = 10f }.also { view.overlays.add(it) }
                }
                view.invalidate()
            }
        )

        // 📍 COMMAND PIN
        if (step != "CONFIRM") {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(if(step == "PICKUP") "📍" else "🏁", fontSize = 40.sp, modifier = Modifier.padding(bottom = 40.dp))
            }
        }

        // 🎛️ PRICING CONTROL HUB
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.White, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).padding(24.dp)) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(ServiceTier.values()) { tier ->
                    val sel = selectedTier == tier
                    Surface(
                        Modifier.clickable { 
                            selectedTier = tier 
                            if(tier.isHr) step = "CONFIRM" else if(step == "CONFIRM" && roadDistance == 0.0) step = "PICKUP"
                        }, 
                        color = if(sel) Color(0xFF4CAF50) else Color(0xFFF0F0F0), 
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(tier.label, Modifier.padding(14.dp, 10.dp), fontWeight = FontWeight.Bold, color = if(sel) Color.White else Color.Black)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            if (step == "PICKUP") {
                Button(onClick = { pickupPt = mapView?.mapCenter as GeoPoint; step = "DEST" }, Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Black)) { Text("SET PICKUP") }
            } else if (step == "DEST") {
                Button(onClick = { 
                    val end = mapView?.mapCenter as GeoPoint; destPt = end
                    thread {
                        try {
                            val url = "https://router.project-osrm.org/route/v1/driving/${pickupPt!!.longitude},${pickupPt!!.latitude};${end.longitude},${end.latitude}?overview=full&geometries=geojson"
                            val json = JSONObject(URL(url).readText())
                            val route = json.getJSONArray("routes").getJSONObject(0)
                            roadDistance = route.getDouble("distance") / 1000.0
                            val geometry = route.getJSONObject("geometry").getJSONArray("coordinates")
                            val pts = mutableListOf<GeoPoint>()
                            for (i in 0 until geometry.length()) { pts.add(GeoPoint(geometry.getJSONArray(i).getDouble(1), geometry.getJSONArray(i).getDouble(0))) }
                            routePoints = pts
                            step = "CONFIRM"
                        } catch (e: Exception) { }
                    }
                }, Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5E4E92))) { Text("SET DESTINATION") }
            } else {
                // --- THE FINAL BILLING VIEW ---
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Column {
                        Text(if(selectedTier.isHr) "UNLIMITED KM" else "${"%.2f".format(roadDistance)} KM", color = Color.Gray, fontSize = 12.sp)
                        Text("$currentPrice ETB", fontSize = 32.sp, fontWeight = FontWeight.Black, color = Color.Red)
                    }
                    if (selectedTier.isHr) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button({ if(hrCount > 1) hrCount-- }, modifier = Modifier.size(36.dp), contentPadding = PaddingValues(0.dp)) { Text("-") }
                            Text("$hrCount HR", Modifier.padding(horizontal = 12.dp), fontWeight = FontWeight.Bold)
                            Button({ if(hrCount < 12) hrCount++ }, modifier = Modifier.size(36.dp), contentPadding = PaddingValues(0.dp)) { Text("+") }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Button(onClick = {
                    val ref = FirebaseDatabase.getInstance().getReference("rides")
                    val id = "R_${System.currentTimeMillis()}"
                    ref.child(id).setValue(mapOf("id" to id, "pName" to pName, "status" to "REQUESTED", "price" to currentPrice.toString(), "tier" to selectedTier.label, "pLat" to pickupPt?.latitude, "pLon" to pickupPt?.longitude))
                }, Modifier.fillMaxWidth().height(65.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5E4E92))) { Text("BOOK ${selectedTier.label.uppercase()}") }
                TextButton({ step = "PICKUP"; pickupPt = null; destPt = null; routePoints = listOf(); roadDistance = 0.0 }, Modifier.fillMaxWidth()) { Text("RESET MAP") }
            }
        }
    }
}

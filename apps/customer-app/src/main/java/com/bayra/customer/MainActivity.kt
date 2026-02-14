package com.bayra.customer

import android.content.Context // 🛡️ FIXED: ADDED MISSING IMPORT
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
import kotlin.math.*

// THE GAMO LAWS
enum class ServiceTier(val label: String, val base: Int, val kmRate: Double, val extra: Int, val isHr: Boolean) {
    POOL("Pool", 80, 15.0, 30, false),
    COMFORT("Comfort", 120, 11.0, 0, false),
    CODE_3("Code 3", 280, 27.5, 60, false),
    BAJAJ_HR("Bajaj Hr", 350, 0.0, 0, true),
    C3_HR("C3 Hr", 550, 0.0, 0, true)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        Configuration.getInstance().userAgentValue = "BayraSovereign"
        setContent { MaterialTheme { BayraSplash() } }
    }
}

@Composable
fun BayraSplash() {
    var show by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { delay(2000); show = false }
    if (show) {
        Box(Modifier.fillMaxSize().background(Color(0xFF5E4E92)), Alignment.Center) {
            Text("BAYRA", fontSize = 48.sp, fontWeight = FontWeight.Black, color = Color.White)
        }
    } else { PassengerAuth() }
}

@Composable
fun PassengerAuth() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("bayra_p_vFINAL", Context.MODE_PRIVATE)
    var name by remember { mutableStateOf(prefs.getString("n", "") ?: "") }
    var phone by remember { mutableStateOf(prefs.getString("p", "") ?: "") }
    var isAuth by remember { mutableStateOf(name.isNotEmpty()) }

    if (!isAuth) {
        Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
            Text("BAYRA LOGIN", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color(0xFF5E4E92))
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(name, { name = it }, label = { Text("Full Name") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(phone, { phone = it }, label = { Text("Phone Number") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(24.dp))
            Button({ if(name.isNotEmpty() && phone.isNotEmpty()){ prefs.edit().putString("n", name).putString("p", phone).apply(); isAuth = true } }, Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5E4E92))) { Text("ENTER") }
        }
    } else { BookingHub(name, phone) }
}

@Composable
fun BookingHub(pName: String, pPhone: String) {
    var step by remember { mutableStateOf("PICKUP") }
    var pickupPt by remember { mutableStateOf<GeoPoint?>(null) }
    var destPt by remember { mutableStateOf<GeoPoint?>(null) }
    var routePoints by remember { mutableStateOf<List<GeoPoint>>(listOf()) }
    var roadDistance by remember { mutableStateOf(0.0) }
    var selectedTier by remember { mutableStateOf(ServiceTier.COMFORT) }
    var hrCount by remember { mutableStateOf(1) }
    var mapView: MapView? by remember { mutableStateOf(null) }

    fun fetchRoute(start: GeoPoint, end: GeoPoint) {
        thread {
            try {
                val url = "https://router.project-osrm.org/route/v1/driving/${start.longitude},${start.latitude};${end.longitude},${end.latitude}?overview=full&geometries=geojson"
                val json = JSONObject(URL(url).readText())
                val route = json.getJSONArray("routes").getJSONObject(0)
                roadDistance = route.getDouble("distance") / 1000.0
                val geometry = route.getJSONObject("geometry").getJSONArray("coordinates")
                val pts = mutableListOf<GeoPoint>()
                for (i in 0 until geometry.length()) { pts.add(GeoPoint(geometry.getJSONArray(i).getDouble(1), geometry.getJSONArray(i).getDouble(0))) }
                routePoints = pts
            } catch (e: Exception) { }
        }
    }

    val isNight = Calendar.getInstance().get(Calendar.HOUR_OF_DAY).let { it >= 20 || it < 6 }
    
    // 🛡️ FIXED MATH: Used kmRate instead of fuelCost
    val totalFare = if (selectedTier.isHr) {
        ((selectedTier.base + (if(isNight) 100 else 0)) * hrCount * 1.15).toInt()
    } else {
        ((selectedTier.base + (roadDistance * selectedTier.kmRate) + selectedTier.extra + (if(isNight) 200 else 0)) * 1.15).toInt()
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

        if (step != "CONFIRM") {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(if(step == "PICKUP") "📍" else "🏁", fontSize = 40.sp, modifier = Modifier.padding(bottom = 40.dp))
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.White, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).padding(24.dp)) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(ServiceTier.values()) { tier ->
                    val sel = selectedTier == tier
                    Surface(Modifier.clickable { selectedTier = tier; if(tier.isHr) { step = "CONFIRM"; roadDistance = 0.0 } else { step = "PICKUP" } }, color = if(sel) Color(0xFF4CAF50) else Color(0xFFF0F0F0), shape = RoundedCornerShape(12.dp)) {
                        Text(tier.label, Modifier.padding(14.dp, 10.dp), fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            
            if (selectedTier.isHr) {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Text("Duration:")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button({ if(hrCount > 1) hrCount-- }, modifier = Modifier.size(40.dp)) { Text("-") }
                        Text("$hrCount HR", Modifier.padding(horizontal = 15.dp), fontWeight = FontWeight.Bold)
                        Button({ if(hrCount < 12) hrCount++ }, modifier = Modifier.size(40.dp)) { Text("+") }
                    }
                }
            }

            if (!selectedTier.isHr && step == "PICKUP") {
                Button(onClick = { pickupPt = mapView?.mapCenter as GeoPoint; step = "DEST" }, Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Black)) { Text("SET PICKUP") }
            } else if (!selectedTier.isHr && step == "DEST") {
                Button(onClick = { val end = mapView?.mapCenter as GeoPoint; destPt = end; fetchRoute(pickupPt!!, end); step = "CONFIRM" }, Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5E4E92))) { Text("SET DESTINATION") }
            } else {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                    Text(if(selectedTier.isHr) "LIMIT: ${hrCount * 12} KM" else "${"%.1f".format(roadDistance)} KM", color = Color.Gray)
                    Text("$totalFare ETB", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color.Red)
                }
                Spacer(Modifier.height(12.dp))
                Button(onClick = {
                    val ref = FirebaseDatabase.getInstance().getReference("rides")
                    val id = "R_${System.currentTimeMillis()}"
                    val lat = if(selectedTier.isHr) (mapView?.mapCenter as GeoPoint).latitude else pickupPt?.latitude
                    val lon = if(selectedTier.isHr) (mapView?.mapCenter as GeoPoint).longitude else pickupPt?.longitude
                    ref.child(id).setValue(mapOf("id" to id, "pName" to pName, "pPhone" to pPhone, "price" to totalFare.toString(), "status" to "REQUESTED", "tier" to selectedTier.label, "pLat" to lat, "pLon" to lon))
                }, Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5E4E92))) { Text("BOOK NOW") }
                TextButton({ step = "PICKUP"; pickupPt = null; destPt = null; routePoints = listOf() }, Modifier.fillMaxWidth()) { Text("RESET MAP") }
            }
        }
    }
}

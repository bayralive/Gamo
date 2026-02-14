package com.bayra.customer

import android.Manifest
import android.os.Bundle
import android.preference.PreferenceManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.firebase.database.FirebaseDatabase
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.net.URL
import kotlin.concurrent.thread
import kotlin.math.*

enum class ServiceTier(val label: String, val base: Int, val kmRate: Double, val extra: Int, val isHr: Boolean) {
    POOL("Pool", 80, 11.0, 30, false),
    COMFORT("Comfort", 120, 11.0, 0, false),
    CODE_3("Code 3", 280, 27.5, 60, false),
    BAJAJ_HR("Bajaj Hr", 350, 0.0, 0, true),
    C3_HR("C3 Hr", 550, 0.0, 0, true)
}

class MainActivity : ComponentActivity() {
    private var locationOverlay: MyLocationNewOverlay? = null
    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) locationOverlay?.enableMyLocation() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        Configuration.getInstance().userAgentValue = "BayraSovereign"
        setContent { MaterialTheme { PassengerApp() } }
        requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}

@Composable
fun PassengerApp() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("bayra_vFINAL", 0)
    var name by remember { mutableStateOf(prefs.getString("n", "") ?: "") }
    var phone by remember { mutableStateOf(prefs.getString("p", "") ?: "") }
    var isAuth by remember { mutableStateOf(name.isNotEmpty()) }

    if (!isAuth) {
        Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
            Text("BAYRA LOGIN", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color(0xFF5E4E92))
            Spacer(Modifier.height(30.dp))
            OutlinedTextField(name, { name = it }, label = { Text("Full Name") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(phone, { phone = it }, label = { Text("Phone Number") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(20.dp))
            Button({ if(name.length > 2 && phone.length > 8) { 
                prefs.edit().putString("n", name).putString("p", phone).apply()
                isAuth = true 
            } }, Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5E4E92))) { Text("ENTER") }
        }
    } else { BookingFlow(name, phone) }
}

@Composable
fun BookingFlow(pName: String, pPhone: String) {
    var step by remember { mutableStateOf("PICKUP") } // PICKUP -> DEST -> CONFIRM
    var pickupPt by remember { mutableStateOf<GeoPoint?>(null) }
    var destPt by remember { mutableStateOf<GeoPoint?>(null) }
    var roadDistance by remember { mutableStateOf(0.0) }
    var routePoints by remember { mutableStateOf<List<GeoPoint>>(listOf()) }
    var selectedTier by remember { mutableStateOf(ServiceTier.COMFORT) }
    var hrCount by remember { mutableStateOf(1) }
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var isSearching by remember { mutableStateOf(false) }

    val totalFare = remember(selectedTier, roadDistance, hrCount) {
        if (selectedTier.isHr) (selectedTier.base * hrCount * 1.15).toInt()
        else (selectedTier.base + (roadDistance * selectedTier.kmRate) + selectedTier.extra).toInt()
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                MapView(ctx).apply {
                    setTileSource(object : XYTileSource("Google-Sat", 0, 20, 256, ".jpg", arrayOf("https://mt0.google.com/vt/lyrs=s&", "https://mt1.google.com/vt/lyrs=s&")) {
                        override fun getTileURLString(pTileIndex: Long): String = baseUrl + "x=" + MapTileIndex.getX(pTileIndex) + "&y=" + MapTileIndex.getY(pTileIndex) + "&z=" + MapTileIndex.getZoom(pTileIndex)
                    })
                    setMultiTouchControls(true)
                    controller.setZoom(17.0)
                    controller.setCenter(GeoPoint(6.0333, 37.5500))
                    val overlay = MyLocationNewOverlay(GpsMyLocationProvider(ctx), this)
                    overlay.enableMyLocation()
                    overlays.add(overlay)
                    mapViewRef = this
                }
            },
            update = { view ->
                view.overlays.filterIsInstance<Marker>().forEach { view.overlays.remove(it) }
                view.overlays.filterIsInstance<Polyline>().forEach { view.overlays.remove(it) }
                pickupPt?.let { pt -> Marker(view).apply { position = pt; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM) }.also { view.overlays.add(it) } }
                if (!selectedTier.isHr) {
                    destPt?.let { pt -> 
                        Marker(view).apply { position = pt; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM); icon = view.context.getDrawable(android.R.drawable.ic_menu_directions) }.also { view.overlays.add(it) }
                        if (routePoints.isNotEmpty()) Polyline().apply { setPoints(routePoints); color = android.graphics.Color.WHITE; width = 12f }.also { view.overlays.add(it) }
                    }
                }
                view.invalidate()
            }
        )

        // --- 📍 CENTER PIN (BLACK WATER DROP) ---
        if (step != "CONFIRM" && !isSearching) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if(step == "PICKUP") "PICKUP" else "DESTINATION", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                    Canvas(Modifier.size(35.dp)) {
                        val path = Path().apply {
                            moveTo(size.width/2, size.height)
                            cubicTo(0f, size.height/2, size.width/4, 0f, size.width/2, 0f)
                            cubicTo(3*size.width/4, 0f, size.width, size.height/2, size.width/2, size.height)
                        }
                        drawPath(path, Color.Black)
                        drawCircle(Color.White, radius = 4.dp.toPx(), center = center.copy(y = center.y - 6.dp.toPx()))
                    }
                }
            }
        }

        // --- 🎛️ CONTROL HUB ---
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.White, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).padding(24.dp)) {
            if (isSearching) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color(0xFF5E4E92))
                    Spacer(Modifier.height(10.dp)); Text("SEARCHING...", fontWeight = FontWeight.Bold)
                    Button({ isSearching = false }, Modifier.fillMaxWidth().padding(top = 20.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text("CANCEL") }
                }
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(ServiceTier.values()) { tier ->
                        val sel = selectedTier == tier
                        Surface(Modifier.clickable { selectedTier = tier; if(tier.isHr) step = "CONFIRM" else step = "PICKUP" }, color = if(sel) Color(0xFF4CAF50) else Color(0xFFF0F0F0), shape = RoundedCornerShape(12.dp)) {
                            Text(tier.label, Modifier.padding(14.dp, 10.dp), fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                if (step == "PICKUP") {
                    Button({ pickupPt = mapViewRef?.mapCenter as GeoPoint; step = if (selectedTier.isHr) "CONFIRM" else "DEST" }, Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Black)) { Text("SET PICKUP") }
                } else if (step == "DEST") {
                    Button({
                        val end = mapViewRef?.mapCenter as GeoPoint; destPt = end
                        thread { try {
                            val url = "https://router.project-osrm.org/route/v1/driving/${pickupPt!!.longitude},${pickupPt!!.latitude};${end.longitude},${end.latitude}?overview=full&geometries=geojson"
                            val json = JSONObject(URL(url).readText()); val route = json.getJSONArray("routes").getJSONObject(0)
                            roadDistance = route.getDouble("distance") / 1000.0
                            val geometry = route.getJSONObject("geometry").getJSONArray("coordinates"); val pts = mutableListOf<GeoPoint>()
                            for (i in 0 until geometry.length()) { pts.add(GeoPoint(geometry.getJSONArray(i).getDouble(1), geometry.getJSONArray(i).getDouble(0))) }
                            routePoints = pts; step = "CONFIRM"
                        } catch (e: Exception) {} }
                    }, Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5E4E92))) { Text("SET DESTINATION") }
                } else {
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        Column { Text(if(selectedTier.isHr) "12KM/HR LIMIT" else "${"%.1f".format(roadDistance)} KM", color = Color.Gray); Text("$totalFare ETB", fontSize = 32.sp, fontWeight = FontWeight.Black, color = Color.Red) }
                        if (selectedTier.isHr) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Button({ if(hrCount > 1) hrCount-- }, modifier = Modifier.size(36.dp), contentPadding = PaddingValues(0.dp)) { Text("-") }
                                Text("$hrCount HR", Modifier.padding(horizontal = 12.dp), fontWeight = FontWeight.Bold)
                                Button({ if(hrCount < 12) hrCount++ }, modifier = Modifier.size(36.dp), contentPadding = PaddingValues(0.dp)) { Text("+") }
                            }
                        }
                    }
                    Button({
                        val ref = FirebaseDatabase.getInstance().getReference("rides")
                        val id = "R_${System.currentTimeMillis()}"
                        ref.child(id).setValue(mapOf("id" to id, "pName" to pName, "pPhone" to pPhone, "status" to "REQUESTED", "price" to totalFare.toString(), "tier" to selectedTier.label, "pLat" to pickupPt?.latitude, "pLon" to pickupPt?.longitude, "dLat" to destPt?.latitude, "dLon" to destPt?.longitude, "hr" to hrCount))
                        isSearching = true
                    }, Modifier.fillMaxWidth().padding(top = 16.dp).height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5E4E92))) { Text("BOOK ${selectedTier.label.uppercase()}") }
                    TextButton({ step = "PICKUP"; pickupPt = null; destPt = null; routePoints = listOf(); roadDistance = 0.0 }, Modifier.fillMaxWidth()) { Text("RESET MAP") }
                }
            }
        }
    }
}

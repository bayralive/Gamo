package com.bayra.customer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.preference.PreferenceManager
import android.widget.Toast
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.firebase.database.*
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
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import kotlin.math.*
import java.util.*

enum class ServiceTier(val label: String, val base: Int, val kmRate: Double, val extra: Int, val isHr: Boolean) {
    POOL("Pool", 80, 11.0, 30, false),
    COMFORT("Comfort", 120, 11.0, 0, false),
    CODE_3("Code 3", 280, 27.5, 60, false),
    BAJAJ_HR("Bajaj Hr", 350, 0.0, 0, true),
    C3_HR("C3 Hr", 550, 0.0, 0, true)
}

class MainActivity : ComponentActivity() {
    private var locationOverlay: MyLocationNewOverlay? = null
    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted -> 
        if (isGranted) locationOverlay?.enableMyLocation() 
    }

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
    val prefs = context.getSharedPreferences("bayra_p_v11", Context.MODE_PRIVATE)
    
    var pName by rememberSaveable { mutableStateOf(prefs.getString("n", "") ?: "") }
    var pPhone by rememberSaveable { mutableStateOf(prefs.getString("p", "") ?: "") }
    var pEmail by rememberSaveable { mutableStateOf(prefs.getString("e", "") ?: "") }
    var isAuth by remember { mutableStateOf(pName.isNotEmpty()) }

    if (!isAuth) {
        Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
            Text("BAYRA LOGIN", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color(0xFF5E4E92))
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(value = pName, onValueChange = { pName = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = pPhone, onValueChange = { pPhone = it }, label = { Text("Phone") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = pEmail, onValueChange = { pEmail = it }, label = { Text("Email (Optional)") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(20.dp))
            Button(onClick = { if(pName.isNotEmpty() && pPhone.length > 8){ 
                prefs.edit().putString("n", pName).putString("p", pPhone).putString("e", pEmail).apply()
                isAuth = true 
            } }, Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5E4E92))) { Text("ENTER") }
        }
    } else {
        BookingHub(pName, pPhone, pEmail)
    }
}

@Composable
fun BookingHub(pName: String, pPhone: String, pEmail: String) {
    val context = LocalContext.current
    var step by remember { mutableStateOf("PICKUP") } // PICKUP -> DEST -> CONFIRM
    
    // MAP STATE
    var pickupPt by remember { mutableStateOf<GeoPoint?>(null) }
    var destPt by remember { mutableStateOf<GeoPoint?>(null) }
    var routePoints by remember { mutableStateOf<List<GeoPoint>>(listOf()) }
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    
    // RIDE STATE
    var roadDistance by remember { mutableStateOf(0.0) }
    var selectedTier by remember { mutableStateOf(ServiceTier.COMFORT) }
    var hrCount by remember { mutableStateOf(1) }
    var isSearching by remember { mutableStateOf(false) }
    var rideStatus by remember { mutableStateOf("IDLE") }
    var ridePrice by remember { mutableStateOf("0") }
    var activeRideId by remember { mutableStateOf("") }
    var driverName by remember { mutableStateOf<String?>(null) }
    var isGeneratingLink by remember { mutableStateOf(false) }

    val isNight = Calendar.getInstance().get(Calendar.HOUR_OF_DAY).let { it >= 20 || it < 6 }
    val calculatedFare = remember(selectedTier, roadDistance, hrCount, isNight) {
        if (selectedTier.isHr) ((selectedTier.base + (if(isNight) 100 else 0)) * hrCount * 1.15).toInt()
        else ((selectedTier.base + (roadDistance * selectedTier.kmRate) + selectedTier.extra + (if(isNight) 200 else 0)) * 1.15).toInt()
    }

    Box(Modifier.fillMaxSize()) {
        // --- 🛰️ 1. NATIVE MAP LAYER (RESTORED) ---
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                MapView(ctx).apply {
                    setTileSource(object : XYTileSource("Google-Hybrid", 0, 20, 256, ".jpg", arrayOf("https://mt0.google.com/vt/lyrs=y&", "https://mt1.google.com/vt/lyrs=y&")) {
                        override fun getTileURLString(pTileIndex: Long): String = baseUrl + "x=" + MapTileIndex.getX(pTileIndex) + "&y=" + MapTileIndex.getY(pTileIndex) + "&z=" + MapTileIndex.getZoom(pTileIndex)
                    })
                    setMultiTouchControls(true); controller.setZoom(17.0); controller.setCenter(GeoPoint(6.0333, 37.5500))
                    val overlay = MyLocationNewOverlay(GpsMyLocationProvider(ctx), this)
                    overlay.enableMyLocation(); overlay.enableFollowLocation()
                    overlays.add(overlay)
                    mapViewRef = this
                }
            },
            update = { view ->
                view.overlays.filterIsInstance<Marker>().forEach { view.overlays.remove(it) }
                view.overlays.filterIsInstance<Polyline>().forEach { view.overlays.remove(it) }
                pickupPt?.let { pt -> Marker(view).apply { position = pt; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM) }.also { view.overlays.add(it) } }
                if(!selectedTier.isHr && destPt != null) {
                    Marker(view).apply { position = destPt!!; icon = view.context.getDrawable(android.R.drawable.ic_menu_directions) }.also { view.overlays.add(it) }
                    if(routePoints.isNotEmpty()) Polyline().apply { setPoints(routePoints); color = android.graphics.Color.BLACK; width = 10f }.also { view.overlays.add(it) }
                }
                view.invalidate()
            }
        )

        // --- 📍 PIN CROSSHAIR (Only when selecting) ---
        if (step != "CONFIRM" && !isSearching && rideStatus != "COMPLETED") {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if(step == "PICKUP") "PICKUP" else "DEST", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.background(Color.Black.copy(alpha=0.6f)).padding(4.dp))
                    Canvas(Modifier.size(35.dp)) {
                        drawCircle(Color.Black, radius = 4.dp.toPx(), center = center.copy(y = center.y))
                    }
                }
            }
        }

        // --- 🎛️ UI OVERLAY ---
        if (rideStatus == "COMPLETED") {
            // PAYMENT SCREEN
            Column(Modifier.fillMaxSize().background(Color.White).padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                Text("ARRIVED!", fontSize = 32.sp, color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold)
                Text("$ridePrice ETB", fontSize = 48.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(40.dp))
                if (isGeneratingLink) {
                     CircularProgressIndicator(color = Color(0xFF5E4E92))
                } else {
                    Button(onClick = { 
                        isGeneratingLink = true
                        thread {
                            try {
                                val url = URL("https://bayra-backend-eu.onrender.com/initialize-payment")
                                val conn = url.openConnection() as HttpURLConnection
                                conn.requestMethod = "POST"; conn.doOutput = true; conn.setRequestProperty("Content-Type", "application/json")
                                val body = JSONObject().put("amount", ridePrice).put("name", pName).put("phone", pPhone).put("email", pEmail).put("rideId", activeRideId).toString()
                                conn.outputStream.write(body.toByteArray())
                                val chapaUrl = JSONObject(conn.inputStream.bufferedReader().readText()).getString("checkout_url")
                                isGeneratingLink = false
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(chapaUrl)))
                                rideStatus = "IDLE"; isSearching = false
                            } catch (e: Exception) { isGeneratingLink = false }
                        }
                    }, Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5E4E92))) { Text("PAY WITH CHAPA") }
                    TextButton({ rideStatus = "IDLE"; isSearching = false }) { Text("PAID WITH CASH") }
                }
            }
        } else if (isSearching) {
            // SEARCHING SCREEN
            Column(Modifier.fillMaxSize().background(Color.White).padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color(0xFF5E4E92))
                Spacer(Modifier.height(20.dp))
                Text(if(driverName != null) "$driverName IS COMING" else "SEARCHING...", fontWeight = FontWeight.Bold)
                Button({ isSearching = false }, Modifier.padding(top = 40.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text("CANCEL") }
            }
        } else {
            // SELECTION SCREEN
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.White, RoundedCornerShape(topStart = 28.dp)).padding(24.dp)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(ServiceTier.values()) { tier ->
                        val sel = selectedTier == tier
                        Surface(Modifier.clickable { selectedTier = tier; if(tier.isHr) step = "CONFIRM" else step = "PICKUP" }, color = if(sel) Color(0xFF4CAF50) else Color(0xFFF0F0F0), shape = RoundedCornerShape(12.dp)) {
                            Text(tier.label, Modifier.padding(14.dp, 10.dp), fontWeight = FontWeight.Bold, color = if(sel) Color.White else Color.Black)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                
                if (step == "PICKUP") Button({ pickupPt = mapViewRef?.mapCenter as GeoPoint; step = if (selectedTier.isHr) "CONFIRM" else "DEST" }, Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Black)) { Text("SET PICKUP") }
                else if (step == "DEST") Button({ 
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
                else {
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                        Text(if(selectedTier.isHr) "12KM/HR LIMIT" else "${"%.1f".format(roadDistance)} KM", color = Color.Gray)
                        Text("$calculatedFare ETB", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color.Red)
                    }
                    if(selectedTier.isHr) {
                         Row { Button({ if(hrCount>1) hrCount-- }) {Text("-")}; Text("$hrCount HR", Modifier.padding(10.dp)); Button({ if(hrCount<12) hrCount++ }) {Text("+")} }
                    }
                    Button({
                        val id = "R_${System.currentTimeMillis()}"; activeRideId = id; ridePrice = calculatedFare.toString()
                        FirebaseDatabase.getInstance().getReference("rides/$id").setValue(mapOf("id" to id, "pName" to pName, "status" to "REQUESTED", "price" to ridePrice, "tier" to selectedTier.label, "pLat" to pickupPt?.latitude, "pLon" to pickupPt?.longitude))
                        isSearching = true
                        FirebaseDatabase.getInstance().getReference("rides/$id").addValueEventListener(object : ValueEventListener {
                            override fun onDataChange(s: DataSnapshot) {
                                if (!s.exists() && isSearching) { isSearching = false; rideStatus = "COMPLETED"; return }
                                rideStatus = s.child("status").getValue(String::class.java) ?: "REQUESTED"
                                driverName = s.child("driverName").getValue(String::class.java)
                            }
                            override fun onCancelled(e: DatabaseError) {}
                        })
                    }, Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5E4E92))) { Text("CONFIRM BOOKING") }
                    TextButton({ step = "PICKUP"; pickupPt = null; destPt = null; roadDistance = 0.0; routePoints = listOf() }, Modifier.fillMaxWidth()) { Text("RESET MAP") }
                }
            }
        }
    }
}

package com.bayra.customer

import android.os.Bundle
import android.preference.PreferenceManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.firebase.database.*
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

enum class ServiceTier(val label: String, val base: Int, val kmRate: Double, val extra: Int, val isHr: Boolean) {
    POOL("Pool", 80, 11.0, 30, false),
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
        setContent { MaterialTheme { PassengerAppController() } }
    }
}

@Composable
fun PassengerAppController() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("bayra_p_vFINAL", 0)
    var name by remember { mutableStateOf(prefs.getString("n", "") ?: "") }
    var phone by remember { mutableStateOf(prefs.getString("p", "") ?: "") }
    var isAuth by remember { mutableStateOf(name.isNotEmpty()) }
    var currentTab by remember { mutableStateOf("BOOK") }

    if (!isAuth) {
        Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
            Text("BAYRA", fontSize = 48.sp, fontWeight = FontWeight.Black, color = Color(0xFF5E4E92))
            Spacer(Modifier.height(40.dp))
            OutlinedTextField(name, { name = it }, label = { Text("Full Name") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(phone, { phone = it }, label = { Text("Phone Number") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(30.dp))
            Button(onClick = { if(name.length > 2 && phone.length > 8) { prefs.edit().putString("n", name).putString("p", phone).apply(); isAuth = true } }, Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5E4E92))) { Text("ENTER") }
        }
    } else {
        Scaffold(
            bottomBar = {
                NavigationBar(containerColor = Color.White) {
                    NavigationBarItem(selected = currentTab == "BOOK", onClick = { currentTab = "BOOK" }, icon = { Text("🚕") }, label = { Text("Book") })
                    NavigationBarItem(selected = currentTab == "ACCOUNT", onClick = { currentTab = "ACCOUNT" }, icon = { Text("👤") }, label = { Text("Account") })
                }
            }
        ) { p -> Box(Modifier.padding(p)) { if (currentTab == "BOOK") BookingHub(name, phone) else AccountView(name, phone) { prefs.edit().clear().apply(); isAuth = false } } }
    }
}

@Composable
fun BookingHub(pName: String, pPhone: String) {
    var isSearching by remember { mutableStateOf(false) }
    var rideStatus by remember { mutableStateOf("REQUESTED") }
    var driverName by remember { mutableStateOf<String?>(null) }
    
    var step by remember { mutableStateOf("PICKUP") }
    var pickupPt by remember { mutableStateOf<GeoPoint?>(null) }
    var destPt by remember { mutableStateOf<GeoPoint?>(null) }
    var roadDistance by remember { mutableStateOf(0.0) }
    var routePoints by remember { mutableStateOf<List<GeoPoint>>(listOf()) }
    var selectedTier by remember { mutableStateOf(ServiceTier.COMFORT) }
    var hrCount by remember { mutableStateOf(1) }
    var mapView: MapView? by remember { mutableStateOf(null) }

    val isNight = Calendar.getInstance().get(Calendar.HOUR_OF_DAY).let { it >= 20 || it < 6 }
    val fare = remember(selectedTier, roadDistance, hrCount, isNight) {
        if (selectedTier.isHr) ((selectedTier.base + (if(isNight) 100 else 0)) * hrCount * 1.15).toInt()
        else ((selectedTier.base + (roadDistance * selectedTier.kmRate) + selectedTier.extra + (if(isNight) 200 else 0)) * 1.15).toInt()
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx -> MapView(ctx).apply { setTileSource(TileSourceFactory.MAPNIK); setMultiTouchControls(true); controller.setZoom(16.0); controller.setCenter(GeoPoint(6.0238, 37.5532)); mapView = this } },
            update = { view ->
                view.overlays.clear()
                pickupPt?.let { pt -> Marker(view).apply { position = pt; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM) }.also { view.overlays.add(it) } }
                if(!selectedTier.isHr && destPt != null) {
                    Marker(view).apply { position = destPt!!; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM); icon = view.context.getDrawable(android.R.drawable.ic_menu_directions) }.also { view.overlays.add(it) }
                    if(routePoints.isNotEmpty()) Polyline().apply { setPoints(routePoints); color = android.graphics.Color.BLACK; width = 10f }.also { view.overlays.add(it) }
                }
                view.invalidate()
            }
        )

        if (isSearching) {
            // --- 🛡️ THE SEARCHING SCREEN (FIXED) ---
            Column(Modifier.fillMaxSize().background(Color.White).padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color(0xFF5E4E92), modifier = Modifier.size(64.dp))
                Spacer(Modifier.height(30.dp))
                Text(if(driverName != null) "$driverName IS ON THE WAY" else "SEARCHING ARBA MINCH...", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text("$fare ETB", color = Color.Red, fontSize = 32.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(50.dp))
                Button({ isSearching = false }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red), modifier = Modifier.fillMaxWidth().height(60.dp)) { Text("CANCEL") }
            }
        } else {
            if(step != "CONFIRM") Box(Modifier.fillMaxSize(), Alignment.Center) { Text(if(step=="PICKUP") "📍" else "🏁", fontSize = 40.sp, modifier = Modifier.padding(bottom = 40.dp)) }
            
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.White, RoundedCornerShape(topStart = 28.dp)).padding(24.dp)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(ServiceTier.values()) { tier ->
                        val sel = selectedTier == tier
                        Surface(Modifier.clickable { selectedTier = tier; if(tier.isHr) step = "CONFIRM" else step = "PICKUP" }, color = if(sel) Color(0xFF4CAF50) else Color(0xFFF0F0F0), shape = RoundedCornerShape(12.dp)) {
                            Text(tier.label, Modifier.padding(14.dp, 10.dp), fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                if (step == "PICKUP") Button(onClick = { pickupPt = mapView?.mapCenter as GeoPoint; step = if(selectedTier.isHr) "CONFIRM" else "DEST" }, Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Black)) { Text("SET PICKUP") }
                else if (step == "DEST") Button(onClick = { 
                    val end = mapView?.mapCenter as GeoPoint; destPt = end
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
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        Text("$fare ETB", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color.Red)
                        if (selectedTier.isHr) { Row(verticalAlignment = Alignment.CenterVertically) {
                            Button({ if(hrCount > 1) hrCount-- }, modifier = Modifier.size(36.dp)) { Text("-") }
                            Text("$hrCount HR", Modifier.padding(horizontal = 10.dp))
                            Button({ if(hrCount < 12) hrCount++ }, modifier = Modifier.size(36.dp)) { Text("+") }
                        } }
                    }
                    Spacer(Modifier.height(16.dp))
                    // --- 🔥 THE LIVE WELD: Firebase Request ---
                    Button(
                        onClick = {
                            val ref = FirebaseDatabase.getInstance().getReference("rides")
                            val id = "R_${System.currentTimeMillis()}"
                            val data = mapOf(
                                "id" to id, "pName" to pName, "pPhone" to pPhone, 
                                "status" to "REQUESTED", "price" to fare.toString(), 
                                "tier" to selectedTier.label, "pLat" to pickupPt?.latitude, "pLon" to pickupPt?.longitude
                            )
                            ref.child(id).setValue(data)
                            isSearching = true
                            ref.child(id).addValueEventListener(object : ValueEventListener {
                                override fun onDataChange(s: DataSnapshot) {
                                    if (!s.exists()) { isSearching = false; return }
                                    rideStatus = s.child("status").getValue(String::class.java) ?: "REQUESTED"
                                    driverName = s.child("driverName").getValue(String::class.java)
                                }
                                override fun onCancelled(e: DatabaseError) {}
                            })
                        },
                        Modifier.fillMaxWidth().height(65.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5E4E92))
                    ) { Text("CONFIRM ${selectedTier.label.uppercase()}") }
                    TextButton({ step = "PICKUP"; pickupPt = null; destPt = null; routePoints = listOf(); roadDistance = 0.0 }, Modifier.fillMaxWidth()) { Text("RESET MAP") }
                }
            }
        }
    }
}

@Composable
fun AccountView(name: String, phone: String, onLogout: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("ACCOUNT", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(20.dp))
        Text(name, fontWeight = FontWeight.Bold); Text(phone, color = Color.Gray)
        Spacer(Modifier.weight(1f))
        Button(onLogout, Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text("LOGOUT") }
    }
}

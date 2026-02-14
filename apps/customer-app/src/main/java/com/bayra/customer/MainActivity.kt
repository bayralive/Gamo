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

// --- THE SOVEREIGN TIERS ---
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
        // --- 🛡️ RESTORED FULL LOGIN PAGE ---
        Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
            Text("BAYRA", fontSize = 48.sp, fontWeight = FontWeight.Black, color = Color(0xFF5E4E92))
            Text("ARBA MINCH PASSENGER", fontSize = 12.sp, color = Color.Gray)
            Spacer(Modifier.height(40.dp))
            OutlinedTextField(name, { name = it }, label = { Text("Full Name") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(phone, { phone = it }, label = { Text("Phone Number") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(30.dp))
            Button(
                onClick = { 
                    if(name.length > 2 && phone.length > 8) {
                        prefs.edit().putString("n", name).putString("p", phone).apply()
                        isAuth = true 
                    }
                },
                modifier = Modifier.fillMaxWidth().height(60.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5E4E92))
            ) { Text("ENTER NETWORK", fontWeight = FontWeight.Bold) }
        }
    } else {
        // --- MAIN TABS ---
        Scaffold(
            bottomBar = {
                NavigationBar(containerColor = Color.White) {
                    NavigationBarItem(
                        selected = currentTab == "BOOK", 
                        onClick = { currentTab = "BOOK" },
                        icon = { Text("🚕", fontSize = 24.sp) },
                        label = { Text("Book") }
                    )
                    NavigationBarItem(
                        selected = currentTab == "ACCOUNT", 
                        onClick = { currentTab = "ACCOUNT" },
                        icon = { Text("👤", fontSize = 24.sp) },
                        label = { Text("Account") }
                    )
                }
            }
        ) { padding ->
            Box(Modifier.padding(padding)) {
                if (currentTab == "BOOK") BookingHub(name, phone) else AccountView(name, phone) {
                    prefs.edit().clear().apply()
                    isAuth = false
                }
            }
        }
    }
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

    val isNight = Calendar.getInstance().get(Calendar.HOUR_OF_DAY).let { it >= 20 || it < 6 }
    val totalFare = remember(selectedTier, roadDistance, hrCount, isNight) {
        if (selectedTier.isHr) ((selectedTier.base + (if(isNight) 100 else 0)) * hrCount * 1.15).toInt()
        else ((selectedTier.base + (roadDistance * selectedTier.kmRate) + selectedTier.extra + (if(isNight) 200 else 0)) * 1.15).toInt()
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx -> MapView(ctx).apply { setTileSource(TileSourceFactory.MAPNIK); setMultiTouchControls(true); controller.setZoom(16.0); controller.setCenter(GeoPoint(6.0238, 37.5532)); mapView = this } },
            update = { view ->
                view.overlays.clear()
                pickupPt?.let { Marker(view).apply { position = it; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM) }.also { view.overlays.add(it) } }
                if(!selectedTier.isHr) destPt?.let { 
                    Marker(view).apply { position = it; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM); icon = view.context.getDrawable(android.R.drawable.ic_menu_directions) }.also { view.overlays.add(it) }
                    if(routePoints.isNotEmpty()) Polyline().apply { setPoints(routePoints); color = android.graphics.Color.BLACK; width = 10f }.also { view.overlays.add(it) }
                }
                view.invalidate()
            }
        )

        if (step != "CONFIRM") Box(Modifier.fillMaxSize(), Alignment.Center) { Text(if(step=="PICKUP") "📍" else "🏁", fontSize = 40.sp, modifier = Modifier.padding(bottom = 40.dp)) }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.White, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).padding(24.dp)) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(ServiceTier.values()) { tier ->
                    val sel = selectedTier == tier
                    Surface(Modifier.clickable { selectedTier = tier; if(tier.isHr) step = "CONFIRM" else if(pickupPt==null) step="PICKUP" else if(destPt==null) step="DEST" else step="CONFIRM" }, color = if(sel) Color(0xFF4CAF50) else Color(0xFFF0F0F0), shape = RoundedCornerShape(12.dp)) {
                        Text(tier.label, Modifier.padding(14.dp, 10.dp), fontWeight = FontWeight.Bold, color = if(sel) Color.White else Color.Black)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            if (step == "PICKUP") Button(onClick = { pickupPt = mapView?.mapCenter as GeoPoint; step = if(selectedTier.isHr) "CONFIRM" else "DEST" }, Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Black)) { Text("SET PICKUP") }
            else if (step == "DEST") Button(onClick = { 
                val end = mapView?.mapCenter as GeoPoint; destPt = end
                thread { try {
                    val url = "https://router.project-osrm.org/route/v1/driving/${pickupPt!!.longitude},${pickupPt!!.latitude};${end.longitude},${end.latitude}?overview=full&geometries=geojson"
                    val json = JSONObject(URL(url).readText())
                    val route = json.getJSONArray("routes").getJSONObject(0)
                    roadDistance = route.getDouble("distance") / 1000.0
                    val geometry = route.getJSONObject("geometry").getJSONArray("coordinates")
                    val pts = mutableListOf<GeoPoint>()
                    for (i in 0 until geometry.length()) { pts.add(GeoPoint(geometry.getJSONArray(i).getDouble(1), geometry.getJSONArray(i).getDouble(0))) }
                    routePoints = pts; step = "CONFIRM"
                } catch (e: Exception) {} }
            }, Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5E4E92))) { Text("SET DESTINATION") }
            else {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Column { Text(if(selectedTier.isHr) "12KM/HR LIMIT" else "${"%.1f".format(roadDistance)} KM", color = Color.Gray); Text("$totalFare ETB", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color.Red) }
                    if (selectedTier.isHr) { Row(verticalAlignment = Alignment.CenterVertically) {
                        Button({ if(hrCount > 1) hrCount-- }, modifier = Modifier.size(36.dp), contentPadding = PaddingValues(0.dp)) { Text("-") }
                        Text("$hrCount HR", Modifier.padding(horizontal = 12.dp), fontWeight = FontWeight.Bold)
                        Button({ if(hrCount < 12) hrCount++ }, modifier = Modifier.size(36.dp), contentPadding = PaddingValues(0.dp)) { Text("+") }
                    } }
                }
                Spacer(Modifier.height(16.dp))
                Button({ /* Firebase Request Code */ }, Modifier.fillMaxWidth().height(65.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5E4E92))) { Text("BOOK ${selectedTier.label.uppercase()}") }
                TextButton({ step = "PICKUP"; pickupPt = null; destPt = null; routePoints = listOf(); roadDistance = 0.0 }, Modifier.fillMaxWidth()) { Text("RESET MAP") }
            }
        }
    }
}

@Composable
fun AccountView(name: String, phone: String, onLogout: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(100.dp).background(Color(0xFF5E4E92), CircleShape), contentAlignment = Alignment.Center) {
            Text(name.take(1).uppercase(), color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(20.dp))
        Text(name, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
        Text(phone, color = Color.Gray)
        
        Spacer(Modifier.height(60.dp))
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5))) {
            Column(Modifier.padding(20.dp)) {
                Text("Account Sovereignty", fontWeight = FontWeight.Bold)
                Text("Your data is stored locally in Arba Minch.", fontSize = 12.sp, color = Color.Gray)
            }
        }
        
        Spacer(Modifier.weight(1f))
        Button(
            onClick = onLogout,
            modifier = Modifier.fillMaxWidth().height(60.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
            shape = RoundedCornerShape(16.dp)
        ) { Text("LOGOUT", fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(20.dp))
    }
}

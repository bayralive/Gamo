package com.bayra.customer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.preference.PreferenceManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
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
import java.net.URL
import java.net.HttpURLConnection
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        setContent { MaterialTheme { PassengerApp() } }
    }
}

@Composable
fun PassengerApp() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("bayra_p_vFINAL", Context.MODE_PRIVATE)
    
    var pName by rememberSaveable { mutableStateOf(prefs.getString("n", "") ?: "") }
    var pPhone by rememberSaveable { mutableStateOf(prefs.getString("p", "") ?: "") }
    var isAuth by remember { mutableStateOf(pName.isNotEmpty()) }
    
    // --- 💎 PERSISTENT GLOBAL STATE 💎 ---
    var isSearching by rememberSaveable { mutableStateOf(false) }
    var rideStatus by rememberSaveable { mutableStateOf("IDLE") }
    var ridePrice by rememberSaveable { mutableStateOf("0") }
    var driverName by rememberSaveable { mutableStateOf<String?>(null) }
    var dPhone by rememberSaveable { mutableStateOf<String?>(null) }
    var activeRideId by rememberSaveable { mutableStateOf("") }

    // GLOBAL FIREBASE LISTENER (The Handshake fix)
    LaunchedEffect(isSearching, activeRideId) {
        if (isSearching && activeRideId.isNotEmpty()) {
            val ref = FirebaseDatabase.getInstance().getReference("rides/$activeRideId")
            ref.addValueEventListener(object : ValueEventListener {
                override fun onDataChange(s: DataSnapshot) {
                    if (!s.exists()) { 
                        if (rideStatus != "COMPLETED") isSearching = false 
                        return 
                    }
                    rideStatus = s.child("status").getValue(String::class.java) ?: "REQUESTED"
                    driverName = s.child("driverName").getValue(String::class.java)
                    dPhone = s.child("dPhone").getValue(String::class.java)
                    
                    if (rideStatus == "COMPLETED") {
                        isSearching = false
                        // We keep the rideStatus as COMPLETED to show the bill
                    }
                }
                override fun onCancelled(e: DatabaseError) {}
            })
        }
    }

    if (!isAuth) {
        Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
            Text("BAYRA LOGIN", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color(0xFF5E4E92))
            OutlinedTextField(pName, { pName = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(pPhone, { pPhone = it }, label = { Text("Phone") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { if(pName.length > 2){ prefs.edit().putString("n", pName).putString("p", pPhone).apply(); isAuth = true } }, Modifier.fillMaxWidth().height(60.dp)) { Text("ENTER") }
        }
    } else {
        BookingHub(pName, pPhone, isSearching, rideStatus, ridePrice, driverName, dPhone, activeRideId, 
            { isSearching = it }, { rideStatus = it }, { ridePrice = it }, { activeRideId = it })
    }
}

@Composable
fun BookingHub(
    name: String, phone: String, 
    isSearching: Boolean, status: String, price: String, driver: String?, dPhone: String?, rideId: String,
    onSearch: (Boolean) -> Unit, onStatus: (String) -> Unit, onPrice: (String) -> Unit, onId: (String) -> Unit
) {
    val context = LocalContext.current
    var step by rememberSaveable { mutableStateOf("PICKUP") }
    var pickupPt by remember { mutableStateOf<GeoPoint?>(null) }
    var destPt by remember { mutableStateOf<GeoPoint?>(null) }
    var roadDistance by remember { mutableStateOf(0.0) }
    var selectedTier by remember { mutableStateOf(ServiceTier.COMFORT) }
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }

    val fare = remember(selectedTier, roadDistance) {
        if (selectedTier.isHr) (selectedTier.base * 1.15).toInt()
        else (selectedTier.base + (roadDistance * selectedTier.kmRate) + selectedTier.extra).toInt()
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx ->
            MapView(ctx).apply {
                setTileSource(object : XYTileSource("G-Hybrid", 0, 20, 256, ".jpg", arrayOf("https://mt1.google.com/vt/lyrs=y&")) {
                    override fun getTileURLString(pTileIndex: Long): String = baseUrl + "x=" + MapTileIndex.getX(pTileIndex) + "&y=" + MapTileIndex.getY(pTileIndex) + "&z=" + MapTileIndex.getZoom(pTileIndex)
                })
                setMultiTouchControls(true); controller.setZoom(17.0); controller.setCenter(GeoPoint(6.0333, 37.5500)); mapViewRef = this
            }
        })

        if (status == "COMPLETED") {
            Column(Modifier.fillMaxSize().background(Color.White).padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                Text("ARRIVED!", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color(0xFF4CAF50))
                Text("$price ETB", fontSize = 48.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(40.dp))
                Button({ onStatus("IDLE"); onSearch(false) }, Modifier.fillMaxWidth().height(60.dp)) { Text("DONE") }
            }
        } else if (isSearching) {
            Column(Modifier.fillMaxSize().background(Color.White).padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color(0xFF5E4E92))
                Spacer(Modifier.height(20.dp))
                val displayMsg = when(status) {
                    "ACCEPTED" -> "$driver IS COMING"
                    "ARRIVED" -> "$driver IS OUTSIDE!"
                    "ON_TRIP" -> "TRIP IN PROGRESS"
                    else -> "SEARCHING..."
                }
                Text(displayMsg, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                if (dPhone != null) Button({ context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$dPhone"))) }) { Text("📞 CALL DRIVER") }
                Spacer(Modifier.height(40.dp))
                if (status == "REQUESTED") Button({ onSearch(false) }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text("CANCEL") }
            }
        } else {
            if(step != "CONFIRM") Box(Modifier.fillMaxSize(), Alignment.Center) { Text(if(step=="PICKUP") "📍" else "🏁", fontSize = 40.sp, modifier = Modifier.padding(bottom = 40.dp)) }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.White, RoundedCornerShape(topStart = 28.dp)).padding(24.dp)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(ServiceTier.values()) { tier ->
                        Surface(Modifier.clickable { onTier(tier); step = if(tier.isHr) "CONFIRM" else "PICKUP" }, color = if(selectedTier == tier) Color(0xFF4CAF50) else Color(0xFFF0F0F0), shape = RoundedCornerShape(12.dp)) {
                            Text(tier.label, Modifier.padding(14.dp, 10.dp), fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                if (step == "PICKUP") Button({ pickupPt = mapViewRef?.mapCenter as GeoPoint; step = if(selectedTier.isHr) "CONFIRM" else "DEST" }, Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Black)) { Text("SET PICKUP") }
                else if (step == "DEST") Button({ 
                    val end = mapViewRef?.mapCenter as GeoPoint; destPt = end
                    thread { try {
                        val url = "https://router.project-osrm.org/route/v1/driving/${pickupPt!!.longitude},${pickupPt!!.latitude};${end.longitude},${end.latitude}?overview=full&geometries=geojson"
                        val json = JSONObject(URL(url).readText()); val route = json.getJSONArray("routes").getJSONObject(0)
                        onDist(route.getDouble("distance") / 1000.0); onStep("CONFIRM")
                    } catch (e: Exception) {} }
                }, Modifier.fillMaxWidth().height(60.dp)) { Text("SET DESTINATION") }
                else {
                    Text("$totalFare ETB", fontSize = 32.sp, color = Color.Red, fontWeight = FontWeight.Black)
                    Button({
                        val id = "R_${System.currentTimeMillis()}"; onId(id); onPrice(totalFare.toString()); onSearch(true)
                        FirebaseDatabase.getInstance().getReference("rides/$id").setValue(mapOf("id" to id, "pName" to name, "pPhone" to phone, "status" to "REQUESTED", "price" to totalFare.toString(), "tier" to selectedTier.label, "pLat" to pickupPt?.latitude, "pLon" to pickupPt?.longitude, "dLat" to destPt?.latitude, "dLon" to destPt?.longitude))
                    }, Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5E4E92))) { Text("BOOK NOW") }
                }
            }
        }
    }
}

@Composable
fun AccountView(name: String, phone: String, onLogout: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("ACCOUNT", fontSize = 24.sp, fontWeight = FontWeight.Bold); Text(name); Text(phone, color = Color.Gray)
        Spacer(Modifier.weight(1f)); Button(onLogout, colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text("LOGOUT") }
    }
}

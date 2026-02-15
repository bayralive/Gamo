package com.bayra.customer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.preference.PreferenceManager
import android.webkit.WebView
import android.webkit.WebViewClient
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
import kotlin.concurrent.thread
import kotlin.math.*
import java.util.*

enum class ServiceTier(val label: String, val base: Int, val kmRate: Double, val extra: Int, val isHr: Boolean) {
    POOL(label = "Pool", base = 80, kmRate = 11.0, extra = 30, isHr = false),
    COMFORT(label = "Comfort", base = 120, kmRate = 11.0, extra = 0, isHr = false),
    CODE_3(label = "Code 3", base = 280, kmRate = 27.5, extra = 60, isHr = false),
    BAJAJ_HR(label = "Bajaj Hr", base = 350, kmRate = 0.0, extra = 0, isHr = true),
    C3_HR(label = "C3 Hr", base = 550, kmRate = 0.0, extra = 0, isHr = true)
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
    val prefs = context.getSharedPreferences("bayra_p_vFINAL", Context.MODE_PRIVATE)
    
    var pName by rememberSaveable { mutableStateOf(prefs.getString("n", "") ?: "") }
    var pPhone by rememberSaveable { mutableStateOf(prefs.getString("p", "") ?: "") }
    var isAuth by remember { mutableStateOf(pName.isNotEmpty()) }
    var currentTab by rememberSaveable { mutableStateOf("BOOK") }
    
    var isSearching by rememberSaveable { mutableStateOf(false) }
    var rideStatus by rememberSaveable { mutableStateOf("IDLE") }
    var ridePrice by rememberSaveable { mutableStateOf("0") }
    var driverName by rememberSaveable { mutableStateOf<String?>(null) }

    if (!isAuth) {
        Column(modifier = Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "BAYRA LOGIN", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color(0xFF5E4E92))
            Spacer(modifier = Modifier.height(30.dp))
            OutlinedTextField(value = pName, onValueChange = { pName = it }, label = { Text(text = "Name") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = pPhone, onValueChange = { pPhone = it }, label = { Text(text = "Phone") }, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(30.dp))
            Button(onClick = { if(pName.isNotEmpty() && pPhone.isNotEmpty()){ prefs.edit().putString("n", pName).putString("p", pPhone).apply(); isAuth = true } }, modifier = Modifier.fillMaxWidth().height(60.dp)) { Text(text = "ENTER") }
        }
    } else {
        Scaffold(
            bottomBar = {
                NavigationBar(containerColor = Color.White) {
                    NavigationBarItem(selected = currentTab == "BOOK", onClick = { currentTab = "BOOK" }, icon = { Text(text = "🚕") }, label = { Text(text = "Book") })
                    NavigationBarItem(selected = currentTab == "ACCOUNT", onClick = { currentTab = "ACCOUNT" }, icon = { Text(text = "👤") }, label = { Text(text = "Account") })
                }
            }
        ) { p ->
            Box(modifier = Modifier.padding(p)) {
                if (currentTab == "BOOK") {
                    BookingHub(
                        name = pName, phone = pPhone, isSearching = isSearching, status = rideStatus, price = ridePrice, driver = driverName,
                        onSearchingChange = { isSearching = it }, onStatusChange = { rideStatus = it }, onPriceChange = { ridePrice = it }, onDriverChange = { driverName = it }
                    )
                } else {
                    AccountView(name = pName, phone = pPhone) { prefs.edit().clear().apply(); isAuth = false; isSearching = false; rideStatus = "IDLE" }
                }
            }
        }
    }
}

@Composable
fun BookingHub(
    name: String, phone: String, 
    isSearching: Boolean, status: String, price: String, driver: String?,
    onSearchingChange: (Boolean) -> Unit, onStatusChange: (String) -> Unit,
    onPriceChange: (String) -> Unit, onDriverChange: (String?) -> Unit
) {
    val context = LocalContext.current
    var step by rememberSaveable { mutableStateOf("PICKUP") }
    var pickupPt by remember { mutableStateOf<GeoPoint?>(null) }
    var destPt by remember { mutableStateOf<GeoPoint?>(null) }
    var roadDistance by remember { mutableStateOf(0.0) }
    var routePoints by remember { mutableStateOf<List<GeoPoint>>(listOf()) }
    var selectedTier by remember { mutableStateOf(ServiceTier.COMFORT) }
    var hrCount by remember { mutableStateOf(1) }
    var paymentMethod by remember { mutableStateOf("CASH") }
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }

    val isNight = Calendar.getInstance().get(Calendar.HOUR_OF_DAY).let { it >= 20 || it < 6 }
    val calculatedFare = remember(selectedTier, roadDistance, hrCount, isNight) {
        if (selectedTier.isHr) ((selectedTier.base + (if(isNight) 100 else 0)) * hrCount * 1.15).toInt()
        else ((selectedTier.base + (roadDistance * selectedTier.kmRate) + selectedTier.extra + (if(isNight) 200 else 0)) * 1.15).toInt()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx ->
            MapView(ctx).apply {
                setTileSource(object : XYTileSource("G-Hybrid", 0, 20, 256, ".jpg", arrayOf("https://mt1.google.com/vt/lyrs=y&")) {
                    override fun getTileURLString(pTileIndex: Long): String = baseUrl + "x=" + MapTileIndex.getX(pTileIndex) + "&y=" + MapTileIndex.getY(pTileIndex) + "&z=" + MapTileIndex.getZoom(pTileIndex)
                })
                setMultiTouchControls(true); controller.setZoom(17.0); controller.setCenter(GeoPoint(6.0333, 37.5500))
                mapViewRef = this
            }
        })

        if (isSearching || status == "COMPLETED") {
            Column(modifier = Modifier.fillMaxSize().background(Color.White).padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                if (status == "COMPLETED") {
                    Text(text = "ARRIVED", fontSize = 32.sp, fontWeight = FontWeight.Black, color = Color(0xFF4CAF50))
                    Text(text = "$price ETB", fontSize = 48.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(modifier = Modifier.height(40.dp))
                    Button(onClick = { 
                        val url = "https://checkout.chapa.co/checkout/web/payment/CHAPUBK-GTviouToMOe9vOg5t1dNR9paQ1M62jOX"
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        onStatusChange("IDLE"); onSearchingChange(false)
                    }, modifier = Modifier.fillMaxWidth().height(65.dp)) { Text(text = "PAY NOW") }
                } else {
                    CircularProgressIndicator(color = Color(0xFF5E4E92))
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(text = if(driver != null) "$driver IS COMING" else "SEARCHING...", fontWeight = FontWeight.Bold)
                    Button(onClick = { onSearchingChange(false) }, modifier = Modifier.padding(top = 40.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text(text = "CANCEL") }
                }
            }
        } else {
            if(step != "CONFIRM") Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = if(step=="PICKUP") "📍" else "🏁", fontSize = 40.sp, modifier = Modifier.padding(bottom = 40.dp))
            }
            Column(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.White, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).padding(24.dp)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(ServiceTier.values()) { tier ->
                        val sel = selectedTier == tier
                        Surface(modifier = Modifier.clickable { selectedTier = tier; if(tier.isHr) step = "CONFIRM" else if(pickupPt != null && destPt != null) step = "CONFIRM" else if(pickupPt != null) step = "DEST" else step = "PICKUP" }, color = if(sel) Color(0xFF4CAF50) else Color(0xFFF0F0F0), shape = RoundedCornerShape(12.dp)) {
                            Text(text = tier.label, modifier = Modifier.padding(14.dp, 10.dp), fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                if (step == "PICKUP") Button(onClick = { pickupPt = mapViewRef?.mapCenter as GeoPoint; step = if (selectedTier.isHr) "CONFIRM" else "DEST" }, modifier = Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Black)) { Text(text = "SET PICKUP") }
                else if (step == "DEST") Button(onClick = { 
                    val end = mapViewRef?.mapCenter as GeoPoint; destPt = end
                    thread { try {
                        val url = "https://router.project-osrm.org/route/v1/driving/${pickupPt!!.longitude},${pickupPt!!.latitude};${end.longitude},${end.latitude}?overview=full&geometries=geojson"
                        val json = JSONObject(URL(url).readText()); val route = json.getJSONArray("routes").getJSONObject(0)
                        roadDistance = route.getDouble("distance") / 1000.0
                        val geometry = route.getJSONObject("geometry").getJSONArray("coordinates"); val pts = mutableListOf<GeoPoint>()
                        for (i in 0 until geometry.length()) { pts.add(GeoPoint(geometry.getJSONArray(i).getDouble(1), geometry.getJSONArray(i).getDouble(0))) }
                        routePoints = pts; step = "CONFIRM"
                    } catch (e: Exception) {} }
                }, modifier = Modifier.fillMaxWidth().height(60.dp)) { Text(text = "SET DESTINATION") }
                else {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column { Text(text = if(selectedTier.isHr) "12KM/HR LIMIT" else "${"%.1f".format(roadDistance)} KM"); Text(text = "$calculatedFare ETB", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color.Red) }
                        if(selectedTier.isHr) Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = {if(hrCount > 1) hrCount--}, modifier = Modifier.size(36.dp), contentPadding = PaddingValues(0.dp)) { Text(text = "-") }
                            Text(text = "$hrCount HR", modifier = Modifier.padding(horizontal = 8.dp))
                            Button(onClick = {if(hrCount < 12) hrCount++}, modifier = Modifier.size(36.dp), contentPadding = PaddingValues(0.dp)) { Text(text = "+") }
                        } else Row { listOf("CASH", "CHAPA").forEach { m -> Surface(modifier = Modifier.clickable { paymentMethod = m }.padding(4.dp), color = if(paymentMethod == m) Color(0xFF5E4E92) else Color(0xFFF0F0F0), shape = RoundedCornerShape(8.dp)) { Text(text = m, modifier = Modifier.padding(6.dp), fontSize = 10.sp, color = if(paymentMethod==m) Color.White else Color.Black) } } }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = {
                        val ref = FirebaseDatabase.getInstance().getReference("rides")
                        val id = "R_${System.currentTimeMillis()}"
                        onPriceChange(calculatedFare.toString())
                        ref.child(id).setValue(mapOf("id" to id, "pName" to name, "status" to "REQUESTED", "price" to calculatedFare.toString(), "tier" to selectedTier.label, "pLat" to pickupPt?.latitude, "pLon" to pickupPt?.longitude, "pay" to paymentMethod))
                        onSearchingChange(true)
                        ref.child(id).addValueEventListener(object : ValueEventListener {
                            override fun onDataChange(s: DataSnapshot) {
                                if (!s.exists() && isSearching) { onSearchingChange(false); onStatusChange("COMPLETED"); return }
                                onStatusChange(s.child("status").getValue(String::class.java) ?: "REQUESTED")
                                onDriverChange(s.child("driverName").getValue(String::class.java))
                            }
                            override fun onCancelled(e: DatabaseError) {}
                        })
                    }, modifier = Modifier.fillMaxWidth().height(65.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5E4E92))) { Text(text = "BOOK ${selectedTier.label.uppercase()}") }
                    TextButton(onClick = { step = "PICKUP"; pickupPt = null; destPt = null; routePoints = listOf(); roadDistance = 0.0 }, modifier = Modifier.fillMaxWidth()) { Text(text = "RESET MAP", color = Color.Gray) }
                }
            }
        }
    }
}

@Composable
fun AccountView(name: String, phone: String, onLogout: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(modifier = Modifier.size(100.dp).background(Color(0xFF5E4E92), CircleShape), contentAlignment = Alignment.Center) { Text(text = name.take(1).uppercase(), color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold) }
        Spacer(modifier = Modifier.height(16.dp)); Text(text = name, fontSize = 24.sp, fontWeight = FontWeight.Bold); Text(text = phone, color = Color.Gray)
        Spacer(modifier = Modifier.weight(1f))
        Button(onClick = onLogout, modifier = Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text(text = "LOGOUT") }
    }
}

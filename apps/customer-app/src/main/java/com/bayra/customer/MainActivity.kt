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
import java.net.HttpURLConnection
import kotlin.concurrent.thread
import kotlin.math.*
import java.util.*
import kotlinx.coroutines.delay

enum class ServiceTier(val label: String, val base: Int, val kmRate: Double, val extra: Int, val isHr: Boolean) {
    POOL(label = "Pool", base = 80, kmRate = 11.0, extra = 30, isHr = false),
    COMFORT(label = "Comfort", base = 120, kmRate = 11.0, extra = 0, isHr = false),
    CODE_3(label = "Code 3", base = 280, kmRate = 27.5, extra = 60, isHr = false),
    BAJAJ_HR(label = "Bajaj Hr", base = 350, kmRate = 0.0, extra = 0, isHr = true),
    C3_HR(label = "C3 Hr", base = 550, kmRate = 0.0, extra = 0, isHr = true)
}

class MainActivity : ComponentActivity() {
    private var locationOverlay: MyLocationNewOverlay? = null
    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isG -> 
        if (isG) locationOverlay?.enableMyLocation() 
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
    val prefs = context.getSharedPreferences("bayra_p_final", Context.MODE_PRIVATE)
    var pName by rememberSaveable { mutableStateOf(prefs.getString("n", "") ?: "") }
    var pPhone by rememberSaveable { mutableStateOf(prefs.getString("p", "") ?: "") }
    var isAuth by remember { mutableStateOf(pName.isNotEmpty()) }
    var currentTab by rememberSaveable { mutableStateOf("BOOK") }
    
    // --- 💎 HOISTED PERSISTENT STATE 💎 ---
    var isSearching by rememberSaveable { mutableStateOf(false) }
    var rideStatus by rememberSaveable { mutableStateOf("IDLE") }
    var ridePrice by rememberSaveable { mutableStateOf("0") }
    var activeRideId by rememberSaveable { mutableStateOf("") }
    var driverName by rememberSaveable { mutableStateOf<String?>(null) }
    var dPhone by rememberSaveable { mutableStateOf<String?>(null) }
    var step by rememberSaveable { mutableStateOf("PICKUP") }
    var pickupPt by remember { mutableStateOf<GeoPoint?>(null) }
    var destPt by remember { mutableStateOf<GeoPoint?>(null) }
    var roadDistance by remember { mutableStateOf(0.0) }
    var selectedTier by remember { mutableStateOf(ServiceTier.COMFORT) }
    var hrCount by remember { mutableStateOf(1) }

    if (!isAuth) {
        Column(modifier = Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "BAYRA", fontSize = 48.sp, fontWeight = FontWeight.Black, color = Color(0xFF5E4E92))
            OutlinedTextField(value = pName, onValueChange = { pName = it }, label = { Text(text = "Name") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = pPhone, onValueChange = { pPhone = it }, label = { Text(text = "Phone") }, modifier = Modifier.fillMaxWidth())
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
                        pName, pPhone, isSearching, rideStatus, ridePrice, driverName, dPhone, activeRideId, step, pickupPt, destPt, roadDistance, selectedTier, hrCount,
                        { isSearching = it }, { rideStatus = it }, { ridePrice = it }, { driverName = it }, { dPhone = it }, { activeRideId = it },
                        { step = it }, { pickupPt = it }, { destPt = it }, { roadDistance = it }, { selectedTier = it }, { hrCount = it }
                    )
                } else {
                    Column(modifier = Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = "ACCOUNT", fontSize = 24.sp, fontWeight = FontWeight.Bold); Text(text = pName)
                        Spacer(modifier = Modifier.weight(1f)); Button({ prefs.edit().clear().apply(); isAuth = false; isSearching = false }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text(text = "LOGOUT") }
                    }
                }
            }
        }
    }
}

@Composable
fun BookingHub(
    name: String, phone: String, isSearching: Boolean, status: String, price: String, driver: String?, dPh: String?, rideId: String,
    step: String, pPt: GeoPoint?, dPt: GeoPoint?, dist: Double, tier: ServiceTier, hrs: Int,
    onSearch: (Boolean) -> Unit, onStatus: (String) -> Unit, onPrice: (String) -> Unit, onDriver: (String?) -> Unit, onDPhone: (String?) -> Unit, onId: (String) -> Unit,
    onStep: (String) -> Unit, onPickup: (GeoPoint?) -> Unit, onDest: (GeoPoint?) -> Unit, onDist: (Double) -> Unit, onTier: (ServiceTier) -> Unit, onHr: (Int) -> Unit
) {
    val context = LocalContext.current
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var routePoints = remember { mutableStateListOf<GeoPoint>() }
    val isNight = Calendar.getInstance().get(Calendar.HOUR_OF_DAY).let { it >= 20 || it < 6 }
    val fare = if (tier.isHr) (tier.base * 1.15).toInt() else (tier.base + (dist * tier.kmRate) + tier.extra + (if(isNight) 200 else 0)).toInt()

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx -> MapView(ctx).apply { 
            setTileSource(object : XYTileSource("G-Hybrid", 0, 20, 256, ".jpg", arrayOf("https://mt1.google.com/vt/lyrs=y&")) {
                override fun getTileURLString(pTileIndex: Long): String = baseUrl + "x=" + MapTileIndex.getX(pTileIndex) + "&y=" + MapTileIndex.getY(pTileIndex) + "&z=" + MapTileIndex.getZoom(pTileIndex)
            })
            setMultiTouchControls(true); controller.setZoom(17.0); controller.setCenter(GeoPoint(6.0333, 37.5500)); mapViewRef = this 
        } }, update = { view ->
            view.overlays.clear()
            pPt?.let { view.overlays.add(Marker(view).apply { position = it; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM) }) }
            if(!tier.isHr && dPt != null) {
                view.overlays.add(Marker(view).apply { position = dPt; icon = view.context.getDrawable(android.R.drawable.ic_menu_directions) })
                if(routePoints.isNotEmpty()) view.overlays.add(Polyline().apply { setPoints(routePoints); color = android.graphics.Color.WHITE; width = 12f })
            }
            view.invalidate()
        })

        if (isSearching || status == "COMPLETED") {
            Column(modifier = Modifier.fillMaxSize().background(Color.White).padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = if(status == "COMPLETED") "ARRIVED" else (if(driver != null) "$driver COMING" else "SEARCHING..."), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(text = "$price ETB", fontSize = 32.sp, fontWeight = FontWeight.Black, color = Color.Red)
                if(dPh != null) Button({ context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$dPh"))) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))) { Text(text = "📞 CALL") }
                Button({ if(status == "COMPLETED") { onStatus("IDLE"); onSearch(false) } else onSearch(false) }, modifier = Modifier.padding(top = 20.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text(text = "CANCEL") }
            }
        } else {
            if(step != "CONFIRM") Box(modifier = Modifier.fillMaxSize(), Alignment.Center) { Text(text = if(step=="PICKUP") "📍" else "🏁", fontSize = 40.sp, modifier = Modifier.padding(bottom = 40.dp)) }
            Column(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.White, RoundedCornerShape(28.dp)).padding(24.dp)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(ServiceTier.values()) { t ->
                        Surface(modifier = Modifier.clickable { onTier(t); onStep(if(t.isHr) "CONFIRM" else "PICKUP") }, color = if(tier == t) Color(0xFF4CAF50) else Color(0xFFF0F0F0), shape = RoundedCornerShape(12.dp)) {
                            Text(text = t.label, modifier = Modifier.padding(14.dp, 10.dp), fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                if (step == "PICKUP") Button({ onPickup(mapViewRef?.mapCenter as GeoPoint); onStep(if(tier.isHr) "CONFIRM" else "DEST") }, modifier = Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Black)) { Text(text = "SET PICKUP") }
                else if (step == "DEST") Button({ 
                    val end = mapViewRef?.mapCenter as GeoPoint; onDest(end)
                    thread { try {
                        val json = JSONObject(URL("https://router.project-osrm.org/route/v1/driving/${pPt!!.longitude},${pPt!!.latitude};${end.longitude},${end.latitude}?overview=full&geometries=geojson").readText())
                        val r = json.getJSONArray("routes").getJSONObject(0)
                        onDist(r.getDouble("distance") / 1000.0)
                        val geo = r.getJSONObject("geometry").getJSONArray("coordinates"); val pts = mutableListOf<GeoPoint>()
                        for (i in 0 until geo.length()) { pts.add(GeoPoint(geo.getJSONArray(i).getDouble(1), geo.getJSONArray(i).getDouble(0))) }
                        routePoints.clear(); routePoints.addAll(pts); onStep("CONFIRM")
                    } catch (e: Exception) {} }
                }, modifier = Modifier.fillMaxWidth().height(60.dp)) { Text(text = "SET DESTINATION") }
                else {
                    Text(text = "$fare ETB", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color.Red)
                    Button({
                        val id = "R_${System.currentTimeMillis()}"
                        onId(id); onPrice(fare.toString()); onSearch(true)
                        FirebaseDatabase.getInstance().getReference("rides/$id").setValue(mapOf("id" to id, "pName" to name, "pPhone" to phone, "status" to "REQUESTED", "price" to fare.toString(), "tier" to tier.label, "pLat" to pPt?.latitude, "pLon" to pPt?.longitude, "dLat" to dPt?.latitude, "dLon" to dPt?.longitude))
                        FirebaseDatabase.getInstance().getReference("rides/$id").addValueEventListener(object : ValueEventListener {
                            override fun onDataChange(s: DataSnapshot) {
                                if(!s.exists()) { onSearch(false); return }
                                onStatus(s.child("status").getValue(String::class.java) ?: "REQUESTED")
                                onDriver(s.child("driverName").getValue(String::class.java))
                                onDPhone(s.child("dPhone").getValue(String::class.java))
                            }
                            override fun onCancelled(e: DatabaseError) {}
                        })
                    }, modifier = Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5E4E92))) { Text(text = "BOOK NOW") }
                }
            }
        }
    }
}

package com.bayra.customer

import android.Manifest // 🛡️ THE MISSING WELD
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

enum class ServiceTier(val label: String, val base: Int) {
    POOL("Pool", 80), COMFORT("Comfort", 120), CODE_3("Code 3", 280), BAJAJ_HR("Bajaj Hr", 350)
}

class MainActivity : ComponentActivity() {
    private var locationOverlay: MyLocationNewOverlay? = null
    
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted -> 
        if (isGranted) locationOverlay?.enableMyLocation() 
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        Configuration.getInstance().userAgentValue = "BayraSovereign"
        
        setContent { MaterialTheme { PassengerApp() } }
        
        // Finalize Permission check
        requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}

@Composable
fun PassengerApp() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("bayra_p_vFINAL", 0)
    
    // --- 💎 PERSISTENT GLOBAL STATE 💎 ---
    var pName by rememberSaveable { mutableStateOf(prefs.getString("n", "") ?: "") }
    var pPhone by rememberSaveable { mutableStateOf(prefs.getString("p", "") ?: "") }
    var isAuth by remember { mutableStateOf(pName.isNotEmpty()) }
    var currentTab by rememberSaveable { mutableStateOf("BOOK") }
    
    // LIVE BOOKING STATE (Keeps running when you switch tabs)
    var isSearching by rememberSaveable { mutableStateOf(false) }
    var rideStatus by rememberSaveable { mutableStateOf("IDLE") }
    var ridePrice by rememberSaveable { mutableStateOf("112") }
    var driverName by rememberSaveable { mutableStateOf<String?>(null) }

    if (!isAuth) {
        Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
            Text("BAYRA LOGIN", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color(0xFF5E4E92))
            Spacer(Modifier.height(30.dp))
            OutlinedTextField(pName, { pName = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(pPhone, { pPhone = it }, label = { Text("Phone") }, modifier = Modifier.fillMaxWidth())
            Button({ if(pName.isNotEmpty() && pPhone.isNotEmpty()){ prefs.edit().putString("n", pName).putString("p", pPhone).apply(); isAuth = true } }, Modifier.fillMaxWidth().height(60.dp)) { Text("ENTER") }
        }
    } else {
        Scaffold(
            bottomBar = {
                NavigationBar(containerColor = Color.White) {
                    NavigationBarItem(selected = currentTab == "BOOK", onClick = { currentTab = "BOOK" }, icon = { Text("🚕") }, label = { Text("Book") })
                    NavigationBarItem(selected = currentTab == "ACCOUNT", onClick = { currentTab = "ACCOUNT" }, icon = { Text("👤") }, label = { Text("Account") })
                }
            }
        ) { p ->
            Box(Modifier.padding(p)) {
                if (currentTab == "BOOK") {
                    BookingHub(
                        pName, pPhone, isSearching, rideStatus, ridePrice, driverName,
                        onSearchingChange = { isSearching = it },
                        onStatusChange = { rideStatus = it },
                        onDriverChange = { driverName = it }
                    )
                } else {
                    AccountView(pName, pPhone) {
                        prefs.edit().clear().apply()
                        isAuth = false
                        isSearching = false
                        rideStatus = "IDLE"
                    }
                }
            }
        }
    }
}

@Composable
fun BookingHub(
    name: String, phone: String,
    isSearching: Boolean, status: String, price: String, driver: String?,
    onSearchingChange: (Boolean) -> Unit,
    onStatusChange: (String) -> Unit,
    onDriverChange: (String?) -> Unit
) {
    val context = LocalContext.current
    var selectedTier by remember { mutableStateOf(ServiceTier.COMFORT) }
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }

    Box(Modifier.fillMaxSize()) {
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
            Column(Modifier.fillMaxSize().background(Color.White).padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                if (status == "COMPLETED") {
                    Text("TRIP FINISHED", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color(0xFF4CAF50))
                    Text("$price ETB", fontSize = 48.sp, fontWeight = FontWeight.Bold)
                    Button({ 
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://checkout.chapa.co/checkout/web/payment/CHAPUBK-GTviouToMOe9vOg5t1dNR9paQ1M62jOX")))
                        onStatusChange("IDLE"); onSearchingChange(false) 
                    }, Modifier.fillMaxWidth().height(60.dp)) { Text("PAY NOW") }
                } else {
                    CircularProgressIndicator(color = Color(0xFF5E4E92))
                    Spacer(Modifier.height(20.dp)); Text(if(driver != null) "$driver IS COMING" else "SEARCHING...", fontWeight = FontWeight.Bold)
                    Button({ onSearchingChange(false) }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red), modifier = Modifier.padding(top = 40.dp)) { Text("CANCEL") }
                }
            }
        } else {
            Box(Modifier.fillMaxSize(), Alignment.Center) { Text("📍", fontSize = 40.sp, modifier = Modifier.padding(bottom = 40.dp)) }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.White, RoundedCornerShape(topStart = 28.dp)).padding(24.dp)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(ServiceTier.values()) { tier ->
                        Surface(Modifier.clickable { selectedTier = tier }, color = if(selectedTier == tier) Color(0xFF4CAF50) else Color(0xFFF0F0F0), shape = RoundedCornerShape(12.dp)) {
                            Text(tier.label, Modifier.padding(14.dp, 10.dp), fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                Button({
                    val ref = FirebaseDatabase.getInstance().getReference("rides")
                    val id = "R_${System.currentTimeMillis()}"
                    ref.child(id).setValue(mapOf("id" to id, "pName" to name, "status" to "REQUESTED", "price" to price))
                    onSearchingChange(true)
                    ref.child(id).addValueEventListener(object : ValueEventListener {
                        override fun onDataChange(s: DataSnapshot) {
                            if (!s.exists() && isSearching) { onSearchingChange(false); onStatusChange("COMPLETED"); return }
                            onStatusChange(s.child("status").getValue(String::class.java) ?: "REQUESTED")
                            onDriverChange(s.child("driverName").getValue(String::class.java))
                        }
                        override fun onCancelled(e: DatabaseError) {}
                    })
                }, Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5E4E92))) { Text("CONFIRM BOOKING") }
            }
        }
    }
}

@Composable
fun AccountView(name: String, phone: String, onLogout: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("ACCOUNT", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(name, fontWeight = FontWeight.Bold); Text(phone, color = Color.Gray)
        Spacer(Modifier.weight(1f))
        Button(onLogout, colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text("LOGOUT") }
    }
}

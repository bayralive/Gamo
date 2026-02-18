package com.bayra.customer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.preference.PreferenceManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import com.google.firebase.database.*
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

enum class Tier(val label: String, val base: Double, val isHr: Boolean) {
    POOL("Pool", 80.0, false), COMFORT("Comfort", 120.0, false), 
    CODE_3("Code 3", 280.0, false), BAJAJ_HR("Bajaj Hr", 350.0, true),
    C3_HR("C3 Hr", 550.0, true)
}

class MainActivity : ComponentActivity() {
    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        requestPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        setContent { MaterialTheme { PassengerApp() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PassengerApp() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("bayra_p_v151", Context.MODE_PRIVATE) }
    
    var pName by rememberSaveable { mutableStateOf(prefs.getString("n", "") ?: "") }
    var pPhone by rememberSaveable { mutableStateOf(prefs.getString("p", "") ?: "") }
    var pEmail by rememberSaveable { mutableStateOf(prefs.getString("e", "") ?: "") }
    var isAuth by remember { mutableStateOf(pName.isNotEmpty() && pEmail.isNotEmpty()) }

    if (!isAuth) {
        LoginView { n, p, e ->
            prefs.edit().putString("n", n).putString("p", p).putString("e", e).apply()
            pName = n; pPhone = p; pEmail = e; isAuth = true
        }
    } else {
        BookingCore(pName, pPhone, pEmail, prefs)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookingCore(name: String, phone: String, email: String, prefs: android.content.SharedPreferences) {
    var step by remember { mutableStateOf("PICKUP") }
    var status by remember { mutableStateOf("IDLE") }
    var activeRideId by remember { mutableStateOf(prefs.getString("active_ride_id", "") ?: "") }
    
    var pickupPt by remember { mutableStateOf<GeoPoint?>(null) }
    var destPt by remember { mutableStateOf<GeoPoint?>(null) }
    var selectedTier by remember { mutableStateOf(Tier.COMFORT) }
    var mapRef by remember { mutableStateOf<MapView?>(null) }

    // 🔥 PERSISTENCE ENGINE: Check if request is still active in Firebase on startup
    LaunchedEffect(Unit) {
        if (activeRideId.isNotEmpty()) {
            FirebaseDatabase.getInstance().getReference("rides/$activeRideId")
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(s: DataSnapshot) {
                        val firebaseStatus = s.child("status").value?.toString()
                        if (firebaseStatus != null && firebaseStatus != "COMPLETED") {
                            status = firebaseStatus
                        } else {
                            prefs.edit().remove("active_ride_id").apply()
                            activeRideId = ""
                            status = "IDLE"
                        }
                    }
                    override fun onCancelled(e: DatabaseError) {}
                })
        }
    }

    // REAL-TIME UPDATES FOR ACTIVE RIDE
    LaunchedEffect(activeRideId) {
        if (activeRideId.isNotEmpty()) {
            FirebaseDatabase.getInstance().getReference("rides/$activeRideId/status")
                .addValueEventListener(object : ValueEventListener {
                    override fun onDataChange(s: DataSnapshot) {
                        val newStatus = s.value?.toString() ?: "IDLE"
                        if (newStatus == "COMPLETED") {
                            status = "IDLE"; activeRideId = ""; prefs.edit().remove("active_ride_id").apply()
                        } else {
                            status = newStatus
                        }
                    }
                    override fun onCancelled(e: DatabaseError) {}
                })
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context -> 
                MapView(context).apply { 
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true) 
                    setBuiltInZoomControls(false)
                    controller.setZoom(17.5)
                    controller.setCenter(GeoPoint(6.0333, 37.5500))
                    
                    // 🔥 GPS SIGNAL POINT (Blue Dot)
                    val locationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(context), this)
                    locationOverlay.enableMyLocation()
                    overlays.add(locationOverlay)
                    
                    mapRef = this 
                } 
            },
            update = { view ->
                view.overlays.filterIsInstance<Marker>().forEach { view.overlays.remove(it) }
                pickupPt?.let { pt -> Marker(view).apply { position = pt; title = "Pickup" }.also { view.overlays.add(it) } }
                destPt?.let { pt -> Marker(view).apply { position = pt; title = "Destination" }.also { view.overlays.add(it) } }
                view.invalidate()
            }
        )

        if (status != "IDLE") {
            // SEARCHING / ACTIVE OVERLAY
            Surface(modifier = Modifier.fillMaxSize(), color = Color.White) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(color = Color(0xFF1A237E), strokeWidth = 8.dp, modifier = Modifier.size(80.dp))
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(text = status, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text(text = "Please stay on this screen", color = Color.Gray)
                    Spacer(modifier = Modifier.height(40.dp))
                    Button(
                        onClick = { 
                            FirebaseDatabase.getInstance().getReference("rides/$activeRideId").removeValue()
                            prefs.edit().remove("active_ride_id").apply()
                            activeRideId = ""; status = "IDLE"
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                        modifier = Modifier.height(55.dp).padding(horizontal = 32.dp)
                    ) { Text(text = "CANCEL REQUEST", fontWeight = FontWeight.Bold) }
                }
            }
        } else {
            // BOOKING UI
            if (step != "CONFIRM") {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(text = "📍", fontSize = 48.sp, modifier = Modifier.padding(bottom = 48.dp))
                }
            }

            Column(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.White, RoundedCornerShape(topStart = 28.dp)).padding(24.dp)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(Tier.values().toList()) { t ->
                        Surface(modifier = Modifier.clickable { selectedTier = t; step = "PICKUP" }, color = if(selectedTier == t) Color(0xFF1A237E) else Color(0xFFEEEEEE), shape = RoundedCornerShape(8.dp)) {
                            Text(text = t.label, modifier = Modifier.padding(12.dp, 8.dp), color = if(selectedTier == t) Color.White else Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
                if (step == "PICKUP") {
                    Button(onClick = { pickupPt = mapRef?.mapCenter as GeoPoint; step = if(selectedTier.isHr) "CONFIRM" else "DEST" }, modifier = Modifier.fillMaxWidth().height(60.dp)) { Text(text = "SET PICKUP", fontWeight = FontWeight.Bold) }
                } else if (step == "DEST") {
                    Button(onClick = { destPt = mapRef?.mapCenter as GeoPoint; step = "CONFIRM" }, modifier = Modifier.fillMaxWidth().height(60.dp)) { Text(text = "SET DESTINATION", fontWeight = FontWeight.Bold) }
                } else {
                    Button(
                        onClick = { 
                            val id = "R_${System.currentTimeMillis()}"
                            val dbRef = FirebaseDatabase.getInstance().getReference("rides/$id")
                            dbRef.setValue(mapOf("id" to id, "pName" to name, "pPhone" to phone, "pEmail" to email, "status" to "REQUESTED", "pLat" to pickupPt?.latitude, "pLon" to pickupPt?.longitude, "dLat" to destPt?.latitude, "dLon" to destPt?.longitude, "tier" to selectedTier.label))
                            prefs.edit().putString("active_ride_id", id).apply()
                            activeRideId = id; status = "REQUESTED"
                        }, 
                        modifier = Modifier.fillMaxWidth().height(65.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A237E)),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text(text = "BOOK NOW", fontWeight = FontWeight.ExtraBold) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginView(onLogin: (String, String, String) -> Unit) {
    var n by remember { mutableStateOf("") }; var p by remember { mutableStateOf("") }; var e by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(32.dp).background(Color.White).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Image(painterResource(id = R.drawable.logo_passenger), null, Modifier.size(200.dp))
        Text(text = "BAYRA TRAVEL", fontSize = 32.sp, fontWeight = FontWeight.Black, color = Color(0xFF1A237E))
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(value = n, onValueChange = { n = it }, label = { Text(text = "Full Name") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = p, onValueChange = { p = it }, label = { Text(text = "Phone Number") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = e, onValueChange = { e = it }, label = { Text(text = "Email") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { if(n.isNotEmpty() && e.contains("@")) onLogin(n, p, e) }, modifier = Modifier.fillMaxWidth().height(60.dp).padding(top = 20.dp)) { Text(text = "START") }
    }
}

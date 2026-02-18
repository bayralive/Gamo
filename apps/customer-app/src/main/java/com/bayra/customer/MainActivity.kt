package com.bayra.customer

import android.Manifest
import android.content.*
import android.net.Uri
import android.os.*
import android.preference.PreferenceManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
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
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.net.URL
import java.net.HttpURLConnection
import kotlin.concurrent.thread

const val DB_URL = "https://bayra-84ecf-default-rtdb.europe-west1.firebasedatabase.app"

enum class Tier(val label: String, val base: Double, val isHr: Boolean) {
    POOL("Pool", 80.0, false), COMFORT("Comfort", 120.0, false), 
    CODE_3("Code 3", 280.0, false), BAJAJ_HR("Bajaj Hr", 350.0, true),
    C3_HR("C3 Hr", 550.0, true)
}

class MainActivity : ComponentActivity() {
    private val requestLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        requestLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        setContent { MaterialTheme { PassengerApp() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PassengerApp() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("bayra_p_v177", Context.MODE_PRIVATE) }
    var pName by rememberSaveable { mutableStateOf(prefs.getString("n", "") ?: "") }
    var pPhone by rememberSaveable { mutableStateOf(prefs.getString("p", "") ?: "") }
    var pEmail by rememberSaveable { mutableStateOf(prefs.getString("e", "") ?: "") }
    var isAuth by remember { mutableStateOf(pName.isNotEmpty() && pEmail.isNotEmpty()) }
    var currentTab by rememberSaveable { mutableStateOf("HOME") }

    if (!isAuth) {
        LoginView { n, p, e ->
            prefs.edit().putString("n", n).putString("p", p).putString("e", e).apply()
            pName = n; pPhone = p; pEmail = e; isAuth = true
        }
    } else {
        Scaffold(
            bottomBar = {
                NavigationBar(containerColor = Color.White) {
                    NavigationBarItem(
                        icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                        label = { Text("Book") },
                        selected = currentTab == "HOME",
                        onClick = { currentTab = "HOME" },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = Color(0xFF1A237E), indicatorColor = Color(0xFFE8EAF6))
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Filled.Person, contentDescription = null) },
                        label = { Text("Account") },
                        selected = currentTab == "ACCOUNT",
                        onClick = { currentTab = "ACCOUNT" },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = Color(0xFF1A237E), indicatorColor = Color(0xFFE8EAF6))
                    )
                }
            }
        ) { paddingValues ->
            Box(Modifier.padding(paddingValues)) {
                if (currentTab == "HOME") {
                    BookingCore(pName, pPhone, pEmail, prefs)
                } else {
                    AccountView(pName, pPhone, pEmail) {
                        prefs.edit().clear().apply()
                        pName = ""; pPhone = ""; pEmail = ""; isAuth = false
                    }
                }
            }
        }
    }
}

@Composable
fun AccountView(name: String, phone: String, email: String, onLogout: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFFFAFAFA)).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(40.dp))
        Image(painterResource(id = R.drawable.logo_passenger), null, Modifier.size(120.dp))
        Spacer(modifier = Modifier.height(20.dp))
        Text(text = name, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.Black)
        Text(text = phone, fontSize = 16.sp, color = Color.Gray)
        Text(text = email, fontSize = 16.sp, color = Color.Gray)
        
        Spacer(modifier = Modifier.height(40.dp))
        
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.padding(16.dp)) {
                Text("Support", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("Contact Arba Minch HQ: 0911000000", color = Color.Blue)
            }
        }

        Spacer(modifier = Modifier.weight(1f))
        
        Button(
            onClick = onLogout,
            modifier = Modifier.fillMaxWidth().height(55.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
        ) { Text("LOGOUT", fontWeight = FontWeight.Bold) }
    }
}

// ... (BookingCore and LoginView remain exactly the same as Phase 176 - Inserted below for completeness)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookingCore(name: String, phone: String, email: String, prefs: android.content.SharedPreferences) {
    val context = LocalContext.current
    var step by remember { mutableStateOf("PICKUP") }
    var status by remember { mutableStateOf("IDLE") }
    var activeRideId by remember { mutableStateOf(prefs.getString("active_id", "") ?: "") }
    var activePrice by remember { mutableStateOf("0") }
    var driverName by remember { mutableStateOf("") }
    
    var pickupPt by remember { mutableStateOf<GeoPoint?>(null) }
    var destPt by remember { mutableStateOf<GeoPoint?>(null) }
    var selectedTier by remember { mutableStateOf(Tier.COMFORT) }
    var hrCount by remember { mutableStateOf(1) }
    var mapRef by remember { mutableStateOf<MapView?>(null) }
    
    var isGeneratingLink by remember { mutableStateOf(false) }

    LaunchedEffect(activeRideId) {
        if (activeRideId.isNotEmpty()) {
            FirebaseDatabase.getInstance(DB_URL).getReference("rides/$activeRideId")
                .addValueEventListener(object : ValueEventListener {
                    override fun onDataChange(s: DataSnapshot) {
                        status = s.child("status").value?.toString() ?: "IDLE"
                        activePrice = s.child("price").value?.toString()?.replace(" ETB", "") ?: "0"
                        driverName = s.child("driverName").value?.toString() ?: ""
                    }
                    override fun onCancelled(e: DatabaseError) {}
                })
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { factoryCtx -> 
                MapView(factoryCtx).apply { 
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true); setBuiltInZoomControls(false)
                    controller.setZoom(17.5); controller.setCenter(GeoPoint(6.0333, 37.5500))
                    val loc = MyLocationNewOverlay(GpsMyLocationProvider(factoryCtx), this)
                    loc.enableMyLocation(); overlays.add(loc)
                    mapRef = this 
                } 
            },
            update = { view ->
                view.overlays.filterIsInstance<Marker>().forEach { view.overlays.remove(it) }
                pickupPt?.let { pt -> Marker(view).apply { position = pt; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM) }.also { view.overlays.add(it) } }
                if (!selectedTier.isHr) {
                    destPt?.let { pt -> Marker(view).apply { position = pt; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM); icon = view.context.getDrawable(android.R.drawable.ic_menu_directions) }.also { view.overlays.add(it) } }
                }
                view.invalidate()
            }
        )

        // PAYMENT HANDOVER
        if (status == "ARRIVED_DEST" || status.startsWith("PAID_")) {
            Surface(modifier = Modifier.fillMaxSize(), color = Color.White.copy(alpha = 0.98f)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, modifier = Modifier.padding(32.dp)) {
                    Text(text = "PAYMENT DUE", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1A237E))
                    Text(text = "$activePrice ETB", fontSize = 60.sp, fontWeight = FontWeight.ExtraBold)
                    
                    if (status.startsWith("PAID_")) {
                         Text(text = "Payment Processing...", color = Color.Green, fontWeight = FontWeight.Bold)
                         CircularProgressIndicator()
                    } else {
                        Spacer(modifier = Modifier.height(40.dp))
                        Button(
                            onClick = {
                                isGeneratingLink = true
                                thread {
                                    try {
                                        val url = URL("https://bayra-backend-eu.onrender.com/initialize-payment")
                                        val conn = url.openConnection() as HttpURLConnection
                                        conn.requestMethod = "POST"; conn.doOutput = true
                                        conn.connectTimeout = 60000; conn.readTimeout = 60000
                                        conn.setRequestProperty("Content-Type", "application/json")
                                        val body = JSONObject().put("amount", activePrice).put("email", email).put("name", name).put("rideId", activeRideId).toString()
                                        conn.outputStream.write(body.toByteArray())
                                        val res = JSONObject(conn.inputStream.bufferedReader().readText())
                                        val payUrl = res.getJSONObject("data").getString("checkout_url")
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(payUrl)))
                                        FirebaseDatabase.getInstance(DB_URL).getReference("rides/$activeRideId/status").setValue("PAID_CHAPA")
                                        isGeneratingLink = false
                                    } catch (e: Exception) { isGeneratingLink = false }
                                }
                            }, 
                            modifier = Modifier.fillMaxWidth().height(60.dp), 
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A237E))
                        ) { 
                            if(isGeneratingLink) CircularProgressIndicator(color = Color.White) else Text(text = "PAY ONLINE") 
                        }
                        
                        TextButton(onClick = { 
                            FirebaseDatabase.getInstance(DB_URL).getReference("rides/$activeRideId/status").setValue("PAID_CASH") 
                        }, modifier = Modifier.padding(top = 16.dp)) { Text(text = "PAY CASH TO DRIVER") }
                    }
                }
            }
        } else if (status == "COMPLETED") {
             LaunchedEffect(Unit) { status = "IDLE"; activeRideId = ""; prefs.edit().remove("active_id").apply() }
        } else if (status != "IDLE") {
             Surface(modifier = Modifier.fillMaxSize(), color = Color.White) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(color = Color(0xFF1A237E))
                    Text(text = status, modifier = Modifier.padding(top=20.dp), fontWeight = FontWeight.Bold)
                    Button(onClick = { FirebaseDatabase.getInstance(DB_URL).getReference("rides/$activeRideId").removeValue(); status = "IDLE"; activeRideId = ""; prefs.edit().remove("active_id").apply() }, modifier = Modifier.padding(top = 40.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text(text = "CANCEL") }
                }
            }
        } else {
             if (step != "CONFIRM") Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = "📍", fontSize = 48.sp, modifier = Modifier.padding(bottom = 48.dp))
            }
            Column(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.White, RoundedCornerShape(topStart = 28.dp)).padding(24.dp)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(Tier.values().toList()) { t ->
                        Surface(modifier = Modifier.clickable { 
                            selectedTier = t
                            if (pickupPt != null && (t.isHr || destPt != null)) step = "CONFIRM" else if (pickupPt != null) step = "DEST" else step = "PICKUP"
                        }, color = if(selectedTier == t) Color(0xFF1A237E) else Color(0xFFEEEEEE), shape = RoundedCornerShape(8.dp)) {
                            Text(text = t.label, modifier = Modifier.padding(12.dp, 8.dp), color = if(selectedTier == t) Color.White else Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                
                if (selectedTier.isHr && step == "CONFIRM") {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "Hours:", fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { if(hrCount > 1) hrCount-- }) { Text(text = "−", fontSize = 24.sp, fontWeight = FontWeight.Bold) }
                            Text(text = "$hrCount HR", fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 8.dp))
                            IconButton(onClick = { if(hrCount < 12) hrCount++ }) { Text(text = "+", fontSize = 24.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
                }

                if (step == "PICKUP") {
                    Button(onClick = { pickupPt = mapRef?.mapCenter as GeoPoint; step = if(selectedTier.isHr) "CONFIRM" else "DEST" }, modifier = Modifier.fillMaxWidth().height(60.dp)) { Text(text = "SET PICKUP", fontWeight = FontWeight.Bold) }
                } else if (step == "DEST") {
                    Button(onClick = { destPt = mapRef?.mapCenter as GeoPoint; step = "CONFIRM" }, modifier = Modifier.fillMaxWidth().height(60.dp)) { Text(text = "SET DESTINATION", fontWeight = FontWeight.Bold) }
                } else {
                    val raw = if(selectedTier.isHr) (selectedTier.base * hrCount) else (selectedTier.base * 2.8)
                    val fare = (raw * 1.15).toInt()
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "$fare ETB", fontSize = 34.sp, fontWeight = FontWeight.Black, color = Color(0xFFD50000))
                        TextButton(onClick = { pickupPt = null; destPt = null; step = "PICKUP" }) { Text(text = "Reset") }
                    }
                    Button(
                        onClick = { 
                            val id = "R_${System.currentTimeMillis()}"
                            FirebaseDatabase.getInstance(DB_URL).getReference("rides/$id").setValue(mapOf(
                                "id" to id, "pName" to name, "pPhone" to phone, "pEmail" to email, "status" to "REQUESTED", "price" to fare.toString(), 
                                "pLat" to pickupPt?.latitude, "pLon" to pickupPt?.longitude, 
                                "dLat" to destPt?.latitude, "dLon" to destPt?.longitude, "tier" to selectedTier.label
                            ))
                            activeRideId = id; prefs.edit().putString("active_id", id).apply()
                        }, 
                        modifier = Modifier.fillMaxWidth().height(65.dp).padding(top = 10.dp), 
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A237E)),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text(text = if(selectedTier.isHr) "BOOK CONTRAT" else "BOOK RIDE", fontWeight = FontWeight.ExtraBold) }
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
        OutlinedTextField(value = n, onValueChange = { n = it }, label = { Text(text = "Name") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(value = p, onValueChange = { p = it }, label = { Text(text = "Phone") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(value = e, onValueChange = { e = it }, label = { Text(text = "Email") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
        Button(onClick = { if(n.isNotEmpty() && e.contains("@")) onLogin(n, p, e) }, modifier = Modifier.fillMaxWidth().height(60.dp).padding(top = 24.dp)) { Text(text = "START TRAVELING") }
    }
}

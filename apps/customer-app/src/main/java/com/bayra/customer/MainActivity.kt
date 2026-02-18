package com.bayra.customer

import android.Manifest
import android.content.*
import android.net.Uri
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
    val prefs = remember { ctx.getSharedPreferences("bayra_treasury_v2", Context.MODE_PRIVATE) }
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
    val context = LocalContext.current // 🔥 DEFINED AS context
    var step by remember { mutableStateOf("PICKUP") }
    var status by remember { mutableStateOf("IDLE") }
    var activeRideId by remember { mutableStateOf(prefs.getString("active_id", "") ?: "") }
    var activePrice by remember { mutableStateOf("0") }
    
    var pickupPt by remember { mutableStateOf<GeoPoint?>(null) }
    var destPt by remember { mutableStateOf<GeoPoint?>(null) }
    var selectedTier by remember { mutableStateOf(Tier.COMFORT) }
    var mapRef by remember { mutableStateOf<MapView?>(null) }
    var isGeneratingLink by remember { mutableStateOf(false) }

    LaunchedEffect(activeRideId) {
        if (activeRideId.isNotEmpty()) {
            FirebaseDatabase.getInstance().getReference("rides/$activeRideId")
                .addValueEventListener(object : ValueEventListener {
                    override fun onDataChange(s: DataSnapshot) {
                        val fs = s.child("status").value?.toString() ?: "IDLE"
                        activePrice = s.child("price").value?.toString() ?: "0"
                        status = fs
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
                    setMultiTouchControls(true) 
                    setBuiltInZoomControls(false)
                    controller.setZoom(17.5)
                    controller.setCenter(GeoPoint(6.0333, 37.5500))
                    val loc = MyLocationNewOverlay(GpsMyLocationProvider(factoryCtx), this)
                    loc.enableMyLocation()
                    overlays.add(loc)
                    mapRef = this 
                } 
            },
            update = { view ->
                view.overlays.filterIsInstance<Marker>().forEach { view.overlays.remove(it) }
                pickupPt?.let { pt -> Marker(view).apply { position = pt; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM) }.also { view.overlays.add(it) } }
                destPt?.let { pt -> Marker(view).apply { position = pt; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM); icon = view.context.getDrawable(android.R.drawable.ic_menu_directions) }.also { view.overlays.add(it) } }
                view.invalidate()
            }
        )

        if (status == "COMPLETED") {
            Surface(modifier = Modifier.fillMaxSize(), color = Color.White.copy(alpha = 0.95f)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, modifier = Modifier.padding(32.dp)) {
                    Text(text = "ARRIVED", fontSize = 24.sp, fontWeight = FontWeight.Black, color = Color(0xFF2E7D32))
                    Text(text = "$activePrice ETB", fontSize = 56.sp, fontWeight = FontWeight.ExtraBold, color = Color.Black)
                    Spacer(modifier = Modifier.height(40.dp))
                    
                    if (isGeneratingLink) {
                        CircularProgressIndicator(color = Color(0xFF1A237E))
                    } else {
                        Button(
                            onClick = { 
                                isGeneratingLink = true
                                thread {
                                    try {
                                        val url = URL("https://bayra-backend-eu.onrender.com/initialize-payment")
                                        val conn = url.openConnection() as HttpURLConnection
                                        conn.requestMethod = "POST"; conn.doOutput = true
                                        conn.setRequestProperty("Content-Type", "application/json")
                                        val body = JSONObject().put("amount", activePrice).put("email", email).put("name", name).put("rideId", activeRideId).toString()
                                        conn.outputStream.write(body.toByteArray())
                                        val response = JSONObject(conn.inputStream.bufferedReader().readText())
                                        val checkoutUrl = response.getString("checkout_url")
                                        isGeneratingLink = false
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(checkoutUrl))
                                        context.startActivity(intent) // 🔥 FIXEDctx -> context
                                        prefs.edit().remove("active_id").apply()
                                        status = "IDLE"; activeRideId = ""
                                    } catch (e: Exception) { isGeneratingLink = false }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(65.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A237E)),
                            shape = RoundedCornerShape(16.dp)
                        ) { Text(text = "PAY WITH CHAPA", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        TextButton(onClick = { status = "IDLE"; activeRideId = ""; prefs.edit().remove("active_id").apply() }) { 
                            Text(text = "PAID WITH CASH", color = Color.Gray) 
                        }
                    }
                }
            }
        } else if (status != "IDLE") {
            Surface(modifier = Modifier.fillMaxSize(), color = Color.White) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(color = Color(0xFF1A237E), modifier = Modifier.size(70.dp))
                    Text(text = status, modifier = Modifier.padding(top = 20.dp), fontWeight = FontWeight.Bold)
                    Button(onClick = { 
                        FirebaseDatabase.getInstance().getReference("rides/$activeRideId").removeValue()
                        status = "IDLE"; activeRideId = ""; prefs.edit().remove("active_id").apply()
                    }, modifier = Modifier.padding(top = 40.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text(text = "CANCEL") }
                }
            }
        } else {
            if (step != "CONFIRM") Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = "📍", fontSize = 48.sp, modifier = Modifier.padding(bottom = 48.dp))
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
                    val raw = if(selectedTier.isHr) selectedTier.base else selectedTier.base * 2.8
                    val fare = (raw * 1.15).toInt()
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "$fare ETB", fontSize = 34.sp, fontWeight = FontWeight.Black, color = Color(0xFFD50000))
                        TextButton(onClick = { pickupPt = null; destPt = null; step = "PICKUP" }) { Text("Reset") }
                    }
                    Button(
                        onClick = { 
                            val id = "R_${System.currentTimeMillis()}"
                            FirebaseDatabase.getInstance().getReference("rides/$id").setValue(mapOf("id" to id, "pName" to name, "pPhone" to phone, "pEmail" to email, "status" to "REQUESTED", "price" to fare.toString(), "pLat" to pickupPt?.latitude, "pLon" to pickupPt?.longitude, "dLat" to destPt?.latitude, "dLon" to destPt?.longitude, "tier" to selectedTier.label))
                            activeRideId = id; prefs.edit().putString("active_id", id).apply()
                        }, 
                        modifier = Modifier.fillMaxWidth().height(65.dp).padding(top = 10.dp), 
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
        OutlinedTextField(value = n, onValueChange = { n = it }, label = { Text(text = "Name") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(value = p, onValueChange = { p = it }, label = { Text(text = "Phone") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(value = e, onValueChange = { e = it }, label = { Text(text = "Email") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
        Button(onClick = { if(n.isNotEmpty() && e.contains("@")) onLogin(n, p, e) }, modifier = Modifier.fillMaxWidth().height(60.dp).padding(top = 24.dp)) { Text(text = "START TRAVELING") }
    }
}

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
    val prefs = remember { ctx.getSharedPreferences("bayra_p_v184", Context.MODE_PRIVATE) }
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
                        icon = { Icon(Icons.Filled.Home, null) }, label = { Text("Book") },
                        selected = currentTab == "HOME", onClick = { currentTab = "HOME" },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = Color(0xFF1A237E))
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Filled.Person, null) }, label = { Text("About & Account") },
                        selected = currentTab == "ACCOUNT", onClick = { currentTab = "ACCOUNT" },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = Color(0xFF1A237E))
                    )
                }
            }
        ) { padding ->
            Box(Modifier.padding(padding)) {
                if (currentTab == "HOME") BookingCore(pName, pPhone, pEmail, prefs)
                else AccountView(pName, pPhone, pEmail) { prefs.edit().clear().apply(); isAuth = false }
            }
        }
    }
}

@Composable
fun AccountView(name: String, phone: String, email: String, onLogout: () -> Unit) {
    val ctx = LocalContext.current
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFFFAFAFA)).verticalScroll(scrollState).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(painterResource(id = R.drawable.logo_passenger), null, Modifier.size(100.dp))
        Spacer(Modifier.height(10.dp))
        Text(text = "Sarotethai nuna maaddo,\nAadhidatethai nuna kaaletho", fontStyle = FontStyle.Italic, textAlign = TextAlign.Center, color = Color(0xFF1A237E))
        
        Spacer(Modifier.height(20.dp))
        
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(2.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text(text = "PROFILE", fontWeight = FontWeight.Bold, color = Color.Gray)
                Text(text = name, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(text = phone, color = Color.DarkGray)
                Text(text = email, color = Color.DarkGray)
            }
        }

        Spacer(Modifier.height(20.dp))

        // 🔥 THE IMPERIAL CONSTITUTION (Amharic)
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.padding(16.dp)) {
                Text(text = "የክፍያ እና የእርዳታ መመሪያ", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color(0xFF1A237E))
                Spacer(Modifier.height(8.dp))
                Text(text = "1. መደበኛ የጉዞ ክፍያ (Ride)\n• መነሻ ክፍያ፦ 50 ብር\n• የሌሊት ጭማሪ፦ 200 ብር (2:00 PM - 12:00 AM)\n• የጋራ ጉዞ (Pool)፦ 20% ቅናሽ", fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Text(text = "2. የኮንትራት ክፍያ (Contrat)\n• ባጃጅ፦ 350 ብር/ሰዓት\n• መኪና፦ 500 ብር/ሰዓት\n• የርቀት ገደብ፦ 12 ኪ.ሜ/ሰዓት (ተጨማሪ፦ 30 ብር/ኪ.ሜ)", fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Text(text = "3. ደህንነት እና ጥበቃ\n• ሁሉም አሽከርካሪዎች የተረጋገጡ ናቸው።\n• እያንዳንዱ ጉዞ በGPS ይከታተላል።", fontSize = 13.sp)
            }
        }

        Spacer(Modifier.height(10.dp))

        // 🔥 THE IMPERIAL CONSTITUTION (English)
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.padding(16.dp)) {
                Text(text = "Policies & Security", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color(0xFF1A237E))
                Spacer(Modifier.height(8.dp))
                Text(text = "• Standard Ride: 50 ETB Base. Night Surcharge 200 ETB.\n• Contrat: 350 ETB/hr (Bajaj), 500 ETB/hr (Car).\n• Security: Verified drivers & Live GPS tracking.", fontSize = 13.sp)
            }
        }

        Spacer(Modifier.height(20.dp))
        
        Text(text = "SUPPORT TEAM", fontWeight = FontWeight.Bold, color = Color.Gray)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Button(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/bayratravel"))) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF229ED9))) {
                Icon(Icons.Filled.Send, null); Spacer(Modifier.width(4.dp)); Text("Telegram")
            }
            Button(onClick = { 
                val intent = Intent(Intent.ACTION_SENDTO).apply { data = Uri.parse("mailto:bayratravel@gmail.com") }
                ctx.startActivity(intent)
            }, colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)) {
                Icon(Icons.Filled.Email, null); Spacer(Modifier.width(4.dp)); Text("Email")
            }
        }

        Spacer(Modifier.height(30.dp))
        Button(onClick = onLogout, Modifier.fillMaxWidth().height(50.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text("LOGOUT") }
        Spacer(Modifier.height(20.dp))
        Text(text = "Bayra Travel v1.0", fontSize = 10.sp, color = Color.LightGray)
    }
}

// ... (BookingCore and LoginView remain EXACTLY as in Phase 176/177. I am omitting them here to save space, but in Termux, this file overwrite includes them if you use the previous full block, or I can provide the full 300 lines if you prefer absolute safety.)

// FOR SAFETY, HERE IS THE FULL FILE CONTENT TO PASTE:
// (Wait, to avoid partial file errors, I will simply append the BookingCore and LoginView here so the 'cat' command writes a complete file.)

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
    var cashModeSelected by remember { mutableStateOf(false) }

    LaunchedEffect(activeRideId) {
        if (activeRideId.isNotEmpty()) {
            FirebaseDatabase.getInstance(DB_URL).getReference("rides/$activeRideId").addValueEventListener(object : ValueEventListener {
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
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx -> MapView(ctx).apply { setTileSource(TileSourceFactory.MAPNIK); setMultiTouchControls(true); setBuiltInZoomControls(false); controller.setZoom(17.5); controller.setCenter(GeoPoint(6.0333, 37.5500)); val loc = MyLocationNewOverlay(GpsMyLocationProvider(ctx), this); loc.enableMyLocation(); overlays.add(loc); mapRef = this } }, update = { view ->
            view.overlays.filterIsInstance<Marker>().forEach { view.overlays.remove(it) }
            pickupPt?.let { pt -> Marker(view).apply { position = pt; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM) }.also { view.overlays.add(it) } }
            if (!selectedTier.isHr) destPt?.let { pt -> Marker(view).apply { position = pt; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM); icon = view.context.getDrawable(android.R.drawable.ic_menu_directions) }.also { view.overlays.add(it) } }
            view.invalidate()
        })

        if (status == "ARRIVED_DEST" || status.startsWith("PAID_")) {
            Surface(modifier = Modifier.fillMaxSize(), color = Color.White.copy(alpha = 0.98f)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, modifier = Modifier.padding(32.dp)) {
                    Text("PAYMENT DUE", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1A237E))
                    Text("$activePrice ETB", fontSize = 60.sp, fontWeight = FontWeight.ExtraBold)
                    if (status.startsWith("PAID_")) {
                         Text("Payment Processing...", color = Color.Green, fontWeight = FontWeight.Bold); CircularProgressIndicator()
                    } else if (!cashModeSelected) {
                        Spacer(Modifier.height(40.dp))
                        Button(onClick = {
                            isGeneratingLink = true
                            thread {
                                try {
                                    val url = URL("https://bayra-backend-eu.onrender.com/initialize-payment")
                                    val conn = url.openConnection() as HttpURLConnection
                                    conn.requestMethod = "POST"; conn.doOutput = true; conn.connectTimeout = 60000; conn.readTimeout = 60000
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
                        }, modifier = Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A237E))) { 
                            if(isGeneratingLink) CircularProgressIndicator(color = Color.White) else Text("PAY ONLINE") 
                        }
                        TextButton(onClick = { cashModeSelected = true }, modifier = Modifier.padding(top = 16.dp)) { Text("PAY CASH TO DRIVER", color = Color.Gray) }
                    } else {
                        Text("Hand cash to the driver.", fontWeight = FontWeight.Bold)
                        Button(onClick = { FirebaseDatabase.getInstance(DB_URL).getReference("rides/$activeRideId/status").setValue("PAID_CASH") }, modifier = Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Black)) { Text("I HAVE PAID CASH") }
                        TextButton(onClick = { cashModeSelected = false }) { Text("GO BACK") }
                    }
                }
            }
        } else if (status == "COMPLETED") {
             LaunchedEffect(Unit) { status = "IDLE"; activeRideId = ""; prefs.edit().remove("active_id").apply() }
        } else if (status != "IDLE") {
             Surface(modifier = Modifier.fillMaxSize(), color = Color.White) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(color = Color(0xFF1A237E)); Text(status, modifier = Modifier.padding(top=20.dp), fontWeight = FontWeight.Bold)
                    Button(onClick = { FirebaseDatabase.getInstance(DB_URL).getReference("rides/$activeRideId").removeValue(); status = "IDLE"; activeRideId = ""; prefs.edit().remove("active_id").apply() }, modifier = Modifier.padding(top = 40.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text("CANCEL") }
                }
            }
        } else {
             if (step != "CONFIRM") Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("📍", fontSize = 48.sp, modifier = Modifier.padding(bottom = 48.dp)) }
            Column(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.White, RoundedCornerShape(topStart = 28.dp)).padding(24.dp)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(Tier.values().toList()) { t -> Surface(modifier = Modifier.clickable { selectedTier = t; if (pickupPt != null && (t.isHr || destPt != null)) step = "CONFIRM" else if (pickupPt != null) step = "DEST" else step = "PICKUP" }, color = if(selectedTier == t) Color(0xFF1A237E) else Color(0xFFEEEEEE), shape = RoundedCornerShape(8.dp)) { Text(t.label, Modifier.padding(12.dp, 8.dp), color = if(selectedTier == t) Color.White else Color.Black, fontWeight = FontWeight.Bold) } } }
                Spacer(modifier = Modifier.height(16.dp))
                if (selectedTier.isHr && step == "CONFIRM") {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Hours:", fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { if(hrCount > 1) hrCount-- }) { Text("−", fontSize = 24.sp, fontWeight = FontWeight.Bold) }
                            Text("$hrCount HR", fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 8.dp))
                            IconButton(onClick = { if(hrCount < 12) hrCount++ }) { Text("+", fontSize = 24.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
                }
                if (step == "PICKUP") Button(onClick = { pickupPt = mapRef?.mapCenter as GeoPoint; step = if(selectedTier.isHr) "CONFIRM" else "DEST" }, modifier = Modifier.fillMaxWidth().height(60.dp)) { Text("SET PICKUP", fontWeight = FontWeight.Bold) }
                else if (step == "DEST") Button(onClick = { destPt = mapRef?.mapCenter as GeoPoint; step = "CONFIRM" }, modifier = Modifier.fillMaxWidth().height(60.dp)) { Text("SET DESTINATION", fontWeight = FontWeight.Bold) }
                else {
                    val raw = if(selectedTier.isHr) selectedTier.base * hrCount else selectedTier.base * 2.8; val fare = (raw * 1.15).toInt()
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("$fare ETB", fontSize = 34.sp, fontWeight = FontWeight.Black, color = Color(0xFFD50000)); TextButton(onClick = { pickupPt = null; destPt = null; step = "PICKUP" }) { Text("Reset") } }
                    Button(onClick = { val id = "R_${System.currentTimeMillis()}"; FirebaseDatabase.getInstance(DB_URL).getReference("rides/$id").setValue(mapOf("id" to id, "pName" to name, "pPhone" to phone, "pEmail" to email, "status" to "REQUESTED", "price" to fare.toString(), "pLat" to pickupPt?.latitude, "pLon" to pickupPt?.longitude, "dLat" to destPt?.latitude, "dLon" to destPt?.longitude, "tier" to selectedTier.label, "hours" to if(selectedTier.isHr) hrCount else 0)); activeRideId = id; prefs.edit().putString("active_id", id).apply() }, modifier = Modifier.fillMaxWidth().height(65.dp).padding(top = 10.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A237E)), shape = RoundedCornerShape(12.dp)) { Text("BOOK NOW", fontWeight = FontWeight.ExtraBold) }
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
        Text("BAYRA TRAVEL", fontSize = 32.sp, fontWeight = FontWeight.Black, color = Color(0xFF1A237E))
        Spacer(Modifier.height(20.dp)); OutlinedTextField(value = n, onValueChange = { n = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp)); OutlinedTextField(value = p, onValueChange = { p = it }, label = { Text("Phone") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp)); OutlinedTextField(value = e, onValueChange = { e = it }, label = { Text("Email") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { if(n.isNotEmpty() && e.contains("@")) onLogin(n, p, e) }, modifier = Modifier.fillMaxWidth().height(60.dp).padding(top = 24.dp)) { Text("START TRAVELING") }
    }
}

package com.bayra.customer

import android.Manifest
import android.content.*
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import com.google.firebase.database.*
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.net.URL
import java.net.HttpURLConnection
import java.net.URLEncoder
import kotlin.concurrent.thread

const val DB_URL = "https://bayra-84ecf-default-rtdb.europe-west1.firebasedatabase.app"
val IMPERIAL_BLUE = Color(0xFF1A237E)
val IMPERIAL_RED = Color(0xFFD50000)

enum class Tier(val label: String, val base: Double, val isHr: Boolean) {
    POOL("Pool", 80.0, false), COMFORT("Comfort", 120.0, false), 
    CODE_3("Code 3", 280.0, false), BAJAJ_HR("Bajaj Hr", 350.0, true),
    C3_HR("C3 Hr", 550.0, true)
}

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    private val requestLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        requestLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        setContent { Surface(color = Color.White) { MaterialTheme(colorScheme = lightColorScheme(primary = IMPERIAL_BLUE)) { PassengerSuperApp() } } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PassengerSuperApp() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("bayra_p_v224", Context.MODE_PRIVATE) }
    var pName by rememberSaveable { mutableStateOf(prefs.getString("n", "") ?: "") }
    var pPhone by rememberSaveable { mutableStateOf(prefs.getString("p", "") ?: "") }
    var pEmail by rememberSaveable { mutableStateOf(prefs.getString("e", "") ?: "") }
    var isAuth by remember { mutableStateOf(prefs.getBoolean("auth", false)) }
    var isVerifying by rememberSaveable { mutableStateOf(prefs.getBoolean("is_v", false)) }
    
    var pickupPt by remember { mutableStateOf<GeoPoint?>(null) }
    var destPt by remember { mutableStateOf<GeoPoint?>(null) }
    var selectedTier by remember { mutableStateOf(Tier.COMFORT) }
    var step by rememberSaveable { mutableStateOf("PICKUP") }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var currentView by rememberSaveable { mutableStateOf("MAP") }

    if (!isAuth) {
        if (!isVerifying) {
            LoginView(pName, pPhone, pEmail) { n, p, e -> 
                val start = System.currentTimeMillis()
                prefs.edit().putString("n", n).putString("p", p).putString("e", e).putBoolean("is_v", true).putLong("v_s", start).apply()
                pName = n; pPhone = p; pEmail = e; isVerifying = true 
                FirebaseDatabase.getInstance(DB_URL).getReference("verifications").child(p).setValue(mapOf("name" to n, "code" to "2025", "time" to start))
            }
        } else {
            VerificationView(pPhone, prefs) { code ->
                if (code == "2025") { prefs.edit().putBoolean("auth", true).putBoolean("is_v", false).apply(); isAuth = true; isVerifying = false }
                else Toast.makeText(ctx, "Invalid PIN", Toast.LENGTH_SHORT).show()
            }
        }
    } else {
        ModalNavigationDrawer(
            drawerState = drawerState, gesturesEnabled = false,
            drawerContent = {
                ModalDrawerSheet {
                    Column(Modifier.padding(20.dp)) {
                        Icon(Icons.Filled.AccountCircle, null, Modifier.size(64.dp), IMPERIAL_BLUE)
                        Text(pName, fontWeight = FontWeight.Bold, fontSize = 20.sp); Text(pPhone, fontSize = 14.sp, color = Color.Gray)
                    }
                    Divider()
                    NavigationDrawerItem(label = { Text("Map") }, selected = currentView == "MAP", onClick = { currentView = "MAP"; scope.launch { drawerState.close() } }, icon = { Icon(Icons.Filled.Home, null) })
                    NavigationDrawerItem(label = { Text("Orders") }, selected = currentView == "ORDERS", onClick = { currentView = "ORDERS"; scope.launch { drawerState.close() } }, icon = { Icon(Icons.Filled.List, null) })
                    Divider()
                    NavigationDrawerItem(label = { Text("Logout") }, selected = false, onClick = { prefs.edit().clear().apply(); isAuth = false }, icon = { Icon(Icons.Filled.ExitToApp, null) })
                }
            }
        ) {
            Scaffold(
                topBar = { TopAppBar(title = { Text("BAYRA TRAVEL", fontWeight = FontWeight.Black) }, navigationIcon = { IconButton(onClick = { scope.launch { drawerState.open() } }) { Icon(Icons.Filled.Menu, null) } }) }
            ) { padding ->
                Box(Modifier.padding(padding)) {
                    if (currentView == "MAP") BookingHub(pName, pEmail, prefs, pickupPt, destPt, selectedTier, step,
                        onPointChange = { p, d, s, t -> pickupPt = p; destPt = d; step = s; selectedTier = t })
                    else HistoryPage(pName)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookingHub(
    name: String, email: String, prefs: SharedPreferences,
    pickupPt: GeoPoint?, destPt: GeoPoint?, selectedTier: Tier, step: String,
    onPointChange: (GeoPoint?, GeoPoint?, String, Tier) -> Unit
) {
    val ctx = LocalContext.current
    var status by remember { mutableStateOf("IDLE") }
    var activeId by remember { mutableStateOf(prefs.getString("active_id", "") ?: "") }
    var driverName by remember { mutableStateOf("") }
    var driverPhone by remember { mutableStateOf("") }
    var activePrice by remember { mutableStateOf("0") }
    var liveDist by remember { mutableStateOf("0.0") }
    var mapRef by remember { mutableStateOf<MapView?>(null) }
    var isGeneratingLink by remember { mutableStateOf(false) }

    LaunchedEffect(activeId) {
        if(activeId.isNotEmpty()) {
            FirebaseDatabase.getInstance(DB_URL).getReference("rides/$activeId").addValueEventListener(object : ValueEventListener {
                override fun onDataChange(s: DataSnapshot) { 
                    status = s.child("status").value?.toString() ?: "IDLE" 
                    activePrice = s.child("price").value?.toString()?.replace("[^0-9]".toRegex(), "") ?: "0"
                    driverName = s.child("driverName").value?.toString() ?: ""
                    driverPhone = s.child("dPhone").value?.toString() ?: ""
                    liveDist = s.child("currentDist").value?.toString() ?: "0.0"
                }
                override fun onCancelled(e: DatabaseError) {}
            })
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { c -> MapView(c).apply { setTileSource(TileSourceFactory.MAPNIK); setBuiltInZoomControls(false); setMultiTouchControls(true); controller.setZoom(17.5); controller.setCenter(GeoPoint(6.0333, 37.5500)); val loc = MyLocationNewOverlay(GpsMyLocationProvider(c), this); loc.enableMyLocation(); overlays.add(loc); mapRef = this } }, update = { view ->
            view.overlays.filterIsInstance<Marker>().forEach { view.overlays.remove(it) }
            pickupPt?.let { Marker(view).apply { position = it; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM) }.also { view.overlays.add(it) } }
            destPt?.let { Marker(view).apply { position = it; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM) }.also { view.overlays.add(it) } }
            view.invalidate()
        })

        if (status != "IDLE") {
            Surface(Modifier.fillMaxSize(), color = Color.White.copy(alpha = 0.98f)) { 
                Column(verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) { 
                    if (status == "ARRIVED_DEST" || status == "PAID_CHAPA" || status == "PAID_CASH") {
                        Text("መድረሻዎ ደርሰዋል / ARRIVED", fontSize = 20.sp, fontWeight = FontWeight.Black, color = Color(0xFF2E7D32))
                        Text("$activePrice ETB", fontSize = 52.sp, fontWeight = FontWeight.ExtraBold)
                        Button(onClick = {
                            isGeneratingLink = true
                            thread { try {
                                val url = URL("https://bayra-backend-eu.onrender.com/initialize-payment")
                                val conn = url.openConnection() as HttpURLConnection
                                conn.requestMethod = "POST"; conn.doOutput = true; conn.connectTimeout = 60000
                                val body = JSONObject().put("amount", activePrice).put("email", email).put("name", name).put("rideId", activeId).toString()
                                conn.outputStream.write(body.toByteArray())
                                val payUrl = JSONObject(conn.inputStream.bufferedReader().readText()).getJSONObject("data").getString("checkout_url")
                                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(payUrl)))
                                FirebaseDatabase.getInstance(DB_URL).getReference("rides/$activeId/status").setValue("PAID_CHAPA")
                                isGeneratingLink = false
                            } catch (e: Exception) { isGeneratingLink = false } }
                        }, Modifier.fillMaxWidth().height(60.dp)) { Text("PAY ONLINE") }
                        TextButton(onClick = { FirebaseDatabase.getInstance(DB_URL).getReference("rides/$activeId/status").setValue("PAID_CASH") }) { Text("PAY CASH TO DRIVER") }
                    } else if (status == "COMPLETED") {
                        LaunchedEffect(Unit) { status = "IDLE"; activeId = ""; prefs.edit().remove("active_id").apply(); onPointChange(null, null, "PICKUP", Tier.COMFORT) }
                    } else {
                        val amh = when(status) { "REQUESTED" -> "ፈለጋ ላይ ነን..."; "ACCEPTED" -> "አሽከርካሪ ተገኝቷል"; "ARRIVED" -> "አሽከርካሪው ደርሷል"; "ON_TRIP" -> "ጉዞ ላይ ነን"; else -> status }
                        Text(text = amh, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = IMPERIAL_BLUE)
                        Text(text = status, fontSize = 16.sp, color = Color.Gray)
                        if (driverName.isNotEmpty()) {
                            Text("DRIVER: $driverName", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top=10.dp))
                            if(driverPhone.isNotEmpty()) Button(onClick = { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$driverPhone"))) }, colors = ButtonDefaults.buttonColors(containerColor = Color.Black)) { Icon(Icons.Filled.Call, null); Text(" CALL") }
                        }
                        if(status == "ON_TRIP") Text("Distance: $liveDist km", fontWeight = FontWeight.Bold)
                        Button(onClick = { status = "IDLE"; activeId = ""; FirebaseDatabase.getInstance(DB_URL).getReference("rides/$activeId").removeValue() }, modifier = Modifier.padding(top = 40.dp), colors = ButtonDefaults.buttonColors(containerColor = IMPERIAL_RED)) { Text("CANCEL") }
                    }
                } 
            }
        } else {
            if(step != "CONFIRM") Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(Alignment.CenterHorizontally) { Text(step, color = Color.White, modifier = Modifier.background(Color.Black.copy(0.6f)).padding(4.dp), fontSize = 10.sp); Text("📍", fontSize = 48.sp, modifier = Modifier.padding(bottom = 48.dp), color = Color.Black) } }
            Column(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.White, RoundedCornerShape(topStart = 24.dp)).padding(24.dp)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(Tier.values().toList()) { t -> Surface(modifier = Modifier.clickable { onPointChange(pickupPt, destPt, if(pickupPt != null) (if(t.isHr) "CONFIRM" else if(destPt != null) "CONFIRM" else "DEST") else "PICKUP", t) }, color = if(selectedTier == t) IMPERIAL_BLUE else Color(0xFFEEEEEE), shape = RoundedCornerShape(8.dp)) { Text(t.label, Modifier.padding(12.dp, 8.dp), color = if(selectedTier == t) Color.White else Color.Black) } } }
                Spacer(modifier = Modifier.height(16.dp))
                if (step == "PICKUP") Button(onClick = { onPointChange(mapRef?.mapCenter as GeoPoint, destPt, if(selectedTier.isHr) "CONFIRM" else "DEST", selectedTier) }, modifier = Modifier.fillMaxWidth().height(60.dp)) { Text("SET PICKUP", fontWeight = FontWeight.Bold) }
                else if (step == "DEST") Button(onClick = { onPointChange(pickupPt, mapRef?.mapCenter as GeoPoint, "CONFIRM", selectedTier) }, modifier = Modifier.fillMaxWidth().height(60.dp)) { Text("SET DESTINATION", fontWeight = FontWeight.Bold) }
                else {
                    val f = if(selectedTier.isHr) (selectedTier.base * 1.15).toInt() else (selectedTier.base * 2.5 * 1.15).toInt()
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) { Text("$f ETB", fontSize = 34.sp, fontWeight = FontWeight.Black, color = IMPERIAL_RED); TextButton(onClick = { onPointChange(null, null, "PICKUP", selectedTier) }) { Text("Reset") } }
                    Button(onClick = { val id = "R_${System.currentTimeMillis()}"; FirebaseDatabase.getInstance(DB_URL).getReference("rides/$id").setValue(mapOf("id" to id, "pName" to name, "pPhone" to prefs.getString("p","") , "status" to "REQUESTED", "price" to f.toString(), "pLat" to pickupPt?.latitude, "pLon" to pickupPt?.longitude, "dLat" to destPt?.latitude, "dLon" to destPt?.longitude, "tier" to selectedTier.label, "pay" to "CASH")); activeId = id; prefs.edit().putString("active_id", id).apply() }, Modifier.fillMaxWidth().height(65.dp), shape = RoundedCornerShape(16.dp)) { Text("BOOK NOW", fontWeight = FontWeight.ExtraBold) }
                }
            }
        }
    }
}
// LoginView & HistoryPage same as before...
@Composable fun LoginView(n: String, p: String, e: String, onL: (String, String, String) -> Unit) { Box(Modifier.fillMaxSize()) }
@Composable fun VerificationView(p: String, pr: SharedPreferences, onV: (String) -> Unit) { Box(Modifier.fillMaxSize()) }
@Composable fun HistoryPage(n: String) { Box(Modifier.fillMaxSize()) }
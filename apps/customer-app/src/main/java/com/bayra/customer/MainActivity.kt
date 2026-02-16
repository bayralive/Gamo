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
import org.osmdroid.tileprovider.tilesource.TileSourceFactory // 🛡️ IMPORT RESTORED
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
    val prefs = context.getSharedPreferences("bayra_p_vFINAL_HARDENED", Context.MODE_PRIVATE)
    
    var pName by rememberSaveable { mutableStateOf(prefs.getString("n", "") ?: "") }
    var pPhone by rememberSaveable { mutableStateOf(prefs.getString("p", "") ?: "") }
    var pEmail by rememberSaveable { mutableStateOf(prefs.getString("e", "") ?: "") } // 📧 ESSENTIAL EMAIL
    var isAuth by remember { mutableStateOf(pName.isNotEmpty() && pEmail.isNotEmpty()) }
    var currentTab by rememberSaveable { mutableStateOf("BOOK") }
    
    var isSearching by rememberSaveable { mutableStateOf(false) }
    var rideStatus by rememberSaveable { mutableStateOf("IDLE") }
    var ridePrice by rememberSaveable { mutableStateOf("0") }
    var driverName by rememberSaveable { mutableStateOf<String?>(null) }
    var dPhone by rememberSaveable { mutableStateOf<String?>(null) }
    var activeRideId by rememberSaveable { mutableStateOf("") }

    // HOISTED MAP STATE
    var step by rememberSaveable { mutableStateOf("PICKUP") }
    var pickupPt by remember { mutableStateOf<GeoPoint?>(null) }
    var destPt by remember { mutableStateOf<GeoPoint?>(null) }
    var roadDistance by remember { mutableStateOf(0.0) }
    var selectedTier by remember { mutableStateOf(ServiceTier.COMFORT) }
    var hrCount by remember { mutableStateOf(1) }

    if (!isAuth) {
        Column(modifier = Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "BAYRA PASSENGER", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color(0xFF5E4E92))
            Spacer(modifier = Modifier.height(20.dp))
            OutlinedTextField(value = pName, onValueChange = { pName = it }, label = { Text(text = "Full Name") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = pPhone, onValueChange = { pPhone = it }, label = { Text(text = "Phone Number") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = pEmail, onValueChange = { pEmail = it }, label = { Text(text = "Email Address (for Chapa)") }, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = { if(pName.length > 2 && pEmail.contains("@")){ 
                prefs.edit().putString("n", pName).putString("p", pPhone).putString("e", pEmail).apply()
                isAuth = true 
            } }, modifier = Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5E4E92))) { Text(text = "ENTER") }
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
                        name = pName, phone = pPhone, email = pEmail,
                        isSearching = isSearching, status = rideStatus, price = ridePrice, driver = driverName, dPh = dPhone, rideId = activeRideId,
                        step = step, pPt = pickupPt, dPt = destPt, dist = roadDistance, tier = selectedTier, hrs = hrCount,
                        onSearch = { isSearching = it }, onStatus = { rideStatus = it }, onPrice = { ridePrice = it }, onDriver = { driverName = it }, onDPh = { dPhone = it }, onId = { activeRideId = it },
                        onStep = { step = it }, onPickup = { pickupPt = it }, onDest = { destPt = it }, onDist = { roadDistance = it }, onTier = { selectedTier = it }, onHr = { hrCount = it }
                    )
                } else {
                    Column(modifier = Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = "ACCOUNT", fontSize = 24.sp, fontWeight = FontWeight.Bold); Text(text = pName)
                        Text(text = pEmail, color = Color.Gray)
                        Spacer(modifier = Modifier.weight(1f)); Button({ prefs.edit().clear().apply(); isAuth = false; isSearching = false }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text(text = "LOGOUT") }
                    }
                }
            }
        }
    }
}

@Composable
fun BookingHub(
    name: String, phone: String, email: String, 
    isSearching: Boolean, status: String, price: String, driver: String?, dPh: String?, rideId: String,
    step: String, pPt: GeoPoint?, dPt: GeoPoint?, dist: Double, tier: ServiceTier, hrs: Int,
    onSearch: (Boolean) -> Unit, onStatus: (String) -> Unit, onPrice: (String) -> Unit, onDriver: (String?) -> Unit, onDPh: (String?) -> Unit, onId: (String) -> Unit,
    onStep: (String) -> Unit, onPickup: (GeoPoint?) -> Unit, onDest: (GeoPoint?) -> Unit, onDist: (Double) -> Unit, onTier: (ServiceTier) -> Unit, onHr: (Int) -> Unit
) {
    val context = LocalContext.current
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var routePoints = remember { mutableStateListOf<GeoPoint>() }
    var isGeneratingLink by remember { mutableStateOf(false) }

    val isNight = Calendar.getInstance().get(Calendar.HOUR_OF_DAY).let { it >= 20 || it < 6 }
    val fare = if (tier.isHr) (tier.base * 1.15).toInt() else (tier.base + (dist * tier.kmRate) + tier.extra + (if(isNight) 200 else 0)).toInt()

    Box(Modifier.fillMaxSize()) {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx -> MapView(ctx).apply { 
            setTileSource(TileSourceFactory.MAPNIK); setMultiTouchControls(true); controller.setZoom(17.0); controller.setCenter(GeoPoint(6.0333, 37.5500)); mapViewRef = this 
        } })

        if (isSearching || status == "COMPLETED") {
            Column(Modifier.fillMaxSize().background(Color.White).padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                if (status == "COMPLETED") {
                    Text("TRIP FINISHED", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color(0xFF4CAF50))
                    Text("$price ETB", fontSize = 48.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.height(30.dp))
                    if(isGeneratingLink) CircularProgressIndicator(color = Color(0xFF5E4E92))
                    else Button(onClick = { 
                        isGeneratingLink = true
                        thread { try {
                            val url = URL("https://bayra-backend-eu.onrender.com/initialize-payment")
                            val conn = url.openConnection() as HttpURLConnection
                            conn.requestMethod = "POST"; conn.doOutput = true; conn.setRequestProperty("Content-Type", "application/json")
                            val body = JSONObject().put("amount", price).put("name", name).put("phone", phone).put("email", email).put("rideId", rideId).toString()
                            conn.outputStream.write(body.toByteArray())
                            val chapaUrl = JSONObject(conn.inputStream.bufferedReader().readText()).getString("checkout_url")
                            isGeneratingLink = false
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(chapaUrl)))
                            onStatus("IDLE"); onSearch(false)
                        } catch (e: Exception) { isGeneratingLink = false; context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://chapa.co"))) } }
                    }, Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5E4E92))) { Text("PAY WITH CHAPA") }
                    TextButton({ onStatus("IDLE"); onSearch(false) }) { Text("PAID WITH CASH") }
                } else {
                    CircularProgressIndicator(color = Color(0xFF5E4E92)); Spacer(Modifier.height(20.dp))
                    Text(if(driver != null) "$driver IS COMING" else "SEARCHING...", fontWeight = FontWeight.Bold)
                    if(dPh != null) Button({ context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$dPh"))) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))) { Text("📞 CALL") }
                    Button({ onSearch(false) }, modifier = Modifier.padding(top = 40.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text("CANCEL") }
                }
            }
        } else {
            if(step != "CONFIRM") Box(Modifier.fillMaxSize(), Alignment.Center) { Text(if(step=="PICKUP") "📍" else "🏁", fontSize = 40.sp, Modifier.padding(bottom = 40.dp)) }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.White, RoundedCornerShape(topStart = 28.dp)).padding(24.dp)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(ServiceTier.values()) { t ->
                        Surface(Modifier.clickable { onTier(t); if(t.isHr) onStep("CONFIRM") else onStep("PICKUP") }, color = if(tier == t) Color(0xFF4CAF50) else Color(0xFFF0F0F0), shape = RoundedCornerShape(12.dp)) {
                            Text(t.label, Modifier.padding(14.dp, 10.dp), fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                if (step == "PICKUP") Button({ onPickup(mapViewRef?.mapCenter as GeoPoint); onStep(if(tier.isHr) "CONFIRM" else "DEST") }, Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Black)) { Text("SET PICKUP") }
                else if (step == "DEST") Button({ 
                    val end = mapViewRef?.mapCenter as GeoPoint; onDest(end)
                    thread { try {
                        val json = JSONObject(URL("https://router.project-osrm.org/route/v1/driving/${pPt!!.longitude},${pPt!!.latitude};${end.longitude},${end.latitude}?overview=full&geometries=geojson").readText())
                        val r = json.getJSONArray("routes").getJSONObject(0)
                        onDist(r.getDouble("distance") / 1000.0); onStep("CONFIRM")
                    } catch (e: Exception) {} }
                }, Modifier.fillMaxWidth().height(60.dp)) { Text("SET DESTINATION") }
                else {
                    Text("$fare ETB", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color.Red)
                    Button({
                        val id = "R_${System.currentTimeMillis()}"
                        onId(id); onPrice(fare.toString()); onSearch(true)
                        FirebaseDatabase.getInstance().getReference("rides/$id").setValue(mapOf("id" to id, "pName" to name, "pPhone" to phone, "pEmail" to email, "status" to "REQUESTED", "price" to fare.toString(), "tier" to tier.label, "pLat" to pPt?.latitude, "pLon" to pPt?.longitude, "dLat" to dPt?.latitude, "dLon" to dPt?.longitude))
                    }, Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5E4E92))) { Text("BOOK NOW") }
                    TextButton({ onStep("PICKUP"); onPickup(null); onDest(null); onDist(0.0) }, Modifier.fillMaxWidth()) { Text("RESET MAP") }
                }
            }
        }
    }
}

@Composable
fun AccountView(name: String, phone: String, onLogout: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("ACCOUNT", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(name, fontWeight = FontWeight.Bold); Text(phone, color = Color.Gray)
        Spacer(Modifier.weight(1f)); Button(onLogout, colors = ButtonDefaults.buttonColors(containerColor = Color.Red), modifier = Modifier.fillMaxWidth().height(60.dp)) { Text("LOGOUT") }
    }
}

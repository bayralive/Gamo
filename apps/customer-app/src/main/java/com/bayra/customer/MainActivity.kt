package com.bayra.customer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.preference.PreferenceManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
        Configuration.getInstance().userAgentValue = "BayraSovereign"
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
    var currentTab by rememberSaveable { mutableStateOf("BOOK") }
    var isSearching by rememberSaveable { mutableStateOf(false) }
    var rideStatus by rememberSaveable { mutableStateOf("IDLE") }
    var ridePrice by rememberSaveable { mutableStateOf("0") }
    var activeRideId by rememberSaveable { mutableStateOf("") }
    var driverName by rememberSaveable { mutableStateOf<String?>(null) }
    
    // 🔥 HOISTED BOOKING STATE
    var step by rememberSaveable { mutableStateOf("PICKUP") }
    var pickupPt by remember { mutableStateOf<GeoPoint?>(null) }
    var destPt by remember { mutableStateOf<GeoPoint?>(null) }
    var roadDistance by remember { mutableStateOf(0.0) }
    var selectedTier by remember { mutableStateOf(ServiceTier.COMFORT) }
    var hrCount by remember { mutableStateOf(1) }
    var paymentMethod by remember { mutableStateOf("CASH") }

    if (!isAuth) {
        Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
            Text("BAYRA LOGIN", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color(0xFF5E4E92))
            OutlinedTextField(pName, { pName = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(pPhone, { pPhone = it }, label = { Text("Phone") }, modifier = Modifier.fillMaxWidth())
            Button({ if(pName.isNotEmpty() && pPhone.length > 8){ prefs.edit().putString("n", pName).putString("p", pPhone).apply(); isAuth = true } }, Modifier.fillMaxWidth().height(60.dp)) { Text("ENTER") }
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
                        pName, pPhone, isSearching, rideStatus, ridePrice, driverName, activeRideId, 
                        step, pickupPt, destPt, roadDistance, selectedTier, hrCount, paymentMethod,
                        { isSearching = it }, { rideStatus = it }, { ridePrice = it }, { driverName = it }, { activeRideId = it },
                        { step = it }, { pickupPt = it }, { destPt = it }, { roadDistance = it }, { selectedTier = it }, { hrCount = it }, { paymentMethod = it }
                    )
                } else {
                    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("ACCOUNT", fontSize = 24.sp, fontWeight = FontWeight.Bold); Text(pName); Text(pPhone, color = Color.Gray)
                        Spacer(Modifier.weight(1f)); Button({ prefs.edit().clear().apply(); isAuth = false }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red), modifier = Modifier.fillMaxWidth()) { Text("LOGOUT") }
                    }
                }
            }
        }
    }
}

@Composable
fun BookingHub(
    name: String, phone: String, isSearching: Boolean, status: String, price: String, driver: String?, activeRideId: String,
    step: String, pickupPt: GeoPoint?, destPt: GeoPoint?, roadDistance: Double, selectedTier: ServiceTier, hrCount: Int, paymentMethod: String,
    onSearch: (Boolean) -> Unit, onStatus: (String) -> Unit, onPrice: (String) -> Unit, onDriver: (String?) -> Unit, onRideId: (String) -> Unit,
    onStep: (String) -> Unit, onPickup: (GeoPoint?) -> Unit, onDest: (GeoPoint?) -> Unit, onDist: (Double) -> Unit, onTier: (ServiceTier) -> Unit, onHr: (Int) -> Unit, onPay: (String) -> Unit
) {
    val context = LocalContext.current
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var routePoints by remember { mutableStateOf<List<GeoPoint>>(listOf()) }
    val isNight = Calendar.getInstance().get(Calendar.HOUR_OF_DAY).let { it >= 20 || it < 6 }
    val fare = remember(selectedTier, roadDistance, hrCount, isNight) {
        if (selectedTier.isHr) ((selectedTier.base + (if(isNight) 100 else 0)) * hrCount * 1.15).toInt()
        else ((selectedTier.base + (roadDistance * selectedTier.kmRate) + selectedTier.extra + (if(isNight) 200 else 0)) * 1.15).toInt()
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx -> MapView(ctx).apply { 
            setTileSource(org.osmdroid.tileprovider.tilesource.TileSourceFactory.MAPNIK)
            setMultiTouchControls(true); controller.setZoom(17.0); controller.setCenter(GeoPoint(6.0333, 37.5500)); mapViewRef = this 
        } }, update = { view ->
            view.overlays.clear()
            pickupPt?.let { pt -> Marker(view).apply { position = pt; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM) }.also { view.overlays.add(it) } }
            if(!selectedTier.isHr && destPt != null) {
                Marker(view).apply { position = destPt; icon = view.context.getDrawable(android.R.drawable.ic_menu_directions) }.also { view.overlays.add(it) }
                if(routePoints.isNotEmpty()) Polyline().apply { setPoints(routePoints); color = android.graphics.Color.BLACK; width = 10f }.also { view.overlays.add(it) }
            }
            view.invalidate()
        })

        if (isSearching || status == "COMPLETED") {
            Column(Modifier.fillMaxSize().background(Color.White).padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                if (status == "COMPLETED") {
                    Text("TRIP FINISHED", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color(0xFF4CAF50))
                    Text("$price ETB", fontSize = 48.sp, fontWeight = FontWeight.ExtraBold)
                    Button({ 
                        thread { try {
                            val url = URL("https://bayra-backend-eu.onrender.com/initialize-payment")
                            val conn = url.openConnection() as HttpURLConnection
                            conn.requestMethod = "POST"; conn.doOutput = true; conn.setRequestProperty("Content-Type", "application/json")
                            val body = JSONObject().put("amount", price).put("name", name).put("phone", phone).put("rideId", activeRideId).toString()
                            conn.outputStream.write(body.toByteArray())
                            val chapaUrl = JSONObject(conn.inputStream.bufferedReader().readText()).getString("checkout_url")
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(chapaUrl)))
                            onStatus("IDLE"); onSearch(false)
                        } catch (e: Exception) { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://chapa.co"))) } }
                    }, Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5E4E92))) { Text("PAY WITH CHAPA") }
                    TextButton({ onStatus("IDLE"); onSearch(false) }) { Text("PAID WITH CASH") }
                } else {
                    CircularProgressIndicator(color = Color(0xFF5E4E92)); Spacer(Modifier.height(20.dp))
                    Text(if(driver != null) "$driver IS COMING" else "SEARCHING...", fontWeight = FontWeight.Bold)
                    Button({ onSearch(false) }, Modifier.padding(top = 40.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text("CANCEL") }
                }
            }
        } else {
            if(step != "CONFIRM") Box(Modifier.fillMaxSize(), Alignment.Center) { 
                Text(if(step=="PICKUP") "📍" else "🏁", fontSize = 40.sp, modifier = Modifier.padding(bottom = 40.dp))
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.White, RoundedCornerShape(topStart = 28.dp)).padding(24.dp)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(ServiceTier.values()) { tier ->
                        Surface(Modifier.clickable { 
                            onTier(tier)
                            if(tier.isHr) { if(pickupPt != null) onStep("CONFIRM") else onStep("PICKUP") }
                            else { if(pickupPt != null && destPt != null) onStep("CONFIRM") else if(pickupPt != null) onStep("DEST") else onStep("PICKUP") }
                        }, color = if(selectedTier == tier) Color(0xFF4CAF50) else Color(0xFFF0F0F0), shape = RoundedCornerShape(12.dp)) {
                            Text(tier.label, Modifier.padding(14.dp, 10.dp), fontWeight = FontWeight.Bold, color = if(selectedTier == tier) Color.White else Color.Black)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                if (step == "PICKUP") Button({ onPickup(mapViewRef?.mapCenter as GeoPoint); onStep(if(selectedTier.isHr) "CONFIRM" else "DEST") }, Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Black)) { Text("SET PICKUP") }
                else if (step == "DEST") Button({ 
                    val end = mapViewRef?.mapCenter as GeoPoint; onDest(end)
                    thread { try {
                        val url = "https://router.project-osrm.org/route/v1/driving/${pickupPt!!.longitude},${pickupPt!!.latitude};${end.longitude},${end.latitude}?overview=full&geometries=geojson"
                        val json = JSONObject(URL(url).readText()); val route = json.getJSONArray("routes").getJSONObject(0)
                        onDist(route.getDouble("distance") / 1000.0)
                        val geometry = route.getJSONObject("geometry").getJSONArray("coordinates"); val pts = mutableListOf<GeoPoint>()
                        for (i in 0 until geometry.length()) { pts.add(GeoPoint(geometry.getJSONArray(i).getDouble(1), geometry.getJSONArray(i).getDouble(0))) }
                        routePoints.clear(); routePoints.addAll(pts); onStep("CONFIRM")
                    } catch (e: Exception) {} }
                }, Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5E4E92))) { Text("SET DESTINATION") }
                else {
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        Column { Text(if(selectedTier.isHr) "12KM/H LIMIT" else "${"%.1f".format(roadDistance)} KM", color = Color.Gray); Text("$fare ETB", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color.Red) }
                        if(selectedTier.isHr) Row(verticalAlignment = Alignment.CenterVertically) {
                            Button({if(hrCount > 1) onHr(hrCount-1)}, Modifier.size(36.dp), contentPadding = PaddingValues(0.dp)) { Text("-") }
                            Text("$hrCount HR", Modifier.padding(horizontal = 8.dp))
                            Button({if(hrCount < 12) onHr(hrCount+1)}, Modifier.size(36.dp), contentPadding = PaddingValues(0.dp)) { Text("+") }
                        } else Row { listOf("CASH", "CHAPA").forEach { m -> Surface(Modifier.clickable { onPay(m) }.padding(4.dp), color = if(paymentMethod == m) Color(0xFF5E4E92) else Color(0xFFF0F0F0), shape = RoundedCornerShape(8.dp)) { Text(m, Modifier.padding(6.dp), fontSize = 10.sp, color = if(paymentMethod==m) Color.White else Color.Black) } } }
                    }
                    Spacer(Modifier.height(16.dp))
                    Button({
                        val id = "R_${System.currentTimeMillis()}"
                        onRideId(id); onPrice(fare.toString()); onSearch(true)
                        FirebaseDatabase.getInstance().getReference("rides/$id").setValue(mapOf("id" to id, "pName" to name, "pPhone" to phone, "status" to "REQUESTED", "price" to fare.toString(), "tier" to selectedTier.label, "pLat" to pickupPt?.latitude, "pLon" to pickupPt?.longitude, "dLat" to destPt?.latitude, "dLon" to destPt?.longitude))
                        FirebaseDatabase.getInstance().getReference("rides/$id").addValueEventListener(object : ValueEventListener {
                            override fun onDataChange(s: DataSnapshot) {
                                if (!s.exists() && isSearching) { onSearch(false); onStatus("COMPLETED"); return }
                                onStatus(s.child("status").getValue(String::class.java) ?: "REQUESTED")
                                onDriver(s.child("driverName").getValue(String::class.java))
                            }
                            override fun onCancelled(e: DatabaseError) {}
                        })
                    }, Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5E4E92))) { Text("BOOK ${selectedTier.label.uppercase()}") }
                    TextButton({ onStep("PICKUP"); onPickup(null); onDest(null); onDist(0.0) }, Modifier.fillMaxWidth()) { Text("RESET MAP") }
                }
            }
        }
    }
}

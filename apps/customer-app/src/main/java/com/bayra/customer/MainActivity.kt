package com.bayra.customer

import android.os.Bundle
import android.preference.PreferenceManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.firebase.database.*
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.net.URL
import kotlin.concurrent.thread
import java.util.*

enum class ServiceTier(val label: String, val base: Int, val rate: Double, val isHr: Boolean) {
    POOL("Pool", 80, 15.0, false), COMFORT("Comfort", 120, 11.0, false),
    CODE_3("Code 3", 280, 27.5, false), BAJAJ_HR("Bajaj Hr", 350, 0.0, true), C3_HR("C3 Hr", 550, 0.0, true)
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
    val prefs = context.getSharedPreferences("bayra_p_vFINAL", 0)
    var pName by rememberSaveable { mutableStateOf(prefs.getString("n", "") ?: "") }
    var pPhone by rememberSaveable { mutableStateOf(prefs.getString("p", "") ?: "") }
    var isAuth by remember { mutableStateOf(pName.isNotEmpty()) }
    var currentTab by rememberSaveable { mutableStateOf("BOOK") }
    
    // --- PERSISTENT COMMAND STATE ---
    var isSearching by rememberSaveable { mutableStateOf(false) }
    var pickupPt by remember { mutableStateOf<GeoPoint?>(null) }
    var destPt by remember { mutableStateOf<GeoPoint?>(null) }
    var roadDistance by remember { mutableStateOf(0.0) }
    var selectedTier by remember { mutableStateOf(ServiceTier.COMFORT) }
    var hrCount by remember { mutableStateOf(1) }

    if (!isAuth) {
        Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
            Text("BAYRA LOGIN", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color(0xFF5E4E92))
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
                        pName, pPhone, isSearching, pickupPt, destPt, roadDistance, selectedTier, hrCount,
                        { isSearching = it }, { pickupPt = it }, { destPt = it }, { roadDistance = it }, { selectedTier = it }, { hrCount = it }
                    )
                } else {
                    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("ACCOUNT", fontWeight = FontWeight.Bold); Text(pName); Text(pPhone, color = Color.Gray)
                        Spacer(Modifier.height(40.dp))
                        Button({ prefs.edit().clear().apply(); isAuth = false }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text("LOGOUT") }
                    }
                }
            }
        }
    }
}

@Composable
fun BookingHub(
    name: String, phone: String, 
    isSearching: Boolean, pickupPt: GeoPoint?, destPt: GeoPoint?, roadDistance: Double, selectedTier: ServiceTier, hrCount: Int,
    onSearch: (Boolean) -> Unit, onPickup: (GeoPoint?) -> Unit, onDest: (GeoPoint?) -> Unit, onDist: (Double) -> Unit, onTier: (ServiceTier) -> Unit, onHr: (Int) -> Unit
) {
    var step by rememberSaveable { mutableStateOf("PICKUP") }
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    val isNight = Calendar.getInstance().get(Calendar.HOUR_OF_DAY).let { it >= 20 || it < 6 }
    val fare = if (selectedTier.isHr) ((selectedTier.base + (if(isNight) 100 else 0)) * hrCount * 1.15).toInt()
    else ((selectedTier.base + (roadDistance * selectedTier.rate * 1.15)) + (if(isNight) 200 else 0)).toInt()

    Box(Modifier.fillMaxSize()) {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx -> MapView(ctx).apply { setTileSource(TileSourceFactory.MAPNIK); controller.setZoom(15.0); controller.setCenter(GeoPoint(6.0333, 37.5500)); mapViewRef = this } }, update = { view ->
            view.overlays.clear()
            pickupPt?.let { pt -> Marker(view).apply { position = pt; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM) }.also { view.overlays.add(it) } }
            if(!selectedTier.isHr && destPt != null) {
                Marker(view).apply { position = destPt; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM); icon = view.context.getDrawable(android.R.drawable.ic_menu_directions) }.also { view.overlays.add(it) }
            }
            view.invalidate()
        })

        if (isSearching) {
            Column(Modifier.fillMaxSize().background(Color.White).padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color(0xFF5E4E92))
                Text("SEARCHING ARBA MINCH...", fontWeight = FontWeight.Bold)
                Button({ onSearch(false) }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text("CANCEL") }
            }
        } else {
            if(step != "CONFIRM") Box(Modifier.fillMaxSize(), Alignment.Center) { Text(if(step == "PICKUP") "📍" else "🏁", fontSize = 40.sp, modifier = Modifier.padding(bottom = 40.dp)) }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.White, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).padding(24.dp)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(ServiceTier.values()) { tier ->
                        Surface(Modifier.clickable { onTier(tier); step = if(tier.isHr) "CONFIRM" else "PICKUP" }, color = if(selectedTier == tier) Color(0xFF4CAF50) else Color(0xFFF0F0F0), shape = RoundedCornerShape(12.dp)) {
                            Text(tier.label, Modifier.padding(14.dp, 10.dp), fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                if (step == "PICKUP") Button({ onPickup(mapViewRef?.mapCenter as GeoPoint); step = if(selectedTier.isHr) "CONFIRM" else "DEST" }, Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Black)) { Text("SET PICKUP") }
                else if (step == "DEST") Button({ onDest(mapViewRef?.mapCenter as GeoPoint); step = "CONFIRM" }, Modifier.fillMaxWidth().height(60.dp)) { Text("SET DESTINATION") }
                else {
                    if(selectedTier.isHr) Row(verticalAlignment = Alignment.CenterVertically) {
                        Button({if(hrCount > 1) onHr(hrCount-1)}) { Text("-") }; Text("$hrCount HR", Modifier.padding(10.dp)); Button({if(hrCount < 12) onHr(hrCount+1)}) { Text("+") }
                    }
                    Text("FARE: $fare ETB", color = Color.Red, fontWeight = FontWeight.Bold, fontSize = 24.sp)
                    Button({
                        val id = "R_${System.currentTimeMillis()}"
                        FirebaseDatabase.getInstance().getReference("rides/$id").setValue(mapOf("id" to id, "pName" to name, "pPhone" to phone, "status" to "REQUESTED", "price" to fare.toString(), "tier" to selectedTier.label, "pLat" to pickupPt?.latitude, "pLon" to pickupPt?.longitude))
                        onSearch(true)
                    }, Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5E4E92))) { Text("BOOK NOW") }
                }
            }
        }
    }
}

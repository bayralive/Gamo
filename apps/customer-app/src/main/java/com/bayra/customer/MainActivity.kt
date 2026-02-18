package com.bayra.customer

import android.content.Context
import android.os.Bundle
import android.preference.PreferenceManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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

enum class Tier(val label: String, val base: Double, val isHr: Boolean) {
    POOL("Pool", 80.0, false), 
    COMFORT("Comfort", 120.0, false), 
    CODE_3("Code 3", 280.0, false), 
    BAJAJ_HR("Bajaj Hr", 350.0, true),
    C3_HR("C3 Hr", 550.0, true)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        setContent { MaterialTheme { PassengerApp() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PassengerApp() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("bayra_p_v149", Context.MODE_PRIVATE) }
    var pName by rememberSaveable { mutableStateOf(prefs.getString("n", "") ?: "") }
    var isAuth by remember { mutableStateOf(pName.isNotEmpty()) }

    if (!isAuth) {
        Column(modifier = Modifier.fillMaxSize().padding(32.dp).background(Color.White), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painter = painterResource(id = R.drawable.logo_passenger), contentDescription = null, modifier = Modifier.size(200.dp))
            Spacer(modifier = Modifier.height(20.dp))
            Text(text = "BAYRA TRAVEL", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color(0xFF1A237E))
            var nIn by remember { mutableStateOf("") }
            OutlinedTextField(value = nIn, onValueChange = { nIn = it }, label = { Text(text = "Full Name") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { if(nIn.isNotEmpty()){ prefs.edit().putString("n", nIn).apply(); pName = nIn; isAuth = true } }, modifier = Modifier.fillMaxWidth().height(60.dp).padding(top = 16.dp)) { Text(text = "START") }
        }
    } else {
        BookingCore(pName)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookingCore(name: String) {
    var step by remember { mutableStateOf("PICKUP") }
    var status by remember { mutableStateOf("IDLE") }
    
    // 🔥 UNIVERSAL POINTS: These persist across tier changes
    var pickupPt by remember { mutableStateOf<GeoPoint?>(null) }
    var destPt by remember { mutableStateOf<GeoPoint?>(null) }
    
    var selectedTier by remember { mutableStateOf(Tier.COMFORT) }
    var hrCount by remember { mutableStateOf(1) }
    var mapRef by remember { mutableStateOf<MapView?>(null) }

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
                    mapRef = this 
                } 
            },
            update = { view ->
                view.overlays.clear()
                // DRAW PERSISTENT PINS
                pickupPt?.let { pt ->
                    val m = Marker(view)
                    m.position = pt
                    m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    m.title = "Pickup"
                    view.overlays.add(m)
                }
                if (!selectedTier.isHr) {
                    destPt?.let { pt ->
                        val m = Marker(view)
                        m.position = pt
                        m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        m.title = "Destination"
                        m.icon = view.context.getDrawable(android.R.drawable.ic_menu_directions)
                        view.overlays.add(m)
                    }
                }
                view.invalidate()
            }
        )

        if (status != "IDLE") {
            Box(modifier = Modifier.fillMaxSize().background(Color.White), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color(0xFF1A237E))
                    Text(text = "FINDING YOUR BAYRA...", modifier = Modifier.padding(top = 20.dp), fontWeight = FontWeight.Bold)
                    Button(onClick = { status = "IDLE" }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text(text = "CANCEL") }
                }
            }
        } else {
            // CENTER PIN SELECTOR (Visible only when setting points)
            if (step != "CONFIRM") {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = if(step == "PICKUP") "PICKUP" else "DESTINATION", color = Color.White, modifier = Modifier.background(Color.Black.copy(alpha=0.6f), RoundedCornerShape(4.dp)).padding(4.dp), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text(text = "📍", fontSize = 48.sp, modifier = Modifier.padding(bottom = 48.dp))
                    }
                }
            }

            Column(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.White, RoundedCornerShape(topStart = 28.dp)).padding(24.dp)) {
                // TIER ROW
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(Tier.values().toList()) { t ->
                        Surface(
                            modifier = Modifier.clickable { 
                                selectedTier = t
                                // Intelligent step jumping: If points are already set, go to confirm
                                if (t.isHr && pickupPt != null) step = "CONFIRM"
                                else if (!t.isHr && pickupPt != null && destPt != null) step = "CONFIRM"
                                else if (pickupPt != null) step = "DEST"
                                else step = "PICKUP"
                            }, 
                            color = if(selectedTier == t) Color(0xFF1A237E) else Color(0xFFEEEEEE), 
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(text = t.label, modifier = Modifier.padding(12.dp, 8.dp), color = if(selectedTier == t) Color.White else Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(20.dp))

                // DURATION CONTROLS
                if (selectedTier.isHr && step == "CONFIRM") {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "Duration:", fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { if(hrCount > 1) hrCount-- }) { Text(text = "−", fontSize = 24.sp, fontWeight = FontWeight.Bold) }
                            Text(text = "$hrCount HR", fontWeight = FontWeight.Black, fontSize = 18.sp, modifier = Modifier.padding(horizontal = 8.dp))
                            IconButton(onClick = { if(hrCount < 12) hrCount++ }) { Text(text = "+", fontSize = 24.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
                }

                // UNIFIED ACTION BUTTON
                if (step == "PICKUP") {
                    Button(
                        onClick = { 
                            pickupPt = mapRef?.mapCenter as GeoPoint
                            step = if(selectedTier.isHr) "CONFIRM" else "DEST" 
                        }, 
                        modifier = Modifier.fillMaxWidth().height(60.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Black)
                    ) { Text(text = "SET PICKUP POINT", fontWeight = FontWeight.Bold) }
                } else if (step == "DEST") {
                    Button(
                        onClick = { 
                            destPt = mapRef?.mapCenter as GeoPoint
                            step = "CONFIRM" 
                        }, 
                        modifier = Modifier.fillMaxWidth().height(60.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Black)
                    ) { Text(text = "SET DESTINATION POINT", fontWeight = FontWeight.Bold) }
                } else {
                    val baseTotal = if(selectedTier.isHr) (selectedTier.base * hrCount) else (selectedTier.base * 2.5) // Estimate for rides
                    val finalFare = (baseTotal * 1.15).toInt()
                    
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "$finalFare ETB", fontSize = 34.sp, fontWeight = FontWeight.Black, color = Color(0xFFD50000))
                        TextButton(onClick = { pickupPt = null; destPt = null; step = "PICKUP" }) { Text("Reset Points") }
                    }
                    
                    Button(
                        onClick = { 
                            val id = "R_${System.currentTimeMillis()}"
                            FirebaseDatabase.getInstance().getReference("rides/$id").setValue(mapOf(
                                "id" to id, "pName" to name, "status" to "REQUESTED", 
                                "price" to finalFare.toString(), "pLat" to pickupPt?.latitude, "pLon" to pickupPt?.longitude, 
                                "dLat" to destPt?.latitude, "dLon" to destPt?.longitude,
                                "tier" to selectedTier.label, "hours" to if(selectedTier.isHr) hrCount else 0
                            ))
                            status = "SEARCHING" 
                        }, 
                        modifier = Modifier.fillMaxWidth().height(65.dp).padding(top = 10.dp), 
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A237E)),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text(text = if(selectedTier.isHr) "CONFIRM CONTRAT" else "CONFIRM RIDE", fontWeight = FontWeight.ExtraBold) }
                }
            }
        }
    }
}

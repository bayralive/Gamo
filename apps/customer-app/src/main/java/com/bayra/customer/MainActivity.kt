package com.bayra.customer

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import java.net.URL

enum class ServiceTier(val label: String, val base: Double, val rate: Double, val isHr: Boolean) {
    POOL("Pool", 80.0, 15.0, false),
    COMFORT("Comfort", 120.0, 20.0, false),
    CODE_3("Code 3", 280.0, 45.0, false),
    BAJAJ_HR("Bajaj Hr", 350.0, 0.0, true)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        setContent { MaterialTheme { PassengerApp() } }
    }
}

@Composable
fun PassengerApp() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("bayra_p_v126", Context.MODE_PRIVATE) }
    var pName by rememberSaveable { mutableStateOf(prefs.getString("n", "") ?: "") }
    var pPhone by rememberSaveable { mutableStateOf(prefs.getString("p", "") ?: "") }
    var isAuth by remember { mutableStateOf(pName.isNotEmpty()) }

    if (!isAuth) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp).background(Color.White),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(id = R.drawable.logo_passenger),
                contentDescription = "Bayra Logo",
                modifier = Modifier.size(220.dp)
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text(text = "BAYRA TRAVEL", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color(0xFF1A237E))
            Spacer(modifier = Modifier.height(30.dp))
            OutlinedTextField(value = pName, onValueChange = { pName = it }, label = { Text(text = "Name") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = pPhone, onValueChange = { pPhone = it }, label = { Text(text = "Phone") }, modifier = Modifier.fillMaxWidth())
            Button(
                onClick = { if (pName.isNotEmpty()) { prefs.edit().putString("n", pName).putString("p", pPhone).apply(); isAuth = true } },
                modifier = Modifier.fillMaxWidth().height(60.dp).padding(top = 16.dp)
            ) {
                Text(text = "START TRAVEL")
            }
        }
    } else {
        BookingHub(pName, pPhone)
    }
}

@Composable
fun BookingHub(name: String, phone: String) {
    var step by remember { mutableStateOf("PICKUP") }
    var status by remember { mutableStateOf("IDLE") }
    var pickupPt by remember { mutableStateOf<GeoPoint?>(null) }
    var destPt by remember { mutableStateOf<GeoPoint?>(null) }
    var tier by remember { mutableStateOf(ServiceTier.COMFORT) }
    var mapRef by remember { mutableStateOf<MapView?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        // 🛰️ SATELLITE MAP ENGINE
        val satellite = object : OnlineTileSourceBase("GoogleHybrid", 0, 20, 256, ".png", arrayOf("https://mt0.google.com/vt/lyrs=y&x={x}&y={y}&z={z}")) {
            override fun getTileURLString(p: Long): String = "$baseUrl&x=${MapTileIndex.getX(p)}&y=${MapTileIndex.getY(p)}&z=${MapTileIndex.getZoom(p)}"
        }
        AndroidView(factory = { MapView(it).apply { setTileSource(satellite); controller.setZoom(16.5); controller.setCenter(GeoPoint(6.0333, 37.5500)); mapRef = this } })

        if (status != "IDLE") {
            // SEARCHING OVERLAY
            Column(
                modifier = Modifier.fillMaxSize().background(Color.White).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(color = Color(0xFF1A237E))
                Spacer(modifier = Modifier.height(20.dp))
                Text(text = status, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(20.dp))
                Button(
                    onClick = { status = "IDLE" },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                ) { Text(text = "CANCEL REQUEST") }
            }
        } else {
            // PIN MARKER
            if(step != "CONFIRM") {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { 
                    Text(text = "📍", fontSize = 44.sp, modifier = Modifier.padding(bottom = 40.dp)) 
                }
            }

            // CONTROL PANEL
            Column(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.White, RoundedCornerShape(topStart = 24.dp)).padding(24.dp)
            ) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ServiceTier.values()) { t ->
                        Surface(
                            modifier = Modifier.clickable { tier = t },
                            color = if(tier == t) Color(0xFF1A237E) else Color(0xFFEEEEEE),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(text = t.label, modifier = Modifier.padding(16.dp, 10.dp), color = if(tier == t) Color.White else Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                if(step == "PICKUP") {
                    Button(
                        onClick = { pickupPt = mapRef?.mapCenter as GeoPoint; step = "DEST" },
                        modifier = Modifier.fillMaxWidth().height(60.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Black)
                    ) { Text(text = "SET PICKUP LOCATION") }
                } else if(step == "DEST") {
                    Button(
                        onClick = { destPt = mapRef?.mapCenter as GeoPoint; step = "CONFIRM" },
                        modifier = Modifier.fillMaxWidth().height(60.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Black)
                    ) { Text(text = "SET DESTINATION") }
                } else {
                    val fare = if(tier.isHr) (tier.base * 1.15).toInt() else (tier.base * 1.15 * 3).toInt() // Estimate
                    Text(text = "$fare ETB", fontSize = 32.sp, fontWeight = FontWeight.Black, color = Color(0xFFD50000))
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = {
                            val id = "R_${System.currentTimeMillis()}"
                            FirebaseDatabase.getInstance().getReference("rides/$id").setValue(mapOf(
                                "id" to id, "pName" to name, "pPhone" to phone,
                                "price" to fare.toString(), "status" to "REQUESTED", "tier" to tier.label,
                                "pLat" to pickupPt?.latitude, "pLon" to pickupPt?.longitude,
                                "dLat" to destPt?.latitude, "dLon" to destPt?.longitude
                            ))
                            status = "SEARCHING FOR DRIVER..."
                        },
                        modifier = Modifier.fillMaxWidth().height(60.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A237E))
                    ) { Text(text = "BOOK BAYRA RIDE") }
                }
            }
        }
    }
}

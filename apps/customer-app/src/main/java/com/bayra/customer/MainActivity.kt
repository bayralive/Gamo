package com.bayra.customer

import android.Manifest
import android.content.Context
import android.os.Bundle
import android.preference.PreferenceManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.firebase.database.FirebaseDatabase
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.util.*

// --- THE GAMO LAWS (FUEL PHYSICS: 275 ETB/L) ---
enum class ServiceTier(val label: String, val base: Int, val isHr: Boolean) {
    POOL("Pool", 80, false),
    COMFORT("Comfort", 120, false),
    CODE_3("Code 3", 280, false),
    BAJAJ_HR("Bajaj Hr", 350, true),
    C3_HR("C3 Hr", 550, true)
}

class MainActivity : ComponentActivity() {
    private var locationOverlay: MyLocationNewOverlay? = null
    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted -> 
        if (isGranted) locationOverlay?.enableMyLocation() 
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
    val prefs = context.getSharedPreferences("bayra_vFINAL", Context.MODE_PRIVATE)
    var name by remember { mutableStateOf(prefs.getString("n", "") ?: "") }
    var phone by remember { mutableStateOf(prefs.getString("p", "") ?: "") }
    var isAuth by remember { mutableStateOf(name.isNotEmpty()) }

    if (!isAuth) {
        Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
            Text("BAYRA LOGIN", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color(0xFF5E4E92))
            Spacer(Modifier.height(30.dp))
            OutlinedTextField(name, { name = it }, label = { Text("Full Name") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(phone, { phone = it }, label = { Text("Phone Number") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(30.dp))
            Button({ if(name.length > 2 && phone.length > 8) { 
                prefs.edit().putString("n", name).putString("p", phone).apply()
                isAuth = true 
            } }, Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5E4E92))) { Text("ENTER") }
        }
    } else { BookingHub(name, phone) }
}

@Composable
fun BookingHub(pName: String, pPhone: String) {
    var isSearching by remember { mutableStateOf(false) }
    var selectedTier by remember { mutableStateOf(ServiceTier.COMFORT) }
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    
    val isNight = Calendar.getInstance().get(Calendar.HOUR_OF_DAY).let { it >= 20 || it < 6 }
    val finalFare = ((selectedTier.base + (if(isNight) 200 else 0)) * 1.15).toInt()

    Box(Modifier.fillMaxSize()) {
        // --- 🛰️ SATELLITE ENGINE ---
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                MapView(ctx).apply {
                    setTileSource(object : XYTileSource("Google-Hybrid", 0, 20, 256, ".jpg", arrayOf("https://mt1.google.com/vt/lyrs=y&")) {
                        override fun getTileURLString(pTileIndex: Long): String = baseUrl + "x=" + MapTileIndex.getX(pTileIndex) + "&y=" + MapTileIndex.getY(pTileIndex) + "&z=" + MapTileIndex.getZoom(pTileIndex)
                    })
                    setMultiTouchControls(true)
                    controller.setZoom(17.0)
                    controller.setCenter(GeoPoint(6.0333, 37.5500))
                    val overlay = MyLocationNewOverlay(GpsMyLocationProvider(ctx), this)
                    overlay.enableMyLocation()
                    overlay.enableFollowLocation()
                    overlays.add(overlay)
                    mapViewRef = this
                }
            }
        )

        if (isSearching) {
            Column(Modifier.fillMaxSize().background(Color.White).padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color(0xFF5E4E92))
                Spacer(Modifier.height(20.dp))
                Text("SEARCHING ARBA MINCH...", fontWeight = FontWeight.Bold)
                Text("$finalFare ETB", fontSize = 32.sp, color = Color.Red, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(40.dp))
                Button({ isSearching = false }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text("CANCEL") }
            }
        } else {
            // CENTRAL PIN
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("📍", fontSize = 50.sp, modifier = Modifier.padding(bottom = 40.dp))
            }

            // --- 🎛️ THE 5-TIER HUB ---
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.White, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).padding(24.dp)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(ServiceTier.values()) { tier ->
                        val sel = selectedTier == tier
                        Surface(
                            Modifier.clickable { selectedTier = tier }, 
                            color = if(sel) Color(0xFF4CAF50) else Color(0xFFF0F0F0), 
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(tier.label, Modifier.padding(14.dp, 10.dp), fontWeight = FontWeight.Bold, color = if(sel) Color.White else Color.Black)
                        }
                    }
                }
                
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Column {
                        Text("TOTAL FARE", fontSize = 12.sp, color = Color.Gray)
                        Text("$finalFare ETB", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color.Red)
                    }
                    Button(
                        onClick = {
                            val loc = mapViewRef?.mapCenter as GeoPoint
                            val id = "R_${System.currentTimeMillis()}"
                            FirebaseDatabase.getInstance().getReference("rides/$id").setValue(mapOf(
                                "id" to id, "pName" to pName, "pPhone" to pPhone, 
                                "status" to "REQUESTED", "price" to finalFare.toString(), 
                                "tier" to selectedTier.label, "pLat" to loc.latitude, "pLon" to loc.longitude
                            ))
                            isSearching = true
                        },
                        modifier = Modifier.height(60.dp).width(160.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5E4E92))
                    ) { Text("BOOK NOW", fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

package com.bayra.customer

import android.Manifest
import android.content.Context
import android.os.Bundle
import android.preference.PreferenceManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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

class MainActivity : ComponentActivity() {
    private var locationOverlay: MyLocationNewOverlay? = null
    
    // 🛡️ THE FIX: Corrected 'isGranted' usage
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted -> 
        if (isGranted) locationOverlay?.enableMyLocation() 
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        Configuration.getInstance().userAgentValue = "BayraSovereign"
        setContent { MaterialTheme { PassengerApp() } }
        requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    fun centerMap(map: MapView?) {
        locationOverlay?.enableFollowLocation()
        locationOverlay?.myLocation?.let { map?.controller?.animateTo(it) }
    }
}

@Composable
fun PassengerApp() {
    val context = LocalContext.current as MainActivity
    val prefs = context.getSharedPreferences("bayra_vFINAL", 0)
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
    } else { BookingFlow(name, phone, context) }
}

@Composable
fun BookingFlow(pName: String, pPhone: String, activity: MainActivity) {
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var isSearching by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                MapView(ctx).apply {
                    // 🛰️ GOOGLE HYBRID WELD (Satellite + Streets + Labels)
                    setTileSource(object : XYTileSource("Google-Hybrid", 0, 20, 256, ".jpg", 
                        arrayOf("https://mt0.google.com/vt/lyrs=y&", "https://mt1.google.com/vt/lyrs=y&", "https://mt2.google.com/vt/lyrs=y&", "https://mt3.google.com/vt/lyrs=y&")
                    ) {
                        override fun getTileURLString(pTileIndex: Long): String = baseUrl + "x=" + MapTileIndex.getX(pTileIndex) + "&y=" + MapTileIndex.getY(pTileIndex) + "&z=" + MapTileIndex.getZoom(pTileIndex)
                    })
                    setMultiTouchControls(true)
                    controller.setZoom(17.0)
                    controller.setCenter(GeoPoint(6.0333, 37.5500))
                    val overlay = MyLocationNewOverlay(GpsMyLocationProvider(ctx), this)
                    overlay.enableMyLocation()
                    overlays.add(overlay)
                    mapViewRef = this
                }
            }
        )

        FloatingActionButton(
            onClick = { activity.centerMap(mapViewRef) },
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).padding(top = 40.dp),
            containerColor = Color.White,
            shape = CircleShape
        ) { Text("🎯", fontSize = 20.sp) }

        if (!isSearching) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(color = Color.Black.copy(alpha = 0.7f), shape = RoundedCornerShape(4.dp)) {
                        Text("SET PICKUP", color = Color.White, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp), fontSize = 10.sp)
                    }
                    Text("📍", fontSize = 40.sp, modifier = Modifier.padding(bottom = 35.dp))
                }
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.White.copy(alpha = 0.9f), RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).padding(24.dp)) {
            if (isSearching) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = Color(0xFF5E4E92))
                    Spacer(Modifier.height(10.dp)); Text("SEARCHING FOR DRIVER...", fontWeight = FontWeight.Bold)
                    Button({ isSearching = false }, Modifier.padding(top = 20.dp).fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text("CANCEL") }
                }
            } else {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Column {
                        Text("ARBA MINCH SECTOR", fontSize = 12.sp, color = Color.Gray)
                        Text("FARE: 112 ETB", fontSize = 24.sp, fontWeight = FontWeight.Black, color = Color.Red)
                    }
                    Button(
                        onClick = {
                            val loc = mapViewRef?.mapCenter as GeoPoint
                            val id = "R_${System.currentTimeMillis()}"
                            FirebaseDatabase.getInstance().getReference("rides/$id")
                                .setValue(mapOf("id" to id, "status" to "REQUESTED", "price" to "112", "pLat" to loc.latitude, "pLon" to loc.longitude, "pName" to pName))
                            isSearching = true
                        },
                        modifier = Modifier.height(60.dp).width(150.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5E4E92))
                    ) { Text("BOOK", fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

package com.bayra.customer

import android.Manifest
import android.annotation.SuppressLint
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
import com.google.android.gms.location.*
import com.google.firebase.database.FirebaseDatabase
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        Configuration.getInstance().userAgentValue = "BayraSovereign"
        setContent { MaterialTheme { PassengerSatelliteApp() } }
    }
}

@Composable
fun PassengerSatelliteApp() {
    val context = LocalContext.current
    val ridesRef = FirebaseDatabase.getInstance().getReference("rides")
    
    // --- 🛰️ THE SATELLITE ENGINE STATE ---
    var mapView by remember { mutableStateOf<MapView?>(null) }
    var locationOverlay by remember { mutableStateOf<MyLocationNewOverlay?>(null) }
    var isSearching by remember { mutableStateOf(false) }
    
    // Permission Launcher
    val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) locationOverlay?.enableMyLocation()
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    Box(Modifier.fillMaxSize()) {
        // --- 🛰️ NATIVE GOOGLE SATELLITE MAP ---
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                MapView(ctx).apply {
                    // WELD GOOGLE SATELLITE TILES
                    setTileSource(object : XYTileSource(
                        "Google-Satellite", 0, 20, 256, ".jpg",
                        arrayOf("https://mt0.google.com/vt/lyrs=s&", "https://mt1.google.com/vt/lyrs=s&", "https://mt2.google.com/vt/lyrs=s&", "https://mt3.google.com/vt/lyrs=s&")
                    ) {
                        override fun getTileURLString(pTileIndex: Long): String {
                            return baseUrl + "x=" + getX(pTileIndex) + "&y=" + getY(pTileIndex) + "&z=" + getZ(pTileIndex)
                        }
                    })

                    setMultiTouchControls(true)
                    controller.setZoom(16.0)
                    controller.setCenter(GeoPoint(6.0333, 37.5500))
                    
                    // Add Real-Time Blue Dot
                    val provider = GpsMyLocationProvider(ctx)
                    locationOverlay = MyLocationNewOverlay(provider, this)
                    locationOverlay?.enableMyLocation()
                    locationOverlay?.enableFollowLocation() // Auto-track initially
                    overlays.add(locationOverlay)
                    
                    mapView = this
                }
            }
        )

        // --- 🎯 TACTICAL "FIND ME" BUTTON ---
        FloatingActionButton(
            onClick = { 
                locationOverlay?.enableFollowLocation()
                mapView?.controller?.animateTo(locationOverlay?.myLocation)
            },
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
            containerColor = Color.White,
            shape = CircleShape
        ) {
            Text("🎯", fontSize = 20.sp)
        }

        // --- 📍 CENTER PIN (STAYS IN CROSSHAIRS) ---
        if (!isSearching) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(color = Color.Black, shape = RoundedCornerShape(4.dp)) {
                        Text("SET PICKUP", color = Color.White, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp), fontSize = 10.sp)
                    }
                    Text("📍", fontSize = 40.sp, modifier = Modifier.padding(bottom = 35.dp))
                }
            }
        }

        // --- 🎛️ CONTROL HUB ---
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.White.copy(alpha = 0.9f), RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).padding(24.dp)) {
            if (isSearching) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = Color(0xFF5E4E92))
                    Spacer(Modifier.height(20.dp))
                    Text("SIGNAL SENT. WAITING FOR DRIVER...", fontWeight = FontWeight.Bold)
                    Button({ isSearching = false }, Modifier.padding(top = 20.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text("CANCEL") }
                }
            } else {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Column {
                        Text("ARBA MINCH SECTOR", fontSize = 12.sp, color = Color.Gray)
                        Text("FARE: 112 ETB", fontSize = 24.sp, fontWeight = FontWeight.Black, color = Color.Red)
                    }
                    Button(
                        onClick = {
                            val loc = mapView?.mapCenter as GeoPoint
                            val id = "R_${System.currentTimeMillis()}"
                            FirebaseDatabase.getInstance().getReference("rides/$id")
                                .setValue(mapOf("id" to id, "status" to "REQUESTED", "price" to "112", "pLat" to loc.latitude, "pLon" to loc.longitude))
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

package com.bayra.customer

import android.Manifest
import android.content.Context
import android.os.Bundle
import android.preference.PreferenceManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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

class MainActivity : ComponentActivity() {

    private var locationOverlay: MyLocationNewOverlay? = null

    // --- 🛡️ THE PERMISSION SHIELD ---
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) locationOverlay?.enableMyLocation()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        Configuration.getInstance().userAgentValue = "BayraSovereign"
        
        setContent { MaterialTheme { PassengerSatelliteApp() } }
        
        // Demand GPS Access
        requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    @Composable
    fun PassengerSatelliteApp() {
        var mapViewRef by remember { mutableStateOf<MapView?>(null) }
        var isSearching by remember { mutableStateOf(false) }

        Box(Modifier.fillMaxSize()) {
            // --- 🛰️ NATIVE GOOGLE SATELLITE ENGINE ---
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    MapView(ctx).apply {
                        // WELD GOOGLE SATELLITE TILES (FIXED SYNTAX)
                        setTileSource(object : XYTileSource(
                            "Google-Sat", 0, 20, 256, ".jpg",
                            arrayOf("https://mt0.google.com/vt/lyrs=s&", "https://mt1.google.com/vt/lyrs=s&", "https://mt2.google.com/vt/lyrs=s&", "https://mt3.google.com/vt/lyrs=s&")
                        ) {
                            override fun getTileURLString(pTileIndex: Long): String {
                                return baseUrl + "x=" + MapTileIndex.getX(pTileIndex) + 
                                       "&y=" + MapTileIndex.getY(pTileIndex) + 
                                       "&z=" + MapTileIndex.getZoom(pTileIndex)
                            }
                        })

                        setMultiTouchControls(true)
                        controller.setZoom(17.0)
                        controller.setCenter(GeoPoint(6.0333, 37.5500)) // Arba Minch Base

                        // --- 🔵 THE LIVE BLUE DOT ---
                        val provider = GpsMyLocationProvider(ctx)
                        locationOverlay = MyLocationNewOverlay(provider, this).apply {
                            enableMyLocation()
                            enableFollowLocation()
                        }
                        overlays.add(locationOverlay)
                        mapViewRef = this
                    }
                }
            )

            // --- 🎯 TACTICAL "FIND ME" BUTTON ---
            FloatingActionButton(
                onClick = { 
                    locationOverlay?.enableFollowLocation()
                    locationOverlay?.myLocation?.let { mapViewRef?.controller?.animateTo(it) }
                },
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).padding(top = 40.dp),
                containerColor = Color.White,
                shape = CircleShape
            ) { Text("🎯", fontSize = 20.sp) }

            // --- 📍 TARGET CROSSHAIR ---
            if (!isSearching) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Surface(color = Color.Black, shape = RoundedCornerShape(4.dp)) {
                            Text("PICKUP POINT", color = Color.White, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp), fontSize = 10.sp)
                        }
                        Text("📍", fontSize = 40.sp, modifier = Modifier.padding(bottom = 35.dp))
                    }
                }
            }

            // --- 🎛️ COMMAND HUB ---
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.White.copy(alpha = 0.9f), RoundedCornerShape(topStart = 28.dp)).padding(24.dp)) {
                if (isSearching) {
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = Color(0xFF5E4E92))
                    Spacer(Modifier.height(10.dp))
                    Text("BEACON ACTIVE: SEARCHING DRIVER...", fontWeight = FontWeight.Bold)
                    Button({ isSearching = false }, Modifier.padding(top = 20.dp).fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text("CANCEL") }
                } else {
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        Column {
                            Text("ARBA MINCH SECTOR", fontSize = 12.sp, color = Color.Gray)
                            Text("EST. FARE: 112 ETB", fontSize = 22.sp, fontWeight = FontWeight.Black, color = Color.Red)
                        }
                        Button(
                            onClick = {
                                val center = mapViewRef?.mapCenter as? GeoPoint
                                val id = "R_${System.currentTimeMillis()}"
                                FirebaseDatabase.getInstance().getReference("rides/$id")
                                    .setValue(mapOf("id" to id, "status" to "REQUESTED", "pLat" to (center?.latitude ?: 0.0), "pLon" to (center?.longitude ?: 0.0)))
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
}

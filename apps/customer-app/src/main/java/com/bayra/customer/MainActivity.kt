package com.bayra.customer

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas // 🛡️ THE MISSING IMPORT
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.firebase.database.FirebaseDatabase
import java.util.*

enum class ServiceTier(val label: String, val base: Int, val ratePerKm: Double) {
    POOL("Pool", 80, 11.0), 
    COMFORT("Comfort", 100, 11.0), 
    CODE_3("Code 3", 250, 27.5), 
    BAJAJ_HR("Bajaj Hr", 350, 0.0), 
    C3_HR("C3 Hr", 550, 0.0)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { BayraRouter() } }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BayraRouter() {
    var webView: WebView? = null
    var step by remember { mutableStateOf("PICKUP") } // PICKUP -> DROPOFF -> CONFIRM
    var pLat by remember { mutableStateOf(6.0333) }
    var pLon by remember { mutableStateOf(37.5500) }
    var dLat by remember { mutableStateOf(0.0) }
    var dLon by remember { mutableStateOf(0.0) }
    var routeDistance by remember { mutableStateOf(0.0) }
    var selectedTier by remember { mutableStateOf(ServiceTier.COMFORT) }
    
    // PRICE CALCULATION
    val totalFare = remember(selectedTier, routeDistance) {
        if (selectedTier.ratePerKm == 0.0) selectedTier.base // Flat rate for hourly
        else (selectedTier.base + (routeDistance * selectedTier.ratePerKm) * 1.15).toInt()
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                    addJavascriptInterface(object {
                        @JavascriptInterface
                        fun onMapMove(lat: Double, lng: Double, mode: String) {
                            if (mode == "PICKUP") { pLat = lat; pLon = lng }
                            else { dLat = lat; dLon = lng }
                        }
                        @JavascriptInterface
                        fun onRouteCalculated(dist: Double) {
                            routeDistance = dist
                        }
                    }, "Android")
                    webViewClient = WebViewClient()
                    loadUrl("file:///android_asset/map.html")
                    webView = this
                }
            }
        )

        // 📍 CENTER PIN (Visible only during selection)
        if (step != "CONFIRM") {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                // VISUAL BLACK DROP PIN (CSS Match)
                Canvas(modifier = Modifier.size(30.dp).offset(y = (-15).dp)) {
                    drawCircle(Color.Black)
                }
                Text("📍", fontSize = 10.sp, color = Color.Transparent) // Anchor
            }
        }

        // 🎛️ CONTROL PANEL
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.White, RoundedCornerShape(topStart = 24.dp)).padding(24.dp)) {
            
            if (step == "PICKUP") {
                Text("SET PICKUP POINT", fontWeight = FontWeight.Bold, color = Color(0xFF5E4E92))
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { 
                        step = "DROPOFF"
                        webView?.loadUrl("javascript:lockPickup($pLat, $pLon)") 
                    }, 
                    Modifier.fillMaxWidth().height(55.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Black)
                ) { Text("CONFIRM PICKUP") }
            } 
            else if (step == "DROPOFF") {
                Text("SET DROP-OFF POINT", fontWeight = FontWeight.Bold, color = Color.Red)
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { 
                        step = "CONFIRM"
                        webView?.loadUrl("javascript:calcRoute($pLat, $pLon, $dLat, $dLon)") 
                    }, 
                    Modifier.fillMaxWidth().height(55.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5E4E92))
                ) { Text("CALCULATE ROUTE") }
            } 
            else {
                // FINAL BOOKING SCREEN
                Text("DISTANCE: ${"%.1f".format(routeDistance)} KM", color = Color.Gray, fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ServiceTier.values()) { tier ->
                        val sel = selectedTier == tier
                        Surface(Modifier.clickable { selectedTier = tier }, color = if(sel) Color(0xFF4CAF50) else Color(0xFFF0F0F0), shape = RoundedCornerShape(8.dp)) {
                            Text(tier.label, Modifier.padding(12.dp, 8.dp), fontWeight = FontWeight.Bold)
                        }
                    }
                }
                
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                    Text("TOTAL", fontWeight = FontWeight.Bold)
                    Text("$totalFare ETB", fontSize = 28.sp, color = Color.Red, fontWeight = FontWeight.Black)
                }
                
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = {
                        val ref = FirebaseDatabase.getInstance().getReference("rides")
                        val id = "R_${System.currentTimeMillis()}"
                        ref.child(id).setValue(mapOf("id" to id, "status" to "REQUESTED", "price" to totalFare.toString(), "tier" to selectedTier.label, "pLat" to pLat, "pLon" to pLon, "dLat" to dLat, "dLon" to dLon))
                    }, 
                    Modifier.fillMaxWidth().height(60.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5E4E92))
                ) { Text("BOOK NOW") }
                
                Spacer(Modifier.height(8.dp))
                TextButton({ 
                    step = "PICKUP"; routeDistance = 0.0 
                    webView?.loadUrl("javascript:resetMap()")
                }, Modifier.fillMaxWidth()) { Text("RESET MAP", color = Color.Gray) }
            }
        }
    }
}

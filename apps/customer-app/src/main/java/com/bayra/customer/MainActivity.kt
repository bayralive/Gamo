package com.bayra.customer

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
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

enum class ServiceTier(val label: String, val base: Double) {
    POOL("Pool", 80.0),
    COMFORT("Comfort", 120.0),
    CODE_3("Code 3", 280.0),
    BAJAJ_HR("Bajaj Hr", 350.0)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { PassengerApp() } }
    }
}

@Composable
fun PassengerApp() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("bayra_liberty_v1", Context.MODE_PRIVATE) }
    var pName by rememberSaveable { mutableStateOf(prefs.getString("n", "") ?: "") }
    var pPhone by rememberSaveable { mutableStateOf(prefs.getString("p", "") ?: "") }
    var isAuth by remember { mutableStateOf(pName.isNotEmpty()) }

    if (!isAuth) {
        LoginScreen { name, phone ->
            prefs.edit().putString("n", name).putString("p", phone).apply()
            pName = name; pPhone = phone; isAuth = true
        }
    } else {
        LibertyBookingHub(pName, pPhone)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LibertyBookingHub(name: String, phone: String) {
    var step by remember { mutableStateOf("PICKUP") }
    var status by remember { mutableStateOf("IDLE") }
    var lat by remember { mutableStateOf(6.0333) }
    var lon by remember { mutableStateOf(37.5500) }
    var pickupPt by remember { mutableStateOf<String?>(null) }
    var destPt by remember { mutableStateOf<String?>(null) }
    var tier by remember { mutableStateOf(ServiceTier.COMFORT) }

    // HTML/JS Content for the Liberty Map
    val mapHtml = """
        <!DOCTYPE html>
        <html>
        <head>
            <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
            <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
            <style>
                body { margin: 0; padding: 0; }
                #map { height: 100vh; width: 100vw; background: #f0f0f0; }
                .leaflet-control-attribution { display: none !important; }
            </style>
        </head>
        <body>
            <div id="map"></div>
            <script>
                var map = L.map('map', { zoomControl: false }).setView([6.0333, 37.5500], 16);
                L.tileLayer('https://tiles.openfreemap.org/styles/liberty/{z}/{x}/{y}.png').addTo(map);
                
                map.on('move', function() {
                    var center = map.getCenter();
                    AndroidBridge.onMapMove(center.lat, center.lng);
                });
            </script>
        </body>
        </html>
    """.trimIndent()

    Box(modifier = Modifier.fillMaxSize()) {
        // 🏗️ WEB-NATIVE HYBRID ENGINE
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    webViewClient = WebViewClient()
                    addJavascriptInterface(object {
                        @JavascriptInterface
                        fun onMapMove(newLat: Double, newLon: Double) {
                            lat = newLat
                            lon = newLon
                        }
                    }, "AndroidBridge")
                    loadDataWithBaseURL(null, mapHtml, "text/html", "UTF-8", null)
                }
            }
        )

        if (status != "IDLE") {
            SearchOverlay(status) { status = "IDLE" }
        } else {
            // THE PRESTIGE 📍 (Overlayed on top of the moving WebView)
            if (step != "CONFIRM") {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(text = "📍", fontSize = 48.sp, modifier = Modifier.padding(bottom = 40.dp))
                }
            }

            // SOVEREIGN CONTROL PANEL
            Column(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.White, RoundedCornerShape(topStart = 28.dp)).padding(24.dp)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(ServiceTier.values().toList()) { t ->
                        Surface(
                            modifier = Modifier.clickable { tier = t },
                            color = if(tier == t) Color(0xFF1A237E) else Color(0xFFF5F5F5),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(text = t.label, modifier = Modifier.padding(14.dp, 10.dp), color = if(tier == t) Color.White else Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(20.dp))
                
                if (step == "PICKUP") {
                    Button(onClick = { pickupPt = "$lat,$lon"; step = "DEST" }, modifier = Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Black)) {
                        Text(text = "SET PICKUP LOCATION", fontWeight = FontWeight.Bold)
                    }
                } else if (step == "DEST") {
                    Button(onClick = { destPt = "$lat,$lon"; step = "CONFIRM" }, modifier = Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Black)) {
                        Text(text = "SET DESTINATION", fontWeight = FontWeight.Bold)
                    }
                } else {
                    val finalFare = (tier.base * 1.15).toInt() // Simplified for demo
                    Text(text = "$finalFare ETB", fontSize = 34.sp, fontWeight = FontWeight.Black, color = Color(0xFFD50000))
                    Button(
                        onClick = {
                            val id = "R_${System.currentTimeMillis()}"
                            FirebaseDatabase.getInstance().getReference("rides/$id").setValue(mapOf(
                                "id" to id, "pName" to name, "pPhone" to phone, "price" to finalFare.toString(),
                                "status" to "REQUESTED", "pLat" to pickupPt?.split(",")?.get(0)?.toDouble(), "pLon" to pickupPt?.split(",")?.get(1)?.toDouble()
                            ))
                            status = "FINDING YOUR RIDE..."
                        },
                        modifier = Modifier.fillMaxWidth().height(65.dp).padding(top = 12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A237E))
                    ) { Text(text = "CONFIRM BAYRA", fontWeight = FontWeight.ExtraBold) }
                }
            }
        }
    }
}

@Composable
fun LoginScreen(onLogin: (String, String) -> Unit) {
    var n by remember { mutableStateOf("") }; var p by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(32.dp).background(Color.White), Arrangement.Center, Alignment.CenterHorizontally) {
        Image(painter = painterResource(id = R.drawable.logo_passenger), contentDescription = null, modifier = Modifier.size(200.dp))
        Spacer(Modifier.height(20.dp))
        Text(text = "BAYRA TRAVEL", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color(0xFF1A237E))
        Spacer(Modifier.height(30.dp))
        OutlinedTextField(value = n, onValueChange = { n = it }, label = { Text(text = "Name") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = p, onValueChange = { p = it }, label = { Text(text = "Phone") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { if(n.isNotEmpty()) onLogin(n, p) }, modifier = Modifier.fillMaxWidth().height(60.dp).padding(top = 20.dp)) { Text(text = "START") }
    }
}

@Composable
fun SearchOverlay(statusText: String, onCancel: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Color.White), Arrangement.Center, Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = Color(0xFF1A237E), strokeWidth = 6.dp)
        Spacer(Modifier.height(24.dp))
        Text(text = statusText, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Button(onClick = onCancel, modifier = Modifier.padding(top = 40.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text(text = "CANCEL") }
    }
}

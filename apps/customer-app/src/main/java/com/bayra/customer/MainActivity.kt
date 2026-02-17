package com.bayra.customer

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.webkit.*
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import com.google.firebase.database.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Enable Hardware Acceleration for smooth map movement
        window.setFlags(android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED, android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
        setContent { MaterialTheme { PassengerApp() } }
    }
}

@Composable
fun PassengerApp() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("bayra_liberty_v2", Context.MODE_PRIVATE) }
    var pName by rememberSaveable { mutableStateOf(text = prefs.getString("n", "") ?: "") }
    var isAuth by remember { mutableStateOf(value = pName.isNotEmpty()) }

    if (!isAuth) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp).background(Color.White),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = "BAYRA TRAVEL", fontSize = 32.sp, fontWeight = FontWeight.Black, color = Color(0xFF1A237E))
            Spacer(modifier = Modifier.height(30.dp))
            var nIn by remember { mutableStateOf(value = "") }
            OutlinedTextField(value = nIn, onValueChange = { nIn = it }, label = { Text(text = "Enter Name") }, modifier = Modifier.fillMaxWidth())
            Button(
                onClick = { if(nIn.isNotEmpty()){ prefs.edit().putString("n", nIn).apply(); pName = nIn; isAuth = true } },
                modifier = Modifier.fillMaxWidth().height(60.dp).padding(top = 20.dp)
            ) { Text(text = "START") }
        }
    } else {
        LibertyEngine()
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LibertyEngine() {
    var lat by remember { mutableStateOf(value = 6.0333) }
    var lon by remember { mutableStateOf(value = 37.5500) }
    var step by remember { mutableStateOf(value = "PICKUP") }
    
    val mapHtml = """
        <!DOCTYPE html>
        <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
            <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
            <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
            <style>
                body { margin: 0; padding: 0; background: #e0e0e0; }
                #map { height: 100vh; width: 100vw; }
                .leaflet-control-attribution { display: none !important; }
            </style>
        </head>
        <body>
            <div id="map"></div>
            <script>
                var map = L.map('map', { zoomControl: false, fadeAnimation: true }).setView([6.0333, 37.5500], 16);
                L.tileLayer('https://tiles.openfreemap.org/styles/liberty/{z}/{x}/{y}.png', {
                    maxZoom: 19,
                    keepBuffer: 2
                }).addTo(map);
                
                map.on('move', function() {
                    var center = map.getCenter();
                    Android.onMapMove(center.lat, center.lng);
                });
            </script>
        </body>
        </html>
    """.trimIndent()

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    webViewClient = WebViewClient()
                    addJavascriptInterface(object {
                        @JavascriptInterface
                        fun onMapMove(newLat: Double, newLon: Double) {
                            lat = newLat
                            lon = newLon
                        }
                    }, "Android")
                    // Use a proper BaseURL to avoid security blocks
                    loadDataWithBaseURL("https://tiles.openfreemap.org", mapHtml, "text/html", "UTF-8", null)
                }
            }
        )

        // 📍 CENTER PIN (STATIONARY)
        if (step != "CONFIRM") {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = "📍", fontSize = 50.sp, modifier = Modifier.padding(bottom = 50.dp))
            }
        }

        // BOTTOM UI PANEL
        Column(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.White, RoundedCornerShape(topStart = 30.dp)).padding(24.dp)) {
            Text(text = if(step == "PICKUP") "Where are you?" else "Where to?", fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Spacer(modifier = Modifier.height(16.dp))
            
            Button(
                onClick = { if(step == "PICKUP") step = "DEST" else step = "CONFIRM" },
                modifier = Modifier.fillMaxWidth().height(65.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Black),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(text = if(step == "PICKUP") "CONFIRM PICKUP" else "CONFIRM DESTINATION", fontWeight = FontWeight.ExtraBold)
            }
        }
    }
}

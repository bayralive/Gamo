package com.bayra.customer

import android.Manifest
import android.content.*
import android.net.Uri
import android.os.Bundle
import android.preference.PreferenceManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import org.json.JSONObject
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
    BAJAJ_HR("Bajaj Hr", 350.0, 0.0, true),
    C3_HR("C3 Hr", 550.0, 0.0, true)
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
    val prefs = ctx.getSharedPreferences("bayra_p_v107", Context.MODE_PRIVATE)
    var pName by rememberSaveable { mutableStateOf(prefs.getString("n", "") ?: "") }
    var pPhone by rememberSaveable { mutableStateOf(prefs.getString("p", "") ?: "") }
    var isAuth by remember { mutableStateOf(pName.isNotEmpty()) }
    
    var step by rememberSaveable { mutableStateOf("PICKUP") }
    var status by rememberSaveable { mutableStateOf("IDLE") }
    var price by rememberSaveable { mutableStateOf("0") }
    var pickupPt by remember { mutableStateOf<GeoPoint?>(null) }
    var destPt by remember { mutableStateOf<GeoPoint?>(null) }
    var tier by remember { mutableStateOf(ServiceTier.COMFORT) }

    if (!isAuth) {
        Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center) {
            Text("BAYRA TRAVEL", fontSize = 32.sp, fontWeight = FontWeight.Black, color = Color(0xFF5E4E92))
            OutlinedTextField(value = pName, onValueChange = { pName = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = pPhone, onValueChange = { pPhone = it }, label = { Text("Phone") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { if(pName.isNotEmpty()){ prefs.edit().putString("n", pName).putString("p", pPhone).apply(); isAuth = true } }, Modifier.fillMaxWidth().padding(top = 20.dp)) { Text("ENTER") }
        }
    } else {
        Box(Modifier.fillMaxSize()) {
            val satellite = object : OnlineTileSourceBase("GoogleHybrid", 0, 20, 256, ".png", arrayOf("https://mt0.google.com/vt/lyrs=y&x={x}&y={y}&z={z}")) {
                override fun getTileURLString(p: Long): String = "$baseUrl&x=${MapTileIndex.getX(p)}&y=${MapTileIndex.getY(p)}&z=${MapTileIndex.getZoom(p)}"
            }
            var mapRef by remember { mutableStateOf<MapView?>(null) }
            AndroidView(factory = { MapView(it).apply { setTileSource(satellite); controller.setZoom(16.5); controller.setCenter(GeoPoint(6.0333, 37.5500)); mapRef = this } })

            if (status != "IDLE") {
                Column(Modifier.fillMaxSize().background(Color.White).padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color(0xFF5E4E92))
                    Text(status, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text("$price ETB", fontSize = 48.sp, fontWeight = FontWeight.Black)
                    Button(onClick = { status = "IDLE" }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text("CANCEL") }
                }
            } else {
                Box(Modifier.fillMaxSize(), Alignment.Center) { if(step != "CONFIRM") Text("📍", fontSize = 40.sp, Modifier.padding(bottom = 40.dp)) }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.White, RoundedCornerShape(topStart = 24.dp)).padding(24.dp)) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(ServiceTier.values()) { t ->
                            Surface(Modifier.clickable { tier = t }, color = if(tier == t) Color(0xFF5E4E92) else Color(0xFFEEEEEE), shape = RoundedCornerShape(8.dp)) {
                                Text(t.label, Modifier.padding(12.dp), color = if(tier == t) Color.White else Color.Black)
                            }
                        }
                    }
                    val fare = if(tier.isHr) (tier.base * 1.15).toInt() else (tier.base * 1.15).toInt()
                    Spacer(Modifier.height(16.dp))
                    if(step == "PICKUP") Button(onClick = { pickupPt = mapRef?.mapCenter as GeoPoint; step = "DEST" }, Modifier.fillMaxWidth().height(60.dp)) { Text("SET PICKUP") }
                    else if(step == "DEST") Button(onClick = { destPt = mapRef?.mapCenter as GeoPoint; step = "CONFIRM" }, Modifier.fillMaxWidth().height(60.dp)) { Text("SET DESTINATION") }
                    else {
                        Text("$fare ETB", fontSize = 28.sp, fontWeight = FontWeight.Black)
                        Button(onClick = {
                            val id = "R_${System.currentTimeMillis()}"
                            FirebaseDatabase.getInstance().getReference("rides/$id").setValue(mapOf("id" to id, "pName" to pName, "pPhone" to pPhone, "price" to fare.toString(), "status" to "REQUESTED", "pLat" to pickupPt?.latitude, "pLon" to pickupPt?.longitude, "dLat" to destPt?.latitude, "dLon" to destPt?.longitude))
                            price = fare.toString(); status = "REQUESTED"
                        }, Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5E4E92))) { Text("BOOK NOW") }
                    }
                }
            }
        }
    }
}

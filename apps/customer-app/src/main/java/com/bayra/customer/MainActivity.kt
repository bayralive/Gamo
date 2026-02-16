package com.bayra.customer

import android.content.*
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
import org.osmdroid.util.*
import org.osmdroid.views.MapView
import java.net.URL
import kotlin.concurrent.thread

enum class Tier(val label: String, val base: Double, val rate: Double, val isHr: Boolean) {
    POOL("Pool", 80.0, 15.0, false), COMFORT("Comfort", 120.0, 20.0, false), 
    CODE_3("Code 3", 280.0, 45.0, false), BAJAJ_HR("Bajaj Hr", 350.0, 0.0, true)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        setContent { MaterialTheme { CustomerCore() } }
    }
}

@Composable
fun CustomerCore() {
    val ctx = LocalContext.current
    val prefs = ctx.getSharedPreferences("p_prefs", Context.MODE_PRIVATE)
    var name by remember { mutableStateOf(prefs.getString("n", "") ?: "") }
    var email by remember { mutableStateOf(prefs.getString("e", "") ?: "") }
    var isAuth by remember { mutableStateOf(name.isNotEmpty()) }
    var step by remember { mutableStateOf("PICKUP") }
    var status by remember { mutableStateOf("IDLE") }
    var pickupPt by remember { mutableStateOf<GeoPoint?>(null) }
    var destPt by remember { mutableStateOf<GeoPoint?>(null) }
    var selectedTier by remember { mutableStateOf(Tier.COMFORT) }
    var dist by remember { mutableStateOf(0.0) }
    var activeRideId by remember { mutableStateOf("") }

    if (!isAuth) {
        Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center) {
            Text("BAYRA EMPIRE", fontSize = 32.sp, fontWeight = FontWeight.Black)
            var nIn by remember { mutableStateOf("") }
            var eIn by remember { mutableStateOf("") }
            OutlinedTextField(value = nIn, onValueChange = { nIn = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = eIn, onValueChange = { eIn = it }, label = { Text("Email") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { if(nIn.isNotEmpty() && eIn.contains("@")){ prefs.edit().putString("n", nIn).putString("e", eIn).apply(); name=nIn; email=eIn; isAuth=true } }, Modifier.fillMaxWidth().padding(top = 10.dp)) { Text("LOGIN") }
        }
    } else {
        Box(Modifier.fillMaxSize()) {
            val satellite = object : OnlineTileSourceBase("Hybrid", 0, 20, 256, ".png", arrayOf("https://mt0.google.com/vt/lyrs=y&x={x}&y={y}&z={z}")) {
                override fun getTileURLString(p: Long): String = "$baseUrl&x=${MapTileIndex.getX(p)}&y=${MapTileIndex.getY(p)}&z=${MapTileIndex.getZoom(p)}"
            }
            var mapRef by remember { mutableStateOf<MapView?>(null) }
            AndroidView(factory = { MapView(it).apply { setTileSource(satellite); controller.setZoom(16.5); controller.setCenter(GeoPoint(6.0333, 37.5500)); mapRef = this } })

            if (status != "IDLE") {
                Column(Modifier.fillMaxSize().background(Color.White).padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color(0xFF5E4E92))
                    Text(status, fontWeight = FontWeight.Bold)
                    Button(onClick = { status = "IDLE"; step = "PICKUP" }, Modifier.padding(top = 20.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text("CANCEL") }
                }
            } else {
                if(step != "CONFIRM") Box(Modifier.fillMaxSize(), Alignment.Center){ Text("📍", fontSize = 40.sp, Modifier.padding(bottom = 40.dp)) }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.White, RoundedCornerShape(topStart = 28.dp)).padding(24.dp)) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(Tier.values()) { t ->
                            Surface(Modifier.clickable { selectedTier = t }, color = if(selectedTier == t) Color(0xFF5E4E92) else Color(0xFFEEEEEE), shape = RoundedCornerShape(8.dp)) {
                                Text(t.label, Modifier.padding(12.dp), color = if(selectedTier == t) Color.White else Color.Black)
                            }
                        }
                    }
                    val fare = (if(selectedTier.isHr) selectedTier.base else (selectedTier.base + (dist * selectedTier.rate)) * 1.15).toInt()
                    Spacer(Modifier.height(16.dp))
                    if(step == "PICKUP") Button(onClick = { pickupPt = mapRef?.mapCenter as GeoPoint; step = "DEST" }, Modifier.fillMaxWidth().height(60.dp)) { Text("SET PICKUP") }
                    else if(step == "DEST") Button(onClick = { destPt = mapRef?.mapCenter as GeoPoint; step = "CONFIRM"; dist = 3.2 }, Modifier.fillMaxWidth().height(60.dp)) { Text("SET DESTINATION") }
                    else {
                        Text("$fare ETB", fontSize = 28.sp, fontWeight = FontWeight.Black)
                        Button(onClick = {
                            val id = "R_${System.currentTimeMillis()}"
                            FirebaseDatabase.getInstance().getReference("rides/$id").setValue(mapOf("id" to id, "pName" to name, "status" to "REQUESTED", "price" to fare.toString(), "pLat" to pickupPt?.latitude, "pLon" to pickupPt?.longitude, "dLat" to destPt?.latitude, "dLon" to destPt?.longitude))
                            activeRideId = id; status = "SEARCHING"
                        }, Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Black)) { Text("BOOK NOW") }
                    }
                }
            }
        }
    }
}

package com.bayra.customer

import android.content.Context
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
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView

enum class Tier(val label: String, val base: Double) {
    POOL("Pool", 80.0), COMFORT("Comfort", 120.0), CODE_3("Code 3", 280.0), BAJAJ_HR("Bajaj Hr", 350.0)
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
    val prefs = remember { ctx.getSharedPreferences("bayra_p_v143", Context.MODE_PRIVATE) }
    var pName by rememberSaveable { mutableStateOf(prefs.getString("n", "") ?: "") }
    var isAuth by remember { mutableStateOf(pName.isNotEmpty()) }

    if (!isAuth) {
        Column(modifier = Modifier.fillMaxSize().padding(32.dp).background(Color.White), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painter = painterResource(id = R.drawable.logo_passenger), contentDescription = null, modifier = Modifier.size(200.dp))
            Spacer(modifier = Modifier.height(20.dp))
            Text(text = "BAYRA TRAVEL", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color(0xFF1A237E))
            var nIn by remember { mutableStateOf("") }
            OutlinedTextField(value = nIn, onValueChange = { nIn = it }, label = { Text(text = "Name") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { if(nIn.isNotEmpty()){ prefs.edit().putString("n", nIn).apply(); pName = nIn; isAuth = true } }, modifier = Modifier.fillMaxWidth().height(60.dp).padding(top = 16.dp)) { Text(text = "START") }
        }
    } else {
        BookingCore(pName)
    }
}

@Composable
fun BookingCore(name: String) {
    var step by remember { mutableStateOf("PICKUP") }
    var status by remember { mutableStateOf("IDLE") }
    var mapRef by remember { mutableStateOf<MapView?>(null) }
    var selectedTier by remember { mutableStateOf(Tier.COMFORT) }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(factory = { context -> MapView(context).apply { setTileSource(TileSourceFactory.MAPNIK); controller.setZoom(16.0); controller.setCenter(GeoPoint(6.0333, 37.5500)); mapRef = this } })

        if (status != "IDLE") {
            Box(modifier = Modifier.fillMaxSize().background(Color.White), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color(0xFF1A237E))
                    Text(text = "FINDING YOUR RIDE...", modifier = Modifier.padding(top = 20.dp))
                    Button(onClick = { status = "IDLE" }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text(text = "CANCEL") }
                }
            }
        } else {
            if (step != "CONFIRM") Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(text = "📍", fontSize = 44.sp, modifier = Modifier.padding(bottom = 40.dp)) }
            Column(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.White, RoundedCornerShape(topStart = 24.dp)).padding(24.dp)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(Tier.values().toList()) { t ->
                        Surface(modifier = Modifier.clickable { selectedTier = t }, color = if(selectedTier == t) Color(0xFF1A237E) else Color(0xFFEEEEEE), shape = RoundedCornerShape(8.dp)) {
                            Text(text = t.label, modifier = Modifier.padding(12.dp), color = if(selectedTier == t) Color.White else Color.Black)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                if (step == "PICKUP") Button(onClick = { step = "DEST" }, modifier = Modifier.fillMaxWidth().height(60.dp)) { Text(text = "SET PICKUP") }
                else if (step == "DEST") Button(onClick = { step = "CONFIRM" }, modifier = Modifier.fillMaxWidth().height(60.dp)) { Text(text = "SET DESTINATION") }
                else {
                    val fare = (selectedTier.base * 1.15).toInt()
                    Text(text = "$fare ETB", fontSize = 32.sp, fontWeight = FontWeight.Black, color = Color.Red)
                    Button(onClick = { 
                        val id = "R_${System.currentTimeMillis()}"
                        val pt = mapRef?.mapCenter as GeoPoint
                        FirebaseDatabase.getInstance().getReference("rides/$id").setValue(mapOf("id" to id, "pName" to name, "status" to "REQUESTED", "price" to fare.toString(), "pLat" to pt.latitude, "pLon" to pt.longitude))
                        status = "SEARCHING" 
                    }, modifier = Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A237E))) { Text(text = "BOOK NOW") }
                }
            }
        }
    }
}

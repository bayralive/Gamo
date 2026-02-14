package com.bayra.customer

import android.os.Bundle
import android.preference.PreferenceManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.firebase.database.*
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import java.util.*

enum class ServiceTier(val label: String, val base: Int) {
    POOL("Pool", 80), COMFORT("Comfort", 120), CODE_3("Code 3", 280), BAJAJ_HR("Bajaj Hr", 350), C3_HR("C3 Hr", 550)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        setContent { MaterialTheme { PassengerEngine() } }
    }
}

@Composable
fun PassengerEngine() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("bayra_p_vFINAL", 0)
    var name by remember { mutableStateOf(prefs.getString("n", "") ?: "") }
    var phone by remember { mutableStateOf(prefs.getString("p", "") ?: "") }
    var isAuth by remember { mutableStateOf(name.isNotEmpty()) }

    if (!isAuth) {
        Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
            Text("BAYRA LOGIN", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color(0xFF5E4E92))
            Spacer(Modifier.height(30.dp))
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(phone, { phone = it }, label = { Text("Phone") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(20.dp))
            Button({ if(name.length > 2){ prefs.edit().putString("n", name).putString("p", phone).apply(); isAuth = true } }, Modifier.fillMaxWidth().height(60.dp)) { Text("ENTER") }
        }
    } else { BookingHub(name, phone) }
}

@Composable
fun BookingHub(pName: String, pPhone: String) {
    var isSearching by remember { mutableStateOf(false) }
    var selectedTier by remember { mutableStateOf(ServiceTier.COMFORT) }
    var mapCenter by remember { mutableStateOf(GeoPoint(6.0333, 37.5500)) }
    val fare = (selectedTier.base * 1.15).toInt()

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx -> MapView(ctx).apply { setTileSource(TileSourceFactory.MAPNIK); setMultiTouchControls(true); controller.setZoom(16.0); controller.setCenter(mapCenter) } },
            update = { view -> mapCenter = view.mapCenter as GeoPoint }
        )

        if (isSearching) {
            Column(Modifier.fillMaxSize().background(Color.White), Arrangement.Center, Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color(0xFF5E4E92))
                Text("SEARCHING...", Modifier.padding(top = 20.dp))
                Button({ isSearching = false }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text("CANCEL") }
            }
        } else {
            Box(Modifier.fillMaxSize(), Alignment.Center) { Text("📍", fontSize = 50.sp, modifier = Modifier.padding(bottom = 40.dp)) }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.White, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).padding(24.dp)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(ServiceTier.values()) { tier ->
                        val sel = selectedTier == tier
                        Surface(Modifier.clickable { selectedTier = tier }, color = if(sel) Color(0xFF4CAF50) else Color(0xFFF0F0F0), shape = RoundedCornerShape(12.dp)) {
                            Text(tier.label, Modifier.padding(14.dp, 10.dp), fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                Text("FARE: $fare ETB", color = Color.Red, fontWeight = FontWeight.Black, fontSize = 24.sp)
                Button(onClick = {
                    val ref = FirebaseDatabase.getInstance().getReference("rides")
                    val id = "R_${System.currentTimeMillis()}"
                    // 🔥 PIPING WELD: Sending full data package
                    ref.child(id).setValue(mapOf(
                        "id" to id, "pName" to pName, "pPhone" to pPhone, 
                        "price" to fare.toString(), "status" to "REQUESTED", 
                        "tier" to selectedTier.label, "pLat" to mapCenter.latitude, "pLon" to mapCenter.longitude
                    ))
                    isSearching = true
                }, Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5E4E92))) { Text("CONFIRM BOOKING") }
            }
        }
    }
}

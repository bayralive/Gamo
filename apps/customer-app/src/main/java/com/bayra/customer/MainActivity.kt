package com.bayra.customer

import android.content.Context
import android.os.Bundle
import android.preference.PreferenceManager
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
import com.google.firebase.database.FirebaseDatabase
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.util.*
import kotlin.math.*

// --- THE GAMO LAWS (SOVEREIGN PRICING) ---
enum class ServiceTier(val label: String, val base: Int, val rate: Double, val isHr: Boolean) {
    POOL("Pool", 80, 15.0, false),
    COMFORT("Comfort", 120, 18.0, false),
    CODE_3("Code 3", 280, 35.0, false),
    BAJAJ_HR("Bajaj Hr", 350, 0.0, true),
    C3_HR("C3 Hr", 550, 0.0, true)
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
    val prefs = context.getSharedPreferences("bayra_p_vFINAL", Context.MODE_PRIVATE)
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
    } else { BookingFlow(name, phone) }
}

@Composable
fun BookingFlow(pName: String, pPhone: String) {
    var step by remember { mutableStateOf("PICKUP") } // PICKUP -> DEST -> CONFIRM
    var pickupPt by remember { mutableStateOf<GeoPoint?>(null) }
    var destPt by remember { mutableStateOf<GeoPoint?>(null) }
    var selectedTier by remember { mutableStateOf(ServiceTier.COMFORT) }
    var hrCount by remember { mutableStateOf(1) }
    var mapCenter by remember { mutableStateOf(GeoPoint(6.0333, 37.5500)) }
    var isSearching by remember { mutableStateOf(false) }

    // --- DISTANCE & PRICE MATH ---
    val distance = remember(pickupPt, destPt) {
        if (pickupPt != null && destPt != null) {
            val a = sin(Math.toRadians(destPt!!.latitude - pickupPt!!.latitude) / 2).pow(2) +
                    cos(Math.toRadians(pickupPt!!.latitude)) * cos(Math.toRadians(destPt!!.latitude)) *
                    sin(Math.toRadians(destPt!!.longitude - pickupPt!!.longitude) / 2).pow(2)
            6371.0 * 2 * asin(sqrt(a))
        } else 0.0
    }

    val totalFare = if (selectedTier.isHr) (selectedTier.base * hrCount * 1.15).toInt()
    else (selectedTier.base + (distance * selectedTier.rate * 1.15)).toInt()

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx -> MapView(ctx).apply { 
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                controller.setZoom(15.0)
                controller.setCenter(mapCenter)
            } },
            update = { view ->
                view.overlays.clear()
                pickupPt?.let { pt -> Marker(view).apply { position = pt; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM) }.also { view.overlays.add(it) } }
                destPt?.let { pt -> 
                    Marker(view).apply { position = pt; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM); icon = view.context.getDrawable(android.R.drawable.ic_menu_directions) }.also { view.overlays.add(it) }
                    Polyline().apply { addPoint(pickupPt); addPoint(destPt); color = android.graphics.Color.BLACK; width = 8f }.also { view.overlays.add(it) }
                }
                mapCenter = view.mapCenter as GeoPoint
                view.invalidate()
            }
        )

        // --- PIN CROSSHAIR ---
        if (step != "CONFIRM" && !isSearching) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(if(step == "PICKUP") "📍" else "🏁", fontSize = 40.sp, modifier = Modifier.padding(bottom = 40.dp))
            }
        }

        // --- CONTROL HUB ---
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.White, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).padding(24.dp)) {
            if (isSearching) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color(0xFF5E4E92))
                    Spacer(Modifier.height(10.dp)); Text("SEARCHING...", fontWeight = FontWeight.Bold)
                    Button({ isSearching = false }, Modifier.fillMaxWidth().padding(top = 20.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text("CANCEL") }
                }
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(ServiceTier.values()) { tier ->
                        val sel = selectedTier == tier
                        Surface(Modifier.clickable { selectedTier = tier; step = "PICKUP" }, color = if(sel) Color(0xFF4CAF50) else Color(0xFFF0F0F0), shape = RoundedCornerShape(12.dp)) {
                            Text(tier.label, Modifier.padding(12.dp, 8.dp), fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                if (selectedTier.isHr) {
                    // --- HOUR GEARBOX ---
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        Text("Duration (12km/h limit):")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button({ if(hrCount > 1) hrCount-- }, modifier = Modifier.size(40.dp), contentPadding = PaddingValues(0.dp)) { Text("-") }
                            Text("$hrCount HR", Modifier.padding(horizontal = 12.dp), fontWeight = FontWeight.Bold)
                            Button({ if(hrCount < 12) hrCount++ }, modifier = Modifier.size(40.dp), contentPadding = PaddingValues(0.dp)) { Text("+") }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }

                if (!selectedTier.isHr && step == "PICKUP") {
                    Button({ pickupPt = mapCenter; step = "DEST" }, Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Black)) { Text("SET PICKUP") }
                } else if (!selectedTier.isHr && step == "DEST") {
                    Button({ destPt = mapCenter; step = "CONFIRM" }, Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5E4E92))) { Text("SET DESTINATION") }
                } else {
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                        Text(if(selectedTier.isHr) "UNLIMITED" else "${"%.1f".format(distance)} KM", color = Color.Gray)
                        Text("$totalFare ETB", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color.Red)
                    }
                    Button({
                        val ref = FirebaseDatabase.getInstance().getReference("rides")
                        val id = "R_${System.currentTimeMillis()}"
                        ref.child(id).setValue(mapOf("id" to id, "pName" to pName, "pPhone" to pPhone, "status" to "REQUESTED", "price" to totalFare.toString(), "tier" to selectedTier.label, "pLat" to pickupPt?.latitude, "pLon" to pickupPt?.longitude, "dLat" to destPt?.latitude, "dLon" to destPt?.longitude, "hr" to hrCount))
                        isSearching = true
                    }, Modifier.fillMaxWidth().padding(top = 16.dp).height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5E4E92))) { Text("BOOK ${selectedTier.label.uppercase()}") }
                    TextButton({ step = "PICKUP"; pickupPt = null; destPt = null }, Modifier.fillMaxWidth()) { Text("RESET MAP") }
                }
            }
        }
    }
}

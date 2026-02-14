package com.bayra.customer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import com.google.firebase.database.*
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import kotlinx.coroutines.delay
import java.util.*

enum class ServiceTier(val label: String, val base: Int, val kmRate: Double, val extra: Int, val isHr: Boolean) {
    POOL("Pool", 80, 11.0, 30, false), COMFORT("Comfort", 120, 11.0, 0, false), 
    CODE_3("Code 3", 280, 27.5, 60, false), BAJAJ_HR("Bajaj Hr", 350, 0.0, 0, true), C3_HR("C3 Hr", 550, 0.0, 0, true)
}

class MainActivity : ComponentActivity() {
    private var locationOverlay: MyLocationNewOverlay? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        setContent { MaterialTheme { PassengerApp(this) } }
    }
    fun dial(n: String) = startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$n")))
}

@Composable
fun PassengerApp(activity: MainActivity) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("bayra_p_vFINAL", Context.MODE_PRIVATE)
    var name by remember { mutableStateOf(prefs.getString("n", "") ?: "") }
    var phone by remember { mutableStateOf(prefs.getString("p", "") ?: "") }
    var isAuth by remember { mutableStateOf(name.isNotEmpty()) }

    if (!isAuth) {
        Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
            Text("BAYRA", fontSize = 48.sp, fontWeight = FontWeight.Black, color = Color(0xFF5E4E92))
            Spacer(Modifier.height(30.dp))
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(phone, { phone = it }, label = { Text("Phone") }, modifier = Modifier.fillMaxWidth())
            Button({ if(name.length > 2 && phone.length > 8) { prefs.edit().putString("n", name).putString("p", phone).apply(); isAuth = true } }, Modifier.fillMaxWidth().height(60.dp)) { Text("ENTER") }
        }
    } else { BookingHub(name, phone, activity) }
}

@Composable
fun BookingHub(pName: String, pPhone: String, activity: MainActivity) {
    var step by remember { mutableStateOf("PICKUP") }
    var pickupPt by remember { mutableStateOf<GeoPoint?>(null) }
    var destPt by remember { mutableStateOf<GeoPoint?>(null) }
    var isSearching by remember { mutableStateOf(false) }
    var selectedTier by remember { mutableStateOf(ServiceTier.COMFORT) }
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var driverName by remember { mutableStateOf<String?>(null) }
    var dPhone by remember { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize()) {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx ->
            MapView(ctx).apply {
                setTileSource(object : XYTileSource("Google-Hybrid", 0, 20, 256, ".jpg", arrayOf("https://mt1.google.com/vt/lyrs=y&")) {
                    override fun getTileURLString(pTileIndex: Long): String = baseUrl + "x=" + MapTileIndex.getX(pTileIndex) + "&y=" + MapTileIndex.getY(pTileIndex) + "&z=" + MapTileIndex.getZoom(pTileIndex)
                })
                setMultiTouchControls(true); controller.setZoom(17.0); controller.setCenter(GeoPoint(6.0333, 37.5500))
                overlays.add(MyLocationNewOverlay(GpsMyLocationProvider(ctx), this).apply { enableMyLocation(); enableFollowLocation() })
                mapViewRef = this
            }
        }, update = { view ->
            view.overlays.filterIsInstance<Marker>().forEach { view.overlays.remove(it) }
            pickupPt?.let { Marker(view).apply { position = it; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM) }.also { view.overlays.add(it) } }
            destPt?.let { Marker(view).apply { position = it; icon = view.context.getDrawable(android.R.drawable.ic_menu_directions) }.also { view.overlays.add(it) } }
            view.invalidate()
        })

        if (isSearching) {
            Column(Modifier.fillMaxSize().background(Color.White).padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color(0xFF5E4E92))
                Text(if(driverName != null) "$driverName IS COMING" else "SEARCHING...", fontWeight = FontWeight.Bold)
                if(dPhone != null) Button({ activity.dial(dPhone!!) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))) { Text("CALL DRIVER") }
                Spacer(Modifier.height(40.dp))
                Button({ isSearching = false }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text("CANCEL") }
            }
        } else {
            if(step != "CONFIRM") Box(Modifier.fillMaxSize(), Alignment.Center) { Text("📍", fontSize = 40.sp, modifier = Modifier.padding(bottom = 40.dp)) }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.White, RoundedCornerShape(28.dp)).padding(24.dp)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(ServiceTier.values()) { tier ->
                        Surface(Modifier.clickable { selectedTier = tier; step = "PICKUP" }, color = if(selectedTier == tier) Color(0xFF4CAF50) else Color(0xFFF0F0F0), shape = RoundedCornerShape(12.dp)) {
                            Text(tier.label, Modifier.padding(12.dp, 8.dp), fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                if (step == "PICKUP") Button({ pickupPt = mapViewRef?.mapCenter as GeoPoint; step = "DEST" }, Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Black)) { Text("SET PICKUP") }
                else if (step == "DEST") Button({ destPt = mapViewRef?.mapCenter as GeoPoint; step = "CONFIRM" }, Modifier.fillMaxWidth().height(60.dp)) { Text("SET DESTINATION") }
                else {
                    Button({
                        val id = "R_${System.currentTimeMillis()}"
                        FirebaseDatabase.getInstance().getReference("rides/$id").setValue(mapOf("id" to id, "pName" to pName, "pPhone" to pPhone, "status" to "REQUESTED", "price" to "150", "pLat" to pickupPt?.latitude, "pLon" to pickupPt?.longitude))
                        isSearching = true
                    }, Modifier.fillMaxWidth().height(60.dp)) { Text("BOOK NOW") }
                }
            }
        }
    }
}

package com.bayra.customer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.preference.PreferenceManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.firebase.database.*
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.net.URL
import java.net.HttpURLConnection
import kotlin.concurrent.thread
import kotlin.math.*
import java.util.*

enum class ServiceTier(val label: String, val base: Int, val kmRate: Double, val extra: Int, val isHr: Boolean) {
    POOL(label = "Pool", base = 80, kmRate = 11.0, extra = 30, isHr = false),
    COMFORT(label = "Comfort", base = 120, kmRate = 11.0, extra = 0, isHr = false),
    CODE_3(label = "Code 3", base = 280, kmRate = 27.5, extra = 60, isHr = false),
    BAJAJ_HR(label = "Bajaj Hr", base = 350, kmRate = 0.0, extra = 0, isHr = true),
    C3_HR(label = "C3 Hr", base = 550, kmRate = 0.0, extra = 0, isHr = true)
}

class MainActivity : ComponentActivity() {
    private var locationOverlay: MyLocationNewOverlay? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        setContent { MaterialTheme { PassengerApp() } }
    }
}

@Composable
fun PassengerApp() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("bayra_p_vFINAL", Context.MODE_PRIVATE)
    var pName by rememberSaveable { mutableStateOf(prefs.getString("n", "") ?: "") }
    var pPhone by rememberSaveable { mutableStateOf(prefs.getString("p", "") ?: "") }
    var isAuth by remember { mutableStateOf(pName.isNotEmpty()) }
    var currentTab by rememberSaveable { mutableStateOf("BOOK") }
    
    // --- PERSISTENT STATE ---
    var isSearching by rememberSaveable { mutableStateOf(false) }
    var rideStatus by rememberSaveable { mutableStateOf("IDLE") }
    var ridePrice by rememberSaveable { mutableStateOf("0") }
    var driverName by rememberSaveable { mutableStateOf<String?>(null) }
    var dPhone by rememberSaveable { mutableStateOf<String?>(null) }
    var activeRideId by rememberSaveable { mutableStateOf("") }
    var paymentMethod by rememberSaveable { mutableStateOf("CASH") }

    if (!isAuth) {
        Column(modifier = Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "BAYRA", fontSize = 48.sp, fontWeight = FontWeight.Black, color = Color(0xFF5E4E92))
            Spacer(modifier = Modifier.height(30.dp))
            OutlinedTextField(value = pName, onValueChange = { pName = it }, label = { Text(text = "Full Name") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = pPhone, onValueChange = { pPhone = it }, label = { Text(text = "Phone Number") }, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = { if(pName.length > 2){ prefs.edit().putString("n", pName).putString("p", pPhone).apply(); isAuth = true } }, modifier = Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5E4E92))) { Text(text = "ENTER") }
        }
    } else {
        Scaffold(
            bottomBar = {
                NavigationBar(containerColor = Color.White) {
                    NavigationBarItem(selected = currentTab == "BOOK", onClick = { currentTab = "BOOK" }, icon = { Text(text = "🚕") }, label = { Text(text = "Book") })
                    NavigationBarItem(selected = currentTab == "ACCOUNT", onClick = { currentTab = "ACCOUNT" }, icon = { Text(text = "👤") }, label = { Text(text = "Account") })
                }
            }
        ) { p ->
            Box(modifier = Modifier.padding(p)) {
                if (currentTab == "BOOK") BookingHub(pName, pPhone, isSearching, rideStatus, ridePrice, driverName, dPhone, activeRideId, paymentMethod, {isSearching = it}, {rideStatus = it}, {ridePrice = it}, {driverName = it}, {dPhone = it}, {activeRideId = it}, {paymentMethod = it})
                else AccountView(pName, pPhone) { prefs.edit().clear().apply(); isAuth = false; isSearching = false }
            }
        }
    }
}

@Composable
fun BookingHub(name: String, phone: String, isSearching: Boolean, status: String, price: String, driver: String?, dPh: String?, rideId: String, payMethod: String, onSearch: (Boolean) -> Unit, onStatus: (String) -> Unit, onPrice: (String) -> Unit, onDriver: (String?) -> Unit, onDPhone: (String?) -> Unit, onId: (String) -> Unit, onPay: (String) -> Unit) {
    val context = LocalContext.current
    var step by rememberSaveable { mutableStateOf("PICKUP") }
    var pickupPt by remember { mutableStateOf<GeoPoint?>(null) }
    var destPt by remember { mutableStateOf<GeoPoint?>(null) }
    var selectedTier by remember { mutableStateOf(ServiceTier.COMFORT) }
    var roadDistance by remember { mutableStateOf(0.0) }
    var isGenerating by remember { mutableStateOf(false) }
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }

    val fare = ((selectedTier.base + (roadDistance * selectedTier.kmRate)) * 1.15).toInt().toString()

    Box(Modifier.fillMaxSize()) {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx -> MapView(ctx).apply { setTileSource(TileSourceFactory.MAPNIK); controller.setZoom(16.0); controller.setCenter(GeoPoint(6.0333, 37.5500)); mapViewRef = this } })

        if (status == "COMPLETED") {
            // --- 💰 FINAL TREASURY LOCK ---
            Column(Modifier.fillMaxSize().background(Color.White).padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                Text("TRIP FINISHED", fontSize = 24.sp, fontWeight = FontWeight.Black, color = Color(0xFF4CAF50))
                Text("$price ETB", fontSize = 48.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(32.dp))
                if (payMethod == "CHAPA") {
                    if(isGenerating) CircularProgressIndicator(color = Color(0xFF5E4E92))
                    else Button(onClick = { 
                        isGenerating = true
                        thread { try {
                            val url = URL("https://bayra-backend-eu.onrender.com/initialize-payment")
                            val conn = url.openConnection() as HttpURLConnection
                            conn.requestMethod = "POST"; conn.doOutput = true; conn.setRequestProperty("Content-Type", "application/json")
                            val body = JSONObject().put("amount", price).put("name", name).put("phone", phone).put("rideId", rideId).toString()
                            conn.outputStream.write(body.toByteArray())
                            val link = JSONObject(conn.inputStream.bufferedReader().readText()).getString("checkout_url")
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
                            onStatus("IDLE"); onSearch(false); isGenerating = false
                        } catch (e: Exception) { isGenerating = false } }
                    }, Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5E4E92))) { Text("PAY WITH CHAPA") }
                } else {
                    Text("PLEASE PAY THE DRIVER IN CASH", fontWeight = FontWeight.Bold, color = Color.Gray)
                    Button({ onStatus("IDLE"); onSearch(false) }, Modifier.fillMaxWidth().padding(top = 20.dp)) { Text("DONE") }
                }
            }
        } else if (isSearching) {
            Column(Modifier.fillMaxSize().background(Color.White).padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color(0xFF5E4E92))
                Text(if(driver != null) "$driver IS COMING" else "SEARCHING...", fontWeight = FontWeight.Bold)
                if(dPh != null) Button({ context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$dPh"))) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))) { Text("📞 CALL") }
                Spacer(Modifier.height(40.dp)); Button({ onSearch(false) }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text("CANCEL") }
            }
        } else {
            // SELECTION UI
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.White, RoundedCornerShape(topStart = 28.dp)).padding(24.dp)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(ServiceTier.values()) { t ->
                        Surface(Modifier.clickable { onTier(t); onStep("PICKUP") }, color = if(selectedTier == t) Color(0xFF4CAF50) else Color(0xFFF0F0F0), shape = RoundedCornerShape(12.dp)) {
                            Text(t.label, Modifier.padding(12.dp, 8.dp), fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                    listOf("CASH", "CHAPA").forEach { m ->
                        Surface(Modifier.weight(1f).clickable { onPay(m) }, color = if(payMethod == m) Color(0xFF5E4E92) else Color(0xFFF0F0F0), shape = RoundedCornerShape(8.dp)) {
                            Text(m, Modifier.padding(10.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = if(payMethod==m) Color.White else Color.Black)
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                Button({
                    val id = "R_${System.currentTimeMillis()}"; onId(id); onPrice(fare); onSearch(true)
                    FirebaseDatabase.getInstance().getReference("rides/$id").setValue(mapOf("id" to id, "pName" to name, "pPhone" to phone, "status" to "REQUESTED", "price" to fare, "tier" to selectedTier.label, "pay" to payMethod))
                }, Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5E4E92))) { Text("BOOK FOR $fare ETB") }
            }
        }
    }
}

@Composable
fun AccountView(name: String, phone: String, onLogout: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("ACCOUNT", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(name); Text(phone, color = Color.Gray)
        Spacer(Modifier.weight(1f)); Button(onLogout, colors = ButtonDefaults.buttonColors(containerColor = Color.Red), modifier = Modifier.fillMaxWidth().height(60.dp)) { Text("LOGOUT") }
    }
}

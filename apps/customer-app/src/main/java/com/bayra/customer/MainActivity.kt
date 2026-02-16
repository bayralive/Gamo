package com.bayra.customer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.preference.PreferenceManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.net.URL
import java.net.HttpURLConnection
import kotlin.concurrent.thread
import java.util.*

enum class ServiceTier(val label: String, val base: Int, val isHr: Boolean) {
    POOL(label = "Pool", base = 80, isHr = false),
    COMFORT(label = "Comfort", base = 120, isHr = false),
    CODE_3(label = "Code 3", base = 280, isHr = false),
    BAJAJ_HR(label = "Bajaj Hr", base = 350, isHr = true),
    C3_HR(label = "C3 Hr", base = 550, isHr = true)
}

class MainActivity : ComponentActivity() {
    private var locationOverlay: MyLocationNewOverlay? = null
    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted -> 
        if (isGranted) locationOverlay?.enableMyLocation() 
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        setContent { MaterialTheme { PassengerApp() } }
        requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
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
    var isSearching by rememberSaveable { mutableStateOf(false) }
    var rideStatus by rememberSaveable { mutableStateOf("IDLE") }
    var ridePrice by rememberSaveable { mutableStateOf("0") }

    if (!isAuth) {
        Column(modifier = Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "BAYRA", fontSize = 48.sp, fontWeight = FontWeight.Bold, color = Color(0xFF5E4E92))
            OutlinedTextField(value = pName, onValueChange = { pName = it }, label = { Text(text = "Name") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = pPhone, onValueChange = { pPhone = it }, label = { Text(text = "Phone") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { if(pName.isNotEmpty()){ prefs.edit().putString("n", pName).putString("p", pPhone).apply(); isAuth = true } }, modifier = Modifier.fillMaxWidth().height(60.dp)) { Text(text = "ENTER") }
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
            Box(Modifier.padding(p)) {
                if (currentTab == "BOOK") BookingHub(pName, pPhone, isSearching, rideStatus, ridePrice, { isSearching = it }, { rideStatus = it }, { ridePrice = it })
                else Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "USER: $pName", fontWeight = FontWeight.Bold); Text(text = pPhone, color = Color.Gray)
                    Button({ prefs.edit().clear().apply(); isAuth = false; isSearching = false }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text(text = "LOGOUT") }
                }
            }
        }
    }
}

@Composable
fun BookingHub(name: String, phone: String, isSearching: Boolean, status: String, price: String, onSearch: (Boolean) -> Unit, onStatus: (String) -> Unit, onPrice: (String) -> Unit) {
    val context = LocalContext.current
    var selectedTier by remember { mutableStateOf(ServiceTier.COMFORT) }
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    val fare = (selectedTier.base * 1.15).toInt().toString()

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx -> MapView(ctx).apply { 
                setTileSource(object : XYTileSource("G-Hybrid", 0, 20, 256, ".jpg", arrayOf("https://mt1.google.com/vt/lyrs=y&")) {
                    override fun getTileURLString(pTileIndex: Long): String = baseUrl + "x=" + MapTileIndex.getX(pTileIndex) + "&y=" + MapTileIndex.getY(pTileIndex) + "&z=" + MapTileIndex.getZoom(pTileIndex)
                })
                setMultiTouchControls(true); controller.setZoom(17.0); controller.setCenter(GeoPoint(6.0333, 37.5500)); mapViewRef = this
            } }
        )

        if (isSearching || status == "COMPLETED") {
            Column(Modifier.fillMaxSize().background(Color.White).padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                if (status == "COMPLETED") {
                    Text(text = "ARRIVED", fontSize = 32.sp, fontWeight = FontWeight.Black, color = Color(0xFF4CAF50))
                    Text(text = "$price ETB", fontSize = 48.sp, fontWeight = FontWeight.Bold)
                    Button({ 
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://checkout.chapa.co/checkout/web/payment/CHAPUBK-rsiltQmE5SqOK21Qqs6UTV7vjCsXycBc")))
                        onStatus("IDLE"); onSearch(false) 
                    }, Modifier.fillMaxWidth().height(60.dp)) { Text(text = "PAY NOW") }
                } else {
                    CircularProgressIndicator(color = Color(0xFF5E4E92))
                    Text(text = "SEARCHING...", fontWeight = FontWeight.Bold)
                    Button({ onSearch(false) }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text(text = "CANCEL") }
                }
            }
        } else {
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.White, RoundedCornerShape(topStart = 28.dp)).padding(24.dp)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(ServiceTier.values()) { tier ->
                        Surface(Modifier.clickable { selectedTier = tier }, color = if(selectedTier == tier) Color(0xFF4CAF50) else Color(0xFFF0F0F0), shape = RoundedCornerShape(12.dp)) {
                            Text(text = tier.label, modifier = Modifier.padding(14.dp, 10.dp), fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(text = "FARE: $fare ETB", color = Color.Red, fontWeight = FontWeight.Black, fontSize = 24.sp)
                Button({
                    val ref = FirebaseDatabase.getInstance().getReference("rides")
                    val id = "R_${System.currentTimeMillis()}"
                    onPrice(fare); onSearch(true)
                    ref.child(id).setValue(mapOf("id" to id, "pName" to name, "pPhone" to phone, "status" to "REQUESTED", "price" to fare, "tier" to selectedTier.label))
                }, Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5E4E92))) { Text(text = "BOOK RIDE") }
            }
        }
    }
}

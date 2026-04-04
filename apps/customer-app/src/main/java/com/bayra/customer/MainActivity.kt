package com.bayra.customer

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.preference.PreferenceManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.NotificationCompat
import coil.compose.AsyncImage
import com.google.firebase.database.*
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.*
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import com.bayra.customer.R
import java.util.Calendar

const val DB_URL = "https://bayra-84ecf-default-rtdb.europe-west1.firebasedatabase.app"
val IMPERIAL_BLUE = Color(0xFF1A237E)
val IMPERIAL_RED = Color(0xFFD50000)
const val BOT_TOKEN = "8594425943:AAH1M1_mYMI4pch-YfbC-hvzZfk_Kdrxb94"
const val CHAT_ID = "5232430147"

enum class Tier(val label: String, val base: Double, val isHr: Boolean, val isCar: Boolean) {
    POOL("Pool", 50.0, false, false), COMFORT("Comfort", 50.0, false, false), 
    CODE_3("Code 3", 50.0, false, true), BAJAJ_HR("Bajaj Hr", 350.0, true, false), C3_HR("C3 Hr", 500.0, true, true)
}

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    private val requestLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this)) } catch (e: Exception) {}
        requestLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS))
        setContent { PassengerSuperApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PassengerSuperApp() {
    val ctx = LocalContext.current
    val activity = ctx as? ComponentActivity
    val prefs = remember { ctx.getSharedPreferences("bayra_p_v230", Context.MODE_PRIVATE) }
    var isAuth by remember { mutableStateOf(prefs.getBoolean("auth", false)) }
    var pName by rememberSaveable { mutableStateOf(prefs.getString("n", "") ?: "") }
    var pPhone by rememberSaveable { mutableStateOf(prefs.getString("p", "") ?: "") }
    var pEmail by rememberSaveable { mutableStateOf(prefs.getString("e", "") ?: "") }
    var isVerifying by rememberSaveable { mutableStateOf(prefs.getBoolean("is_v", false)) }
    var pickupPt by remember { mutableStateOf<GeoPoint?>(null) }
    var destPt by remember { mutableStateOf<GeoPoint?>(null) }
    var selectedTier by remember { mutableStateOf(Tier.COMFORT) }
    var step by rememberSaveable { mutableStateOf("PICKUP") }
    var hrCount by rememberSaveable { mutableStateOf(1) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var currentView by rememberSaveable { mutableStateOf("MAP") }

    if (!isAuth) {
        if (!isVerifying) {
            LoginView(pName, pPhone, pEmail) { n, p, e -> 
                prefs.edit().putString("n", n).putString("p", p).putString("e", e).putBoolean("is_v", true).apply()
                pName = n; pPhone = p; pEmail = e; isVerifying = true 
            }
        } else { Text("Verify Code...") }
    } else {
        Scaffold(topBar = { SmallTopAppBar(title = { Text("Bayra Travel", color = Color.White) }, colors = TopAppBarDefaults.smallTopAppBarColors(containerColor = IMPERIAL_BLUE)) }) { padding ->
            Box(Modifier.padding(padding)) { BookingHub(pName, pEmail, pPhone, prefs, pickupPt, destPt, selectedTier, step, hrCount) { p, d, s, t, h -> pickupPt = p; destPt = d; step = s; selectedTier = t; hrCount = h } }
        }
    }
}

@Composable
fun BookingHub(name: String, email: String, phone: String, prefs: SharedPreferences, pickupPt: GeoPoint?, destPt: GeoPoint?, selectedTier: Tier, step: String, hrCount: Int, onPointChange: (GeoPoint?, GeoPoint?, String, Tier, Int) -> Unit) {
    val ctx = LocalContext.current
    var mapRef by remember { mutableStateOf<MapView?>(null) }
    var locationOverlay by remember { mutableStateOf<MyLocationNewOverlay?>(null) }

    // Pricing Logic
    val distKm = 2.0
    val kmRate = if (selectedTier.isCar) 65.0 else 25.0
    val roundedFare = (100.0 + (distKm * kmRate)).toInt()

    LaunchedEffect(Unit) {
        while(true) {
            locationOverlay?.enableMyLocation()
            mapRef?.invalidate()
            delay(5000L)
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { c -> 
            MapView(c).apply { 
                setTileSource(org.osmdroid.tileprovider.tilesource.TileSourceFactory.MAPNIK)
                val loc = MyLocationNewOverlay(GpsMyLocationProvider(c), this)
                loc.enableMyLocation()
                loc.enableFollowLocation()
                loc.isDrawAccuracyEnabled = true
                overlays.add(loc)
                locationOverlay = loc
                mapRef = this 
            } 
        }, modifier = Modifier.fillMaxSize())
        Text("Rate: $roundedFare ETB", modifier = Modifier.align(Alignment.BottomCenter).padding(20.dp), fontWeight = FontWeight.Bold)
    }
}

@Composable
fun LoginView(name: String, phone: String, email: String, onLogin: (String, String, String) -> Unit) {
    var n by remember { mutableStateOf(name) }; var p by remember { mutableStateOf(phone) }; var e by remember { mutableStateOf(email) }
    val ctx = LocalContext.current
    Column(modifier = Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center) {
        Text("Bayra Travel", fontSize = 28.sp, fontWeight = FontWeight.Black, color = IMPERIAL_BLUE)
        OutlinedTextField(n, { n = it }, label = { Text("Full Name") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(p, { p = it }, label = { Text("Phone Number") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(e, { e = it }, label = { Text("Email Address") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { if(n.length > 2 && (p.length >= 9 || e.contains("@"))) onLogin(n, p, e) else Toast.makeText(ctx, "Name + (Phone or Email) required!", Toast.LENGTH_SHORT).show() }, modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) { Text("GET PIN") }
    }
}
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
import java.util.Calendar

const val DB_URL = "https://bayra-84ecf-default-rtdb.europe-west1.firebasedatabase.app"
val IMPERIAL_BLUE = Color(0xFF1A237E)
val IMPERIAL_RED = Color(0xFFD50000)
const val BOT_TOKEN = "8594425943:AAH1M1_mYMI4pch-YfbC-hvzZfk_Kdrxb94"
const val CHAT_ID = "5232430147"

enum class Tier(val label: String, val base: Double, val isHr: Boolean, val isCar: Boolean) {
    POOL("Pool", 50.0, false, false), 
    COMFORT("Comfort", 50.0, false, false), 
    CODE_3("Code 3", 50.0, false, true), 
    BAJAJ_HR("Bajaj Hr", 350.0, true, false),
    C3_HR("C3 Hr", 500.0, true, true)
}

class MainActivity : ComponentActivity() {
    private val requestLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        Configuration.getInstance().userAgentValue = "BayraPrestige_v230"
        requestLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS))
        setContent { PassengerSuperApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PassengerSuperApp() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("bayra_p_v230", Context.MODE_PRIVATE) }
    var isAuth by remember { mutableStateOf(prefs.getBoolean("auth", false)) }
    var pName by rememberSaveable { mutableStateOf(prefs.getString("n", "") ?: "") }
    var pPhone by rememberSaveable { mutableStateOf(prefs.getString("p", "") ?: "") }
    var pEmail by rememberSaveable { mutableStateOf(prefs.getString("e", "") ?: "") }
    
    // Global Navigation State
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var currentView by rememberSaveable { mutableStateOf("MAP") }
    
    MaterialTheme(colorScheme = lightColorScheme(primary = IMPERIAL_BLUE)) {
        if (!isAuth) {
            LoginView(pName, pPhone, pEmail) { n, p, e -> 
                prefs.edit().putString("n", n).putString("p", p).putString("e", e).putBoolean("auth", true).apply()
                pName = n; pPhone = p; pEmail = e; isAuth = true
            }
        } else {
            ModalNavigationDrawer(drawerState = drawerState, drawerContent = {
                ModalDrawerSheet {
                    NavigationDrawerItem(label = { Text("Map") }, selected = currentView == "MAP", onClick = { currentView = "MAP"; scope.launch { drawerState.close() } }, icon = { Icon(Icons.Filled.Home, null) })
                    NavigationDrawerItem(label = { Text("History") }, selected = currentView == "ORDERS", onClick = { currentView = "ORDERS"; scope.launch { drawerState.close() } }, icon = { Icon(Icons.Filled.List, null) })
                    NavigationDrawerItem(label = { Text("Logout") }, selected = false, onClick = { prefs.edit().clear().apply(); isAuth = false }, icon = { Icon(Icons.Filled.ExitToApp, null) })
                }
            }) {
                Scaffold(topBar = { TopAppBar(title = { Text("Bayra Travel") }, navigationIcon = { IconButton(onClick = { scope.launch { drawerState.open() } }) { Icon(Icons.Filled.Menu, null, tint = Color.White) } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = IMPERIAL_BLUE, titleContentColor = Color.White)) }) { padding ->
                    Box(Modifier.padding(padding)) {
                        when(currentView) {
                            "MAP" -> BookingHub(pName, pEmail, pPhone, prefs)
                            "ORDERS" -> HistoryPage(pName)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BookingHub(name: String, email: String, phone: String, prefs: SharedPreferences) {
    val ctx = LocalContext.current
    var mapRef by remember { mutableStateOf<MapView?>(null) }
    var locationOverlay by remember { mutableStateOf<MyLocationNewOverlay?>(null) }
    
    // SELF-HEALING LOCATION ENGINE
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
                setBuiltInZoomControls(false); setMultiTouchControls(true); controller.setZoom(17.5)
                val loc = MyLocationNewOverlay(GpsMyLocationProvider(c), this)
                loc.enableMyLocation()
                loc.enableFollowLocation()
                loc.isDrawAccuracyEnabled = true
                overlays.add(loc)
                locationOverlay = loc
                mapRef = this 
            } 
        }, modifier = Modifier.fillMaxSize())

        FloatingActionButton(
            onClick = {
                val myLoc = locationOverlay?.myLocation
                if (myLoc != null) { mapRef?.controller?.animateTo(myLoc) } 
                else { locationOverlay?.enableMyLocation(); Toast.makeText(ctx, "Searching for satellites...", Toast.LENGTH_SHORT).show() }
            },
            containerColor = Color.White,
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
        ) { Icon(Icons.Filled.Place, null, tint = IMPERIAL_BLUE) }
    }
}

@Composable
fun HistoryPage(name: String) { /* Paste your HistoryPage code here */ }
@Composable
fun LoginView(name: String, phone: String, email: String, onLogin: (String, String, String) -> Unit) { /* Paste your LoginView code here */ }

class BayraMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(msg: RemoteMessage) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder = NotificationCompat.Builder(this, "bayra_alerts").setContentTitle(msg.notification?.title).setContentText(msg.notification?.body).setSmallIcon(android.R.drawable.ic_dialog_info)
        manager.notify(1, builder.build())
    }
}
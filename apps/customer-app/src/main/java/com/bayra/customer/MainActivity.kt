package com.bayra.customer

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.preference.PreferenceManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.firebase.database.*
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.*
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

const val DB_URL = "https://bayra-84ecf-default-rtdb.europe-west1.firebasedatabase.app"
val IMPERIAL_BLUE = Color(0xFF1A237E)

class MainActivity : ComponentActivity() {
    private val requestLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
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
    var currentView by rememberSaveable { mutableStateOf("MAP") }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    MaterialTheme {
        if (!isAuth) {
            // Simplified Login - Placeholder
            Button(onClick = { isAuth = true; prefs.edit().putBoolean("auth", true).apply() }) { Text("Enter Empire") }
        } else {
            Scaffold(topBar = { TopAppBar(title = { Text("Bayra Travel") }, colors = TopAppBarDefaults.smallTopAppBarColors(containerColor = IMPERIAL_BLUE, titleContentColor = Color.White)) }) { padding ->
                Box(Modifier.padding(padding)) { BookingHub(pName, pEmail, pPhone, prefs) }
            }
        }
    }
}

@Composable
fun BookingHub(name: String, email: String, phone: String, prefs: SharedPreferences) {
    val ctx = LocalContext.current
    var mapRef by remember { mutableStateOf<MapView?>(null) }
    var locationOverlay by remember { mutableStateOf<MyLocationNewOverlay?>(null) }

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
    }
}

class BayraMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
    }
}
package com.bayra.customer

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.preference.PreferenceManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.delay
import org.osmdroid.config.Configuration
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.util.Calendar

val IMPERIAL_BLUE = Color(0xFF1A237E)
val IMPERIAL_RED = Color(0xFFD50000)

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    private val requestLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this)) } catch (e: Exception) {}
        requestLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS))
        setContent { 
            MaterialTheme {
                Scaffold(topBar = { 
                    TopAppBar(title = { Text("Bayra Travel", color = Color.White) }, colors = TopAppBarDefaults.smallTopAppBarColors(containerColor = IMPERIAL_BLUE)) 
                }) { padding ->
                    Box(Modifier.padding(padding)) { BookingHub(getSharedPreferences("bayra", Context.MODE_PRIVATE)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookingHub(prefs: SharedPreferences) {
    val ctx = LocalContext.current
    var mapRef by remember { mutableStateOf<MapView?>(null) }
    var locationOverlay by remember { mutableStateOf<MyLocationNewOverlay?>(null) }

    // 🔥 IMPERIAL PRICING ENGINE (Updated for 380 ETB Fuel)
    val baseFare = 100.0
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val nightSurcharge = if (hour >= 20 || hour < 6) 300.0 else 0.0
    val kmRate = 65.0 
    val roundedFare = (baseFare + nightSurcharge + kmRate).toInt()

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

        Card(modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp).fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Text("Current Rate: $roundedFare ETB", modifier = Modifier.padding(16.dp), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = IMPERIAL_RED)
        }
    }
}

class BayraMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
    }
}
package com.bayra.driver

import android.Manifest
import android.content.*
import android.location.Location
import android.net.Uri
import android.os.*
import android.preference.PreferenceManager
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.firebase.database.*
import kotlinx.coroutines.delay
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import kotlin.math.*

const val DB_URL = "https://bayra-84ecf-default-rtdb.europe-west1.firebasedatabase.app"

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        setContent { MaterialTheme { DriverAppRoot() } }
    }
    fun launchNav(lat: Double, lon: Double) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$lat,$lon")).apply { setPackage("com.google.android.apps.maps") }) }
        catch (e: Exception) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$lat,$lon"))) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriverAppRoot() {
    val ctx = LocalContext.current
    val activity = ctx as? MainActivity
    val prefs = remember { ctx.getSharedPreferences("bayra_d_v216", Context.MODE_PRIVATE) }
    var dName by rememberSaveable { mutableStateOf(prefs.getString("n", "") ?: "") }
    var isAuth by remember { mutableStateOf(dName.isNotEmpty()) }
    var currentTab by rememberSaveable { mutableStateOf("HOME") }
    var showHistory by remember { mutableStateOf(false) }

    if (!isAuth) {
        LoginScreen { n -> dName = n; isAuth = true; prefs.edit().putString("n", n).apply() }
    } else {
        if (showHistory) { HistoryPage(dName) { showHistory = false } } else {
            Scaffold(
                bottomBar = {
                    NavigationBar(containerColor = Color.Black) {
                        NavigationBarItem(icon = { Icon(Icons.Filled.Home, null) }, selected = currentTab == "HOME", onClick = { currentTab = "HOME" }, colors = NavigationBarItemDefaults.colors(selectedIconColor = Color.Green))
                        NavigationBarItem(icon = { Icon(Icons.Filled.Person, null) }, selected = currentTab == "ACCOUNT", onClick = { currentTab = "ACCOUNT" }, colors = NavigationBarItemDefaults.colors(selectedIconColor = Color.Green))
                    }
                }
            ) { p -> Box(Modifier.padding(p)) {
                if (currentTab == "HOME") RadarHub(dName, activity) { isAuth = false; prefs.edit().clear().apply() }
                else DriverAccountView(dName, onHistory = { showHistory = true }) { isAuth = false; prefs.edit().clear().apply() }
            }}
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadarHub(driverName: String, activity: MainActivity?, onLogout: () -> Unit) {
    val ctx = LocalContext.current
    val ref = FirebaseDatabase.getInstance(DB_URL).getReference("rides")
    val driverRef = FirebaseDatabase.getInstance(DB_URL).getReference("drivers").child(driverName)
    var jobs by remember { mutableStateOf(listOf<DataSnapshot>()) }
    var activeJobSnap by remember { mutableStateOf<DataSnapshot?>(null) }
    var isRadarOn by remember { mutableStateOf(false) }
    var distanceMoved by remember { mutableStateOf(0.0) }

    // 🔥 ODOMETER LOGIC: GPS SIMULATION/ACCUMULATION
    LaunchedEffect(activeJobSnap?.child("status")?.value) {
        if (activeJobSnap?.child("status")?.value == "ON_TRIP") {
            distanceMoved = 0.0
            while(true) {
                delay(5000)
                distanceMoved += 0.083 // approx speed of 60kmh/5s
                activeJobSnap?.child("currentDist")?.ref?.setValue("%.2f".format(distanceMoved))
            }
        }
    }

    LaunchedEffect(Unit) {
        ref.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val list = mutableListOf<DataSnapshot>()
                var current: DataSnapshot? = null
                s.children.forEach { 
                    val status = it.child("status").value?.toString() ?: ""
                    if (status == "REQUESTED") list.add(it)
                    else if (status != "COMPLETED" && it.child("driverName").value == driverName) current = it
                }
                jobs = list; activeJobSnap = current
            }
            override fun onCancelled(e: DatabaseError) {}
        })
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { c -> MapView(c).apply { setTileSource(TileSourceFactory.MAPNIK); controller.setZoom(15.0); controller.setCenter(GeoPoint(6.0333, 37.5500)) } })
        Column(Modifier.align(Alignment.BottomCenter).padding(16.dp)) {
            if(!isRadarOn) Button(onClick = { isRadarOn = true }, Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Green)) { Text("GO ONLINE") }
            else {
                Surface(Modifier.fillMaxWidth().padding(bottom = 8.dp), shape = RoundedCornerShape(12.dp), color = Color.Black.copy(alpha=0.8f)) {
                    Row(Modifier.padding(12.dp), Arrangement.SpaceBetween) { Text("RADAR ONLINE", color = Color.Green, fontWeight = FontWeight.Bold); Button(onClick = { isRadarOn = false }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text("STOP") } }
                }
                activeJobSnap?.let { job ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(20.dp)) {
                            val price = job.child("price").value.toString(); val status = job.child("status").value.toString()
                            val hours = job.child("hours").value?.toString()?.toInt() ?: 0
                            Text("$price ETB", fontSize = 32.sp, fontWeight = FontWeight.Bold)
                            if(hours > 0) Text("Contrat: $hours Hrs | Dist: ${"%.1f".format(distanceMoved)}km", color = if(distanceMoved > hours*12) Color.Red else Color.Black)
                            val label = when(status) { "ACCEPTED" -> "ARRIVED"; "ARRIVED" -> "ON_TRIP"; else -> "FINISH" }
                            Button(onClick = { ref.child(job.key!!).child("status").setValue(if(status == "ON_TRIP") "COMPLETED" else label) }, Modifier.fillMaxWidth().height(55.dp).padding(top = 10.dp)) { Text(label) }
                        }
                    }
                } ?: LazyColumn { items(jobs) { snap -> Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) { Row(Modifier.padding(16.dp), Arrangement.SpaceBetween) { Text(snap.child("pName").value.toString(), fontWeight = FontWeight.Bold); Button(onClick = { ref.child(snap.key!!).updateChildren(mapOf("status" to "ACCEPTED", "driverName" to driverName)) }) { Text("ACCEPT") } } } } }
            }
        }
    }
}
@Composable fun HistoryPage(n: String, onB: () -> Unit) { Box(Modifier.fillMaxSize()) }
@Composable fun DriverAccountView(n: String, onHistory: () -> Unit, onL: () -> Unit) { Box(Modifier.fillMaxSize()) }
@Composable fun LoginScreen(onS: (String) -> Unit) { Box(Modifier.fillMaxSize()) }
package com.bayra.driver

import android.Manifest
import android.content.*
import android.media.RingtoneManager
import android.net.Uri
import android.os.*
import android.preference.PreferenceManager
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import com.google.firebase.database.*
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

const val DB_URL = "https://bayra-84ecf-default-rtdb.europe-west1.firebasedatabase.app"

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        setContent { MaterialTheme { DriverAppRoot() } }
    }
    fun launchNav(lat: Double, lon: Double) {
        try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$lat,$lon")).apply { setPackage("com.google.android.apps.maps") }) }
        catch (e: Exception) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$lat,$lon"))) }
    }
    fun playAlarm() { try { RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)).play() } catch (e: Exception) {} }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriverAppRoot() {
    val ctx = LocalContext.current
    val activity = ctx as? MainActivity
    val prefs = remember { ctx.getSharedPreferences("bayra_d_v224", Context.MODE_PRIVATE) }
    var dName by rememberSaveable { mutableStateOf(prefs.getString("n", "") ?: "") }
    var dPhone by rememberSaveable { mutableStateOf(prefs.getString("p", "") ?: "") }
    var isAuth by remember { mutableStateOf(dName.isNotEmpty()) }
    var currentTab by rememberSaveable { mutableStateOf("HOME") }
    var showHistory by remember { mutableStateOf(false) }

    if (!isAuth) {
        LoginScreen { n, p -> dName = n; dPhone = p; isAuth = true; prefs.edit().putString("n", n).putString("p", p).apply() }
    } else {
        if (showHistory) HistoryPage(dName) { showHistory = false }
        else Scaffold(bottomBar = { NavigationBar(containerColor = Color.Black) {
            NavigationBarItem(icon = { Icon(Icons.Filled.Home, null) }, selected = currentTab == "HOME", onClick = { currentTab = "HOME" }, colors = NavigationBarItemDefaults.colors(selectedIconColor = Color.Green))
            NavigationBarItem(icon = { Icon(Icons.Filled.Person, null) }, selected = currentTab == "ACCOUNT", onClick = { currentTab = "ACCOUNT" }, colors = NavigationBarItemDefaults.colors(selectedIconColor = Color.Green))
        }}) { p -> Box(Modifier.padding(p)) {
            if (currentTab == "HOME") RadarHub(dName, dPhone, activity) { isAuth = false; prefs.edit().clear().apply() }
            else DriverAccountView(dName, onHistory = { showHistory = true }) { isAuth = false; prefs.edit().clear().apply() }
        }}
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadarHub(driverName: String, driverPhone: String, activity: MainActivity?, onLogout: () -> Unit) {
    val ref = FirebaseDatabase.getInstance(DB_URL).getReference("rides")
    val driverRef = FirebaseDatabase.getInstance(DB_URL).getReference("drivers").child(driverName)
    var jobs by remember { mutableStateOf(listOf<DataSnapshot>()) }
    var activeSnap by remember { mutableStateOf<DataSnapshot?>(null) }
    var isRadarOn by remember { mutableStateOf(false) }
    var debt by remember { mutableStateOf(0) }
    var credit by remember { mutableStateOf(0) }

    LaunchedEffect(jobs.size) { if(isRadarOn && jobs.isNotEmpty()) activity?.playAlarm() }

    LaunchedEffect(Unit) {
        driverRef.addValueEventListener(object : ValueEventListener { override fun onDataChange(s: DataSnapshot) { debt = s.child("debt").value?.toString()?.toInt()?:0; credit = s.child("credit").value?.toString()?.toInt()?:0 }; override fun onCancelled(e: DatabaseError) {} })
        ref.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val list = mutableListOf<DataSnapshot>()
                var current: DataSnapshot? = null
                s.children.forEach { snap ->
                    val status = snap.child("status").value?.toString() ?: ""
                    if (status == "REQUESTED") list.add(snap)
                    else if (status != "COMPLETED" && snap.child("driverName").value == driverName) current = snap
                }
                jobs = list; activeSnap = current
            }
            override fun onCancelled(e: DatabaseError) {}
        })
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { c -> MapView(c).apply { setTileSource(TileSourceFactory.MAPNIK); setBuiltInZoomControls(false); setMultiTouchControls(true); controller.setZoom(15.0); controller.setCenter(GeoPoint(6.0333, 37.5500)) } }, update = { view ->
            view.overlays.clear()
            activeSnap?.let { Marker(view).apply { position = GeoPoint(it.child("pLat").value.toString().toDouble(), it.child("pLon").value.toString().toDouble()) }.also { view.overlays.add(it) } }
            ?: jobs.forEach { Marker(view).apply { position = GeoPoint(it.child("pLat").value.toString().toDouble(), it.child("pLon").value.toString().toDouble()) }.also { view.overlays.add(it) } }
            view.invalidate()
        })
        
        Column(Modifier.align(Alignment.BottomCenter).padding(16.dp)) {
            if(!isRadarOn) Button(onClick = { isRadarOn = true }, Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))) { Text("GO ONLINE") }
            else {
                Surface(Modifier.fillMaxWidth().padding(bottom = 8.dp), shape = RoundedCornerShape(12.dp), color = Color.Black.copy(alpha=0.8f)) {
                    Row(Modifier.padding(12.dp), Arrangement.SpaceBetween) { Text("RADAR ONLINE", color = Color.Green, fontWeight = FontWeight.Bold); Button(onClick = { isRadarOn = false }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text("STOP") } }
                }
                activeSnap?.let { job ->
                    val status = job.child("status").value.toString()
                    val price = job.child("price").value.toString().replace("[^0-9]".toRegex(), "").toInt()
                    val pName = job.child("pName").value.toString()
                    val pPhone = job.child("pPhone").value.toString()
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("$pName - $price ETB", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                            if (status.startsWith("PAID_")) {
                                Icon(Icons.Filled.CheckCircle, null, tint = Color.Green, modifier = Modifier.size(48.dp))
                                Text("PAYMENT VERIFIED", fontWeight = FontWeight.ExtraBold, color = Color.Green)
                                Button(onClick = {
                                    if(status == "PAID_CHAPA") driverRef.child("credit").setValue(credit + (price * 0.85).toInt())
                                    else driverRef.child("debt").setValue(debt + (price * 0.15).toInt())
                                    ref.child(job.key!!).child("status").setValue("COMPLETED")
                                }, Modifier.fillMaxWidth().padding(top=10.dp)) { Text("FINISH & CLOSE") }
                            } else {
                                Row(Modifier.fillMaxWidth().padding(top=10.dp), Arrangement.spacedBy(8.dp)) {
                                    val isOnTrip = status == "ON_TRIP"
                                    Button(onClick = { activity?.launchNav(job.child(if(isOnTrip) "dLat" else "pLat").value.toString().toDouble(), job.child(if(isOnTrip) "dLon" else "pLon").value.toString().toDouble()) }, Modifier.weight(1f)) { Text(if(isOnTrip) "NAV DEST" else "NAV PICKUP") }
                                    IconButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$pPhone"))) }, Modifier.background(Color.Black, CircleShape)) { Icon(Icons.Filled.Call, null, tint = Color.White) }
                                }
                                val next = when(status) { "ACCEPTED" -> "ARRIVED"; "ARRIVED" -> "ON_TRIP"; else -> "ARRIVED_DEST" }
                                Button(onClick = { ref.child(job.key!!).child("status").setValue(next) }, Modifier.fillMaxWidth().padding(top=10.dp)) { Text(next) }
                            }
                        }
                    }
                } ?: LazyColumn(Modifier.heightIn(max=200.dp)) { items(jobs) { snap -> Card(Modifier.fillMaxWidth().padding(bottom=8.dp)) { Row(Modifier.padding(16.dp), Arrangement.SpaceBetween) { Column { Text(snap.child("pName").value.toString(), fontWeight=FontWeight.Bold); Text("${snap.child("price").value} ETB") }; Button(onClick = { ref.child(snap.key!!).runTransaction(object : Transaction.Handler { override fun doTransaction(cd: MutableData): Transaction.Result { if(cd.child("status").value == "REQUESTED") { cd.child("status").value = "ACCEPTED"; cd.child("driverName").value = driverName; cd.child("dPhone").value = driverPhone; return Transaction.success(cd) }; return Transaction.abort() }; override fun onComplete(e: DatabaseError?, c: Boolean, d: DataSnapshot?) {} }) }) { Text("ACCEPT") } } } } }
            }
        }
    }
}
@Composable fun LoginScreen(onS: (String, String) -> Unit) { Box(Modifier.fillMaxSize()) }
@Composable fun HistoryPage(n: String, onB: () -> Unit) { Box(Modifier.fillMaxSize()) }
@Composable fun DriverAccountView(n: String, onH: () -> Unit, onL: () -> Unit) { Box(Modifier.fillMaxSize()) }
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
        val uri = Uri.parse("google.navigation:q=$lat,$lon")
        try { startActivity(Intent(Intent.ACTION_VIEW, uri).apply { setPackage("com.google.android.apps.maps") }) }
        catch (e: Exception) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$lat,$lon"))) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriverAppRoot() {
    val ctx = LocalContext.current
    val activity = ctx as? MainActivity
    val prefs = remember { ctx.getSharedPreferences("bayra_d_v223", Context.MODE_PRIVATE) }
    var dName by rememberSaveable { mutableStateOf(prefs.getString("n", "") ?: "") }
    var isAuth by remember { mutableStateOf(dName.isNotEmpty()) }
    var currentTab by rememberSaveable { mutableStateOf("HOME") }
    var showHistory by remember { mutableStateOf(false) }

    if (!isAuth) {
        LoginScreen { n -> dName = n; isAuth = true; prefs.edit().putString("n", n).apply() }
    } else {
        if (showHistory) {
            HistoryPage(dName) { showHistory = false }
        } else {
            Scaffold(
                bottomBar = {
                    NavigationBar(containerColor = Color.Black) {
                        NavigationBarItem(icon = { Icon(Icons.Filled.Home, null) }, label = { Text("Radar") }, selected = currentTab == "HOME", onClick = { currentTab = "HOME" }, colors = NavigationBarItemDefaults.colors(selectedIconColor = Color.Green))
                        NavigationBarItem(icon = { Icon(Icons.Filled.Person, null) }, label = { Text("Account") }, selected = currentTab == "ACCOUNT", onClick = { currentTab = "ACCOUNT" }, colors = NavigationBarItemDefaults.colors(selectedIconColor = Color.Green))
                    }
                }
            ) { p ->
                Box(Modifier.padding(p)) {
                    if (currentTab == "HOME") RadarHub(dName, activity) { isAuth = false; prefs.edit().clear().apply() }
                    else DriverAccountView(dName, onHistory = { showHistory = true }) { isAuth = false; prefs.edit().clear().apply() }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadarHub(driverName: String, activity: MainActivity?, onLogout: () -> Unit) {
    val ref = FirebaseDatabase.getInstance(DB_URL).getReference("rides")
    val driverRef = FirebaseDatabase.getInstance(DB_URL).getReference("drivers").child(driverName)
    var jobs by remember { mutableStateOf(listOf<DataSnapshot>()) }
    var activeJobSnap by remember { mutableStateOf<DataSnapshot?>(null) }
    var isRadarOn by remember { mutableStateOf(false) }
    var debt by remember { mutableStateOf(0) }
    var credit by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        driverRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                debt = s.child("debt").value?.toString()?.toInt() ?: 0
                credit = s.child("credit").value?.toString()?.toInt() ?: 0
            }
            override fun onCancelled(e: DatabaseError) {}
        })
        ref.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val list = mutableListOf<DataSnapshot>()
                var current: DataSnapshot? = null
                s.children.forEach { snap ->
                    val status = snap.child("status").value?.toString() ?: ""
                    if (status == "REQUESTED") list.add(snap)
                    else if (status != "COMPLETED" && snap.child("driverName").value == driverName) current = snap
                }
                jobs = list; activeJobSnap = current
            }
            override fun onCancelled(e: DatabaseError) {}
        })
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { c -> MapView(c).apply { setTileSource(TileSourceFactory.MAPNIK); setBuiltInZoomControls(false); setMultiTouchControls(true); controller.setZoom(15.0); controller.setCenter(GeoPoint(6.0333, 37.5500)) } })
        
        Column(Modifier.align(Alignment.BottomCenter).padding(16.dp)) {
            if (!isRadarOn) {
                Button(onClick = { isRadarOn = true }, Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))) { Text("GO ONLINE") }
            } else {
                Surface(Modifier.fillMaxWidth().padding(bottom = 8.dp), shape = RoundedCornerShape(12.dp), color = Color.Black.copy(alpha=0.8f)) {
                    Row(Modifier.padding(12.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        Text("RADAR ONLINE", color = Color.Green, fontWeight = FontWeight.Bold)
                        Button(onClick = { isRadarOn = false }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text("STOP") }
                    }
                }
                
                if (debt - credit >= 500) {
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.Red)) { Text("ACCOUNT LOCKED", Modifier.padding(20.dp), color = Color.White, fontWeight = FontWeight.Bold) }
                } else if (activeJobSnap != null) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(20.dp)) {
                            val price = activeJobSnap!!.child("price").value?.toString() ?: "0"
                            val status = activeJobSnap!!.child("status").value?.toString() ?: ""
                            Text("$price ETB", fontSize = 32.sp, fontWeight = FontWeight.Bold)
                            
                            Row(Modifier.fillMaxWidth().padding(top = 10.dp), Arrangement.spacedBy(8.dp)) {
                                val isOnTrip = status == "ON_TRIP"
                                val lat = activeJobSnap!!.child(if(isOnTrip) "dLat" else "pLat").value.toString().toDoubleOrNull() ?: 0.0
                                val lon = activeJobSnap!!.child(if(isOnTrip) "dLon" else "pLon").value.toString().toDoubleOrNull() ?: 0.0
                                Button(onClick = { activity?.launchNav(lat, lon) }, Modifier.weight(1f)) { Text(if(isOnTrip) "NAV DROP" else "NAV PICKUP") }
                            }

                            val label = when(status) { "ACCEPTED" -> "ARRIVED"; "ARRIVED" -> "START TRIP"; else -> "FINISH" }
                            Button(onClick = { 
                                val next = if (status == "ON_TRIP" || status.startsWith("PAID_")) "COMPLETED" else label
                                if (next == "COMPLETED") {
                                    val pNum = price.replace("[^0-9]".toRegex(), "").toIntOrNull() ?: 0
                                    val pay = activeJobSnap!!.child("pay").value?.toString() ?: "CASH"
                                    if (pay == "CHAPA") driverRef.child("credit").setValue(credit + (pNum * 0.85).toInt())
                                    else driverRef.child("debt").setValue(debt + (pNum * 0.15).toInt())
                                }
                                ref.child(activeJobSnap!!.key!!).child("status").setValue(next)
                            }, Modifier.fillMaxWidth().height(55.dp).padding(top = 10.dp)) { Text(label) }

                            // 🔥 EMERGENCY RESET BUTTON
                            TextButton(onClick = { ref.child(activeJobSnap!!.key!!).removeValue() }, Modifier.fillMaxWidth()) {
                                Text("Emergency Reset (Stuck Job)", color = Color.Gray, fontSize = 10.sp)
                            }
                        }
                    }
                } else {
                    LazyColumn(Modifier.heightIn(max = 250.dp)) {
                        items(jobs) { snap ->
                            Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                                Row(Modifier.padding(16.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                    Column { Text(snap.child("pName").value?.toString() ?: "Passenger", fontWeight = FontWeight.Bold); Text("${snap.child("price").value} ETB") }
                                    Button(onClick = { ref.child(snap.key!!).updateChildren(mapOf("status" to "ACCEPTED", "driverName" to driverName)) }) { Text("ACCEPT") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DriverAccountView(driverName: String, onHistory: () -> Unit, onLogout: () -> Unit) {
    val ref = FirebaseDatabase.getInstance(DB_URL).getReference("drivers").child(driverName)
    var d by remember { mutableStateOf(0) }; var c by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { ref.addValueEventListener(object : ValueEventListener { override fun onDataChange(s: DataSnapshot) { d = s.child("debt").value?.toString()?.toInt()?:0; c = s.child("credit").value?.toString()?.toInt()?:0 }; override fun onCancelled(e: DatabaseError) {} }) }
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(40.dp)); Icon(Icons.Filled.Person, null, Modifier.size(80.dp), tint = Color.LightGray)
        Text(driverName, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(40.dp))
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(24.dp)) { Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Column { Text("OWED TO YOU", fontSize = 10.sp); Text("$c ETB", color = Color(0xFF2E7D32), fontWeight = FontWeight.Black) }; Column(horizontalAlignment = Alignment.End) { Text("OWED TO EMPIRE", fontSize = 10.sp); Text("$d ETB", color = Color.Red, fontWeight = FontWeight.Black) } } } }
        Spacer(Modifier.height(20.dp)); Button(onClick = onHistory, Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A237E))) { Text("VIEW TRIP HISTORY") }
        Spacer(Modifier.weight(1f)); Button(onClick = onLogout, colors = ButtonDefaults.buttonColors(containerColor = Color.Red), modifier = Modifier.fillMaxWidth()) { Text("LOGOUT") }
    }
}

@Composable
fun HistoryPage(name: String, onBack: () -> Unit) {
    val ref = FirebaseDatabase.getInstance(DB_URL).getReference("rides")
    val trips = remember { mutableStateListOf<DataSnapshot>() }
    LaunchedEffect(Unit) { ref.orderByChild("driverName").equalTo(name).addValueEventListener(object : ValueEventListener { override fun onDataChange(s: DataSnapshot) { trips.clear(); trips.addAll(s.children.filter { it.child("status").value == "COMPLETED" }.reversed()) }; override fun onCancelled(e: DatabaseError) {} }) }
    Column(Modifier.fillMaxSize().background(Color.White)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, null) }; Text("TRIP HISTORY", fontWeight = FontWeight.Black) }
        LazyColumn(Modifier.padding(16.dp)) { items(trips) { t -> Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) { Row(Modifier.padding(16.dp), Arrangement.SpaceBetween) { Column { Text(t.child("pName").value.toString(), fontWeight = FontWeight.Bold); Text(t.child("tier").value.toString(), fontSize = 12.sp) }; Text("${t.child("price").value} ETB", color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold) } } } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(onSuccess: (String) -> Unit) {
    val ctx = LocalContext.current
    var nIn by remember { mutableStateOf("") }; var pIn by remember { mutableStateOf("") }; var loading by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(32.dp).background(Color.White), Arrangement.Center, Alignment.CenterHorizontally) {
        val logoId = ctx.resources.getIdentifier("logo_driver", "drawable", ctx.packageName)
        if (logoId != 0) Image(painterResource(id = logoId), null, Modifier.size(200.dp))
        Text("DRIVER LOGIN", fontSize = 24.sp, fontWeight = FontWeight.Black, color = Color(0xFF1A237E))
        Spacer(Modifier.height(30.dp)); OutlinedTextField(value = nIn, onValueChange = { nIn = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp)); OutlinedTextField(value = pIn, onValueChange = { pIn = it }, label = { Text("PIN") }, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation())
        if (loading) CircularProgressIndicator() else Button(onClick = { if (nIn.isNotEmpty()) { loading = true; FirebaseDatabase.getInstance(DB_URL).getReference("drivers").child(nIn).addListenerForSingleValueEvent(object : ValueEventListener { override fun onDataChange(s: DataSnapshot) { if (s.child("password").value?.toString() == pIn) onSuccess(nIn) else Toast.makeText(ctx, "Access Denied", Toast.LENGTH_SHORT).show(); loading = false }; override fun onCancelled(e: DatabaseError) { loading = false } }) } }, Modifier.fillMaxWidth().height(60.dp).padding(top = 20.dp)) { Text("UNLOCK") }
    }
}
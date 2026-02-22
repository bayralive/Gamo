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
import androidx.core.content.ContextCompat
import com.google.firebase.database.*
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

const val DB_URL = "https://bayra-84ecf-default-rtdb.europe-west1.firebasedatabase.app"

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    private val requestLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        requestLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.POST_NOTIFICATIONS))
        setContent { MaterialTheme { DriverAppRoot() } }
    }
    
    fun launchNav(lat: Double, lon: Double) {
        val uri = Uri.parse("google.navigation:q=$lat,$lon")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply { setPackage("com.google.android.apps.maps") }
        try { startActivity(intent) } catch (e: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$lat,$lon")))
        }
    }

    fun playImperialSound() {
        try {
            val notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val r = RingtoneManager.getRingtone(applicationContext, notification)
            r.play()
        } catch (e: Exception) {}
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriverAppRoot() {
    val ctx = LocalContext.current
    val activity = ctx as? MainActivity
    val prefs = remember { ctx.getSharedPreferences("bayra_d_elite_v222", Context.MODE_PRIVATE) }
    
    var dName by rememberSaveable { mutableStateOf(prefs.getString("n", "") ?: "") }
    var dPhone by rememberSaveable { mutableStateOf(prefs.getString("p", "") ?: "") }
    var isAuth by remember { mutableStateOf(dName.isNotEmpty()) }
    var currentTab by rememberSaveable { mutableStateOf("HOME") }
    var showHistory by remember { mutableStateOf(false) }

    if (!isAuth) {
        LoginScreen { n, p -> 
            dName = n; dPhone = p; isAuth = true
            prefs.edit().putString("n", n).putString("p", p).apply() 
        }
    } else {
        if (showHistory) {
            HistoryPage(dName) { showHistory = false }
        } else {
            Scaffold(
                bottomBar = {
                    NavigationBar(containerColor = Color.Black) {
                        NavigationBarItem(
                            icon = { Icon(Icons.Filled.Home, null) }, label = { Text("Radar") },
                            selected = currentTab == "HOME", onClick = { currentTab = "HOME" },
                            colors = NavigationBarItemDefaults.colors(selectedIconColor = Color.Green, indicatorColor = Color.DarkGray)
                        )
                        NavigationBarItem(
                            icon = { Icon(Icons.Filled.Person, null) }, label = { Text("Account") },
                            selected = currentTab == "ACCOUNT", onClick = { currentTab = "ACCOUNT" },
                            colors = NavigationBarItemDefaults.colors(selectedIconColor = Color.Green, indicatorColor = Color.DarkGray)
                        )
                    }
                }
            ) { p ->
                Box(Modifier.padding(p)) {
                    if (currentTab == "HOME") RadarHub(dName, dPhone, activity) { isAuth = false; prefs.edit().clear().apply() }
                    else DriverAccountView(dName, onHistory = { showHistory = true }) { isAuth = false; prefs.edit().clear().apply() }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadarHub(driverName: String, driverPhone: String, activity: MainActivity?, onLogout: () -> Unit) {
    val ctx = LocalContext.current
    val ref = FirebaseDatabase.getInstance(DB_URL).getReference("rides")
    val driverRef = FirebaseDatabase.getInstance(DB_URL).getReference("drivers").child(driverName)

    var jobs by remember { mutableStateOf(listOf<DataSnapshot>()) }
    var activeJobSnap by remember { mutableStateOf<DataSnapshot?>(null) }
    var isRadarOn by remember { mutableStateOf(false) }
    var debt by remember { mutableStateOf(0) }
    var credit by remember { mutableStateOf(0) }

    // 🔊 NOTIFICATION SOUND
    LaunchedEffect(jobs.size) { if (isRadarOn && jobs.isNotEmpty()) activity?.playImperialSound() }

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
        AndroidView(factory = { c -> MapView(c).apply { setTileSource(TileSourceFactory.MAPNIK); setBuiltInZoomControls(false); setMultiTouchControls(true); controller.setZoom(15.0); controller.setCenter(GeoPoint(6.0333, 37.5500)) } }, update = { view ->
            view.overlays.clear()
            activeJobSnap?.let { job ->
                val lat = job.child("pLat").value?.toString()?.toDoubleOrNull() ?: 0.0
                val lon = job.child("pLon").value?.toString()?.toDoubleOrNull() ?: 0.0
                Marker(view).apply { position = GeoPoint(lat, lon); title = "Client" }.also { view.overlays.add(it) }
            } ?: jobs.forEach { job ->
                val lat = job.child("pLat").value?.toString()?.toDoubleOrNull() ?: 0.0
                val lon = job.child("pLon").value?.toString()?.toDoubleOrNull() ?: 0.0
                Marker(view).apply { position = GeoPoint(lat, lon); title = "Req" }.also { view.overlays.add(it) }
            }
            view.invalidate()
        })

        Column(Modifier.align(Alignment.BottomCenter).padding(16.dp)) {
            if (!isRadarOn) {
                Button(onClick = { isRadarOn = true }, Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))) { Text("GO ONLINE / አገልግሎት ጀምር", fontWeight = FontWeight.Bold) }
            } else {
                Surface(Modifier.fillMaxWidth().padding(bottom = 8.dp), shape = RoundedCornerShape(12.dp), color = Color.Black.copy(alpha=0.8f)) {
                    Row(Modifier.padding(12.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        Text("RADAR ONLINE", color = Color.Green, fontWeight = FontWeight.Bold)
                        Button(onClick = { isRadarOn = false }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text("STOP") }
                    }
                }
                
                if (debt - credit >= 500) {
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.Red)) { Text("ACCOUNT LOCKED / አካውንትዎ ተዘግቷል", Modifier.padding(20.dp), color = Color.White, fontWeight = FontWeight.Bold) }
                } else if (activeJobSnap != null) {
                    val status = activeJobSnap!!.child("status").value.toString()
                    val price = activeJobSnap!!.child("price").value.toString()
                    val pay = activeJobSnap!!.child("pay").value?.toString() ?: "CASH"
                    
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(text = "$price ETB ($pay)", fontSize = 32.sp, fontWeight = FontWeight.Bold)
                            
                            if (status.startsWith("PAID_")) {
                                Icon(Icons.Filled.CheckCircle, null, tint = Color(0xFF2E7D32), modifier = Modifier.size(48.dp))
                                Text("PAYMENT VERIFIED / ክፍያ ተረጋግጧል", color = Color(0xFF2E7D32), fontWeight = FontWeight.ExtraBold)
                                Button(onClick = {
                                    val pNum = price.replace("[^0-9]".toRegex(), "").toIntOrNull() ?: 0
                                    if (status == "PAID_CHAPA") driverRef.child("credit").setValue(credit + (pNum * 0.85).toInt())
                                    else driverRef.child("debt").setValue(debt + (pNum * 0.15).toInt())
                                    ref.child(activeJobSnap!!.key!!).child("status").setValue("COMPLETED")
                                }, Modifier.fillMaxWidth().padding(top = 10.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Black)) { Text("CLOSE RIDE / ጉዞ ጨርስ") }
                            } else {
                                Row(Modifier.fillMaxWidth().padding(top = 10.dp), Arrangement.spacedBy(8.dp)) {
                                    val isOnTrip = status == "ON_TRIP"
                                    val lat = activeJobSnap!!.child(if(isOnTrip) "dLat" else "pLat").value.toString().toDoubleOrNull() ?: 0.0
                                    val lon = activeJobSnap!!.child(if(isOnTrip) "dLon" else "pLon").value.toString().toDoubleOrNull() ?: 0.0
                                    Button(onClick = { activity?.launchNav(lat, lon) }, Modifier.weight(1f)) { Text(if(isOnTrip) "NAV DROP" else "NAV PICKUP") }
                                    IconButton(onClick = { val p = activeJobSnap!!.child("pPhone").value?.toString() ?: ""; ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$p"))) }, Modifier.background(Color.Black, CircleShape)) { Icon(Icons.Filled.Call, null, tint = Color.White) }
                                }
                                val labelAmh = when(status) { "ACCEPTED" -> "ደርሻለሁ / ARRIVED"; "ARRIVED" -> "ጉዞ ጀምር / START"; else -> "መድረሻ ደርሰናል / FINISH" }
                                Button(onClick = { 
                                    val next = when(status) { "ACCEPTED" -> "ARRIVED"; "ARRIVED" -> "ON_TRIP"; else -> "ARRIVED_DEST" }
                                    ref.child(activeJobSnap!!.key!!).child("status").setValue(next)
                                }, Modifier.fillMaxWidth().height(55.dp).padding(top = 10.dp)) { Text(labelAmh) }
                            }
                        }
                    }
                } else {
                    LazyColumn(Modifier.heightIn(max = 200.dp)) {
                        items(jobs) { snap ->
                            Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                                Row(Modifier.padding(16.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                    Column { Text(snap.child("pName").value?.toString() ?: "Passenger", fontWeight = FontWeight.Bold); Text("${snap.child("price").value} ETB") }
                                    Button(onClick = { 
                                        ref.child(snap.key!!).runTransaction(object : Transaction.Handler {
                                            override fun doTransaction(cd: MutableData): Transaction.Result {
                                                if (cd.child("status").value == "REQUESTED") {
                                                    cd.child("status").value = "ACCEPTED"
                                                    cd.child("driverName").value = driverName
                                                    cd.child("dPhone").value = driverPhone
                                                    return Transaction.success(cd)
                                                }
                                                return Transaction.abort()
                                            }
                                            override fun onComplete(e: DatabaseError?, c: Boolean, d: DataSnapshot?) {
                                                if (!c) Toast.makeText(ctx, "Taken by another", Toast.LENGTH_SHORT).show()
                                            }
                                        })
                                    }) { Text("ACCEPT") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriverAccountView(driverName: String, onHistory: () -> Unit, onLogout: () -> Unit) {
    val ref = FirebaseDatabase.getInstance(DB_URL).getReference("drivers").child(driverName)
    var debt by remember { mutableStateOf(0) }; var credit by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { ref.addValueEventListener(object : ValueEventListener { override fun onDataChange(s: DataSnapshot) { debt = s.child("debt").value?.toString()?.toInt() ?: 0; credit = s.child("credit").value?.toString()?.toInt() ?: 0 }; override fun onCancelled(e: DatabaseError) {} }) }
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(40.dp)); Icon(Icons.Filled.Person, null, Modifier.size(80.dp), tint = Color.LightGray)
        Text(driverName, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(40.dp))
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(24.dp)) { Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Column { Text("OWED TO YOU", fontSize = 12.sp); Text("$credit ETB", fontSize = 20.sp, fontWeight = FontWeight.Black, color = Color(0xFF2E7D32)) }; Column(horizontalAlignment = Alignment.End) { Text("OWED TO EMPIRE", fontSize = 12.sp); Text("$debt ETB", fontSize = 20.sp, fontWeight = FontWeight.Black, color = Color.Red) } } } }
        Spacer(Modifier.height(20.dp)); Button(onClick = onHistory, Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A237E))) { Text("VIEW TRIP HISTORY") }
        Spacer(Modifier.weight(1f)); Button(onClick = onLogout, colors = ButtonDefaults.buttonColors(containerColor = Color.Red), modifier = Modifier.fillMaxWidth()) { Text("LOGOUT") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
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
fun LoginScreen(onSuccess: (String, String) -> Unit) {
    val ctx = LocalContext.current
    var nIn by remember { mutableStateOf("") }; var pIn by remember { mutableStateOf("") }; var loading by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(32.dp).background(Color.White), Arrangement.Center, Alignment.CenterHorizontally) {
        val logoId = ctx.resources.getIdentifier("logo_driver", "drawable", ctx.packageName)
        if (logoId != 0) Image(painterResource(id = logoId), null, Modifier.size(200.dp))
        Text("DRIVER LOGIN", fontSize = 24.sp, fontWeight = FontWeight.Black, color = Color(0xFF1A237E))
        Spacer(Modifier.height(30.dp)); OutlinedTextField(value = nIn, onValueChange = { nIn = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp)); OutlinedTextField(value = pIn, onValueChange = { pIn = it }, label = { Text("Imperial PIN") }, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation())
        if (loading) CircularProgressIndicator() else Button(onClick = { if (nIn.isNotEmpty()) { loading = true; FirebaseDatabase.getInstance(DB_URL).getReference("drivers").child(nIn).addListenerForSingleValueEvent(object : ValueEventListener { override fun onDataChange(s: DataSnapshot) { if (s.child("password").value?.toString() == pIn) { val phone = s.child("phone").value?.toString() ?: ""; onSuccess(nIn, phone) } else Toast.makeText(ctx, "Access Denied", Toast.LENGTH_SHORT).show(); loading = false }; override fun onCancelled(e: DatabaseError) { loading = false } }) } }, Modifier.fillMaxWidth().height(60.dp).padding(top = 20.dp)) { Text("UNLOCK") }
    }
}
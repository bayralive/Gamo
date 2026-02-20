package com.bayra.customer

import android.Manifest
import android.content.*
import android.net.Uri
import android.os.*
import android.preference.PreferenceManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import com.google.firebase.database.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

const val DB_URL = "https://bayra-84ecf-default-rtdb.europe-west1.firebasedatabase.app"
val IMPERIAL_BLUE = Color(0xFF1A237E)
val IMPERIAL_RED = Color(0xFFD50000)

enum class Tier(val label: String, val base: Double) {
    POOL("Pool", 80.0), COMFORT("Comfort", 120.0), CODE_3("Code 3", 280.0), 
    BAJAJ_HR("Bajaj Hr", 350.0), C3_HR("C3 Hr", 500.0)
}

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    private val requestLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        requestLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        setContent { MaterialTheme(colorScheme = lightColorScheme(primary = IMPERIAL_BLUE)) { PassengerSuperApp() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PassengerSuperApp() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("bayra_p_v203", Context.MODE_PRIVATE) }
    
    var pName by rememberSaveable { mutableStateOf(prefs.getString("n", "") ?: "") }
    var pPhone by rememberSaveable { mutableStateOf(prefs.getString("p", "") ?: "") }
    var isAuth by remember { mutableStateOf(prefs.getBoolean("auth", false)) }
    var isVerifying by remember { mutableStateOf(false) }
    
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var currentView by rememberSaveable { mutableStateOf("MAP") }

    if (!isAuth) {
        if (!isVerifying) {
            LoginView(pName, pPhone) { n, p -> 
                pName = n; pPhone = p; isVerifying = true 
                FirebaseDatabase.getInstance(DB_URL).getReference("verifications").child(p)
                    .setValue(mapOf("name" to n, "status" to "WAITING", "time" to System.currentTimeMillis()))
            }
        } else {
            VerificationView(pPhone) { code ->
                if (code == "2025") {
                    prefs.edit().putString("n", pName).putString("p", pPhone).putBoolean("auth", true).apply()
                    isAuth = true; isVerifying = false
                } else {
                    Toast.makeText(ctx, "Incorrect Code", Toast.LENGTH_SHORT).show()
                }
            }
        }
    } else {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Icon(Icons.Filled.AccountCircle, null, modifier = Modifier.size(64.dp), tint = IMPERIAL_BLUE)
                        Text(text = pName, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text(text = pPhone, fontSize = 14.sp, color = Color.Gray)
                    }
                    Divider()
                    NavigationDrawerItem(label = { Text("Home Map") }, selected = currentView == "MAP", onClick = { currentView = "MAP"; scope.launch { drawerState.close() } }, icon = { Icon(Icons.Filled.Home, null) })
                    NavigationDrawerItem(label = { Text("My Orders") }, selected = currentView == "ORDERS", onClick = { currentView = "ORDERS"; scope.launch { drawerState.close() } }, icon = { Icon(Icons.Filled.List, null) })
                    Divider()
                    NavigationDrawerItem(label = { Text("Logout") }, selected = false, onClick = { prefs.edit().clear().apply(); isAuth = false }, icon = { Icon(Icons.Filled.ExitToApp, null) })
                }
            }
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(text = "BAYRA TRAVEL", fontWeight = FontWeight.Black, color = IMPERIAL_BLUE) },
                        navigationIcon = { IconButton(onClick = { scope.launch { drawerState.open() } }) { Icon(Icons.Filled.Menu, null, tint = IMPERIAL_BLUE) } }
                    )
                }
            ) { p ->
                Box(modifier = Modifier.padding(p)) {
                    if (currentView == "MAP") BookingHub(pName, prefs) else HistoryPage(pName)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VerificationView(phone: String, onVerify: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    var timeLeft by remember { mutableStateOf(600) } // 10 minutes in seconds

    LaunchedEffect(key1 = timeLeft) {
        if (timeLeft > 0) {
            delay(1000L)
            timeLeft--
        }
    }

    val minutes = timeLeft / 60
    val seconds = timeLeft % 60
    val timerText = String.format("%02d:%02d", minutes, seconds)

    Column(Modifier.fillMaxSize().padding(32.dp).background(Color.White), Arrangement.Center, Alignment.CenterHorizontally) {
        Text(text = "VERIFYING NUMBER", fontSize = 24.sp, fontWeight = FontWeight.Black, color = IMPERIAL_BLUE)
        Text(text = "Sent to $phone", color = Color.Gray)
        
        Spacer(Modifier.height(40.dp))
        
        Text(
            text = timerText,
            fontSize = 48.sp,
            fontWeight = FontWeight.ExtraBold,
            color = if (timeLeft < 60) IMPERIAL_RED else Color.Black
        )
        Text(text = "remaining to enter code", fontSize = 12.sp, color = Color.Gray)

        Spacer(Modifier.height(40.dp))
        
        OutlinedTextField(
            value = code, 
            onValueChange = { if(it.length <= 4) code = it }, 
            label = { Text("4-Digit Code") }, 
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        )
        
        Button(
            onClick = { onVerify(code) }, 
            Modifier.fillMaxWidth().height(65.dp).padding(top = 24.dp), 
            colors = ButtonDefaults.buttonColors(containerColor = IMPERIAL_BLUE),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(text = "VALIDATE ACCESS", color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginView(name: String, phone: String, onLogin: (String, String) -> Unit) {
    var n by remember { mutableStateOf(name) }; var p by remember { mutableStateOf(phone) }
    Column(Modifier.fillMaxSize().padding(32.dp).background(Color.White), Arrangement.Center, Alignment.CenterHorizontally) {
        Image(painterResource(id = R.drawable.logo_passenger), null, Modifier.size(200.dp))
        Text(text = "BAYRA TRAVEL", fontSize = 32.sp, fontWeight = FontWeight.Black, color = IMPERIAL_BLUE)
        Spacer(Modifier.height(30.dp))
        OutlinedTextField(value = n, onValueChange = { n = it }, label = { Text("Full Name") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(value = p, onValueChange = { p = it }, label = { Text("Phone Number (+251...)") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
        Button(
            onClick = { if(n.length > 2 && p.length > 8) onLogin(n, p) }, 
            modifier = Modifier.fillMaxWidth().height(65.dp).padding(top = 32.dp), 
            colors = ButtonDefaults.buttonColors(containerColor = IMPERIAL_BLUE),
            shape = RoundedCornerShape(16.dp)
        ) { 
            Text(text = "REQUEST ACCESS CODE", color = Color.White, fontWeight = FontWeight.Bold) 
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookingHub(name: String, prefs: android.content.SharedPreferences) {
    var status by remember { mutableStateOf("IDLE") }
    var activeId by remember { mutableStateOf(prefs.getString("active_id", "") ?: "") }
    var mapRef by remember { mutableStateOf<MapView?>(null) }
    var selectedTier by remember { mutableStateOf(Tier.COMFORT) }
    
    LaunchedEffect(activeId) {
        if(activeId.isNotEmpty()) {
            FirebaseDatabase.getInstance(DB_URL).getReference("rides/$activeId/status").addValueEventListener(object : ValueEventListener {
                override fun onDataChange(s: DataSnapshot) { status = s.value?.toString() ?: "IDLE" }
                override fun onCancelled(e: DatabaseError) {}
            })
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { ctx -> MapView(ctx).apply { setTileSource(TileSourceFactory.MAPNIK); controller.setZoom(17.5); controller.setCenter(GeoPoint(6.0333, 37.5500)); setBuiltInZoomControls(false); setMultiTouchControls(true); val loc = MyLocationNewOverlay(GpsMyLocationProvider(ctx), this); loc.enableMyLocation(); overlays.add(loc); mapRef = this } })
        if (status != "IDLE") {
            Surface(Modifier.fillMaxSize(), color = Color.White) {
                Column(verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = IMPERIAL_BLUE); Text(text = status, modifier = Modifier.padding(20.dp), fontWeight = FontWeight.Bold, color = IMPERIAL_BLUE)
                    Button(onClick = { status = "IDLE"; activeId = ""; prefs.edit().remove("active_id").apply() }, colors = ButtonDefaults.buttonColors(containerColor = IMPERIAL_RED)) { Text("CANCEL") }
                }
            }
        } else {
            Box(Modifier.fillMaxSize(), Alignment.Center) { Text(text = "📍", fontSize = 48.sp, modifier = Modifier.padding(bottom = 48.dp), color = IMPERIAL_RED) }
            Column(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.White, RoundedCornerShape(topStart = 24.dp)).padding(24.dp)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { 
                    items(Tier.values().toList()) { t -> 
                        Surface(modifier = Modifier.clickable { selectedTier = t }, color = if(selectedTier == t) IMPERIAL_BLUE else Color(0xFFEEEEEE), shape = RoundedCornerShape(8.dp)) { 
                            Text(t.label, Modifier.padding(12.dp, 8.dp), color = if(selectedTier == t) Color.White else Color.Black) 
                        } 
                    } 
                }
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { 
                    val id = "R_${System.currentTimeMillis()}"
                    val pt = mapRef?.mapCenter as GeoPoint
                    FirebaseDatabase.getInstance(DB_URL).getReference("rides/$id").setValue(mapOf("id" to id, "pName" to name, "status" to "REQUESTED", "price" to "450", "pLat" to pt.latitude, "pLon" to pt.longitude, "tier" to selectedTier.label))
                    activeId = id; prefs.edit().putString("active_id", id).apply()
                }, Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = IMPERIAL_BLUE)) { 
                    Text("BOOK ${selectedTier.label.uppercase()}", color = Color.White, fontWeight = FontWeight.Bold) 
                }
            }
        }
    }
}

@Composable
fun HistoryPage(name: String) {
    val trips = remember { mutableStateListOf<DataSnapshot>() }
    LaunchedEffect(Unit) {
        FirebaseDatabase.getInstance(DB_URL).getReference("rides").orderByChild("pName").equalTo(name).addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) { trips.clear(); trips.addAll(s.children.filter { it.child("status").value == "COMPLETED" }) }
            override fun onCancelled(e: DatabaseError) {}
        })
    }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text("Order History", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = IMPERIAL_BLUE)
            IconButton(onClick = { trips.forEach { it.ref.removeValue() } }) { Icon(Icons.Filled.Delete, null, tint = IMPERIAL_RED) }
        }
        LazyColumn { items(trips) { t -> Card(Modifier.fillMaxWidth().padding(top = 8.dp)) { Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text("Ride"); Text("${t.child("price").value} ETB", fontWeight = FontWeight.Bold) } } } }
    }
}

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
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

const val DB_URL = "https://bayra-84ecf-default-rtdb.europe-west1.firebasedatabase.app"
val IMPERIAL_BLUE = Color(0xFF1A237E)
val IMPERIAL_RED = Color(0xFFD50000)
const val BOT_TOKEN = "8594425943:AAH1M1_mYMI4pch-YfbC-hvzZfk_Kdrxb94"
const val CHAT_ID = "5232430147"

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
        setContent { 
            Surface(modifier = Modifier.fillMaxSize(), color = Color.White) {
                MaterialTheme(colorScheme = lightColorScheme(primary = IMPERIAL_BLUE)) { PassengerSuperApp() } 
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PassengerSuperApp() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("bayra_p_v211", Context.MODE_PRIVATE) }
    
    var isDarkMode by rememberSaveable { mutableStateOf(prefs.getBoolean("dark", false)) }
    var pName by rememberSaveable { mutableStateOf(prefs.getString("n", "") ?: "") }
    var pPhone by rememberSaveable { mutableStateOf(prefs.getString("p", "") ?: "") }
    var isAuth by remember { mutableStateOf(prefs.getBoolean("auth", false)) }
    var isVerifying by remember { mutableStateOf(prefs.getBoolean("is_v", false)) }
    
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var currentView by rememberSaveable { mutableStateOf("MAP") }

    if (!isAuth) {
        if (!isVerifying) {
            LoginView(pName, pPhone) { n, p -> 
                val start = System.currentTimeMillis()
                prefs.edit().putString("n", n).putString("p", p).putBoolean("is_v", true).putLong("v_s", start).apply()
                pName = n; pPhone = p; isVerifying = true 
                FirebaseDatabase.getInstance(DB_URL).getReference("verifications").child(p).setValue(mapOf("name" to n, "code" to "2025", "time" to start))
            }
        } else {
            VerificationView(pPhone, prefs) { code ->
                if (code == "2025") { prefs.edit().putBoolean("auth", true).apply(); isAuth = true; isVerifying = false }
                else Toast.makeText(ctx, "Invalid PIN", Toast.LENGTH_SHORT).show()
            }
        }
    } else {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = false,
            drawerContent = {
                ModalDrawerSheet {
                    Column(Modifier.padding(20.dp)) {
                        Icon(Icons.Filled.AccountCircle, null, Modifier.size(64.dp), IMPERIAL_BLUE)
                        Text(text = pName, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(text = pPhone, fontSize = 12.sp, color = Color.Gray)
                    }
                    Divider()
                    NavigationDrawerItem(label = { Text("Book a Ride") }, selected = currentView == "MAP", onClick = { currentView = "MAP"; scope.launch { drawerState.close() } }, icon = { Icon(Icons.Filled.Home, null) })
                    NavigationDrawerItem(label = { Text("My Orders") }, selected = currentView == "ORDERS", onClick = { currentView = "ORDERS"; scope.launch { drawerState.close() } }, icon = { Icon(Icons.Filled.List, null) })
                    NavigationDrawerItem(label = { Text("Notifications") }, selected = currentView == "NOTIF", onClick = { currentView = "NOTIF"; scope.launch { drawerState.close() } }, icon = { Icon(Icons.Filled.Notifications, null) })
                    NavigationDrawerItem(label = { Text("About Bayra") }, selected = currentView == "ABOUT", onClick = { currentView = "ABOUT"; scope.launch { drawerState.close() } }, icon = { Icon(Icons.Filled.Info, null) })
                    Divider()
                    NavigationDrawerItem(label = { Text("Logout") }, selected = false, onClick = { prefs.edit().clear().apply(); isAuth = false }, icon = { Icon(Icons.Filled.ExitToApp, null) })
                }
            }
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(title = { Text("BAYRA TRAVEL", fontWeight = FontWeight.Black) }, navigationIcon = { IconButton(onClick = { scope.launch { drawerState.open() } }) { Icon(Icons.Filled.Menu, null) } })
                }
            ) { p ->
                Box(Modifier.padding(p)) {
                    when(currentView) {
                        "MAP" -> BookingHub(pName, prefs)
                        "ORDERS" -> HistoryPage(pName)
                        "NOTIF" -> NotificationPage()
                        "ABOUT" -> AboutUsPage()
                    }
                }
            }
        }
    }
}

@Composable
fun NotificationPage() {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Notifications", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Notifications, null, tint = IMPERIAL_BLUE) // 🔥 SAFE ICON
                Spacer(Modifier.width(12.dp))
                Column { Text("Arba Minch Hub Active", fontWeight = FontWeight.Bold); Text("Fleet is ready for dispatch.", fontSize = 12.sp) }
            }
        }
    }
}

@Composable
fun AboutUsPage() {
    Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState())) {
        Text("Bayra Travel", fontSize = 28.sp, fontWeight = FontWeight.Black, color = IMPERIAL_BLUE)
        Text("Sarotethai nuna maaddo, Aadhidatethai nuna kaaletho", fontStyle = FontStyle.Italic, color = Color.Gray)
        Spacer(Modifier.height(24.dp))
        Text("Policy", fontWeight = FontWeight.Bold); Text("• Ride Base: 50 ETB\n• Night: 200 ETB\n• Support: bayratravel@gmail.com")
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
        AndroidView(factory = { ctx -> MapView(ctx).apply { setTileSource(TileSourceFactory.MAPNIK); controller.setZoom(17.5); controller.setCenter(GeoPoint(6.0333, 37.5500)); setBuiltInZoomControls(false); setMultiTouchControls(true); mapRef = this } })
        if (status != "IDLE") {
            Surface(Modifier.fillMaxSize(), color = Color.White) { Column(verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(); Text(text = status, Modifier.padding(20.dp)); Button(onClick = { status = "IDLE"; activeId = "" }, colors = ButtonDefaults.buttonColors(containerColor = IMPERIAL_RED)) { Text("CANCEL") } } }
        } else {
            Box(Modifier.fillMaxSize(), Alignment.Center) { Text("📍", fontSize = 48.sp, modifier = Modifier.padding(bottom = 48.dp), color = IMPERIAL_RED) }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.White, RoundedCornerShape(topStart = 24.dp)).padding(24.dp)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(Tier.values().toList()) { t -> Surface(modifier = Modifier.clickable { selectedTier = t }, color = if(selectedTier == t) IMPERIAL_BLUE else Color(0xFFEEEEEE), shape = RoundedCornerShape(8.dp)) { Text(t.label, Modifier.padding(12.dp, 8.dp), color = if(selectedTier == t) Color.White else Color.Black) } } }
                Spacer(Modifier.height(16.dp))
                Button(onClick = { 
                    val id = "R_${System.currentTimeMillis()}"
                    FirebaseDatabase.getInstance(DB_URL).getReference("rides/$id").setValue(mapOf("id" to id, "pName" to name, "status" to "REQUESTED", "price" to "450"))
                    activeId = id; prefs.edit().putString("active_id", id).apply()
                }, Modifier.fillMaxWidth().height(60.dp)) { Text("BOOK ${selectedTier.label.uppercase()}") }
            }
        }
    }
}

@Composable
fun HistoryPage(name: String) {
    Column(Modifier.fillMaxSize().padding(16.dp)) { Text("Order History", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = IMPERIAL_BLUE); Spacer(Modifier.height(16.dp)); Text("No recent trips.") }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VerificationView(phone: String, prefs: SharedPreferences, onVerify: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(Color.White).padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        Text("VERIFICATION", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = IMPERIAL_BLUE)
        Spacer(Modifier.height(20.dp)); OutlinedTextField(value = code, onValueChange = { code = it }, label = { Text("Code") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { onVerify(code) }, Modifier.fillMaxWidth().height(60.dp).padding(top = 20.dp)) { Text("VALIDATE") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginView(name: String, phone: String, onLogin: (String, String) -> Unit) {
    var n by remember { mutableStateOf(name) }; var p by remember { mutableStateOf(phone) }
    Column(Modifier.fillMaxSize().background(Color.White).padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        Image(painterResource(id = R.drawable.logo_passenger), null, Modifier.size(160.dp))
        Text("BAYRA TRAVEL", fontSize = 28.sp, fontWeight = FontWeight.Black, color = IMPERIAL_BLUE)
        Spacer(Modifier.height(30.dp)); OutlinedTextField(value = n, onValueChange = { n = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp)); OutlinedTextField(value = p, onValueChange = { p = it }, label = { Text("Phone") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { onLogin(n, p) }, Modifier.fillMaxWidth().height(65.dp).padding(top = 24.dp)) { Text("LOGIN") }
    }
}

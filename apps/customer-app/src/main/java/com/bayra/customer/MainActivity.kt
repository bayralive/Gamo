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
import androidx.compose.foundation.shape.CircleShape
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
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

const val DB_URL = "https://bayra-84ecf-default-rtdb.europe-west1.firebasedatabase.app"

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
        setContent { PassengerSuperApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PassengerSuperApp() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("bayra_p_v191", Context.MODE_PRIVATE) }
    
    var isDarkMode by rememberSaveable { mutableStateOf(prefs.getBoolean("dark", false)) }
    var pName by rememberSaveable { mutableStateOf(prefs.getString("n", "") ?: "") }
    var pEmail by rememberSaveable { mutableStateOf(prefs.getString("e", "") ?: "") }
    var isAuth by remember { mutableStateOf(pName.isNotEmpty()) }
    
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var currentView by rememberSaveable { mutableStateOf("MAP") }

    MaterialTheme(colorScheme = if (isDarkMode) darkColorScheme() else lightColorScheme()) {
        if (!isAuth) {
            LoginView { n, e -> 
                prefs.edit().putString("n", n).putString("e", e).apply()
                pName = n; pEmail = e; isAuth = true 
            }
        } else {
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    ModalDrawerSheet {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Icon(Icons.Filled.AccountCircle, null, modifier = Modifier.size(64.dp), tint = Color(0xFF1A237E))
                            Text(text = pName, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                            Text(text = pEmail, fontSize = 14.sp, color = Color.Gray)
                        }
                        Divider()
                        NavigationDrawerItem(label = { Text("Home Map") }, selected = currentView == "MAP", onClick = { currentView = "MAP"; scope.launch { drawerState.close() } }, icon = { Icon(Icons.Filled.Home, null) })
                        NavigationDrawerItem(label = { Text("Orders") }, selected = currentView == "ORDERS", onClick = { currentView = "ORDERS"; scope.launch { drawerState.close() } }, icon = { Icon(Icons.Filled.List, null) })
                        NavigationDrawerItem(label = { Text("Notifications") }, selected = currentView == "NOTIF", onClick = { currentView = "NOTIF"; scope.launch { drawerState.close() } }, icon = { Icon(Icons.Filled.Notifications, null) })
                        NavigationDrawerItem(label = { Text("Settings") }, selected = currentView == "SETTINGS", onClick = { currentView = "SETTINGS"; scope.launch { drawerState.close() } }, icon = { Icon(Icons.Filled.Settings, null) })
                        NavigationDrawerItem(label = { Text("About Us") }, selected = currentView == "ABOUT", onClick = { currentView = "ABOUT"; scope.launch { drawerState.close() } }, icon = { Icon(Icons.Filled.Info, null) })
                        Divider()
                        NavigationDrawerItem(label = { Text("Logout") }, selected = false, onClick = { prefs.edit().clear().apply(); isAuth = false }, icon = { Icon(Icons.Filled.ExitToApp, null) })
                    }
                }
            ) {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(text = "BAYRA TRAVEL", fontWeight = FontWeight.Black) },
                            navigationIcon = { IconButton(onClick = { scope.launch { drawerState.open() } }) { Icon(Icons.Filled.Menu, null) } }
                        )
                    }
                ) { p ->
                    Box(modifier = Modifier.padding(p)) {
                        when(currentView) {
                            "MAP" -> BookingHub(pName, prefs)
                            "ORDERS" -> HistoryPage(pName)
                            "NOTIF" -> NotificationPage()
                            "SETTINGS" -> SettingsPage(isDarkMode) { isDarkMode = it; prefs.edit().putBoolean("dark", it).apply() }
                            "ABOUT" -> AboutUsPage()
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsPage(isDarkMode: Boolean, onToggle: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Text(text = "Settings", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(20.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Settings, null) // 🔥 REPLACED Brightness6
                Spacer(modifier = Modifier.width(12.dp))
                Text(text = "Dark Mode")
            }
            Switch(checked = isDarkMode, onCheckedChange = onToggle)
        }
        Divider(modifier = Modifier.padding(vertical = 16.dp))
        Text(text = "Get in Touch", fontWeight = FontWeight.Bold, color = Color.Gray)
        Spacer(modifier = Modifier.height(12.dp))
        Button(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/bayratravel"))) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF229ED9))) {
            Icon(Icons.Filled.Send, null); Spacer(Modifier.width(8.dp)); Text("Telegram Support")
        }
    }
}

@Composable
fun AboutUsPage() {
    Column(modifier = Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState())) {
        Text(text = "Bayra Travel", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color(0xFF1A237E))
        Text(text = "Sarotethai nuna maaddo,\nAadhidatethai nuna kaaletho", fontStyle = FontStyle.Italic, color = Color.Gray)
        Spacer(modifier = Modifier.height(20.dp))
        Text(text = "የክፍያ እና የእርዳታ መመሪያ (Billing & Support)", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(text = "• መነሻ ክፍያ፦ 50 ብር\n• የሌሊት ጭማሪ፦ 200 ብር\n• የጋራ ጉዞ (Pool)፦ 20% ቅናሽ\n• ባጃጅ፦ 350 ብር/ሰዓት\n• መኪና፦ 500 ብር/ሰዓት", fontSize = 14.sp)
        Spacer(modifier = Modifier.height(20.dp))
        Text(text = "About Bayra", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(text = "We are the local pride of Arba Minch, dedicated to modernizing tourism and trade in the South.\n\nWisdom and Peace in Every Journey! 🕊️✨🏁", fontSize = 14.sp)
    }
}

@Composable
fun NotificationPage() {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(text = "Notifications", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(20.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(text = "🎁", fontSize = 30.sp)
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(text = "Welcome to Bayra Travel", fontWeight = FontWeight.Bold)
                    Text(text = "Your journey starts here.", fontSize = 12.sp, color = Color.Gray)
                }
            }
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
        AndroidView(factory = { ctx -> MapView(ctx).apply { setTileSource(TileSourceFactory.MAPNIK); controller.setZoom(17.5); controller.setCenter(GeoPoint(6.0333, 37.5500)); mapRef = this } })
        if (status != "IDLE") {
            Surface(Modifier.fillMaxSize(), color = Color.White) {
                Column(verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(); Text(text = status, modifier = Modifier.padding(20.dp), fontWeight = FontWeight.Bold)
                    Button(onClick = { status = "IDLE"; activeId = ""; prefs.edit().remove("active_id").apply() }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text("CANCEL") }
                }
            }
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(text = "📍", fontSize = 48.sp, modifier = Modifier.padding(bottom = 48.dp)) }
            Column(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.White, RoundedCornerShape(topStart = 24.dp)).padding(24.dp)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(Tier.values().toList()) { t -> Surface(modifier = Modifier.clickable { selectedTier = t }, color = if(selectedTier == t) Color(0xFF1A237E) else Color(0xFFEEEEEE), shape = RoundedCornerShape(8.dp)) { Text(t.label, Modifier.padding(12.dp, 8.dp), color = if(selectedTier == t) Color.White else Color.Black) } } }
                Spacer(Modifier.height(16.dp))
                Button(onClick = { 
                    val id = "R_${System.currentTimeMillis()}"
                    val pt = mapRef?.mapCenter as GeoPoint
                    FirebaseDatabase.getInstance(DB_URL).getReference("rides/$id").setValue(mapOf("id" to id, "pName" to name, "status" to "REQUESTED", "price" to "450", "pLat" to pt.latitude, "pLon" to pt.longitude, "tier" to selectedTier.label))
                    activeId = id; prefs.edit().putString("active_id", id).apply()
                }, Modifier.fillMaxWidth().height(60.dp)) { Text(text = "BOOK ${selectedTier.label.uppercase()}") }
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
    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        item { Text(text = "Order History", fontSize = 24.sp, fontWeight = FontWeight.Bold); Spacer(modifier = Modifier.height(16.dp)) }
        items(trips) { t -> Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) { Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text(text = "Ride"); Text(text = "${t.child("price").value} ETB", fontWeight = FontWeight.Bold) } } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginView(onLogin: (String, String) -> Unit) {
    var n by remember { mutableStateOf("") }; var e by remember { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxSize().padding(32.dp).background(Color.White), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Image(painter = painterResource(id = R.drawable.logo_passenger), contentDescription = null, modifier = Modifier.size(180.dp))
        Text(text = "BAYRA TRAVEL", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color(0xFF1A237E))
        Spacer(modifier = Modifier.height(30.dp))
        OutlinedTextField(value = n, onValueChange = { n = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = e, onValueChange = { e = it }, label = { Text("Email") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { if(n.isNotEmpty() && e.contains("@")) onLogin(n, e) }, modifier = Modifier.fillMaxWidth().height(60.dp).padding(top = 20.dp)) { Text("START") }
    }
}

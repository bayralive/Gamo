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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.google.firebase.database.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.net.URL
import java.net.HttpURLConnection
import java.net.URLEncoder
import kotlin.concurrent.thread

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
        Configuration.getInstance().userAgentValue = "BayraPrestige_v212"
        requestLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        setContent { PassengerSuperApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PassengerSuperApp() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("bayra_p_v212", Context.MODE_PRIVATE) }
    
    var isDarkMode by rememberSaveable { mutableStateOf(prefs.getBoolean("dark", false)) }
    var pName by rememberSaveable { mutableStateOf(prefs.getString("n", "") ?: "") }
    var pPhone by rememberSaveable { mutableStateOf(prefs.getString("p", "") ?: "") }
    var pEmail by rememberSaveable { mutableStateOf(prefs.getString("e", "") ?: "") }
    var isAuth by remember { mutableStateOf(prefs.getBoolean("auth", false)) }
    var isVerifying by remember { mutableStateOf(prefs.getBoolean("is_v", false)) }
    
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var currentView by rememberSaveable { mutableStateOf("MAP") }

    MaterialTheme(colorScheme = if (isDarkMode) darkColorScheme() else lightColorScheme(primary = IMPERIAL_BLUE)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            if (!isAuth) {
                if (!isVerifying) {
                    LoginView(pName, pPhone, pEmail) { n, p, e -> 
                        val start = System.currentTimeMillis()
                        prefs.edit().putString("n", n).putString("p", p).putString("e", e).putBoolean("is_v", true).putLong("v_s", start).apply()
                        pName = n; pPhone = p; pEmail = e; isVerifying = true 
                        val pin = (1000..9999).random().toString()
                        FirebaseDatabase.getInstance(DB_URL).getReference("verifications").child(p).setValue(mapOf("name" to n, "code" to pin, "time" to start))
                        thread { try { URL("https://api.telegram.org/bot$BOT_TOKEN/sendMessage?chat_id=$CHAT_ID&text=${URLEncoder.encode("🚨 NEW ACCESS: $n\n📞 $p\n🗝️ CODE: $pin", "UTF-8")}").openConnection().apply { (this as HttpURLConnection).requestMethod = "GET" }.inputStream.bufferedReader().readText() } catch (ex: Exception) {} }
                    }
                } else {
                    VerificationView(pPhone, prefs) { code ->
                        FirebaseDatabase.getInstance(DB_URL).getReference("verifications").child(pPhone).child("code").addListenerForSingleValueEvent(object : ValueEventListener {
                            override fun onDataChange(s: DataSnapshot) {
                                if (s.value?.toString() == code) { prefs.edit().putBoolean("auth", true).apply(); isAuth = true; isVerifying = false }
                                else Toast.makeText(ctx, "Invalid Code", Toast.LENGTH_SHORT).show()
                            }
                            override fun onCancelled(e: DatabaseError) {}
                        })
                    }
                }
            } else {
                ModalNavigationDrawer(
                    drawerState = drawerState,
                    gesturesEnabled = false, // 🔥 LOCKED GESTURES
                    drawerContent = {
                        ModalDrawerSheet {
                            Column(Modifier.padding(20.dp)) {
                                Icon(Icons.Filled.AccountCircle, null, Modifier.size(64.dp), IMPERIAL_BLUE)
                                Text(text = pName, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                                Text(text = pEmail, fontSize = 12.sp, color = Color.Gray)
                            }
                            Divider()
                            NavigationDrawerItem(label = { Text("Book a Ride") }, selected = currentView == "MAP", onClick = { currentView = "MAP"; scope.launch { drawerState.close() } }, icon = { Icon(Icons.Filled.Home, null) })
                            NavigationDrawerItem(label = { Text("My Orders") }, selected = currentView == "ORDERS", onClick = { currentView = "ORDERS"; scope.launch { drawerState.close() } }, icon = { Icon(Icons.Filled.List, null) })
                            NavigationDrawerItem(label = { Text("Notifications") }, selected = currentView == "NOTIF", onClick = { currentView = "NOTIF"; scope.launch { drawerState.close() } }, icon = { Icon(Icons.Filled.Notifications, null) })
                            NavigationDrawerItem(label = { Text("Settings") }, selected = currentView == "SETTINGS", onClick = { currentView = "SETTINGS"; scope.launch { drawerState.close() } }, icon = { Icon(Icons.Filled.Settings, null) })
                            NavigationDrawerItem(label = { Text("About Bayra") }, selected = currentView == "ABOUT", onClick = { currentView = "ABOUT"; scope.launch { drawerState.close() } }, icon = { Icon(Icons.Filled.Info, null) })
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
                        Box(Modifier.padding(p)) {
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
}

@Composable
fun SettingsPage(isDarkMode: Boolean, onToggle: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text(text = "Settings", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text(text = "Dark Mode Appearance")
            Switch(checked = isDarkMode, onCheckedChange = onToggle)
        }
        Divider(Modifier.padding(vertical = 20.dp))
        Text(text = "Imperial Community", fontWeight = FontWeight.Bold, color = Color.Gray)
        Spacer(Modifier.height(12.dp))
        Button(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/bayratravel"))) }, Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF229ED9))) {
            Icon(Icons.Filled.Send, null); Spacer(Modifier.width(8.dp)); Text("Telegram Community")
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = { ctx.startActivity(Intent(Intent.ACTION_SENDTO).apply { data = Uri.parse("mailto:bayratravel@gmail.com") }) }, Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)) {
            Icon(Icons.Filled.Email, null); Spacer(Modifier.width(8.dp)); Text("Email Support")
        }
    }
}

@Composable
fun AboutUsPage() {
    Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState())) {
        Text(text = "Bayra Travel", fontSize = 28.sp, fontWeight = FontWeight.Black, color = IMPERIAL_BLUE)
        Text(text = "Sarotethai nuna maaddo,\nAadhidatethai nuna kaaletho", fontStyle = FontStyle.Italic, color = Color.Gray)
        Spacer(Modifier.height(24.dp))
        Text(text = "የክፍያ እና የእርዳታ መመሪያ (Billing & Support)", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(text = "1. መደበኛ የጉዞ ክፍያ (Ride)\nመነሻ ክፍያ፦ 50 ብር\nየሌሊት ጭማሪ፦ 200 ብር (2:00 PM - 12:00 AM)\nየጋራ ጉዞ (Pool)፦ 20% ቅናሽ", fontSize = 14.sp)
        Spacer(Modifier.height(12.dp))
        Text(text = "2. የኮንትራት ክፍያ (Contrat)\nየሰዓት ሂሳብ፦ 350 ብር (ባጃጅ) ወይም 500 ብር (መኪና)\nየርቀት ገደብ፦ 12 ኪ.ሜ/ሰዓት", fontSize = 14.sp)
        Spacer(Modifier.height(20.dp))
        Text(text = "About Bayra Travel", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(text = "We are the local pride of Arba Minch, dedicated to modernizing tourism and trade in the South. Speed, comfort, and community trust are our core pillars.\n\nWisdom and Peace in Every Journey! 🕊️✨🏁", fontSize = 14.sp)
    }
}

@Composable
fun NotificationPage() {
    val bulletins = remember { mutableStateListOf<DataSnapshot>() }
    LaunchedEffect(Unit) {
        FirebaseDatabase.getInstance(DB_URL).getReference("bulletins").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) { bulletins.clear(); s.children.forEach { bulletins.add(it) } }
            override fun onCancelled(e: DatabaseError) {}
        })
    }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(text = "Notifications", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = IMPERIAL_BLUE)
        Spacer(Modifier.height(16.dp))
        LazyColumn {
            items(bulletins) { b ->
                Card(Modifier.fillMaxWidth().padding(bottom = 16.dp), elevation = CardDefaults.cardElevation(4.dp)) {
                    Column {
                        val img = b.child("imageUrl").value?.toString() ?: ""
                        if(img.isNotEmpty()) AsyncImage(model = img, contentDescription = null, modifier = Modifier.fillMaxWidth().height(180.dp), contentScale = ContentScale.Crop)
                        Column(Modifier.padding(16.dp)) {
                            Text(text = b.child("title").value?.toString() ?: "", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text(text = b.child("message").value?.toString() ?: "", fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookingHub(name: String, prefs: SharedPreferences) {
    var step by remember { mutableStateOf("PICKUP") }
    var pickupPt by remember { mutableStateOf<GeoPoint?>(null) }
    var destPt by remember { mutableStateOf<GeoPoint?>(null) }
    var selectedTier by remember { mutableStateOf(Tier.COMFORT) }
    var status by remember { mutableStateOf("IDLE") }
    var activeId by remember { mutableStateOf(prefs.getString("active_id", "") ?: "") }
    var mapRef by remember { mutableStateOf<MapView?>(null) }
    
    val prestigeTiles = object : OnlineTileSourceBase("Prestige", 0, 20, 256, ".png", arrayOf("https://mt1.google.com/vt/lyrs=m&")) {
        override fun getTileURLString(p: Long): String = "$baseUrl&x=${MapTileIndex.getX(p)}&y=${MapTileIndex.getY(p)}&z=${MapTileIndex.getZoom(p)}"
    }

    LaunchedEffect(activeId) {
        if(activeId.isNotEmpty()) {
            FirebaseDatabase.getInstance(DB_URL).getReference("rides/$activeId/status").addValueEventListener(object : ValueEventListener {
                override fun onDataChange(s: DataSnapshot) { status = s.value?.toString() ?: "IDLE" }
                override fun onCancelled(e: DatabaseError) {}
            })
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { ctx -> MapView(ctx).apply { setTileSource(prestigeTiles); setBuiltInZoomControls(false); setMultiTouchControls(true); controller.setZoom(17.5); controller.setCenter(GeoPoint(6.0333, 37.5500)); val loc = MyLocationNewOverlay(GpsMyLocationProvider(ctx), this); loc.enableMyLocation(); overlays.add(loc); mapRef = this } }, update = { view ->
            view.overlays.filterIsInstance<Marker>().forEach { view.overlays.remove(it) }
            pickupPt?.let { pt -> Marker(view).apply { position = pt; title = "Pickup" }.also { view.overlays.add(it) } }
            destPt?.let { pt -> Marker(view).apply { position = pt; title = "Dropoff" }.also { view.overlays.add(it) } }
            view.invalidate()
        })

        if (status != "IDLE") {
            Surface(Modifier.fillMaxSize(), color = Color.White) { Column(verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(); Text(text = status, Modifier.padding(20.dp), fontWeight = FontWeight.Bold); Button(onClick = { status = "IDLE"; activeId = ""; prefs.edit().remove("active_id").apply() }, colors = ButtonDefaults.buttonColors(containerColor = IMPERIAL_RED)) { Text("CANCEL") } } }
        } else {
            if(step != "CONFIRM") Box(Modifier.fillMaxSize(), Alignment.Center) { Text(text = "📍", fontSize = 48.sp, modifier = Modifier.padding(bottom = 48.dp), color = IMPERIAL_RED) }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.White, RoundedCornerShape(topStart = 24.dp)).padding(24.dp)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(Tier.values().toList()) { t -> Surface(modifier = Modifier.clickable { selectedTier = t; if(pickupPt != null) step = if(t.label.contains("Hr")) "CONFIRM" else "DEST" }, color = if(selectedTier == t) IMPERIAL_BLUE else Color(0xFFEEEEEE), shape = RoundedCornerShape(8.dp)) { Text(text = t.label, modifier = Modifier.padding(12.dp, 8.dp), color = if(selectedTier == t) Color.White else Color.Black) } } }
                Spacer(modifier = Modifier.height(16.dp))
                if (step == "PICKUP") Button(onClick = { pickupPt = mapRef?.mapCenter as GeoPoint; step = if(selectedTier.label.contains("Hr")) "CONFIRM" else "DEST" }, modifier = Modifier.fillMaxWidth().height(60.dp)) { Text(text = "SET PICKUP", fontWeight = FontWeight.Bold) }
                else if (step == "DEST") Button(onClick = { destPt = mapRef?.mapCenter as GeoPoint; step = "CONFIRM" }, modifier = Modifier.fillMaxWidth().height(60.dp)) { Text(text = "SET DESTINATION", fontWeight = FontWeight.Bold) }
                else {
                    Button(onClick = { 
                        val id = "R_${System.currentTimeMillis()}"
                        FirebaseDatabase.getInstance(DB_URL).getReference("rides/$id").setValue(mapOf("id" to id, "pName" to name, "status" to "REQUESTED", "price" to "450", "pLat" to pickupPt?.latitude, "pLon" to pickupPt?.longitude, "dLat" to destPt?.latitude, "dLon" to destPt?.longitude, "tier" to selectedTier.label))
                        activeId = id; prefs.edit().putString("active_id", id).apply()
                    }, Modifier.fillMaxWidth().height(60.dp)) { Text(text = "BOOK ${selectedTier.label.uppercase()}", fontWeight = FontWeight.Bold) }
                    TextButton(onClick = { step = "PICKUP"; pickupPt = null; destPt = null }, Modifier.fillMaxWidth()) { Text(text = "Reset Points", color = IMPERIAL_BLUE) }
                }
            }
        }
    }
}

@Composable
fun HistoryPage(name: String) {
    val trips = remember { mutableStateListOf<DataSnapshot>() }
    LaunchedEffect(Unit) { FirebaseDatabase.getInstance(DB_URL).getReference("rides").orderByChild("pName").equalTo(name).addListenerForSingleValueEvent(object : ValueEventListener { override fun onDataChange(s: DataSnapshot) { trips.clear(); trips.addAll(s.children.filter { it.child("status").value == "COMPLETED" }) }; override fun onCancelled(e: DatabaseError) {} }) }
    Column(Modifier.fillMaxSize().padding(16.dp)) { Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) { Text(text = "Order History", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = IMPERIAL_BLUE); IconButton(onClick = { trips.forEach { it.ref.removeValue() } }) { Icon(Icons.Filled.Delete, null, tint = IMPERIAL_RED) } }; LazyColumn { items(trips) { t -> Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text("Ride"); Text("${t.child("price").value} ETB", fontWeight = FontWeight.Bold) } } } } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VerificationView(phone: String, prefs: SharedPreferences, onVerify: (String) -> Unit) {
    val start = prefs.getLong("v_s", System.currentTimeMillis())
    var time by remember { mutableStateOf(0L) }
    var code by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { while (true) { time = (600 - (System.currentTimeMillis() - start)/1000).coerceAtLeast(0); delay(1000L) } }
    Column(Modifier.fillMaxSize().background(Color.White).padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        Image(painterResource(id = R.drawable.logo_passenger), null, Modifier.size(120.dp))
        Text(text = "VERIFICATION", fontSize = 28.sp, fontWeight = FontWeight.Black, color = IMPERIAL_BLUE)
        Text(text = "A 4-digit code will be sent to your phone.", fontSize = 14.sp, color = Color.Gray, textAlign = TextAlign.Center)
        Spacer(Modifier.height(40.dp))
        Text(text = String.format("%02d:%02d", time/60, time%60), fontSize = 64.sp, fontWeight = FontWeight.ExtraBold, color = if(time < 60) IMPERIAL_RED else Color.Black)
        Spacer(Modifier.height(40.dp))
        OutlinedTextField(value = code, onValueChange = { if(it.length <= 4) code = it }, label = { Text("Enter Code") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
        Button(onClick = { onVerify(code) }, Modifier.fillMaxWidth().height(60.dp).padding(top = 20.dp), shape = RoundedCornerShape(16.dp)) { Text("VALIDATE", fontWeight = FontWeight.Bold) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginView(name: String, phone: String, email: String, onLogin: (String, String, String) -> Unit) {
    var n by remember { mutableStateOf(name) }; var p by remember { mutableStateOf(phone) }; var e by remember { mutableStateOf(email) }
    Column(modifier = Modifier.fillMaxSize().background(Color.White).verticalScroll(rememberScrollState()).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Image(painter = painterResource(id = R.drawable.logo_passenger), null, modifier = Modifier.size(160.dp))
        Text(text = "BAYRA TRAVEL", fontSize = 28.sp, fontWeight = FontWeight.Black, color = IMPERIAL_BLUE)
        Text(text = "Welcome to Arba Minch", fontSize = 14.sp, color = Color.Gray, modifier = Modifier.padding(bottom = 32.dp))
        OutlinedTextField(value = n, onValueChange = { n = it }, label = { Text("Full Name") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(value = p, onValueChange = { p = it }, label = { Text("Phone Number") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(value = e, onValueChange = { e = it }, label = { Text("Email (for online payment)") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
        Spacer(Modifier.height(40.dp))
        Button(onClick = { if(n.length > 2 && p.length > 8 && e.contains("@")) onLogin(n, p, e) }, modifier = Modifier.fillMaxWidth().height(65.dp), shape = RoundedCornerShape(16.dp)) { Text(text = "LOGIN", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp) }
    }
}
package com.bayra.customer

import android.Manifest
import android.content.*
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
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
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
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

enum class Tier(val label: String, val base: Double, val isHr: Boolean) {
    POOL("Pool", 80.0, false), COMFORT("Comfort", 120.0, false), 
    CODE_3("Code 3", 280.0, false), BAJAJ_HR("Bajaj Hr", 350.0, true),
    C3_HR("C3 Hr", 550.0, true)
}

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    private val requestLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        Configuration.getInstance().userAgentValue = "BayraPrestige_v225"
        requestLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        setContent { PassengerSuperApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PassengerSuperApp() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("bayra_p_v225", Context.MODE_PRIVATE) }
    
    var isDarkMode by rememberSaveable { mutableStateOf(prefs.getBoolean("dark", false)) }
    var pName by rememberSaveable { mutableStateOf(prefs.getString("n", "") ?: "") }
    var pPhone by rememberSaveable { mutableStateOf(prefs.getString("p", "") ?: "") }
    var pEmail by rememberSaveable { mutableStateOf(prefs.getString("e", "") ?: "") }
    var isAuth by remember { mutableStateOf(prefs.getBoolean("auth", false)) }
    var isVerifying by rememberSaveable { mutableStateOf(prefs.getBoolean("is_v", false)) }
    
    // 🔥 HOISTED BOOKING STATE
    var pickupPt by remember { mutableStateOf<GeoPoint?>(null) }
    var destPt by remember { mutableStateOf<GeoPoint?>(null) }
    var selectedTier by remember { mutableStateOf(Tier.COMFORT) }
    var step by rememberSaveable { mutableStateOf("PICKUP") }
    var hrCount by rememberSaveable { mutableStateOf(1) }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var currentView by rememberSaveable { mutableStateOf("MAP") }

    MaterialTheme(colorScheme = if (isDarkMode) darkColorScheme() else lightColorScheme(primary = IMPERIAL_BLUE)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
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
                                if (s.value?.toString() == code) { prefs.edit().putBoolean("auth", true).putBoolean("is_v", false).apply(); isAuth = true; isVerifying = false }
                                else Toast.makeText(ctx, "Invalid PIN", Toast.LENGTH_SHORT).show()
                            }
                            override fun onCancelled(e: DatabaseError) {}
                        })
                    }
                }
            } else {
                ModalNavigationDrawer(
                    drawerState = drawerState, gesturesEnabled = false,
                    drawerContent = {
                        ModalDrawerSheet {
                            Column(modifier = Modifier.padding(20.dp)) {
                                Icon(Icons.Filled.AccountCircle, null, modifier = Modifier.size(64.dp), tint = IMPERIAL_BLUE)
                                Text(text = pName, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                                Text(text = pPhone, fontSize = 14.sp, color = Color.Gray)
                            }
                            Divider()
                            NavigationDrawerItem(label = { Text("Map") }, selected = currentView == "MAP", onClick = { currentView = "MAP"; scope.launch { drawerState.close() } }, icon = { Icon(Icons.Filled.Home, null) })
                            NavigationDrawerItem(label = { Text("Orders") }, selected = currentView == "ORDERS", onClick = { currentView = "ORDERS"; scope.launch { drawerState.close() } }, icon = { Icon(Icons.Filled.List, null) })
                            NavigationDrawerItem(label = { Text("Settings") }, selected = currentView == "SETTINGS", onClick = { currentView = "SETTINGS"; scope.launch { drawerState.close() } }, icon = { Icon(Icons.Filled.Settings, null) })
                            NavigationDrawerItem(label = { Text("About Us") }, selected = currentView == "ABOUT", onClick = { currentView = "ABOUT"; scope.launch { drawerState.close() } }, icon = { Icon(Icons.Filled.Info, null) })
                            Divider()
                            NavigationDrawerItem(label = { Text("Logout") }, selected = false, onClick = { prefs.edit().clear().apply(); isAuth = false }, icon = { Icon(Icons.Filled.ExitToApp, null) })
                        }
                    }
                ) {
                    Scaffold(
                        topBar = { TopAppBar(title = { Text("BAYRA TRAVEL", fontWeight = FontWeight.Black) }, navigationIcon = { IconButton(onClick = { scope.launch { drawerState.open() } }) { Icon(Icons.Filled.Menu, null) } }) }
                    ) { padding ->
                        Box(modifier = Modifier.padding(padding)) {
                            when(currentView) {
                                "MAP" -> BookingHub(pName, pEmail, pPhone, prefs, pickupPt, destPt, selectedTier, step, hrCount,
                                    onPointChange = { p, d, s, t, h -> pickupPt = p; destPt = d; step = s; selectedTier = t; hrCount = h })
                                "ORDERS" -> HistoryPage(pName)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookingHub(
    name: String, email: String, phone: String, prefs: SharedPreferences,
    pickupPt: GeoPoint?, destPt: GeoPoint?, selectedTier: Tier, step: String, hrCount: Int,
    onPointChange: (GeoPoint?, GeoPoint?, String, Tier, Int) -> Unit
) {
    val ctx = LocalContext.current
    var status by remember { mutableStateOf("IDLE") }
    var activeId by remember { mutableStateOf(prefs.getString("active_id", "") ?: "") }
    var driverName by remember { mutableStateOf("") }
    var driverPhone by remember { mutableStateOf("") }
    var activePrice by remember { mutableStateOf("0") }
    var mapRef by remember { mutableStateOf<MapView?>(null) }
    var isGeneratingLink by remember { mutableStateOf(false) }

    // 🍭 GREEN HAND LOLLIPOP PIN
    val lollipopIcon = remember {
        val size = 100
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = android.graphics.Paint().apply { color = android.graphics.Color.parseColor("#2E7D32"); isAntiAlias = true }
        canvas.drawRect(size/2f - 4, size/2f, size/2f + 4, size.toFloat(), paint)
        canvas.drawCircle(size/2f, size/4f + 10, 25f, paint)
        paint.color = android.graphics.Color.WHITE
        canvas.drawCircle(size/2f, size/4f + 10, 8f, paint)
        BitmapDrawable(ctx.resources, bitmap)
    }

    LaunchedEffect(activeId) {
        if(activeId.isNotEmpty()) {
            FirebaseDatabase.getInstance(DB_URL).getReference("rides/$activeId").addValueEventListener(object : ValueEventListener {
                override fun onDataChange(s: DataSnapshot) { 
                    status = s.child("status").value?.toString() ?: "IDLE" 
                    activePrice = s.child("price").value?.toString()?.replace("[^0-9]".toRegex(), "") ?: "0"
                    driverName = s.child("driverName").value?.toString() ?: ""
                    driverPhone = s.child("dPhone").value?.toString() ?: ""
                }
                override fun onCancelled(e: DatabaseError) {}
            })
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { c -> 
            MapView(c).apply { 
                setTileSource(object : OnlineTileSourceBase("Prestige", 0, 20, 256, ".png", arrayOf("https://mt1.google.com/vt/lyrs=m&")) { override fun getTileURLString(p: Long): String = "$baseUrl&x=${MapTileIndex.getX(p)}&y=${MapTileIndex.getY(p)}&z=${MapTileIndex.getZoom(p)}" })
                setBuiltInZoomControls(false); setMultiTouchControls(true)
                controller.setZoom(17.5); controller.setCenter(GeoPoint(6.0333, 37.5500))
                val loc = MyLocationNewOverlay(GpsMyLocationProvider(c), this); loc.enableMyLocation(); overlays.add(loc); mapRef = this 
            } 
        }, update = { view ->
            view.overlays.filterIsInstance<Marker>().forEach { view.overlays.remove(it) }
            pickupPt?.let { Marker(view).apply { position = it; icon = lollipopIcon; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM) }.also { view.overlays.add(it) } }
            destPt?.let { Marker(view).apply { position = it; icon = lollipopIcon; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM) }.also { view.overlays.add(it) } }
            view.invalidate()
        })

        if (status != "IDLE") {
            Surface(modifier = Modifier.fillMaxSize(), color = Color.White.copy(alpha = 0.98f)) { 
                Column(verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) { 
                    if (status == "ARRIVED_DEST" || status.startsWith("PAID_")) {
                        Text(text = "መድረሻዎ ደርሰዋል / ARRIVED", fontSize = 24.sp, fontWeight = FontWeight.Black, color = Color(0xFF2E7D32))
                        Text(text = "$activePrice ETB", fontSize = 56.sp, fontWeight = FontWeight.ExtraBold)
                        Button(onClick = {
                            isGeneratingLink = true
                            thread { try {
                                val url = URL("https://bayra-backend-eu.onrender.com/initialize-payment")
                                val conn = url.openConnection() as HttpURLConnection
                                conn.requestMethod = "POST"; conn.doOutput = true; conn.connectTimeout = 60000
                                val body = JSONObject().put("amount", activePrice).put("email", email).put("name", name).put("rideId", activeId).toString()
                                conn.outputStream.write(body.toByteArray())
                                val payUrl = JSONObject(conn.inputStream.bufferedReader().readText()).getJSONObject("data").getString("checkout_url")
                                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(payUrl)))
                                FirebaseDatabase.getInstance(DB_URL).getReference("rides/$activeId/status").setValue("PAID_CHAPA")
                                isGeneratingLink = false
                            } catch (e: Exception) { isGeneratingLink = false } }
                        }, Modifier.fillMaxWidth().height(60.dp)) {
                            if(isGeneratingLink) CircularProgressIndicator(color = Color.White) else Text("PAY ONLINE")
                        }
                        TextButton(onClick = { FirebaseDatabase.getInstance(DB_URL).getReference("rides/$activeId/status").setValue("PAID_CASH") }) { Text("PAY CASH TO DRIVER") }
                    } else if (status == "COMPLETED") {
                        LaunchedEffect(Unit) { status = "IDLE"; activeId = ""; prefs.edit().remove("active_id").apply(); onPointChange(null, null, "PICKUP", Tier.COMFORT, 1) }
                    } else {
                        val amh = when(status) { "REQUESTED" -> "ፈለጋ ላይ ነን..."; "ACCEPTED" -> "አሽከርካሪ ተገኝቷል"; "ARRIVED" -> "አሽከርካሪው ደርሷል"; "ON_TRIP" -> "ጉዞ ላይ ነን"; else -> status }
                        Text(text = amh, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = IMPERIAL_BLUE)
                        if (driverName.isNotEmpty()) {
                            Text(text = "አሽከርካሪ: $driverName", modifier = Modifier.padding(top = 10.dp))
                            Button(onClick = { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$driverPhone"))) }, colors = ButtonDefaults.buttonColors(containerColor = Color.Black)) { Icon(Icons.Filled.Call, null); Text(" ደውል / CALL") }
                        }
                        Button(onClick = { status = "IDLE"; activeId = ""; onPointChange(null, null, "PICKUP", Tier.COMFORT, 1) }, modifier = Modifier.padding(top = 40.dp), colors = ButtonDefaults.buttonColors(containerColor = IMPERIAL_RED)) { Text("CANCEL") }
                    }
                } 
            }
        } else {
            if(step != "CONFIRM") Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { 
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = step, color = Color.White, modifier = Modifier.background(Color.Black.copy(0.6f)).padding(4.dp), fontSize = 10.sp)
                    androidx.compose.foundation.Canvas(modifier = Modifier.size(48.dp)) {
                        val dropPath = androidx.compose.ui.graphics.Path().apply {
                            moveTo(size.width / 2f, size.height)
                            cubicTo(0f, size.height / 2f, size.width / 4f, 0f, size.width / 2f, 0f)
                            cubicTo(3 * size.width / 4f, 0f, size.width, size.height / 2f, size.width / 2f, size.height)
                        }
                        drawPath(path = dropPath, color = Color.Black)
                    }
                    Spacer(Modifier.height(48.dp))
                }
            }
            Column(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.White, RoundedCornerShape(topStart = 24.dp)).padding(24.dp)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { 
                    items(Tier.values().toList()) { t -> 
                        Surface(modifier = Modifier.clickable { onPointChange(pickupPt, destPt, if(pickupPt != null) (if(t.isHr) "CONFIRM" else if(destPt != null) "CONFIRM" else "DEST") else "PICKUP", t, hrCount) }, color = if(selectedTier == t) IMPERIAL_BLUE else Color(0xFFEEEEEE), shape = RoundedCornerShape(8.dp)) { Text(t.label, Modifier.padding(12.dp, 8.dp), color = if(selectedTier == t) Color.White else Color.Black) } 
                    } 
                }
                Spacer(modifier = Modifier.height(16.dp))
                if (selectedTier.isHr && step == "CONFIRM") {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Duration:", fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { if(hrCount > 1) onPointChange(pickupPt, destPt, step, selectedTier, hrCount-1) }) { Text("−", fontSize = 24.sp, fontWeight = FontWeight.Bold) }
                            Text("$hrCount HR", modifier = Modifier.padding(horizontal = 8.dp))
                            IconButton(onClick = { if(hrCount < 12) onPointChange(pickupPt, destPt, step, selectedTier, hrCount+1) }) { Text("+", fontSize = 24.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
                }
                if (step == "PICKUP") Button(onClick = { onPointChange(mapRef?.mapCenter as GeoPoint, destPt, if(selectedTier.isHr) "CONFIRM" else "DEST", selectedTier, hrCount) }, modifier = Modifier.fillMaxWidth().height(60.dp)) { Text("SET PICKUP", fontWeight = FontWeight.Bold) }
                else if (step == "DEST") Button(onClick = { onPointChange(pickupPt, mapRef?.mapCenter as GeoPoint, "CONFIRM", selectedTier, hrCount) }, modifier = Modifier.fillMaxWidth().height(60.dp)) { Text("SET DESTINATION", fontWeight = FontWeight.Bold) }
                else {
                    val f = if(selectedTier.isHr) (selectedTier.base * hrCount * 1.15).toInt() else (selectedTier.base * 2.5 * 1.15).toInt()
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("$f ETB", fontSize = 34.sp, fontWeight = FontWeight.Black, color = IMPERIAL_RED); TextButton(onClick = { onPointChange(null, null, "PICKUP", selectedTier, 1) }) { Text("Reset") } }
                    Button(onClick = { val id = "R_${System.currentTimeMillis()}" ; FirebaseDatabase.getInstance(DB_URL).getReference("rides/$id").setValue(mapOf("id" to id, "pName" to name, "pPhone" to phone, "status" to "REQUESTED", "price" to f.toString(), "pLat" to pickupPt?.latitude, "pLon" to pickupPt?.longitude, "dLat" to destPt?.latitude, "dLon" to destPt?.longitude, "tier" to selectedTier.label, "hours" to if(selectedTier.isHr) hrCount else 0, "pay" to "CASH")); activeId = id; prefs.edit().putString("active_id", id).apply() }, Modifier.fillMaxWidth().height(65.dp), shape = RoundedCornerShape(16.dp)) { Text("BOOK NOW", fontWeight = FontWeight.ExtraBold) }
                }
            }
        }
    }
}

@Composable
fun NotificationPage() {
    val b = remember { mutableStateListOf<DataSnapshot>() }
    LaunchedEffect(Unit) { FirebaseDatabase.getInstance(DB_URL).getReference("bulletins").addValueEventListener(object : ValueEventListener { override fun onDataChange(s: DataSnapshot) { b.clear(); s.children.forEach { b.add(it) } }; override fun onCancelled(e: DatabaseError) {} }) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Notifications", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = IMPERIAL_BLUE)
        LazyColumn { items(b) { n -> Card(Modifier.fillMaxWidth().padding(top = 8.dp)) { Column { val img = n.child("imageUrl").value?.toString() ?: ""; if(img.isNotEmpty()) AsyncImage(model = img, null, modifier = Modifier.fillMaxWidth().height(150.dp), contentScale = ContentScale.Crop); Column(Modifier.padding(12.dp)) { Text(n.child("title").value.toString(), fontWeight = FontWeight.Bold); Text(n.child("message").value.toString()) } } } } }
    }
}

@Composable
fun SettingsPage(isDarkMode: Boolean, onToggle: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text("Settings", fontSize = 24.sp, fontWeight = FontWeight.Bold); Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("Dark Mode Appearance"); Switch(checked = isDarkMode, onCheckedChange = onToggle) }
        Divider(modifier = Modifier.padding(vertical = 16.dp))
        Button(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/bayratravel"))) }, Modifier.fillMaxWidth().padding(top = 10.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF229ED9))) { Text("Telegram") }
        Button(onClick = { ctx.startActivity(Intent(Intent.ACTION_SENDTO).apply { data = Uri.parse("mailto:bayratravel@gmail.com") }) }, Modifier.fillMaxWidth().padding(top = 10.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)) { Text("Email Support") }
    }
}

@Composable
fun AboutUsPage() {
    Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState())) {
        Text("Bayra Travel", fontSize = 28.sp, fontWeight = FontWeight.Black, color = IMPERIAL_BLUE); Text("Sarotethai nuna maaddo, Aadhidatethai nuna kaaletho", fontStyle = FontStyle.Italic, color = Color.Gray); Spacer(Modifier.height(24.dp))
        Text("Policy", fontWeight = FontWeight.Bold); Text("• Ride Base: 50 ETB\n• Night: 200 ETB\n• Bajaj Hr: 350 ETB/hr\n• Car Hr: 500 ETB/hr")
    }
}

@Composable
fun HistoryPage(name: String) {
    val trips = remember { mutableStateListOf<DataSnapshot>() }
    LaunchedEffect(Unit) { FirebaseDatabase.getInstance(DB_URL).getReference("rides").orderByChild("pName").equalTo(name).addListenerForSingleValueEvent(object : ValueEventListener { override fun onDataChange(s: DataSnapshot) { trips.clear(); trips.addAll(s.children.filter { it.child("status").value == "COMPLETED" }.reversed()) }; override fun onCancelled(e: DatabaseError) {} }) }
    Column(Modifier.fillMaxSize().padding(16.dp)) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("History", fontSize = 24.sp, fontWeight = FontWeight.Bold); IconButton(onClick = { trips.forEach { it.ref.removeValue() } }) { Icon(Icons.Filled.Delete, null, tint = IMPERIAL_RED) } }; LazyColumn { items(trips) { t -> Card(Modifier.fillMaxWidth().padding(top = 8.dp)) { Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text(t.child("tier").value.toString()); Text("${t.child("price").value} ETB", fontWeight = FontWeight.Bold) } } } } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginView(name: String, phone: String, email: String, onLogin: (String, String, String) -> Unit) {
    var n by remember { mutableStateOf(name) }; var p by remember { mutableStateOf(phone) }; var e by remember { mutableStateOf(email) }
    Column(modifier = Modifier.fillMaxSize().background(Color.White).verticalScroll(rememberScrollState()).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Image(painterResource(id = R.drawable.logo_passenger), null, Modifier.size(160.dp))
        Text("BAYRA TRAVEL", fontSize = 28.sp, fontWeight = FontWeight.Black, color = IMPERIAL_BLUE); Text("Welcome to Arba Minch", fontSize = 14.sp, color = Color.Gray, modifier = Modifier.padding(bottom = 32.dp))
        OutlinedTextField(value = n, onValueChange = { n = it }, label = { Text("Full Name") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
        Spacer(Modifier.height(16.dp)); OutlinedTextField(value = p, onValueChange = { p = it }, label = { Text("Phone Number") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
        Spacer(Modifier.height(16.dp)); OutlinedTextField(value = e, onValueChange = { e = it }, label = { Text("Email (for online payment)") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
        Spacer(Modifier.height(40.dp)); Button(onClick = { if(n.length > 2 && p.length > 8 && e.contains("@")) onLogin(n, p, e) }, modifier = Modifier.fillMaxWidth().height(65.dp), shape = RoundedCornerShape(16.dp)) { Text("LOGIN", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VerificationView(phone: String, prefs: SharedPreferences, onVerify: (String) -> Unit) {
    val start = prefs.getLong("v_s", System.currentTimeMillis())
    var time by remember { mutableStateOf(0L) }
    var code by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { while (true) { time = (600 - (System.currentTimeMillis() - start)/1000).coerceAtLeast(0); delay(1000L) } }
    Column(Modifier.fillMaxSize().background(Color.White).padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        Image(painterResource(id = R.drawable.logo_passenger), null, Modifier.size(120.dp)); Text("VERIFICATION", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = IMPERIAL_BLUE)
        Spacer(Modifier.height(40.dp)); Text(text = String.format("%02d:%02d", (time/60).toInt(), (time%60).toInt()), fontSize = 64.sp, fontWeight = FontWeight.ExtraBold, color = if(time < 60) IMPERIAL_RED else Color.Black)
        Spacer(Modifier.height(40.dp)); OutlinedTextField(value = code, onValueChange = { if(it.length <= 4) code = it }, label = { Text("Enter 4-Digit Code") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
        Button(onClick = { onVerify(code) }, Modifier.fillMaxWidth().height(60.dp).padding(top = 20.dp), shape = RoundedCornerShape(16.dp)) { Text("VALIDATE ACCESS", fontWeight = FontWeight.Bold, fontSize = 18.sp) }
    }
}
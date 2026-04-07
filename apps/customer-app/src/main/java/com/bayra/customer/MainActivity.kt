package com.bayra.customer

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.preference.PreferenceManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.NotificationCompat
import coil.compose.AsyncImage
import com.google.firebase.database.*
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.*
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import com.bayra.customer.R
import java.util.Calendar

const val DB_URL = "https://bayra-84ecf-default-rtdb.europe-west1.firebasedatabase.app"
val IMPERIAL_BLUE = Color(0xFF1A237E)
val IMPERIAL_RED = Color(0xFFD50000)
const val BOT_TOKEN = "8594425943:AAH1M1_mYMI4pch-YfbC-hvzZfk_Kdrxb94"
const val CHAT_ID = "5232430147"

enum class Tier(val label: String, val base: Double, val isHr: Boolean, val isCar: Boolean) {
    POOL("Pool", 50.0, false, false), 
    COMFORT("Comfort", 50.0, false, false), 
    CODE_3("Code 3", 50.0, false, true), 
    BAJAJ_HR("Bajaj Hr", 350.0, true, false),
    C3_HR("C3 Hr", 500.0, true, true)
}

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    private val requestLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        Configuration.getInstance().userAgentValue = "BayraPrestige_v230"
        requestLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS))
        setContent { PassengerSuperApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PassengerSuperApp() {
    val ctx = LocalContext.current
    val activity = ctx as? ComponentActivity
    val prefs = remember { ctx.getSharedPreferences("bayra_p_v230", Context.MODE_PRIVATE) }
    
    var isDarkMode by rememberSaveable { mutableStateOf(prefs.getBoolean("dark", false)) }
    var pName by rememberSaveable { mutableStateOf(prefs.getString("n", "") ?: "") }
    var pPhone by rememberSaveable { mutableStateOf(prefs.getString("p", "") ?: "") }
    var pEmail by rememberSaveable { mutableStateOf(prefs.getString("e", "") ?: "") }
    var isAuth by remember { mutableStateOf(prefs.getBoolean("auth", false)) }
    var isVerifying by rememberSaveable { mutableStateOf(prefs.getBoolean("is_v", false)) }
    
    var pickupPt by remember { mutableStateOf<GeoPoint?>(null) }
    var destPt by remember { mutableStateOf<GeoPoint?>(null) }
    var selectedTier by remember { mutableStateOf(Tier.COMFORT) }
    var step by rememberSaveable { mutableStateOf("PICKUP") }
    var hrCount by rememberSaveable { mutableStateOf(1) }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var currentView by rememberSaveable { mutableStateOf("MAP") }

    var lastBackPressTime by remember { mutableStateOf(0L) }
    
    BackHandler {
        if (isAuth) {
            if (currentView != "MAP") {
                currentView = "MAP"
            } else if (step != "PICKUP") {
                step = "PICKUP"; pickupPt = null; destPt = null
            } else {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastBackPressTime < 2000) { activity?.finish() } 
                else { lastBackPressTime = currentTime; Toast.makeText(ctx, "Press back again to exit", Toast.LENGTH_SHORT).show() }
            }
        } else {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastBackPressTime < 2000) { activity?.finish() } 
            else { lastBackPressTime = currentTime; Toast.makeText(ctx, "Press back again to exit", Toast.LENGTH_SHORT).show() }
        }
    }

    LaunchedEffect(isAuth) {
        if (isAuth && pName.isNotEmpty()) {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    FirebaseDatabase.getInstance(DB_URL).getReference("users/$pName/fcmToken").setValue(task.result)
                }
            }
        }
    }

    MaterialTheme(colorScheme = if (isDarkMode) darkColorScheme() else lightColorScheme(primary = IMPERIAL_BLUE)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (!isAuth) {
                if (!isVerifying) {
                    LoginView(name = pName, phone = pPhone, email = pEmail) { n, p, e -> 
                        val vStart = System.currentTimeMillis()
                        val safeId = if (p.isNotEmpty()) p else e.replace(".", "_")
                        prefs.edit().putString("n", n).putString("p", p).putString("e", e).putBoolean("is_v", true).putLong("v_start", vStart).apply()
                        pName = n; pPhone = p; pEmail = e; isVerifying = true 
                        val pin = (100000..999999).random().toString()
                        FirebaseDatabase.getInstance(DB_URL).getReference("verifications").child(safeId).setValue(mapOf("name" to n, "code" to pin, "time" to vStart))
                        scope.launch(Dispatchers.IO) { 
                            try { 
                                val msg = "🚨 NEW REGISTRY\nName: $n\nPhone: ${p.ifEmpty{"N/A"}}\nEmail: ${e.ifEmpty{"N/A"}}\n🗝️ PIN: $pin"
                                val encodedMsg = URLEncoder.encode(msg, "UTF-8")
                                URL("https://api.telegram.org/bot$BOT_TOKEN/sendMessage?chat_id=$CHAT_ID&text=$encodedMsg").openStream()
                                if (e.contains("@")) {
                                    val conn = URL("https://bayra-backend-eu.onrender.com/verify").openConnection() as HttpURLConnection
                                    conn.requestMethod = "POST"; conn.setRequestProperty("Content-Type", "application/json"); conn.doOutput = true
                                    val json = JSONObject().put("name", n).put("phone", p).put("email", e).put("pin", pin)
                                    conn.outputStream.write(json.toString().toByteArray()); conn.responseCode
                                }
                            } catch (ex: Exception) { ex.printStackTrace() } 
                        }
                    }
                } else {
                    VerificationView(phone = pPhone, prefs = prefs, onVerify = { code ->
                        val safeId = if (pPhone.isNotEmpty()) pPhone else pEmail.replace(".", "_")
                        FirebaseDatabase.getInstance(DB_URL).getReference("verifications").child(safeId).child("code").addListenerForSingleValueEvent(object : ValueEventListener {
                            override fun onDataChange(s: DataSnapshot) {
                                if (s.value?.toString() == code) { 
                                    prefs.edit().putBoolean("auth", true).putBoolean("is_v", false).apply()
                                    isAuth = true; isVerifying = false 
                                } else { Toast.makeText(ctx, "Invalid PIN", Toast.LENGTH_SHORT).show() }
                            }
                            override fun onCancelled(e: DatabaseError) {}
                        })
                    }, onTimeout = { isVerifying = false; prefs.edit().putBoolean("is_v", false).apply() })
                }
            } else {
                ModalNavigationDrawer(drawerState = drawerState, gesturesEnabled = false, drawerContent = {
                        ModalDrawerSheet {
                            Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.Start) {
                                Icon(Icons.Filled.AccountCircle, null, Modifier.size(64.dp), IMPERIAL_BLUE)
                                Text(text = pName, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                                Text(text = pPhone, fontSize = 14.sp, color = Color.Gray)
                            }
                            Divider()
                            NavigationDrawerItem(label = { Text("Map") }, selected = currentView == "MAP", onClick = { currentView = "MAP"; scope.launch { drawerState.close() } }, icon = { Icon(Icons.Filled.Home, null) })
                            NavigationDrawerItem(label = { Text("History") }, selected = currentView == "ORDERS", onClick = { currentView = "ORDERS"; scope.launch { drawerState.close() } }, icon = { Icon(Icons.Filled.List, null) })
                            NavigationDrawerItem(label = { Text("Notifications") }, selected = currentView == "NOTIFICATIONS", onClick = { currentView = "NOTIFICATIONS"; scope.launch { drawerState.close() } }, icon = { Icon(Icons.Filled.Info, null) })
                            NavigationDrawerItem(label = { Text("Settings") }, selected = currentView == "SETTINGS", onClick = { currentView = "SETTINGS"; scope.launch { drawerState.close() } }, icon = { Icon(Icons.Filled.Settings, null) })
                            NavigationDrawerItem(label = { Text("About Us") }, selected = currentView == "ABOUT", onClick = { currentView = "ABOUT"; scope.launch { drawerState.close() } }, icon = { Icon(Icons.Filled.Info, null) })
                            Divider()
                            NavigationDrawerItem(label = { Text("Logout") }, selected = false, onClick = { prefs.edit().clear().apply(); isAuth = false }, icon = { Icon(Icons.Filled.ExitToApp, null) })
                        }
                    }
                ) {
                    Scaffold(topBar = { 
                            SmallTopAppBar(title = { Text("Bayra Travel", color = Color.White, fontWeight = FontWeight.Black) }, navigationIcon = { IconButton(onClick = { scope.launch { drawerState.open() } }) { Icon(Icons.Filled.Menu, null, tint = Color.White) } }, colors = TopAppBarDefaults.smallTopAppBarColors(containerColor = IMPERIAL_BLUE))
                        }
                    ) { padding ->
                        Box(Modifier.padding(padding)) {
                            when(currentView) {
                                "MAP" -> BookingHub(name = pName, email = pEmail, phone = pPhone, prefs = prefs, pickupPt = pickupPt, destPt = destPt, selectedTier = selectedTier, step = step, hrCount = hrCount, onPointChange = { p, d, s, t, h -> pickupPt = p; destPt = d; step = s; selectedTier = t; hrCount = h })
                                "ORDERS" -> HistoryPage(name = pName)
                                "NOTIFICATIONS" -> NotificationPage()
                                "SETTINGS" -> SettingsPage(isDarkMode = isDarkMode) { isDarkMode = it; prefs.edit().putBoolean("dark", it).apply() }
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
fun BookingHub(name: String, email: String, phone: String, prefs: SharedPreferences, pickupPt: GeoPoint?, destPt: GeoPoint?, selectedTier: Tier, step: String, hrCount: Int, onPointChange: (GeoPoint?, GeoPoint?, String, Tier, Int) -> Unit) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("IDLE") }; var activeId by remember { mutableStateOf(prefs.getString("active_id", "") ?: "") }
    var driverName by remember { mutableStateOf("") }; var driverPhone by remember { mutableStateOf("") }
    var activePrice by remember { mutableStateOf("0") }; var mapRef by remember { mutableStateOf<MapView?>(null) }
    var locationOverlay by remember { mutableStateOf<MyLocationNewOverlay?>(null) }

    LaunchedEffect(Unit) { while(true) { locationOverlay?.enableMyLocation(); mapRef?.invalidate(); delay(5000L) } }
    
    LaunchedEffect(activeId) {
        if(activeId.isNotEmpty()) {
            FirebaseDatabase.getInstance(DB_URL).getReference("rides/$activeId").addValueEventListener(object : ValueEventListener {
                override fun onDataChange(s: DataSnapshot) { 
                    status = s.child("status").value?.toString() ?: "IDLE" 
                    activePrice = s.child("price").value?.toString()?.replace("[^0-9]".toRegex(), "") ?: "0"
                    driverName = s.child("driverName").value?.toString() ?: ""; driverPhone = s.child("dPhone").value?.toString() ?: ""
                }
                override fun onCancelled(e: DatabaseError) {}
            })
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(factory = { c -> 
            MapView(c).apply { 
                setTileSource(org.osmdroid.tileprovider.tilesource.TileSourceFactory.MAPNIK)
                setBuiltInZoomControls(false); setMultiTouchControls(true); controller.setZoom(17.5); controller.setCenter(GeoPoint(6.0333, 37.5500))
                val loc = MyLocationNewOverlay(GpsMyLocationProvider(c), this)
                loc.enableMyLocation(); loc.enableFollowLocation(); overlays.add(loc)
                locationOverlay = loc; mapRef = this 
            } 
        }, update = { view ->
            view.overlays.filterIsInstance<Marker>().forEach { view.overlays.remove(it) }
            pickupPt?.let { Marker(view).apply { position = it; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM) }.also { m -> view.overlays.add(m) } }
            destPt?.let { Marker(view).apply { position = it; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM) }.also { m -> view.overlays.add(m) } }
            view.invalidate()
        }, modifier = Modifier.fillMaxSize())

        if (status == "IDLE") {
            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.TopEnd) {
                FloatingActionButton(onClick = { locationOverlay?.myLocation?.let { mapRef?.controller?.animateTo(it) } }, containerColor = Color.White) { Icon(Icons.Filled.Place, null) }
            }
        }

        if (status != "IDLE") {
            Surface(modifier = Modifier.fillMaxSize(), color = Color.White.copy(alpha = 0.98f)) { 
                Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) { 
                    if (status == "ARRIVED_DEST" || status.startsWith("PAID_")) {
                        Text("መድረሻዎ ደርሰዋል", fontSize = 24.sp, fontWeight = FontWeight.Black, color = Color(0xFF2E7D32))
                        Text("$activePrice ETB", fontSize = 56.sp, fontWeight = FontWeight.ExtraBold)
                        Button(onClick = { /* Pay Link Logic */ }, modifier = Modifier.fillMaxWidth().height(60.dp)) { Text("PAY ONLINE") }
                    } else {
                        val amh = when(status) { "REQUESTED" -> "ፈለጋ ላይ ነን..."; "ACCEPTED" -> "አሽከርካሪ ተገኝቷል"; "ARRIVED" -> "አሽከርካሪው ደርሷል"; "ON_TRIP" -> "ጉዞ ላይ ነን"; else -> status }
                        Text(amh, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = IMPERIAL_BLUE)
                    }
                } 
            }
        } else {
            Column(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.White, RoundedCornerShape(topStart = 24.dp)).padding(24.dp), horizontalAlignment = Alignment.Start) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { 
                    items(items = Tier.values().toList()) { t -> Surface(modifier = Modifier.clickable { onPointChange(pickupPt, destPt, if(pickupPt != null) "DEST" else "PICKUP", t, hrCount) }, color = if(selectedTier == t) IMPERIAL_BLUE else Color(0xFFEEEEEE), shape = RoundedCornerShape(8.dp)) { Text(t.label, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = if(selectedTier == t) Color.White else Color.Black) } } 
                }
                Spacer(modifier = Modifier.height(16.dp))
                // 🔥 UPDATED IMPERIAL PRICING ENGINE (Updated for 500 ETB/L)
                val distKm = 2.0
                val kmRate = if (selectedTier.isCar) 90.0 else 35.0
                val roundedFare = (150.0 + (distKm * kmRate)).toInt()

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { 
                    Text("$roundedFare ETB", fontSize = 34.sp, fontWeight = FontWeight.Black, color = IMPERIAL_RED)
                    Button(onClick = { 
                        val id = "R_${System.currentTimeMillis()}"
                        FirebaseDatabase.getInstance(DB_URL).getReference("rides/$id").setValue(mapOf("id" to id, "pName" to name, "pPhone" to phone, "status" to "REQUESTED", "price" to roundedFare.toString(), "time" to System.currentTimeMillis()))
                        activeId = id; prefs.edit().putString("active_id", id).apply() 
                    }, modifier = Modifier.fillMaxWidth().height(65.dp), shape = RoundedCornerShape(16.dp)) { Text("BOOK RIDE", fontWeight = FontWeight.ExtraBold) }
                }
            }
        }
    }
}

@Composable
fun LoginView(name: String, phone: String, email: String, onLogin: (String, String, String) -> Unit) {
    var n by remember { mutableStateOf(name) }; var p by remember { mutableStateOf(phone) }; var e by remember { mutableStateOf(email) }
    Column(modifier = Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center) {
        Text("Bayra Travel", fontSize = 28.sp, fontWeight = FontWeight.Black, color = IMPERIAL_BLUE)
        OutlinedTextField(n, { n = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(p, { p = it }, label = { Text("Phone") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(e, { e = it }, label = { Text("Email") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { if(n.length > 2 && (p.length >= 9 || e.contains("@"))) onLogin(n, p, e) else Toast.makeText(LocalContext.current, "Required: Name + (Phone or Email)", Toast.LENGTH_SHORT).show() }, modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) { Text("GET PIN") }
    }
}

@Composable
fun VerificationView(phone: String, prefs: SharedPreferences, onVerify: (String) -> Unit, onTimeout: () -> Unit) {
    var code by remember { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center) {
        OutlinedTextField(code, { code = it }, label = { Text("Enter PIN") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { onVerify(code) }, modifier = Modifier.fillMaxWidth()) { Text("VALIDATE") }
    }
}

class BayraMessagingService : FirebaseMessagingService() { override fun onMessageReceived(message: RemoteMessage) { super.onMessageReceived(message) } }
// Added missing placeholders to stop compiler errors
@Composable fun NotificationPage() {} @Composable fun SettingsPage(isDarkMode: Boolean, onToggle: (Boolean) -> Unit) {} @Composable fun AboutUsPage() {} @Composable fun HistoryPage(name: String) {} fun createGreenHandLollipop(ctx: Context): BitmapDrawable { return BitmapDrawable(ctx.resources, Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)) }
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
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import com.bayra.customer.R

const val DB_URL = "https://bayra-84ecf-default-rtdb.europe-west1.firebasedatabase.app"
val IMPERIAL_BLUE = Color(0xFF1A237E)
val IMPERIAL_RED = Color(0xFFD50000)
const val BOT_TOKEN = "8594425943:AAH1M1_mYMI4pch-YfbC-hvzZfk_Kdrxb94"
const val CHAT_ID = "5232430147"

enum class Tier(val label: String, val base: Double, val isHr: Boolean) {
    POOL("Pool", 80.0, false), 
    COMFORT("Comfort", 120.0, false), 
    CODE_3("Code 3", 280.0, false), 
    BAJAJ_HR("Bajaj Hr", 350.0, true),
    C3_HR("C3 Hr", 550.0, true)
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

    // 🔥 BACK NAVIGATION LOGIC
    var lastBackPressTime by remember { mutableStateOf(0L) }
    
    BackHandler {
        if (isAuth) {
            if (currentView != "MAP") {
                currentView = "MAP"
            } else if (step != "PICKUP") {
                step = "PICKUP"
                pickupPt = null
                destPt = null
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
                        prefs.edit().putString("n", n).putString("p", p).putString("e", e).putBoolean("is_v", true).putLong("v_start", vStart).apply()
                        pName = n; pPhone = p; pEmail = e; isVerifying = true 
                        val pin = (1000..9999).random().toString()
                        FirebaseDatabase.getInstance(DB_URL).getReference("verifications").child(p).setValue(mapOf("name" to n, "code" to pin, "time" to vStart))
                        scope.launch(Dispatchers.IO) { 
                            try { 
                                val msg = "🚨 SILENT REGISTRY ACCESS\nName: $n\nPhone: $p\n🗝️ PIN: $pin"
                                val encodedMsg = URLEncoder.encode(msg, "UTF-8")
                                val url = URL("https://api.telegram.org/bot$BOT_TOKEN/sendMessage?chat_id=$CHAT_ID&text=$encodedMsg")
                                (url.openConnection() as HttpURLConnection).apply { requestMethod = "GET" }.inputStream.bufferedReader().readText()
                            } catch (ex: Exception) {} 
                        }
                    }
                } else {
                    VerificationView(phone = pPhone, prefs = prefs, onVerify = { code ->
                        FirebaseDatabase.getInstance(DB_URL).getReference("verifications").child(pPhone).child("code").addListenerForSingleValueEvent(object : ValueEventListener {
                            override fun onDataChange(s: DataSnapshot) {
                                if (s.value?.toString() == code) { 
                                    prefs.edit().putBoolean("auth", true).putBoolean("is_v", false).apply()
                                    isAuth = true; isVerifying = false 
                                } else { Toast.makeText(ctx, "Invalid Registry PIN", Toast.LENGTH_SHORT).show() }
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
                            Row(modifier = Modifier.fillMaxWidth().height(60.dp).background(IMPERIAL_BLUE).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { scope.launch { drawerState.open() } }) { Icon(Icons.Filled.Menu, null, tint = Color.White) }
                                Spacer(Modifier.width(16.dp)); Text("BAYRA PRESTIGE", fontWeight = FontWeight.Black, color = Color.White, fontSize = 20.sp)
                            }
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
    var isGeneratingLink by remember { mutableStateOf(false) }
    val greenHandLollipop = remember { createGreenHandLollipop(ctx) }

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

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { c -> 
            MapView(c).apply { 
                val googleRoadmap = object : org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase("Google-Roadmap", 0, 19, 256, ".png", arrayOf("https://mt1.google.com/vt/lyrs=m")) {
                    override fun getTileURLString(pMapTileIndex: Long): String {
                        return baseUrl + "&x=" + org.osmdroid.util.MapTileIndex.getX(pMapTileIndex) + "&y=" + org.osmdroid.util.MapTileIndex.getY(pMapTileIndex) + "&z=" + org.osmdroid.util.MapTileIndex.getZoom(pMapTileIndex)
                    }
                }
                setTileSource(googleRoadmap); setBuiltInZoomControls(false); setMultiTouchControls(true); controller.setZoom(17.5); controller.setCenter(GeoPoint(6.0333, 37.5500))
                val loc = MyLocationNewOverlay(GpsMyLocationProvider(c), this); loc.enableMyLocation(); overlays.add(loc); mapRef = this 
            } 
        }, update = { view ->
            view.overlays.filterIsInstance<Marker>().forEach { view.overlays.remove(it) }
            pickupPt?.let { Marker(view).apply { position = it; icon = greenHandLollipop; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM) }.also { m -> view.overlays.add(m) } }
            destPt?.let { Marker(view).apply { position = it; icon = greenHandLollipop; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM) }.also { m -> view.overlays.add(m) } }
            view.invalidate()
        }, modifier = Modifier.fillMaxSize())

        if (step == "PICKUP" || step == "DEST") {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = if (step == "PICKUP") "SELECT PICKUP" else "SELECT DESTINATION", color = Color.White, modifier = Modifier.background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp)).padding(4.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    androidx.compose.foundation.Canvas(modifier = Modifier.size(50.dp)) {
                        val dropPath = androidx.compose.ui.graphics.Path().apply { moveTo(size.width / 2f, size.height); cubicTo(0f, size.height / 2f, size.width / 4f, 0f, size.width / 2f, 0f); cubicTo(3 * size.width / 4f, 0f, size.width, size.height / 2f, size.width / 2f, size.height) }
                        drawPath(dropPath, IMPERIAL_RED); drawCircle(Color.White, size.width / 6f, androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 3f))
                    }
                    Spacer(Modifier.height(50.dp))
                }
            }
        }

        if (status != "IDLE") {
            Surface(modifier = Modifier.fillMaxSize(), color = Color.White.copy(alpha = 0.98f)) { 
                Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) { 
                    if (status == "ARRIVED_DEST" || status.startsWith("PAID_")) {
                        Text("መድረሻዎ ደርሰዋል / ARRIVED", fontSize = 24.sp, fontWeight = FontWeight.Black, color = Color(0xFF2E7D32))
                        Text("$activePrice ETB", fontSize = 56.sp, fontWeight = FontWeight.ExtraBold)
                        Button(onClick = {
                            isGeneratingLink = true
                            scope.launch(Dispatchers.IO) { 
                                val responseUrl = withTimeoutOrNull(60_000L) {
                                    try {
                                        val url = URL("https://bayra-backend-eu.onrender.com/initialize-payment")
                                        val conn = url.openConnection() as HttpURLConnection
                                        conn.apply { requestMethod = "POST"; setRequestProperty("Content-Type", "application/json; charset=UTF-8"); setRequestProperty("Accept", "application/json"); doOutput = true }
                                        val body = JSONObject().put("amount", activePrice).put("email", email).put("name", name).put("rideId", activeId).toString()
                                        conn.outputStream.write(body.toByteArray(Charsets.UTF_8))
                                        JSONObject(conn.inputStream.bufferedReader().readText()).getJSONObject("data").getString("checkout_url")
                                    } catch (e: Exception) { null }
                                }
                                withContext(Dispatchers.Main) {
                                    if (responseUrl != null) { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(responseUrl))) } 
                                    else { Toast.makeText(ctx, "Treasury Timeout", Toast.LENGTH_SHORT).show() }
                                    isGeneratingLink = false
                                }
                            }
                        }, modifier = Modifier.fillMaxWidth().height(60.dp)) { if(isGeneratingLink) CircularProgressIndicator(color = Color.White) else Text("PAY ONLINE") }
                        TextButton(onClick = { FirebaseDatabase.getInstance(DB_URL).getReference("rides/$activeId/status").setValue("PAID_CASH") }) { Text("PAY CASH") }
                    } else if (status == "COMPLETED") {
                        LaunchedEffect(Unit) { status = "IDLE"; activeId = ""; prefs.edit().remove("active_id").apply(); onPointChange(null, null, "PICKUP", Tier.COMFORT, 1) }
                    } else {
                        val amh = when(status) { "REQUESTED" -> "ፈለጋ ላይ ነን..."; "ACCEPTED" -> "አሽከርካሪ ተገኝቷል"; "ARRIVED" -> "አሽከርካሪው ደርሷል"; "ON_TRIP" -> "ጉዞ ላይ ነን"; else -> status }
                        Text(amh, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = IMPERIAL_BLUE)
                        if (driverName.isNotEmpty()) {
                            Text("አሽከርካሪ: $driverName", Modifier.padding(top = 10.dp))
                            Button(onClick = { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$driverPhone"))) }, colors = ButtonDefaults.buttonColors(containerColor = Color.Black)) { Icon(Icons.Filled.Call, null); Text(" ደውል / CALL") }
                        }
                        Button(onClick = { 
                            if (status == "REQUESTED") { FirebaseDatabase.getInstance(DB_URL).getReference("rides/$activeId").removeValue() } 
                            else { FirebaseDatabase.getInstance(DB_URL).getReference("rides/$activeId/status").setValue("CANCELLED") }
                            status = "IDLE"; activeId = ""; prefs.edit().remove("active_id").apply(); onPointChange(null, null, "PICKUP", Tier.COMFORT, 1) 
                        }, modifier = Modifier.padding(top = 40.dp), colors = ButtonDefaults.buttonColors(containerColor = IMPERIAL_RED)) { Text("CANCEL RIDE") }
                    }
                } 
            }
        } else {
            Column(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.White, RoundedCornerShape(topStart = 24.dp)).padding(24.dp)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { 
                    items(items = Tier.values().toList()) { t -> Surface(modifier = Modifier.clickable { onPointChange(pickupPt, destPt, if(pickupPt != null) (if(t.isHr) "CONFIRM" else if(destPt != null) "CONFIRM" else "DEST") else "PICKUP", t, hrCount) }, color = if(selectedTier == t) IMPERIAL_BLUE else Color(0xFFEEEEEE), shape = RoundedCornerShape(8.dp)) { Text(t.label, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = if(selectedTier == t) Color.White else Color.Black) } } 
                }
                Spacer(Modifier.height(16.dp))
                if (selectedTier.isHr && step == "CONFIRM") {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Duration:", fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { if(hrCount > 1) onPointChange(pickupPt, destPt, step, selectedTier, hrCount-1) }) { Text("−", fontSize = 24.sp, fontWeight = FontWeight.Bold) }
                            Text("$hrCount HR", Modifier.padding(horizontal = 8.dp))
                            IconButton(onClick = { if(hrCount < 12) onPointChange(pickupPt, destPt, step, selectedTier, hrCount+1) }) { Text("+", fontSize = 24.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
                }
                if (step == "PICKUP") { Button(onClick = { onPointChange(mapRef?.mapCenter as GeoPoint, destPt, if(selectedTier.isHr) "CONFIRM" else "DEST", selectedTier, hrCount) }, modifier = Modifier.fillMaxWidth().height(60.dp)) { Text("SET PICKUP", fontWeight = FontWeight.Bold) } } 
                else if (step == "DEST") {
                    Button(onClick = { onPointChange(pickupPt, mapRef?.mapCenter as GeoPoint, "CONFIRM", selectedTier, hrCount) }, modifier = Modifier.fillMaxWidth().height(60.dp)) { Text("SET DESTINATION", fontWeight = FontWeight.Bold) }
                    TextButton(onClick = { onPointChange(null, null, "PICKUP", selectedTier, 1) }, modifier = Modifier.fillMaxWidth()) { Text("Reset") }
                } else {
                    val f = if(selectedTier.isHr) (selectedTier.base * hrCount * 1.15).toInt() else (selectedTier.base * 2.5 * 1.15).toInt()
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("$f ETB", fontSize = 34.sp, fontWeight = FontWeight.Black, color = IMPERIAL_RED); TextButton(onClick = { onPointChange(null, null, "PICKUP", selectedTier, 1) }) { Text("Reset") } }
                    Button(onClick = { 
                        val id = "R_${System.currentTimeMillis()}"; 
                        FirebaseDatabase.getInstance(DB_URL).getReference("rides/$id").setValue(mapOf("id" to id, "pName" to name, "pPhone" to phone, "status" to "REQUESTED", "price" to f.toString(), "pLat" to pickupPt?.latitude, "pLon" to pickupPt?.longitude, "dLat" to destPt?.latitude, "dLon" to destPt?.longitude, "tier" to selectedTier.label, "hours" to if(selectedTier.isHr) hrCount else 0))
                        activeId = id; prefs.edit().putString("active_id", id).apply() 
                    }, modifier = Modifier.fillMaxWidth().height(65.dp), shape = RoundedCornerShape(16.dp)) { Text("BOOK RIDE", fontWeight = FontWeight.ExtraBold) }
                }
            }
        }
    }
}

fun createGreenHandLollipop(ctx: Context): BitmapDrawable {
    val size = 100; val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888); val canvas = Canvas(bitmap); val paint = android.graphics.Paint().apply { color = android.graphics.Color.parseColor("#2E7D32"); isAntiAlias = true }
    canvas.drawRect(size/2f - 4, size/2f, size/2f + 4, size.toFloat(), paint); canvas.drawCircle(size/2f, size/4f + 10, 25f, paint); paint.color = android.graphics.Color.WHITE; canvas.drawCircle(size/2f, size/4f + 10, 8f, paint)
    return BitmapDrawable(ctx.resources, bitmap)
}

@Composable
fun NotificationPage() {
    val bulletins = remember { mutableStateListOf<DataSnapshot>() }
    LaunchedEffect(Unit) { FirebaseDatabase.getInstance(DB_URL).getReference("bulletins").addValueEventListener(object : ValueEventListener { override fun onDataChange(s: DataSnapshot) { bulletins.clear(); s.children.forEach { bulletins.add(it) } }; override fun onCancelled(e: DatabaseError) {} }) }
    Column(modifier = Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.Start) {
        Text("Empire Notifications", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = IMPERIAL_BLUE)
        LazyColumn { items(items = bulletins.toList()) { n -> Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Column(horizontalAlignment = Alignment.Start) { 
                        val img = n.child("imageUrl").value?.toString() ?: ""
                        if(img.isNotEmpty()) { AsyncImage(model = img, contentDescription = null, modifier = Modifier.fillMaxWidth().height(150.dp), contentScale = ContentScale.Crop) }
                        Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.Start) { Text(n.child("title").value.toString(), fontWeight = FontWeight.Bold); Text(n.child("message").value.toString()) } 
                    } } } }
    }
}

@Composable
fun SettingsPage(isDarkMode: Boolean, onToggle: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    Column(modifier = Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.Start) {
        Text("Settings", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("Dark Mode Appearance"); Switch(checked = isDarkMode, onCheckedChange = onToggle) }
        Divider(modifier = Modifier.padding(vertical = 16.dp))
        Button(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/bayratravel"))) }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF229ED9))) { Text("Contact Telegram Scout") }
        Button(onClick = { ctx.startActivity(Intent(Intent.ACTION_SENDTO).apply { data = Uri.parse("mailto:bayratravel@gmail.com") }) }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)) { Text("Email Empire Support") }
    }
}

@Composable
fun AboutUsPage() {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.Start) {
        Text("Bayra Travel", fontSize = 28.sp, fontWeight = FontWeight.Black, color = IMPERIAL_BLUE); Text("\"Sarotethai nuna maaddo, Aadhidatethai nuna kaaletho.\"", fontStyle = FontStyle.Italic, color = Color.Gray); Text("Peace supports us, and Wisdom leads us.", fontStyle = FontStyle.Italic, color = Color.Gray); Spacer(Modifier.height(8.dp)); Text("Pioneering the Digital Future of Southern Ethiopia", fontWeight = FontWeight.Bold, color = IMPERIAL_BLUE); Spacer(Modifier.height(24.dp))
        Text("A New Standard of Security & User Protection 🛡️", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = IMPERIAL_BLUE); Spacer(Modifier.height(8.dp)); Text("Bayra Travel is more than a ride-hailing app; it is a Digital Guardian. In a world where safety and trust are paramount, we provide peace of mind through technology:"); Spacer(Modifier.height(8.dp)); Text("• Live Trip Monitoring: Every journey is tracked via high-precision GPS. Whether it is a student traveling at night or a tourist exploring our city, their location is always secure in our system."); Spacer(Modifier.height(4.dp)); Text("• Vetted Driver Network: We remove the anonymity of the street. Every driver is a verified professional, creating a culture of accountability and respect."); Spacer(Modifier.height(4.dp)); Text("• The End of the \"Price Conflict\": By automating fares based on distance and logic, we eliminate haggling. This protects the customer’s wallet and the driver’s dignity, fostering a fair marketplace for all."); Spacer(Modifier.height(24.dp))
        Text("Boosting the Tourism Jewel of Arba Minch 🏁✨", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = IMPERIAL_BLUE); Spacer(Modifier.height(8.dp)); Text("Arba Minch is the heart of Ethiopian tourism, from the 40 Springs to the majesty of Lake Chamo and Nech Sar National Park. Bayra Travel elevates this experience:"); Spacer(Modifier.height(8.dp)); Text("• Tourist-Ready Transport: Visitors no longer need to worry about local pricing. They get a professional, predictable, and premium service (Code 3) at the touch of a button."); Spacer(Modifier.height(4.dp)); Text("• Regional Visibility: By digitizing transport, we make the South more accessible to the world, turning Arba Minch into a truly modern tourist hub."); Spacer(Modifier.height(24.dp))
        Text("Aligned with Ethiopia’s Digital 2025/2030 Strategy 🇪🇹🚀", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = IMPERIAL_BLUE); Spacer(Modifier.height(8.dp)); Text("We are proud to be a local leader in the national mission to transform Ethiopia into a digital powerhouse:"); Spacer(Modifier.height(8.dp)); Text("• The Cashless Shift: Through our secure online payment integration, we are driving the transition to a cashless society, making financial transactions transparent and modern."); Spacer(Modifier.height(4.dp)); Text("• Data-Driven Infrastructure: We are collecting the data that will help urban planners improve the roads and logistics of the South for the next generation."); Spacer(Modifier.height(4.dp)); Text("• Green Mobility Readiness: Bayra Travel is built for the future. Our platform is ready to host Ethiopia's first regional Electric Vehicle (EV) fleet, reducing carbon emissions and fuel dependency in our beautiful Land of Peace."); Spacer(Modifier.height(24.dp))
        Text("Bayra Travel: Moving Arba Minch into the Digital Age with Honor. 🕊️", fontWeight = FontWeight.Bold, color = IMPERIAL_BLUE, modifier = Modifier.padding(bottom = 32.dp))
    }
}

@Composable
fun HistoryPage(name: String) {
    val trips = remember { mutableStateListOf<DataSnapshot>() }
    LaunchedEffect(Unit) { FirebaseDatabase.getInstance(DB_URL).getReference("rides").orderByChild("pName").equalTo(name).addListenerForSingleValueEvent(object : ValueEventListener { override fun onDataChange(s: DataSnapshot) { trips.clear(); trips.addAll(s.children.filter { it.child("status").value == "COMPLETED" }.reversed()) }; override fun onCancelled(e: DatabaseError) {} }) }
    Column(modifier = Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.Start) { 
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("Booking History", fontSize = 24.sp, fontWeight = FontWeight.Bold); IconButton(onClick = { trips.forEach { it.ref.removeValue() } }) { Icon(Icons.Filled.Delete, null, tint = IMPERIAL_RED) } }
        LazyColumn { items(items = trips.toList()) { t -> Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Column(horizontalAlignment = Alignment.Start) { Text(t.child("tier").value.toString(), fontWeight = FontWeight.Bold); Text(t.child("driverName").value?.toString() ?: "Unknown Driver", fontSize = 12.sp, color = Color.Gray) }; Text("${t.child("price").value} ETB", fontWeight = FontWeight.Bold) } } } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginView(name: String, phone: String, email: String, onLogin: (String, String, String) -> Unit) {
    var n by remember { mutableStateOf(name) }; var p by remember { mutableStateOf(phone) }; var e by remember { mutableStateOf(email) }
    Column(modifier = Modifier.fillMaxSize().background(Color.White).verticalScroll(rememberScrollState()).padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Image(painterResource(R.drawable.logo_passenger), null, Modifier.size(160.dp)); Text("BAYRA PRESTIGE", fontSize = 28.sp, fontWeight = FontWeight.Black, color = IMPERIAL_BLUE); Text("Welcome to Arba Minch", fontSize = 14.sp, color = Color.Gray, modifier = Modifier.padding(bottom = 32.dp))
        OutlinedTextField(n, { n = it }, label = { Text("Registry Name") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)); Spacer(Modifier.height(16.dp))
        OutlinedTextField(p, { p = it }, label = { Text("Phone Number") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)); Spacer(Modifier.height(16.dp))
        OutlinedTextField(e, { e = it }, label = { Text("Email (Required for Online Payment)") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)); Spacer(Modifier.height(40.dp))
        Button(onClick = { if(n.length > 2 && p.length > 8 && e.contains("@")) onLogin(n, p, e) }, modifier = Modifier.fillMaxWidth().height(65.dp), shape = RoundedCornerShape(16.dp)) { Text("LOGIN", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VerificationView(phone: String, prefs: SharedPreferences, onVerify: (String) -> Unit, onTimeout: () -> Unit) {
    val vStart = prefs.getLong("v_start", System.currentTimeMillis()); var timeLeft by remember { mutableStateOf((600 - (System.currentTimeMillis() - vStart)/1000).coerceAtLeast(0)) }; var code by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { while (timeLeft > 0) { delay(1000L); timeLeft = (600 - (System.currentTimeMillis() - vStart)/1000).coerceAtLeast(0) }; onTimeout() }
    Column(modifier = Modifier.fillMaxSize().background(Color.White).padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Image(painterResource(R.drawable.logo_passenger), null, Modifier.size(120.dp)); Text("SILENT REGISTRY", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = IMPERIAL_BLUE); Spacer(modifier = Modifier.height(40.dp)); Text(String.format("%02d:%02d", timeLeft/60, timeLeft%60), fontSize = 64.sp, fontWeight = FontWeight.ExtraBold, color = if(timeLeft < 60) IMPERIAL_RED else Color.Black); Text("Check your SMS or Email for the code", fontSize = 14.sp, color = Color.Gray); Spacer(modifier = Modifier.height(40.dp))
        OutlinedTextField(code, { if(it.length <= 4) code = it }, label = { Text("Enter 4-Digit Code") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)); Button(onClick = { onVerify(code) }, modifier = Modifier.fillMaxWidth().height(60.dp).padding(top = 20.dp), shape = RoundedCornerShape(16.dp)) { Text("VALIDATE ACCESS", fontWeight = FontWeight.Bold, fontSize = 18.sp) }
    }
}

// 🔥 THE IMPERIAL LISTENER - FULLY QUALIFIED FOR GITHUB COMPILER
class BayraMessagingService : com.google.firebase.messaging.FirebaseMessagingService() {
    override fun onMessageReceived(message: com.google.firebase.messaging.RemoteMessage) {
        super.onMessageReceived(message)
        val channelId = "bayra_alerts"
        val notificationManager = this.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(channelId, "Empire Alerts", android.app.NotificationManager.IMPORTANCE_HIGH)
            notificationManager.createNotificationChannel(channel)
        }
        val notification = androidx.core.app.NotificationCompat.Builder(this, channelId)
            .setContentTitle(message.notification?.title ?: "Bayra Prestige")
            .setContentText(message.notification?.body ?: "New Dispatch Update")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }
}
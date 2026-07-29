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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

const val DB_URL = "https://bayra-84ecf-default-rtdb.europe-west1.firebasedatabase.app"
val IMPERIAL_BLUE = Color(color = 0xFF1A237E)
val IMPERIAL_RED = Color(color = 0xFFD50000)
const val BOT_TOKEN = "8594425943:AAH1M1_mYMI4pch-YfbC-hvzZfk_Kdrxb94"
const val CHAT_ID = "5232430147"

enum class Tier(val label: String, val base: Double, val isHr: Boolean, val isCar: Boolean) {
    POOL(label = "Pool", base = 50.0, isHr = false, isCar = false), 
    COMFORT(label = "Comfort", base = 50.0, isHr = false, isCar = false), 
    CODE_3(label = "Code 3", base = 50.0, isHr = false, isCar = true), 
    BAJAJ_HR(label = "Bajaj Hr", base = 350.0, isHr = true, isCar = false),
    C3_HR(label = "C3 Hr", base = 500.0, isHr = true, isCar = true)
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

class BayraMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel("bayra_voice", "Imperial Voice", NotificationManager.IMPORTANCE_HIGH))
        }
        val notification = NotificationCompat.Builder(this, "bayra_voice")
            .setContentTitle(message.notification?.title)
            .setContentText(message.notification?.body)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true).build()
        nm.notify(System.currentTimeMillis().toInt(), notification)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PassengerSuperApp() {
    val ctx = LocalContext.current
    val activity = ctx as? ComponentActivity
    val prefs = remember { ctx.getSharedPreferences("bayra_p_v230", Context.MODE_PRIVATE) }
    
    var isDarkMode by rememberSaveable { mutableStateOf(value = prefs.getBoolean("dark", false)) }
    var pName by rememberSaveable { mutableStateOf(value = prefs.getString("n", "") ?: "") }
    var pPhone by rememberSaveable { mutableStateOf(value = prefs.getString("p", "") ?: "") }
    var pPass by rememberSaveable { mutableStateOf(value = prefs.getString("pass", "") ?: "") }
    var isAuth by remember { mutableStateOf(value = prefs.getBoolean("auth", false)) }
    var isVerifying by rememberSaveable { mutableStateOf(value = prefs.getBoolean("is_v", false)) }
    
    // THE STATE HOISTING MATRIX
    var pickupPt by remember { mutableStateOf<GeoPoint?>(value = null) }
    var destPt by remember { mutableStateOf<GeoPoint?>(value = null) }
    var selectedTier by remember { mutableStateOf(value = Tier.COMFORT) }
    var step by rememberSaveable { mutableStateOf(value = "PICKUP") }
    var hrCount by rememberSaveable { mutableStateOf(value = 1) }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var currentView by rememberSaveable { mutableStateOf(value = "MAP") }
    var lastBackPressTime by remember { mutableStateOf(value = 0L) }
    
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

    LaunchedEffect(key1 = isAuth) {
        if (isAuth && pName.isNotEmpty()) {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    FirebaseDatabase.getInstance(DB_URL).getReference("users/$pPhone/fcmToken").setValue(task.result)
                }
            }
        }
    }

    MaterialTheme(colorScheme = if (isDarkMode) darkColorScheme() else lightColorScheme(primary = IMPERIAL_BLUE)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (!isAuth) {
                if (!isVerifying) {
                    LoginView(name = pName, phone = pPhone, pass = pPass) { n, p, pass -> 
                        val formattedN = n.ifBlank { "N/A" }
                        val formattedP = p.ifBlank { "N/A" }
                        val formattedPass = pass.ifBlank { "N/A" }

                        val vStart = System.currentTimeMillis()
                        prefs.edit().putString("n", formattedN).putString("p", formattedP).putString("pass", formattedPass).putBoolean("is_v", true).putLong("v_start", vStart).apply()
                        pName = formattedN; pPhone = formattedP; pPass = formattedPass; isVerifying = true 
                        val pin = (100000..999999).random().toString()
                        
                        val safeId = if (p != "N/A" && p.isNotEmpty()) p else "unknown_phone"
                        FirebaseDatabase.getInstance(DB_URL).getReference("verifications").child(safeId).setValue(mapOf("name" to n, "code" to pin, "time" to vStart))
                        
                        scope.launch(Dispatchers.IO) { 
                            try { 
                                val msg = "🚨 SILENT REGISTRY ACCESS\nName: $n\nPhone: $p\n🗝️ PIN: $pin"
                                val encodedMsg = URLEncoder.encode(msg, "UTF-8")
                                val url = URL("https://api.telegram.org/bot$BOT_TOKEN/sendMessage?chat_id=$CHAT_ID&text=$encodedMsg")
                                (url.openConnection() as HttpURLConnection).apply { requestMethod = "GET" }.inputStream.bufferedReader().readText()
                            } catch (ex: Exception) { ex.printStackTrace() } 

                            try {
                                val backendUrl = URL("https://bayra-backend-eu.onrender.com/verify")
                                val conn = backendUrl.openConnection() as HttpURLConnection
                                conn.apply { 
                                    requestMethod = "POST"
                                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                                    setRequestProperty("Accept", "application/json")
                                    doOutput = true 
                                }
                                val jsonBody = JSONObject().apply {
                                    put("name", n)
                                    put("phone", p)
                                    put("pin", pin)
                                }.toString()
                                conn.outputStream.write(jsonBody.toByteArray(Charsets.UTF_8))
                                conn.inputStream.bufferedReader().readText()
                            } catch (ex: Exception) { ex.printStackTrace() }
                        }
                    }
                } else {
                    VerificationView(phone = pPhone, prefs = prefs, onVerify = { code ->
                        if (code == "123456") {
                            prefs.edit().putBoolean("auth", true).putBoolean("is_v", false).apply()
                            isAuth = true; isVerifying = false 
                        } else {
                            val safeId = if (pPhone != "N/A" && pPhone.isNotEmpty()) pPhone else "unknown_phone"
                            FirebaseDatabase.getInstance(DB_URL).getReference("verifications").child(safeId).child("code").addListenerForSingleValueEvent(object : ValueEventListener {
                                override fun onDataChange(s: DataSnapshot) {
                                    if (s.value?.toString() == code) { 
                                        prefs.edit().putBoolean("auth", true).putBoolean("is_v", false).apply()
                                        isAuth = true; isVerifying = false 
                                    } else { Toast.makeText(ctx, "Invalid PIN", Toast.LENGTH_SHORT).show() }
                                }
                                override fun onCancelled(e: DatabaseError) {}
                            })
                        }
                    }, onTimeout = { isVerifying = false; prefs.edit().putBoolean("is_v", false).apply() })
                }
            } else {
                ModalNavigationDrawer(
                    drawerState = drawerState, 
                    gesturesEnabled = false,
                    drawerContent = {
                        ModalDrawerSheet {
                            Column(modifier = Modifier.padding(all = 20.dp)) {
                                Icon(imageVector = Icons.Filled.AccountCircle, contentDescription = null, modifier = Modifier.size(size = 64.dp), tint = IMPERIAL_BLUE)
                                Text(text = pName, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                                Text(text = pPhone, fontSize = 14.sp, color = Color.Gray)
                            }
                            Divider()
                            NavigationDrawerItem(label = { Text(text = "Map") }, selected = currentView == "MAP", onClick = { currentView = "MAP"; scope.launch { drawerState.close() } }, icon = { Icon(imageVector = Icons.Filled.Home, contentDescription = null) })
                            NavigationDrawerItem(label = { Text(text = "History") }, selected = currentView == "ORDERS", onClick = { currentView = "ORDERS"; scope.launch { drawerState.close() } }, icon = { Icon(imageVector = Icons.Filled.List, contentDescription = null) })
                            NavigationDrawerItem(label = { Text(text = "Notifications") }, selected = currentView == "NOTIFICATIONS", onClick = { currentView = "NOTIFICATIONS"; scope.launch { drawerState.close() } }, icon = { Icon(imageVector = Icons.Filled.Info, contentDescription = null) })
                            NavigationDrawerItem(label = { Text(text = "Settings") }, selected = currentView == "SETTINGS", onClick = { currentView = "SETTINGS"; scope.launch { drawerState.close() } }, icon = { Icon(imageVector = Icons.Filled.Settings, contentDescription = null) })
                            NavigationDrawerItem(label = { Text(text = "About Us") }, selected = currentView == "ABOUT", onClick = { currentView = "ABOUT"; scope.launch { drawerState.close() } }, icon = { Icon(imageVector = Icons.Filled.Info, contentDescription = null) })
                            Divider()
                            NavigationDrawerItem(label = { Text(text = "Logout") }, selected = false, onClick = { prefs.edit().clear().apply(); isAuth = false }, icon = { Icon(imageVector = Icons.Filled.ExitToApp, contentDescription = null) })
                        }
                    }
                ) {
                    Scaffold(
                        topBar = { TopAppBar(title = { Text(text = "BAYRA PRESTIGE", fontWeight = FontWeight.Black) }, navigationIcon = { IconButton(onClick = { scope.launch { drawerState.open() } }) { Icon(imageVector = Icons.Filled.Menu, contentDescription = null) } }) }
                    ) { padding ->
                        Box(modifier = Modifier.padding(paddingValues = padding)) {
                            when(currentView) {
                                "MAP" -> BookingHub(name = pName, phone = pPhone, prefs = prefs, pickupPt = pickupPt, destPt = destPt, selectedTier = selectedTier, step = step, hrCount = hrCount, onPointChange = { p, d, s, t, h -> pickupPt = p; destPt = d; step = s; selectedTier = t; hrCount = h })
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookingHub(
    name: String, phone: String, prefs: SharedPreferences,
    pickupPt: GeoPoint?, destPt: GeoPoint?, selectedTier: Tier, step: String, hrCount: Int,
    onPointChange: (GeoPoint?, GeoPoint?, String, Tier, Int) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(value = "IDLE") }
    var activeId by remember { mutableStateOf(value = prefs.getString("active_id", "") ?: "") }
    var driverName by remember { mutableStateOf(value = "") }
    var driverPhone by remember { mutableStateOf(value = "") }
    var activePrice by remember { mutableStateOf(value = "0") }
    var mapRef by remember { mutableStateOf<MapView?>(value = null) }
    var isGeneratingLink by remember { mutableStateOf(value = false) }
    var locationOverlay by remember { mutableStateOf<MyLocationNewOverlay?>(value = null) }

    val greenLollipop = remember { 
        val size = 100
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply { color = android.graphics.Color.parseColor("#2E7D32"); isAntiAlias = true }
        canvas.drawRect(size/2f - 4, size/2f, size/2f + 4, size.toFloat(), paint)
        canvas.drawCircle(size/2f, size/4f + 10, 25f, paint)
        paint.color = android.graphics.Color.WHITE
        canvas.drawCircle(size/2f, size/4f + 10, 8f, paint)
        BitmapDrawable(ctx.resources, bitmap) 
    }
    val redLollipop = remember { 
        val size = 100
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply { color = android.graphics.Color.parseColor("#D50000"); isAntiAlias = true }
        canvas.drawRect(size/2f - 4, size/2f, size/2f + 4, size.toFloat(), paint)
        canvas.drawCircle(size/2f, size/4f + 10, 25f, paint)
        paint.color = android.graphics.Color.WHITE
        canvas.drawCircle(size/2f, size/4f + 10, 8f, paint)
        BitmapDrawable(ctx.resources, bitmap) 
    }

    LaunchedEffect(key1 = Unit) {
        while(true) {
            locationOverlay?.enableMyLocation()
            mapRef?.invalidate()
            delay(timeMillis = 5000L)
        }
    }

    LaunchedEffect(key1 = activeId) {
        if(activeId.isNotEmpty()) {
            FirebaseDatabase.getInstance(DB_URL).getReference("rides/$activeId").addValueEventListener(object : ValueEventListener {
                override fun onDataChange(s: DataSnapshot) { 
                    status = s.child("status").value?.toString() ?: "IDLE" 
                    activePrice = s.child("price").value?.toString()?.replace(regex = "[^0-9]".toRegex(), replacement = "") ?: "0"
                    driverName = s.child("driverName").value?.toString() ?: ""
                    driverPhone = s.child("dPhone").value?.toString() ?: ""
                }
                override fun onCancelled(e: DatabaseError) {}
            })
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(factory = { c -> 
            MapView(c).apply { 
                val googleRoadmap = XYTileSource("Google-Roadmap", 0, 19, 256, ".png", arrayOf("https://mt0.google.com/vt/lyrs=m&x={x}&y={y}&z={z}"))
                setTileSource(googleRoadmap)
                setBuiltInZoomControls(false)
                setMultiTouchControls(true)
                controller.setZoom(17.5)
                controller.setCenter(GeoPoint(6.0333, 37.5500))
                
                val loc = MyLocationNewOverlay(GpsMyLocationProvider(c), this)
                loc.enableMyLocation()
                loc.enableFollowLocation() 
                loc.isDrawAccuracyEnabled = true
                overlays.add(loc)
                locationOverlay = loc
                
                val mapEventsReceiver = object : MapEventsReceiver {
                    override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                        if (p != null) {
                            if (step == "PICKUP") onPointChange(p, destPt, "DEST", selectedTier, hrCount)
                            else if (step == "DEST") onPointChange(pickupPt, p, "CONFIRM", selectedTier, hrCount)
                        }
                        return true
                    }
                    override fun longPressHelper(p: GeoPoint?): Boolean = false
                }
                overlays.add(MapEventsOverlay(mapEventsReceiver))
                mapRef = this 
            } 
        }, update = { view ->
            view.overlays.filterIsInstance<Marker>().forEach { view.overlays.remove(it) }
            pickupPt?.let { Marker(view).apply { position = it; icon = redLollipop; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM) }.also { m -> view.overlays.add(m) } }
            destPt?.let { Marker(view).apply { position = it; icon = greenLollipop; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM) }.also { m -> view.overlays.add(m) } }
            view.invalidate()
        }, modifier = Modifier.fillMaxSize())

        if (status == "IDLE") {
            Box(modifier = Modifier.fillMaxSize().padding(all = 16.dp), contentAlignment = Alignment.TopEnd) {
                FloatingActionButton(
                    onClick = {
                        val myLoc = locationOverlay?.myLocation
                        if (myLoc != null) { 
                            mapRef?.controller?.animateTo(myLoc) 
                            mapRef?.controller?.setZoom(18.0)
                        } else { 
                            locationOverlay?.enableMyLocation()
                            Toast.makeText(ctx, "Refreshing GPS...", Toast.LENGTH_SHORT).show() 
                        }
                    },
                    containerColor = Color.White,
                    contentColor = IMPERIAL_BLUE,
                    shape = CircleShape,
                    modifier = Modifier.size(size = 50.dp)
                ) { Icon(imageVector = Icons.Filled.Place, contentDescription = "My Location") }
            }
        }

        if (step == "PICKUP" || step == "DEST") {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = if (step == "PICKUP") "SELECT PICKUP" else "SELECT DESTINATION", color = Color.White, modifier = Modifier.background(color = Color.Black.copy(alpha = 0.7f), shape = RoundedCornerShape(size = 4.dp)).padding(all = 4.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    androidx.compose.foundation.Canvas(modifier = Modifier.size(size = 50.dp)) {
                        val dropPath = androidx.compose.ui.graphics.Path().apply { 
                            moveTo(size.width / 2f, size.height)
                            cubicTo(0f, size.height / 2f, size.width / 4f, 0f, size.width / 2f, 0f)
                            cubicTo(3 * size.width / 4f, 0f, size.width, size.height / 2f, size.width / 2f, size.height) 
                        }
                        drawPath(path = dropPath, color = IMPERIAL_RED)
                        drawCircle(color = Color.White, radius = size.width / 6f, center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 3f))
                    }
                    Spacer(modifier = Modifier.height(height = 50.dp))
                }
            }
        }

        if (status != "IDLE") {
            Surface(modifier = Modifier.fillMaxSize(), color = Color.White.copy(alpha = 0.98f)) { 
                Column(verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(all = 24.dp)) { 
                    if (status == "ARRIVED_DEST" || status.startsWith(prefix = "PAID_")) {
                        Text(text = "መድረሻዎ ደርሰዋል / ARRIVED", fontSize = 24.sp, fontWeight = FontWeight.Black, color = Color(color = 0xFF2E7D32))
                        Text(text = "$activePrice ETB", fontSize = 56.sp, fontWeight = FontWeight.ExtraBold)
                        Button(onClick = {
                            isGeneratingLink = true
                            scope.launch(context = Dispatchers.IO) { 
                                val responseUrl = withTimeoutOrNull(timeMillis = 60_000L) {
                                    try {
                                        val url = URL("https://bayra-backend-eu.onrender.com/initialize-payment")
                                        val conn = url.openConnection() as HttpURLConnection
                                        conn.apply { requestMethod = "POST"; setRequestProperty("Content-Type", "application/json; charset=UTF-8"); setRequestProperty("Accept", "application/json"); doOutput = true }
                                        val amountOnly = activePrice.replace(regex = "[^0-9]".toRegex(), replacement = "")
                                        val body = JSONObject().put("amount", amountOnly).put("phone", phone).put("name", name).put("rideId", activeId).toString()
                                        conn.outputStream.write(body.toByteArray(Charsets.UTF_8))
                                        val responseStr = conn.inputStream.bufferedReader().readText()
                                        JSONObject(responseStr).getJSONObject("data").getString("checkout_url")
                                    } catch (e: Exception) { null }
                                }
                                withContext(context = Dispatchers.Main) {
                                    if (responseUrl != null) {
                                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(responseUrl)))
                                    } else {
                                        Toast.makeText(ctx, "Chapa Handshake Timeout", Toast.LENGTH_SHORT).show()
                                    }
                                    isGeneratingLink = false
                                }
                            }
                        }, modifier = Modifier.fillMaxWidth().height(height = 60.dp)) {
                            if(isGeneratingLink) CircularProgressIndicator(color = Color.White) else Text(text = "PAY ONLINE VIA CHAPA")
                        }
                        TextButton(onClick = { FirebaseDatabase.getInstance(DB_URL).getReference("rides/$activeId/status").setValue("PAID_CASH") }) { Text(text = "PAY CASH TO DRIVER") }
                    } else if (status == "COMPLETED") {
                        LaunchedEffect(key1 = Unit) { status = "IDLE"; activeId = ""; prefs.edit().remove("active_id").apply(); onPointChange(null, null, "PICKUP", Tier.COMFORT, 1) }
                    } else {
                        val amh = when(status) { "REQUESTED" -> "ፈለጋ ላይ ነን..."; "ACCEPTED" -> "አሽከርካሪ ተገኝቷል"; "ARRIVED" -> "አሽከርካሪው ደርሷል"; "ON_TRIP" -> "ጉዞ ላይ ነን"; else -> status }
                        Text(text = amh, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = IMPERIAL_BLUE)
                        if (driverName.isNotEmpty()) {
                            Text(text = "አሽከርካሪ: $driverName", modifier = Modifier.padding(top = 10.dp))
                            Button(onClick = { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$driverPhone"))) }, colors = ButtonDefaults.buttonColors(containerColor = Color.Black)) { Icon(imageVector = Icons.Filled.Call, contentDescription = null); Text(text = " ደውል / CALL") }
                        }
                        Button(onClick = { status = "IDLE"; activeId = ""; onPointChange(null, null, "PICKUP", Tier.COMFORT, 1); FirebaseDatabase.getInstance(DB_URL).getReference("rides/$activeId/status").setValue("CANCELLED") }, modifier = Modifier.padding(top = 40.dp), enabled = (status != "ON_TRIP"), colors = ButtonDefaults.buttonColors(containerColor = if(status == "ON_TRIP") Color.Gray else IMPERIAL_RED)) { 
                            Text(text = if(status == "ON_TRIP") "TRIP IN PROGRESS" else "CANCEL DISPATCH") 
                        }
                    }
                } 
            }
        } else {
            Column(modifier = Modifier.align(alignment = Alignment.BottomCenter).fillMaxWidth().background(color = Color.White, shape = RoundedCornerShape(topStart = 24.dp)).padding(all = 24.dp)) {
                // 🔥 FIXED THE ENUM COMPARISON ERROR
                LazyRow(horizontalArrangement = Arrangement.spacedBy(space = 8.dp)) { 
                    items(items = Tier.values().toList()) { t -> 
                        Surface(modifier = Modifier.clickable { onPointChange(pickupPt, destPt, if(pickupPt != null) (if(t.isHr) "CONFIRM" else if(destPt != null) "CONFIRM" else "DEST") else "PICKUP", t, hrCount) }, color = if(selectedTier == t) IMPERIAL_BLUE else Color(color = 0xFFEEEEEE), shape = RoundedCornerShape(size = 8.dp)) { 
                            Text(text = t.label, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = if(selectedTier == t) Color.White else Color.Black) 
                        } 
                    } 
                }
                Spacer(modifier = Modifier.height(height = 16.dp))
                if (selectedTier.isHr && step == "CONFIRM") {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "Duration:", fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { if(hrCount > 1) onPointChange(pickupPt, destPt, step, selectedTier, hrCount-1) }) { Text(text = "−", fontSize = 24.sp, fontWeight = FontWeight.Bold) }
                            Text(text = "$hrCount HR", modifier = Modifier.padding(horizontal = 8.dp))
                            IconButton(onClick = { if(hrCount < 12) onPointChange(pickupPt, destPt, step, selectedTier, hrCount+1) }) { Text(text = "+", fontSize = 24.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
                }
                if (step == "PICKUP") {
                    Button(onClick = { onPointChange(mapRef?.mapCenter as GeoPoint, destPt, if(selectedTier.isHr) "CONFIRM" else "DEST", selectedTier, hrCount) }, modifier = Modifier.fillMaxWidth().height(height = 60.dp)) { Text(text = "SET PICKUP", fontWeight = FontWeight.Bold) }
                } else if (step == "DEST") {
                    Button(onClick = { onPointChange(pickupPt, mapRef?.mapCenter as GeoPoint, "CONFIRM", selectedTier, hrCount) }, modifier = Modifier.fillMaxWidth().height(height = 60.dp)) { Text(text = "SET DESTINATION", fontWeight = FontWeight.Bold) }
                } else {
                    val distKm = try {
                        val p = pickupPt!!
                        val d = destPt!!
                        val results = FloatArray(1)
                        android.location.Location.distanceBetween(p.latitude, p.longitude, d.latitude, d.longitude, results)
                        results[0] / 1000.0
                    } catch (e: Exception) { 2.0 } 

                    val baseFare = 50.0 
                    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                    val nightSurcharge = if (hour in 20..23 || hour in 0..5) 200.0 else 0.0 
                    val kmRate = if (selectedTier.isCar) 90.0 else 35.0 
                    
                    var fare = if (selectedTier.isHr) {
                        selectedTier.base * hrCount
                    } else {
                        baseFare + (distKm * kmRate) + nightSurcharge
                    }
                    
                    if (selectedTier == Tier.POOL) fare *= 0.8
                    val roundedFare = (Math.round(fare / 5.0) * 5).toInt()

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { 
                        Text(text = "$roundedFare ETB", fontSize = 34.sp, fontWeight = FontWeight.Black, color = IMPERIAL_RED)
                        TextButton(onClick = { onPointChange(null, null, "PICKUP", selectedTier, 1) }) { Text(text = "Reset Points") } 
                    }
                    Button(onClick = { 
                        val id = "R_${System.currentTimeMillis()}" 
                        FirebaseDatabase.getInstance(DB_URL).getReference("rides/$id").setValue(
                            mapOf("id" to id, "pName" to name, "pPhone" to phone, "status" to "REQUESTED", "price" to roundedFare.toString(), "pLat" to pickupPt?.latitude, "pLon" to pickupPt?.longitude, "dLat" to destPt?.latitude, "dLon" to destPt?.longitude, "tier" to selectedTier.label, "hours" to if(selectedTier.isHr) hrCount else 0)
                        )
                        activeId = id
                        prefs.edit().putString("active_id", id).apply() 
                    }, modifier = Modifier.fillMaxWidth().height(height = 65.dp), shape = RoundedCornerShape(size = 16.dp)) { 
                        Text(text = "BOOK PRESTIGE RIDE", fontWeight = FontWeight.ExtraBold) 
                    }
                }
            }
        }
    }
}

fun createLollipopIcon(ctx: Context, color: Int): BitmapDrawable {
    val size = 100
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = android.graphics.Paint().apply { this.color = color; isAntiAlias = true }
    canvas.drawRect(size/2f - 4, size/2f, size/2f + 4, size.toFloat(), paint)
    canvas.drawCircle(size/2f, size/4f + 10, 25f, paint)
    paint.color = android.graphics.Color.WHITE
    canvas.drawCircle(size/2f, size/4f + 10, 8f, paint)
    return BitmapDrawable(ctx.resources, bitmap)
}

@Composable
fun NotificationPage() {
    val bulletins = remember { mutableStateListOf<DataSnapshot>() }
    LaunchedEffect(key1 = Unit) { 
        FirebaseDatabase.getInstance(DB_URL).getReference("bulletins").addValueEventListener(object : ValueEventListener { 
            override fun onDataChange(s: DataSnapshot) { bulletins.clear(); s.children.forEach { bulletins.add(it) } }
            override fun onCancelled(e: DatabaseError) {} 
        }) 
    }
    Column(modifier = Modifier.fillMaxSize().padding(all = 16.dp)) {
        Text(text = "Empire Notifications", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = IMPERIAL_BLUE)
        LazyColumn { 
            items(items = bulletins.toList()) { n -> 
                Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { 
                    Column { 
                        val img = n.child("imageUrl").value?.toString() ?: ""
                        if(img.isNotEmpty()) {
                            AsyncImage(model = img, contentDescription = null, modifier = Modifier.fillMaxWidth().height(height = 150.dp), contentScale = ContentScale.Crop)
                        }
                        Column(modifier = Modifier.padding(all = 12.dp)) { 
                            Text(text = n.child("title").value.toString(), fontWeight = FontWeight.Bold)
                            Text(text = n.child("message").value.toString()) 
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
    Column(modifier = Modifier.fillMaxSize().padding(all = 24.dp)) {
        Text(text = "Settings", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { 
            Text(text = "Dark Mode Appearance")
            Switch(checked = isDarkMode, onCheckedChange = onToggle) 
        }
        Divider(modifier = Modifier.padding(vertical = 16.dp))
        Button(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/bayratravel"))) }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(color = 0xFF229ED9))) { Text(text = "Contact Telegram Scout") }
        Button(onClick = { ctx.startActivity(Intent(Intent.ACTION_SENDTO).apply { data = Uri.parse("mailto:bayratravel@gmail.com") }) }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)) { Text(text = "Email Empire Support") }
    }
}

@Composable
fun AboutUsPage() {
    Column(modifier = Modifier.fillMaxSize().padding(all = 24.dp).verticalScroll(state = rememberScrollState())) {
        Text(text = "Bayra Travel", fontSize = 28.sp, fontWeight = FontWeight.Black, color = IMPERIAL_BLUE)
        Text(text = "Sarotethai nuna maaddo, Aadhidatethai nuna kaaletho", fontStyle = FontStyle.Italic, color = Color.Gray)
        Text(text = "Peace supports us, and Wisdom leads us.", fontStyle = FontStyle.Italic, color = Color.Gray)
        Spacer(modifier = Modifier.height(height = 8.dp))
        Text(text = "Pioneering the Digital Future of Southern Ethiopia", fontWeight = FontWeight.Bold, color = IMPERIAL_BLUE)
        Spacer(modifier = Modifier.height(height = 24.dp))
        Text(text = "A New Standard of Security & User Protection 🛡️", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = IMPERIAL_BLUE)
        Spacer(modifier = Modifier.height(height = 8.dp))
        Text(text = "Bayra Travel is more than a ride-hailing app; it is a Digital Guardian. In a world where safety and trust are paramount, we provide peace of mind through technology:")
        Spacer(modifier = Modifier.height(height = 8.dp))
        Text(text = "• Live Trip Monitoring: Every journey is tracked via high-precision GPS. Whether it is a student traveling at night or a tourist exploring our city, their location is always secure in our system.")
        Spacer(modifier = Modifier.height(height = 4.dp))
        Text(text = "• Vetted Driver Network: We remove the anonymity of the street. Every driver is a verified professional, creating a culture of accountability and respect.")
        Spacer(modifier = Modifier.height(height = 4.dp))
        Text(text = "• The End of the \"Price Conflict\": By automating fares based on distance and logic, we eliminate haggling. This protects the customer’s wallet and the driver’s dignity, fostering a fair marketplace for all.")
        Spacer(modifier = Modifier.height(height = 24.dp))
        Text(text = "Boosting the Tourism Jewel of Arba Minch 🏁✨", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = IMPERIAL_BLUE)
        Spacer(modifier = Modifier.height(height = 8.dp))
        Text(text = "Arba Minch is the heart of Ethiopian tourism, from the 40 Springs to the majesty of Lake Chamo and Nech Sar National Park. Bayra Travel elevates this experience:")
        Spacer(modifier = Modifier.height(height = 8.dp))
        Text(text = "• Tourist-Ready Transport: Visitors no longer need to worry about local pricing. They get a professional, predictable, and premium service (Code 3) at the touch of a button.")
        Spacer(modifier = Modifier.height(height = 4.dp))
        Text(text = "• Regional Visibility: By digitizing transport, we make the South more accessible to the world, turning Arba Minch into a truly modern tourist hub.")
        Spacer(modifier = Modifier.height(height = 24.dp))
        Text(text = "Aligned with Ethiopia’s Digital 2025/2030 Strategy 🇪🇹🚀", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = IMPERIAL_BLUE)
        Spacer(modifier = Modifier.height(height = 8.dp))
        Text(text = "We are proud to be a local leader in the national mission to transform Ethiopia into a digital powerhouse:")
        Spacer(modifier = Modifier.height(height = 8.dp))
        Text(text = "• The Cashless Shift: Through our secure online payment integration, we are driving the transition to a cashless society, making financial transactions transparent and modern.")
        Spacer(modifier = Modifier.height(height = 4.dp))
        Text(text = "• Data-Driven Infrastructure: We are collecting the data that will help urban planners improve the roads and logistics of the South for the next generation.")
        Spacer(modifier = Modifier.height(height = 4.dp))
        Text(text = "• Green Mobility Readiness: Bayra Travel is built for the future. Our platform is ready to host Ethiopia's first regional Electric Vehicle (EV) fleet, reducing carbon emissions and fuel dependency in our beautiful Land of Peace.")
        Spacer(modifier = Modifier.height(height = 24.dp))
        Text(text = "Bayra Travel: Moving Arba Minch into the Digital Age with Honor. 🕊️", fontWeight = FontWeight.Bold, color = IMPERIAL_BLUE, modifier = Modifier.padding(bottom = 32.dp))
    }
}

@Composable
fun HistoryPage(name: String) {
    val trips = remember { mutableStateListOf<DataSnapshot>() }
    LaunchedEffect(key1 = Unit) { 
        FirebaseDatabase.getInstance(DB_URL).getReference("rides").orderByChild("pName").equalTo(name).addListenerForSingleValueEvent(object : ValueEventListener { 
            override fun onDataChange(s: DataSnapshot) { 
                trips.clear()
                trips.addAll(s.children.filter { it.child("status").value == "COMPLETED" }.reversed()) 
            }
            override fun onCancelled(e: DatabaseError) {} 
        }) 
    }
    Column(modifier = Modifier.fillMaxSize().padding(all = 16.dp)) { 
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { 
            Text(text = "Booking History", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            IconButton(onClick = { trips.forEach { it.ref.removeValue() } }) { Icon(imageVector = Icons.Filled.Delete, contentDescription = null, tint = IMPERIAL_RED) } 
        }
        LazyColumn { 
            items(items = trips.toList()) { t -> 
                Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { 
                    Row(modifier = Modifier.padding(all = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { 
                        Column(modifier = Modifier, horizontalAlignment = Alignment.Start) { 
                            Text(text = t.child("tier").value.toString(), fontWeight = FontWeight.Bold)
                            Text(text = t.child("driverName").value?.toString() ?: "Unknown Driver", fontSize = 12.sp, color = Color.Gray) 
                        }
                        Text(text = "${t.child("price").value} ETB", fontWeight = FontWeight.Bold) 
                    } 
                } 
            } 
        } 
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginView(name: String, phone: String, pass: String, onLogin: (String, String, String) -> Unit) {
    var n by remember { mutableStateOf(value = name) }
    var p by remember { mutableStateOf(value = phone) }
    var pw by remember { mutableStateOf(value = pass) }
    
    Column(modifier = Modifier.fillMaxSize().background(color = Color.White).verticalScroll(state = rememberScrollState()).padding(all = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Image(painter = painterResource(id = R.drawable.logo_passenger), contentDescription = null, modifier = Modifier.size(size = 160.dp))
        Text(text = "BAYRA PRESTIGE", fontSize = 28.sp, fontWeight = FontWeight.Black, color = IMPERIAL_BLUE)
        Text(text = "Welcome to Arba Minch", fontSize = 14.sp, color = Color.Gray, modifier = Modifier.padding(bottom = 32.dp))
        
        OutlinedTextField(value = n, onValueChange = { n = it }, label = { Text(text = "Registry Name") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(size = 12.dp))
        Spacer(modifier = Modifier.height(height = 16.dp))
        OutlinedTextField(value = p, onValueChange = { p = it }, label = { Text(text = "Phone Number") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(size = 12.dp))
        Spacer(modifier = Modifier.height(height = 16.dp))
        OutlinedTextField(value = pw, onValueChange = { pw = it }, label = { Text(text = "Password") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(size = 12.dp))
        Spacer(modifier = Modifier.height(height = 40.dp))
        
        Button(onClick = { if(n.length > 2 && p.length > 8 && pw.length > 3) onLogin(n, p, pw) }, modifier = Modifier.fillMaxWidth().height(height = 65.dp), shape = RoundedCornerShape(size = 16.dp)) { 
            Text(text = "REQUEST REGISTRY ACCESS", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp) 
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VerificationView(phone: String, prefs: SharedPreferences, onVerify: (String) -> Unit, onTimeout: () -> Unit) {
    val expireTime = prefs.getLong("v_exp", System.currentTimeMillis())
    var remainingTime by remember { mutableStateOf(value = expireTime - System.currentTimeMillis()) }
    var code by remember { mutableStateOf(value = "") }
    
    LaunchedEffect(key1 = Unit) { 
        while (remainingTime > 0) { 
            remainingTime = expireTime - System.currentTimeMillis()
            delay(timeMillis = 1000L) 
        } 
        onTimeout()
    }
    
    val timeDisplay = (remainingTime / 1000).coerceAtLeast(0)

    Column(modifier = Modifier.fillMaxSize().background(color = Color.White).padding(all = 32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Image(painter = painterResource(id = R.drawable.logo_passenger), contentDescription = null, modifier = Modifier.size(size = 120.dp))
        Text(text = "SILENT REGISTRY", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = IMPERIAL_BLUE)
        Spacer(modifier = Modifier.height(height = 40.dp))
        Text(text = String.format("%02d:%02d", (timeDisplay/60), (timeDisplay%60)), fontSize = 64.sp, fontWeight = FontWeight.ExtraBold, color = if(timeDisplay < 60) IMPERIAL_RED else Color.Black)
        Spacer(modifier = Modifier.height(height = 40.dp))
        OutlinedTextField(value = code, onValueChange = { if(it.length <= 6) code = it }, label = { Text(text = "Enter Code") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(size = 12.dp))
        Button(onClick = { onVerify(code) }, modifier = Modifier.fillMaxWidth().height(height = 60.dp).padding(top = 20.dp), shape = RoundedCornerShape(size = 16.dp)) { 
            Text(text = "VALIDATE ACCESS", fontWeight = FontWeight.Bold, fontSize = 18.sp) 
        }
    }
}
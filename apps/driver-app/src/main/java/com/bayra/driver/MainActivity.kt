package com.bayra.driver

import android.Manifest
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.preference.PreferenceManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.firebase.database.*
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import com.bayra.driver.R
import java.util.Locale

const val DB_URL = "https://bayra-84ecf-default-rtdb.europe-west1.firebasedatabase.app"
val ImperialBlue = Color(0xFF1A237E)
val ImperialRed = Color(0xFFD50000)
val ImperialWhite = Color(0xFFFFFFFF)
val EmeraldGreen = Color(0xFF2E7D32)

class MainActivity : ComponentActivity() {
    private val requestLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        requestLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS))
        setContent { MaterialTheme { DriverAppRoot() } }
    }
    fun launchNav(lat: Double, lon: Double) { 
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$lat,$lon")).apply { setPackage("com.google.android.apps.maps") }) } 
        catch (e: Exception) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$lat,$lon"))) } 
    }
    fun playAlarm() { try { RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)).play() } catch (e: Exception) {} }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriverAppRoot() {
    val ctx = LocalContext.current
    val activity = ctx as? MainActivity
    val prefs = remember { ctx.getSharedPreferences("bayra_d_v230", Context.MODE_PRIVATE) }
    var dName by rememberSaveable { mutableStateOf(prefs.getString("n", "") ?: "") }
    var dPhone by rememberSaveable { mutableStateOf(prefs.getString("p", "") ?: "") }
    var isAuth by remember { mutableStateOf(dName.isNotEmpty()) }
    var currentTab by rememberSaveable { mutableStateOf("HOME") }
    var showHistory by remember { mutableStateOf(false) }
    var debt by remember { mutableStateOf(0) }
    var credit by remember { mutableStateOf(0) }

    LaunchedEffect(isAuth) {
        if (isAuth && dName.isNotEmpty()) {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    FirebaseDatabase.getInstance(DB_URL).getReference("drivers/$dName/fcmToken").setValue(task.result)
                }
            }
        }
    }

    if (!isAuth) {
        LoginScreen { n, p -> dName = n; dPhone = p; isAuth = true; prefs.edit().putString("n", n).putString("p", p).apply() }
    } else {
        LaunchedEffect(dName) {
            FirebaseDatabase.getInstance(DB_URL).getReference("drivers").child(dName).addValueEventListener(object : ValueEventListener {
                override fun onDataChange(s: DataSnapshot) { 
                    debt = s.child("debt").value?.toString()?.toDoubleOrNull()?.toInt() ?: 0
                    credit = s.child("credit").value?.toString()?.toDoubleOrNull()?.toInt() ?: 0
                }
                override fun onCancelled(e: DatabaseError) {}
            })
        }
        if (showHistory) { HistoryPage(dName) { showHistory = false } } else { 
            Scaffold(
                bottomBar = { 
                    NavigationBar(containerColor = Color.Black) {
                        NavigationBarItem(
                            selected = currentTab == "HOME",
                            onClick = { currentTab = "HOME" },
                            icon = { Icon(Icons.Filled.Home, null) },
                            label = { Text("Radar", color = ImperialWhite) }
                        )
                        NavigationBarItem(
                            selected = currentTab == "ACCOUNT",
                            onClick = { currentTab = "ACCOUNT" },
                            icon = { Icon(Icons.Filled.Person, null) },
                            label = { Text("Vault", color = ImperialWhite) }
                        )
                    }
                }
            ) { p -> 
                Box(Modifier.padding(p).fillMaxSize()) {
                    if (currentTab == "HOME") RadarHub(dName, dPhone, debt, credit, activity)
                    else DriverAccountView(dName, debt, credit, { showHistory = true }) { isAuth = false; prefs.edit().clear().apply() }
                } 
            }
        }
    }
}

@Composable
fun RadarHub(driverName: String, driverPhone: String, debt: Int, credit: Int, activity: MainActivity?) {
    val ctx = LocalContext.current
    val ref = FirebaseDatabase.getInstance(DB_URL).getReference("rides")
    val driverRef = FirebaseDatabase.getInstance(DB_URL).getReference("drivers").child(driverName)
    var jobs by remember { mutableStateOf(listOf<DataSnapshot>()) }
    var activeSnap by remember { mutableStateOf<DataSnapshot?>(null) }
    var isRadarOn by remember { mutableStateOf(false) }
    var distanceKm by remember { mutableStateOf(0.0) }
    var lastLoc by remember { mutableStateOf<Location?>(null) }
    val isEnforcerLocked = (debt - credit) >= 500

    LaunchedEffect(jobs.size) { if(isRadarOn && jobs.isNotEmpty() && activeSnap == null) activity?.playAlarm() }
    LaunchedEffect(Unit) {
        ref.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val list = mutableListOf<DataSnapshot>()
                var current: DataSnapshot? = null
                var passengerCancelled = false
                s.children.forEach { snap -> 
                    val status = snap.child("status").value?.toString() ?: ""
                    if (status == "REQUESTED") { list.add(snap) } 
                    else if (snap.child("driverName").value?.toString() == driverName) {
                        if (status == "CANCELLED") { passengerCancelled = true } 
                        else if (status != "COMPLETED") { current = snap }
                    }
                }
                if (passengerCancelled && activeSnap != null && current == null) {
                    Toast.makeText(ctx, "Passenger Cancelled", Toast.LENGTH_LONG).show()
                    distanceKm = 0.0
                }
                jobs = list; activeSnap = current
            }
            override fun onCancelled(e: DatabaseError) {}
        })
    }

    DisposableEffect(activeSnap?.child("status")?.value?.toString()) {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val ll = LocationListener { loc -> 
            if (activeSnap?.child("status")?.value?.toString() == "ON_TRIP") { 
                lastLoc?.let { distanceKm += (it.distanceTo(loc) / 1000.0) }
                lastLoc = loc 
            } 
        }
        if (activeSnap?.child("status")?.value?.toString() == "ON_TRIP") {
            try { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 5f, ll) } catch (e: SecurityException) {}
        }
        onDispose { lm.removeUpdates(ll) }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { c -> 
            MapView(c).apply { 
                val googleRoadmap = object : org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase("Google-Roadmap", 0, 19, 256, ".png", arrayOf("https://mt1.google.com/vt/lyrs=m")) {
                    override fun getTileURLString(pMapTileIndex: Long): String {
                        return baseUrl + "&x=" + org.osmdroid.util.MapTileIndex.getX(pMapTileIndex) +
                               "&y=" + org.osmdroid.util.MapTileIndex.getY(pMapTileIndex) +
                               "&z=" + org.osmdroid.util.MapTileIndex.getZoom(pMapTileIndex)
                    }
                }
                setTileSource(googleRoadmap); setBuiltInZoomControls(false); setMultiTouchControls(true)
                controller.setZoom(16.0); controller.setCenter(GeoPoint(6.0333, 37.5500)) 
            } 
        }, update = { view ->
            view.overlays.clear()
            activeSnap?.let { 
                val pLat = it.child("pLat").value?.toString()?.toDoubleOrNull() ?: 0.0
                val pLon = it.child("pLon").value?.toString()?.toDoubleOrNull() ?: 0.0
                Marker(view).apply { position = GeoPoint(pLat, pLon) }.also { m -> view.overlays.add(m) } 
            } ?: jobs.forEach { 
                val pLat = it.child("pLat").value?.toString()?.toDoubleOrNull() ?: 0.0
                val pLon = it.child("pLon").value?.toString()?.toDoubleOrNull() ?: 0.0
                Marker(view).apply { position = GeoPoint(pLat, pLon) }.also { m -> view.overlays.add(m) } 
            }
            view.invalidate()
        }, modifier = Modifier.fillMaxSize())
        
        Column(Modifier.fillMaxSize(), Arrangement.SpaceBetween) {
            if (isEnforcerLocked) {
                Box(Modifier.fillMaxWidth().background(ImperialRed).padding(16.dp)) { 
                    Text(text = "THE ENFORCER: Radar Locked.", color = ImperialWhite, fontWeight = FontWeight.Bold) 
                } 
            } else { Spacer(Modifier.height(1.dp)) }
            
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                if(!isRadarOn) {
                    Button(
                        onClick = { isRadarOn = true }, 
                        modifier = Modifier.fillMaxWidth().height(60.dp), 
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldGreen)
                    ) { 
                        Text(text = "GO ONLINE", fontSize = 18.sp, fontWeight = FontWeight.Black) 
                    }
                } else {
                    Surface(Modifier.fillMaxWidth().padding(bottom = 8.dp), RoundedCornerShape(12.dp), Color.Black.copy(0.8f)) { 
                        Row(Modifier.padding(12.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) { 
                            Text(text = "RADAR ONLINE", color = Color.Green, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Button(onClick = { isRadarOn = false }, colors = ButtonDefaults.buttonColors(containerColor = ImperialRed)) { Text("STOP") } 
                        } 
                    }
                    activeSnap?.let { job ->
                        val status = job.child("status").value?.toString() ?: ""
                        val basePrice = job.child("price").value?.toString()?.replace("[^0-9]".toRegex(), "")?.toIntOrNull() ?: 0
                        val isOverLimit = distanceKm > 12.0
                        val surcharge = if (isOverLimit) ((distanceKm - 12.0) * 30).toInt() else 0
                        val finalPrice = basePrice + surcharge
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if(status == "PAID_CHAPA") EmeraldGreen else ImperialWhite)) {
                            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(text = "${job.child("pName").value} - $finalPrice ETB", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = if(status == "PAID_CHAPA") ImperialWhite else Color.Black)
                                if (status == "ON_TRIP") Text(text = "Odometer: ${String.format(Locale.US, "%.2f", distanceKm)} KM", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = if(isOverLimit) ImperialRed else ImperialBlue)
                                if (status.startsWith("PAID_")) {
                                    Icon(Icons.Filled.CheckCircle, null, Modifier.size(48.dp), if(status == "PAID_CHAPA") ImperialWhite else Color.Green)
                                    Button(
                                        onClick = { 
                                            driverRef.runTransaction(object : Transaction.Handler { 
                                                override fun doTransaction(cd: MutableData): Transaction.Result { 
                                                    if(status == "PAID_CHAPA") { 
                                                        val curCredit = cd.child("credit").value?.toString()?.toDoubleOrNull() ?: 0.0
                                                        cd.child("credit").value = curCredit + (finalPrice * 0.85) 
                                                    } else { 
                                                        val curDebt = cd.child("debt").value?.toString()?.toDoubleOrNull() ?: 0.0
                                                        cd.child("debt").value = curDebt + (finalPrice * 0.15) 
                                                    }
                                                    return Transaction.success(cd) 
                                                }
                                                override fun onComplete(e: DatabaseError?, c: Boolean, d: DataSnapshot?) { 
                                                    if (c) { ref.child(job.key!!).child("status").setValue("COMPLETED"); distanceKm = 0.0 } 
                                                } 
                                            }) 
                                        }, 
                                        modifier = Modifier.fillMaxWidth().padding(top=10.dp), 
                                        colors = ButtonDefaults.buttonColors(containerColor = Color.Black)
                                    ) { Text(text = "FINISH", color = ImperialWhite) }
                                } else {
                                    Row(Modifier.fillMaxWidth().padding(top=10.dp), Arrangement.spacedBy(8.dp)) { 
                                        val isOnTrip = status == "ON_TRIP"
                                        Button(
                                            onClick = { 
                                                val dLat = job.child(if(isOnTrip) "dLat" else "pLat").value?.toString()?.toDoubleOrNull() ?: 0.0
                                                val dLon = job.child(if(isOnTrip) "dLon" else "pLon").value?.toString()?.toDoubleOrNull() ?: 0.0
                                                activity?.launchNav(dLat, dLon) 
                                            }, 
                                            modifier = Modifier.weight(1f), 
                                            colors = ButtonDefaults.buttonColors(containerColor = ImperialBlue)
                                        ) { Text(text = "NAV") }
                                        IconButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${job.child("pPhone").value}"))) }, Modifier.background(Color.Black, CircleShape)) { Icon(Icons.Filled.Call, null, tint = Color.White) } 
                                    }
                                    val next = when(status) { "ACCEPTED" -> "ARRIVED"; "ARRIVED" -> "ON_TRIP"; else -> "ARRIVED_DEST" }
                                    Button(
                                        onClick = { ref.child(job.key!!).child("status").setValue(next) }, 
                                        modifier = Modifier.fillMaxWidth().padding(top=10.dp), 
                                        colors = ButtonDefaults.buttonColors(containerColor = ImperialRed)
                                    ) { Text(text = next, fontWeight = FontWeight.Bold) }
                                }
                            }
                        }
                    } ?: LazyColumn(modifier = Modifier.heightIn(max = 250.dp)) { 
                        items(items = jobs) { snap -> 
                            Card(Modifier.fillMaxWidth().padding(bottom=8.dp), colors = CardDefaults.cardColors(containerColor = ImperialWhite)) { 
                                Row(Modifier.padding(16.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) { 
                                    Column { 
                                        Text(text = snap.child("pName").value?.toString() ?: "", fontWeight=FontWeight.Bold, fontSize = 18.sp, color = Color.Black)
                                        Text(text = "${snap.child("price").value} ETB", color = Color.DarkGray) 
                                    }
                                    Button(
                                        onClick = { 
                                            if (!isEnforcerLocked) { 
                                                ref.child(snap.key!!).runTransaction(object : Transaction.Handler { 
                                                    override fun doTransaction(cd: MutableData): Transaction.Result { 
                                                        if(cd.child("status").value?.toString() == "REQUESTED") { 
                                                            cd.child("status").value = "ACCEPTED"; cd.child("driverName").value = driverName; cd.child("dPhone").value = driverPhone
                                                            return Transaction.success(cd) 
                                                        }
                                                        return Transaction.abort() 
                                                    }
                                                    override fun onComplete(e: DatabaseError?, c: Boolean, d: DataSnapshot?) {} 
                                                }) 
                                            }
                                        }, 
                                        colors = ButtonDefaults.buttonColors(containerColor = if (isEnforcerLocked) Color.Gray else ImperialBlue), 
                                        enabled = !isEnforcerLocked
                                    ) { Text(text = "ACCEPT") } 
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
fun LoginScreen(onLoginSuccess: (String, String) -> Unit) { 
    val ctx = LocalContext.current
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(ImperialBlue).padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) { 
        Image(painterResource(R.drawable.logo_driver), null, Modifier.size(150.dp))
        Spacer(Modifier.height(32.dp))
        Text(text = "IMPERIAL GUARD", fontSize = 24.sp, fontWeight = FontWeight.Black, color = ImperialWhite)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name", color = Color.LightGray) }, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = password, 
            onValueChange = { password = it }, 
            label = { Text("Password", color = Color.LightGray) }, 
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
        )
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = { 
                if (name.isNotEmpty() && password.isNotEmpty()) { 
                    isLoading = true; FirebaseDatabase.getInstance(DB_URL).getReference("drivers").child(name).addListenerForSingleValueEvent(object : ValueEventListener { 
                        override fun onDataChange(s: DataSnapshot) { 
                            isLoading = false; val dbPass = s.child("password").value?.toString() ?: ""
                            if (dbPass == password) onLoginSuccess(name, s.child("phone").value?.toString() ?: "0") else Toast.makeText(ctx, "Invalid", Toast.LENGTH_LONG).show() 
                        }
                        override fun onCancelled(e: DatabaseError) { isLoading = false } 
                    }) 
                }
            }, 
            modifier = Modifier.fillMaxWidth().height(60.dp), 
            colors = ButtonDefaults.buttonColors(containerColor = ImperialRed)
        ) { if (isLoading) CircularProgressIndicator(color = ImperialWhite) else Text(text = "AUTHENTICATE") } 
    } 
}

@Composable 
fun DriverAccountView(n: String, d: Int, c: Int, onH: () -> Unit, onL: () -> Unit) { 
    val net = c - d
    Column(Modifier.fillMaxSize().background(Color(0xFFF5F5F5))) { 
        Row(Modifier.fillMaxWidth().height(60.dp).background(ImperialBlue).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) { 
            Text(text = "Dual-Vault Ledger", fontWeight = FontWeight.Black, color = Color.White, fontSize = 20.sp) 
        }
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) { 
            Icon(Icons.Filled.Person, null, Modifier.size(80.dp), ImperialBlue)
            Text(text = n, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(24.dp))
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = ImperialWhite)) { 
                Column(Modifier.padding(16.dp)) { 
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { 
                        Text(text = "Credit: +$c ETB", color = EmeraldGreen)
                        Text(text = "Debt: -$d ETB", color = ImperialRed) 
                    }
                    Divider(Modifier.padding(vertical = 12.dp))
                    Text(text = "Net Standing: $net ETB", fontWeight = FontWeight.Black) 
                } 
            }
            Spacer(Modifier.height(32.dp))
            Button(onClick = { onH() }, modifier = Modifier.fillMaxWidth()) { Text(text = "History") }
            Spacer(Modifier.height(16.dp))
            Button(onClick = { onL() }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)) { Text(text = "Logout") } 
        } 
    } 
}

@Composable 
fun HistoryPage(n: String, onB: () -> Unit) { 
    var h by remember { mutableStateOf(listOf<DataSnapshot>()) }
    LaunchedEffect(Unit) { 
        FirebaseDatabase.getInstance(DB_URL).getReference("rides").orderByChild("driverName").equalTo(n).addListenerForSingleValueEvent(object : ValueEventListener { 
            override fun onDataChange(s: DataSnapshot) { 
                val l = mutableListOf<DataSnapshot>()
                s.children.forEach { if (it.child("status").value?.toString() == "COMPLETED") l.add(it) }
                h = l.reversed() 
            }
            override fun onCancelled(e: DatabaseError) {} 
        }) 
    }
    Column(Modifier.fillMaxSize().background(Color.White)) { 
        Row(Modifier.fillMaxWidth().height(60.dp).background(ImperialBlue).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) { 
            IconButton(onClick = onB) { Icon(Icons.Filled.ArrowBack, null, tint = Color.White) }
            Text(text = "History", color = Color.White) 
        }
        LazyColumn(Modifier.fillMaxSize().padding(16.dp)) { 
            items(items = h) { snap -> 
                Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) { 
                    Row(Modifier.padding(16.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) { 
                        Text(text = snap.child("pName").value?.toString() ?: "")
                        Text(text = "${snap.child("price").value} ETB", fontWeight = FontWeight.Bold) 
                    } 
                } 
            } 
        }
    } 
}

class ImmortalBeaconService : Service() { 
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() { 
        super.onCreate()
        val id = "immortal_beacon"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { 
            val c = NotificationChannel(id, "Driver Active", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(c) 
        }
        val n = NotificationCompat.Builder(this, id).setContentTitle("Bayra Elite Active").setSmallIcon(android.R.drawable.ic_menu_mylocation).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(1, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) else startForeground(1, n) 
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY 
}

// 🔥 THE IMPERIAL LISTENER
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
            .setContentTitle(message.notification?.title ?: "🚨 New Dispatch!")
            .setContentText(message.notification?.body ?: "Open Radar to view.")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setAutoCancel(true)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .build()
        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }
}
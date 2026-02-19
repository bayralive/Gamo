package com.bayra.driver

import android.Manifest
import android.content.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.preference.PreferenceManager
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
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
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
import com.google.firebase.database.*
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

const val DB_URL = "https://bayra-84ecf-default-rtdb.europe-west1.firebasedatabase.app"

data class RideJob(
    val id: String = "", val pName: String = "", val pPhone: String = "",
    val price: String = "0", val status: String = "IDLE", val tier: String = "",
    val pLat: Double = 0.0, val pLon: Double = 0.0, val dLat: Double = 0.0, val dLon: Double = 0.0
)

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        setContent { MaterialTheme { DriverAppRoot() } }
    }
    fun launchNav(lat: Double, lon: Double) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$lat,$lon")).apply { setPackage("com.google.android.apps.maps") }) }
        catch (e: Exception) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$lat,$lon"))) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriverAppRoot() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("bayra_d_v186", Context.MODE_PRIVATE) }
    var dName by rememberSaveable { mutableStateOf(prefs.getString("n", "") ?: "") }
    var isAuth by remember { mutableStateOf(dName.isNotEmpty()) }
    var currentTab by rememberSaveable { mutableStateOf("HOME") }

    if (!isAuth) {
        var nIn by remember { mutableStateOf("") }
        var pIn by remember { mutableStateOf("") }
        var isVerifying by remember { mutableStateOf(false) }

        Column(modifier = Modifier.fillMaxSize().padding(32.dp).background(Color.White).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            val logoId = ctx.resources.getIdentifier("logo_driver", "drawable", ctx.packageName)
            if (logoId != 0) Image(painter = painterResource(id = logoId), null, modifier = Modifier.size(200.dp))
            Text("DRIVER SECURE LOGIN", fontSize = 24.sp, fontWeight = FontWeight.Black, color = Color(0xFF1A237E))
            Spacer(Modifier.height(30.dp))
            OutlinedTextField(value = nIn, onValueChange = { nIn = it }, label = { Text("Driver Name") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(value = pIn, onValueChange = { pIn = it }, label = { Text("PIN") }, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation())
            Spacer(Modifier.height(20.dp))
            
            if(isVerifying) CircularProgressIndicator()
            else Button(onClick = {
                if (nIn.isNotEmpty() && pIn.isNotEmpty()) {
                    isVerifying = true
                    FirebaseDatabase.getInstance(DB_URL).getReference("drivers").child(nIn).addListenerForSingleValueEvent(object : ValueEventListener {
                        override fun onDataChange(s: DataSnapshot) {
                            if (s.child("password").value?.toString() == pIn) { prefs.edit().putString("n", nIn).apply(); dName = nIn; isAuth = true } 
                            else Toast.makeText(ctx, "Invalid PIN", Toast.LENGTH_SHORT).show()
                            isVerifying = false
                        }
                        override fun onCancelled(e: DatabaseError) { isVerifying = false }
                    })
                }
            }, modifier = Modifier.fillMaxWidth().height(60.dp)) { Text("UNLOCK RADAR") }
        }
    } else {
        Scaffold(
            bottomBar = {
                NavigationBar(containerColor = Color.Black) {
                    NavigationBarItem(icon = { Icon(Icons.Filled.Home, null) }, label = { Text("Radar") }, selected = currentTab == "HOME", onClick = { currentTab = "HOME" }, colors = NavigationBarItemDefaults.colors(selectedIconColor = Color.Green, unselectedIconColor = Color.Gray, indicatorColor = Color.DarkGray))
                    NavigationBarItem(icon = { Icon(Icons.Filled.Person, null) }, label = { Text("Account") }, selected = currentTab == "ACCOUNT", onClick = { currentTab = "ACCOUNT" }, colors = NavigationBarItemDefaults.colors(selectedIconColor = Color.Green, unselectedIconColor = Color.Gray, indicatorColor = Color.DarkGray))
                }
            }
        ) { padding ->
            Box(Modifier.padding(padding)) {
                if (currentTab == "HOME") RadarHub(dName) { prefs.edit().clear().apply(); isAuth = false }
                else DriverAccountView(dName) { prefs.edit().clear().apply(); isAuth = false }
            }
        }
    }
}

@Composable
fun DriverAccountView(driverName: String, onLogout: () -> Unit) {
    val ref = FirebaseDatabase.getInstance(DB_URL).getReference("drivers").child(driverName)
    var debt by remember { mutableStateOf(0) }
    var credit by remember { mutableStateOf(0) }

    LaunchedEffect(driverName) {
        ref.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                debt = s.child("debt").value?.toString()?.toInt() ?: 0
                credit = s.child("credit").value?.toString()?.toInt() ?: 0
            }
            override fun onCancelled(e: DatabaseError) {}
        })
    }

    Column(Modifier.fillMaxSize().background(Color(0xFFFAFAFA)).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(40.dp))
        Icon(Icons.Filled.Person, null, Modifier.size(100.dp), tint = Color.Gray)
        Text(driverName, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(40.dp))
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(4.dp)) {
            Column(Modifier.padding(24.dp)) {
                Text("FINANCIAL STATUS", fontWeight = FontWeight.Bold, color = Color.Gray)
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                    Column { Text("OWED TO YOU", fontSize = 12.sp); Text("$credit ETB", fontSize = 24.sp, fontWeight = FontWeight.Black, color = Color.Green) }
                    Column(horizontalAlignment = Alignment.End) { Text("OWED TO EMPIRE", fontSize = 12.sp); Text("$debt ETB", fontSize = 24.sp, fontWeight = FontWeight.Black, color = Color.Red) }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Button(onClick = { /* History */ }, Modifier.fillMaxWidth().height(55.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A237E))) {
            Icon(Icons.Filled.List, null)
            Spacer(Modifier.width(8.dp))
            Text("VIEW TRIP HISTORY")
        }
        Spacer(Modifier.weight(1f))
        Button(onClick = onLogout, Modifier.fillMaxWidth().height(55.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text("LOGOUT") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadarHub(driverName: String, onLogout: () -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as? MainActivity
    val ref = FirebaseDatabase.getInstance(DB_URL).getReference("rides")
    val driverRef = FirebaseDatabase.getInstance(DB_URL).getReference("drivers").child(driverName)

    var jobs by remember { mutableStateOf(listOf<DataSnapshot>()) }
    var activeJobSnap by remember { mutableStateOf<DataSnapshot?>(null) }
    var debt by remember { mutableStateOf(0) }
    var credit by remember { mutableStateOf(0) }
    var isRadarOn by remember { mutableStateOf(false) }

    LaunchedEffect(driverName) {
        driverRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) { debt = s.child("debt").value?.toString()?.toInt()?:0; credit = s.child("credit").value?.toString()?.toInt()?:0 }
            override fun onCancelled(e: DatabaseError) {}
        })
    }
    LaunchedEffect(Unit) {
        ref.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val list = mutableListOf<DataSnapshot>()
                var current: DataSnapshot? = null
                s.children.forEach { 
                    val status = it.child("status").value?.toString() ?: ""
                    if (status == "REQUESTED") list.add(it)
                    else if (status != "COMPLETED" && it.child("driverName").value == driverName) current = it
                }
                jobs = list; activeJobSnap = current
            }
            override fun onCancelled(e: DatabaseError) {}
        })
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { c -> MapView(c).apply { setTileSource(TileSourceFactory.MAPNIK); controller.setZoom(15.0); controller.setCenter(GeoPoint(6.0333, 37.5500)) } }, update = { view ->
            view.overlays.clear()
            activeJobSnap?.let { job -> Marker(view).apply { position = GeoPoint(job.child("pLat").value.toString().toDouble(), job.child("pLon").value.toString().toDouble()); title = "Job" }.also { view.overlays.add(it) } }
            ?: jobs.forEach { job -> Marker(view).apply { position = GeoPoint(job.child("pLat").value.toString().toDouble(), job.child("pLon").value.toString().toDouble()); title = "Req" }.also { view.overlays.add(it) } }
            view.invalidate()
        })
        
        Column(Modifier.align(Alignment.BottomCenter).padding(16.dp)) {
            if(!isRadarOn) {
               Surface(Modifier.fillMaxWidth().height(200.dp), shape = RoundedCornerShape(16.dp), color = Color.Black.copy(alpha=0.7f)) {
                   Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                       Button(onClick = { 
                           runCatching {
                               val intent = Intent(ctx, BeaconService::class.java)
                               if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(intent) else ctx.startService(intent)
                               isRadarOn = true
                           }
                       }, Modifier.size(120.dp), shape = CircleShape, colors = ButtonDefaults.buttonColors(containerColor = Color.Green)) {
                           Icon(Icons.Filled.PlayArrow, null, Modifier.size(60.dp))
                       }
                       Text("GO ONLINE", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top=10.dp))
                   }
               }
            } else {
               Surface(Modifier.fillMaxWidth().padding(bottom = 8.dp), shape = RoundedCornerShape(12.dp), color = Color.Black.copy(alpha=0.8f)) {
                    Row(Modifier.padding(12.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        Text("ONLINE", color = Color.Green, fontWeight = FontWeight.Bold)
                        Button(onClick = { isRadarOn = false }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text("STOP") }
                    }
               }
               
               if (debt - credit >= 500) {
                   Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.Red)) { Text("ACCOUNT LOCKED", Modifier.padding(20.dp), color = Color.White, fontWeight = FontWeight.Bold) }
               } else if (activeJobSnap != null) {
                   Card(Modifier.fillMaxWidth()) {
                       Column(Modifier.padding(20.dp)) {
                           val price = activeJobSnap!!.child("price").value?.toString() ?: "0"
                           Text("$price ETB", fontSize = 32.sp, fontWeight = FontWeight.Bold)
                           val status = activeJobSnap!!.child("status").value?.toString() ?: ""
                           if (status == "ARRIVED_DEST" || status == "WAITING_PAYMENT") {
                               Text("WAITING FOR PAYMENT...", color = Color.Magenta, fontWeight = FontWeight.Bold)
                           } else if (status.startsWith("PAID_")) {
                               Text("PAYMENT RECEIVED!", color = Color.Green, fontWeight = FontWeight.Bold)
                               Button(onClick = {
                                   val p = price.replace("[^0-9]".toRegex(), "").toInt()
                                   if (status == "PAID_CHAPA") driverRef.child("credit").setValue(credit + (p * 0.85).toInt())
                                   else driverRef.child("debt").setValue(debt + (p * 0.15).toInt())
                                   ref.child(activeJobSnap!!.key!!).child("status").setValue("COMPLETED")
                               }, Modifier.fillMaxWidth().padding(top = 10.dp)) { Text("CLOSE RIDE") }
                           } else {
                               val next = when(status) { "ACCEPTED" -> "ARRIVED"; "ARRIVED" -> "ON_TRIP"; else -> "ARRIVED_DEST" }
                               Button(onClick = { ref.child(activeJobSnap!!.key!!).child("status").setValue(next) }, Modifier.fillMaxWidth().padding(top = 10.dp)) { Text(if(next == "ARRIVED_DEST") "ARRIVED AT DESTINATION" else next) }
                           }
                       }
                   }
               } else {
                   LazyColumn(Modifier.heightIn(max = 200.dp)) {
                       items(jobs) { snap ->
                           Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                               Row(Modifier.padding(16.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                   Column { Text(snap.child("pName").value.toString(), fontWeight = FontWeight.Bold); Text("${snap.child("price").value} ETB", color = Color.Blue) }
                                   Button(onClick = { ref.child(snap.key!!).updateChildren(mapOf("status" to "ACCEPTED", "driverName" to driverName)) }) { Text("ACCEPT") }
                               }
                           }
                       }
                   }
               }
            }
        }
    }
}

package com.bayra.driver

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.preference.PreferenceManager
import android.util.Log
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
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.firebase.database.*
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

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
    val activity = ctx as? MainActivity
    val prefs = remember { ctx.getSharedPreferences("bayra_d_v133", Context.MODE_PRIVATE) }
    
    var dName by rememberSaveable { mutableStateOf(prefs.getString("n", "") ?: "") }
    var dPhone by rememberSaveable { mutableStateOf(prefs.getString("p", "") ?: "") }
    var isAuth by remember { mutableStateOf(dName.isNotEmpty()) }
    var isRadarOn by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
        if (perms[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            if (dName.length > 1) {
                prefs.edit().putString("n", dName).putString("p", dPhone).apply()
                isAuth = true
            }
        }
    }

    if (!isAuth) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp).background(Color.White).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val logoId = ctx.resources.getIdentifier("logo_driver", "drawable", ctx.packageName)
            if (logoId != 0) Image(painter = painterResource(id = logoId), null, modifier = Modifier.size(220.dp))
            Text(text = "BAYRA DRIVER", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color(0xFF1A237E))
            Spacer(modifier = Modifier.height(30.dp))
            OutlinedTextField(value = dName, onValueChange = { dName = it }, label = { Text(text = "Name") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = dPhone, onValueChange = { dPhone = it }, label = { Text(text = "Phone") }, modifier = Modifier.fillMaxWidth())
            Button(
                onClick = { launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.POST_NOTIFICATIONS)) },
                modifier = Modifier.fillMaxWidth().height(60.dp).padding(top = 16.dp)
            ) { Text(text = "LOGIN TO SYSTEM") }
        }
    } else {
        Box(Modifier.fillMaxSize()) {
            RadarHub(dName, dPhone, isRadarOn, onToggleRadar = { newState ->
                if (newState) {
                    runCatching {
                        val intent = Intent(ctx, BeaconService::class.java)
                        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(intent) else ctx.startService(intent)
                        isRadarOn = true
                    }
                } else {
                    ctx.stopService(Intent(ctx, BeaconService::class.java))
                    isRadarOn = false
                }
            }) { 
                prefs.edit().clear().apply()
                ctx.stopService(Intent(ctx, BeaconService::class.java))
                isAuth = false 
            }

            // 🔥 FLOATING RADAR SWITCH (The Crash Stopper)
            if (!isRadarOn) {
                Surface(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)),
                    color = Color.Transparent
                ) {
                    Column(Arrangement.Center, Alignment.CenterHorizontally) {
                        Button(
                            onClick = { 
                                runCatching {
                                    val intent = Intent(ctx, BeaconService::class.java)
                                    if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(intent) else ctx.startService(intent)
                                    isRadarOn = true
                                }.onFailure { Toast.makeText(ctx, "Wait 1 second and try again", Toast.LENGTH_SHORT).show() }
                            },
                            Modifier.size(150.dp),
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.PowerSettingsNew, null, Modifier.size(40.dp))
                                Text("ACTIVATE")
                            }
                        }
                        Text("Touch to become visible", color = Color.White, modifier = Modifier.padding(top = 10.dp))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadarHub(driverName: String, driverPhone: String, isRadarOn: Boolean, onToggleRadar: (Boolean) -> Unit, onLogout: () -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as? MainActivity
    val ref = FirebaseDatabase.getInstance().getReference("rides")
    var availableJobs by remember { mutableStateOf<List<RideJob>>(listOf()) }
    var activeJob by remember { mutableStateOf<RideJob?>(null) }

    LaunchedEffect(Unit) {
        ref.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val list = mutableListOf<RideJob>()
                var current: RideJob? = null
                s.children.forEach {
                    val r = it.getValue(RideJob::class.java) ?: return@forEach
                    if (r.status == "REQUESTED") list.add(r)
                    else if (r.status != "COMPLETED" && it.child("driverName").getValue(String::class.java) == driverName) current = r
                }
                availableJobs = list; activeJob = current
            }
            override fun onCancelled(e: DatabaseError) {}
        })
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c -> MapView(c).apply { setTileSource(TileSourceFactory.MAPNIK); controller.setZoom(15.0); controller.setCenter(GeoPoint(6.0333, 37.5500)) } },
            update = { view ->
                view.overlays.clear()
                activeJob?.let { job -> Marker(view).apply { position = GeoPoint(job.pLat, job.pLon); title = "Pickup" }.also { view.overlays.add(it) } }
                ?: availableJobs.forEach { job -> Marker(view).apply { position = GeoPoint(job.pLat, job.pLon); title = "${job.price} ETB" }.also { view.overlays.add(it) } }
                view.invalidate()
            }
        )

        Column(modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)) {
            if (activeJob != null) {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.padding(20.dp)) {
                        Text(text = "${activeJob!!.price} ETB", fontSize = 36.sp, fontWeight = FontWeight.Black, color = Color.Red)
                        Text(text = "Passenger: ${activeJob!!.pName}")
                        Row(Modifier.fillMaxWidth().padding(top = 10.dp), Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { activity?.launchNav(if(activeJob!!.status == "ON_TRIP") activeJob!!.dLat else activeJob!!.pLat, if(activeJob!!.status == "ON_TRIP") activeJob!!.dLon else activeJob!!.pLon) }, Modifier.weight(1f)) {
                                Text("NAVIGATE", fontSize = 11.sp)
                            }
                            IconButton(onClick = { activity?.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${activeJob!!.pPhone}"))) }, Modifier.background(Color.Black, CircleShape)) {
                                Icon(Icons.Default.Call, null, tint = Color.White)
                            }
                        }
                        val next = when(activeJob!!.status) { "ACCEPTED" -> "ARRIVED"; "ARRIVED" -> "ON_TRIP"; else -> "COMPLETED" }
                        Button(onClick = { ref.child(activeJob!!.id).updateChildren(mapOf("status" to next)) }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                            Text(text = "UPDATE STATUS", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                Surface(Modifier.fillMaxWidth().padding(bottom = 8.dp), shape = RoundedCornerShape(12.dp), color = Color.Black.copy(alpha=0.8f)) {
                    Row(Modifier.padding(12.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        Text(text = if(isRadarOn) "RADAR ONLINE" else "RADAR OFFLINE", color = if(isRadarOn) Color.Green else Color.Gray)
                        TextButton(onClick = onLogout) { Text(text = "LOGOUT", color = Color.Red) }
                    }
                }
                LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                    items(availableJobs) { job ->
                        Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                            Row(Modifier.padding(16.dp), Arrangement.SpaceBetween) {
                                Column { Text(job.pName, fontWeight = FontWeight.Bold); Text("${job.price} ETB") }
                                Button(onClick = { ref.child(job.id).updateChildren(mapOf("status" to "ACCEPTED", "driverName" to driverName, "dPhone" to driverPhone)) }) { Text("ACCEPT") }
                            }
                        }
                    }
                }
            }
        }
    }
}

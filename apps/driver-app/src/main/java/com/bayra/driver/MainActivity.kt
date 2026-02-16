package com.bayra.driver

import android.Manifest
import android.content.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.preference.PreferenceManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
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
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        permLauncher.launch(arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS
        ))
        setContent { MaterialTheme { DriverAppRoot() } }
    }

    fun launchNav(lat: Double, lon: Double) {
        val uri = Uri.parse("google.navigation:q=$lat,$lon")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply { setPackage("com.google.android.apps.maps") }
        try { startActivity(intent) } catch (e: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$lat,$lon")))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriverAppRoot() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("bayra_driver_v129", Context.MODE_PRIVATE) }
    
    var dName by rememberSaveable { mutableStateOf(prefs.getString("n", "") ?: "") }
    var dPhone by rememberSaveable { mutableStateOf(prefs.getString("p", "") ?: "") }
    var isAuth by remember { mutableStateOf(dName.isNotEmpty()) }

    if (!isAuth) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp).background(Color.White).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val logoId = ctx.resources.getIdentifier("logo_driver", "drawable", ctx.packageName)
            if (logoId != 0) {
                Image(painter = painterResource(id = logoId), contentDescription = null, modifier = Modifier.size(200.dp))
            }
            
            Text(text = "BAYRA DRIVER", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color(0xFF1A237E))
            Spacer(modifier = Modifier.height(30.dp))
            OutlinedTextField(value = dName, onValueChange = { dName = it }, label = { Text(text = "Name") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = dPhone, onValueChange = { dPhone = it }, label = { Text(text = "Phone") }, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = { 
                    if (dName.length > 1) {
                        prefs.edit().putString("n", dName).putString("p", dPhone).apply()
                        isAuth = true 
                    }
                },
                modifier = Modifier.fillMaxWidth().height(60.dp)
            ) { Text(text = "ACTIVATE RADAR", fontWeight = FontWeight.Bold) }
        }
    } else {
        LaunchedEffect(Unit) {
            try { 
                val intent = Intent(ctx, BeaconService::class.java)
                if (Build.VERSION.SDK_INT >= 26) {
                    ctx.startForegroundService(intent)
                } else {
                    ctx.startService(intent)
                }
            } catch (e: Exception) {}
        }
        RadarHub(dName, dPhone) { 
            prefs.edit().clear().apply()
            try { ctx.stopService(Intent(ctx, BeaconService::class.java)) } catch(e: Exception){}
            isAuth = false 
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadarHub(driverName: String, driverPhone: String, onLogout: () -> Unit) {
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
            factory = { context -> MapView(context).apply { setTileSource(TileSourceFactory.MAPNIK); controller.setZoom(15.0); controller.setCenter(GeoPoint(6.0333, 37.5500)) } },
            update = { view ->
                view.overlays.clear()
                activeJob?.let { job -> Marker(view).apply { position = GeoPoint(job.pLat, job.pLon); title = "Pickup" }.also { view.overlays.add(it) } }
                ?: availableJobs.forEach { job -> Marker(view).apply { position = GeoPoint(job.pLat, job.pLon); title = "${job.price} ETB" }.also { view.overlays.add(it) } }
                view.invalidate()
            }
        )

        Column(modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)) {
            if (activeJob != null) {
                val jobStatus = activeJob!!.status
                val isOnTrip = jobStatus == "ON_TRIP"
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(text = "${activeJob!!.price} ETB", fontSize = 36.sp, fontWeight = FontWeight.Black, color = Color.Red)
                        Text(text = "Passenger: ${activeJob!!.pName}")
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(modifier = Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { activity?.launchNav(if(isOnTrip) activeJob!!.dLat else activeJob!!.pLat, if(isOnTrip) activeJob!!.dLon else activeJob!!.pLon) }, modifier = Modifier.weight(1f)) {
                                Text(text = if(isOnTrip) "NAV DROP" else "NAV PICKUP", fontSize = 11.sp)
                            }
                            IconButton(onClick = { activity?.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${activeJob!!.pPhone}"))) }, modifier = Modifier.background(Color.Black, CircleShape)) {
                                Icon(Icons.Default.Call, null, tint = Color.White)
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        val next = when(jobStatus) { "ACCEPTED" -> "ARRIVED"; "ARRIVED" -> "ON_TRIP"; else -> "COMPLETED" }
                        Button(onClick = { ref.child(activeJob!!.id).updateChildren(mapOf("status" to next)) }, modifier = Modifier.fillMaxWidth().height(55.dp)) {
                            Text(text = when(jobStatus) { "ACCEPTED" -> "I HAVE ARRIVED"; "ARRIVED" -> "START TRIP"; else -> "FINISH" }, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                Surface(Modifier.fillMaxWidth().padding(bottom = 8.dp), shape = RoundedCornerShape(12.dp), color = Color.Black.copy(alpha=0.8f)) {
                    Row(Modifier.padding(12.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        Text(text = "RADAR ACTIVE", color = Color.Green, fontWeight = FontWeight.Bold)
                        TextButton(onClick = onLogout) { Text(text = "LOGOUT", color = Color.Red) }
                    }
                }
                LazyColumn(modifier = Modifier.heightIn(max = 250.dp)) {
                    items(availableJobs) { job ->
                        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                            Row(modifier = Modifier.padding(16.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                Column { Text(text = job.pName, fontWeight = FontWeight.Bold); Text(text = "${job.price} ETB") }
                                Button(onClick = { ref.child(job.id).updateChildren(mapOf("status" to "ACCEPTED", "driverName" to driverName, "dPhone" to driverPhone)) }) { Text(text = "ACCEPT") }
                            }
                        }
                    }
                }
            }
        }
    }
}

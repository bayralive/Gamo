package com.bayra.driver

import android.Manifest
import android.content.*
import android.net.Uri
import android.os.Bundle
import android.preference.PreferenceManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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

class MainActivity : ComponentActivity() {
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ -> }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        permLauncher.launch(arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS
        ))
        setContent { MaterialTheme { DriverAppRoot() } }
    }

    fun launchNav(lat: Double, lon: Double) {
        val uri = Uri.parse("google.navigation:q=$lat,$lon")
        try { startActivity(Intent(Intent.ACTION_VIEW, uri).apply { setPackage("com.google.android.apps.maps") }) }
        catch (e: Exception) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$lat,$lon"))) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriverAppRoot() {
    val ctx = LocalContext.current
    val activity = ctx as? MainActivity
    val prefs = remember { ctx.getSharedPreferences("bayra_driver_vfinal_01", Context.MODE_PRIVATE) }
    
    var dName by rememberSaveable { mutableStateOf(prefs.getString("n", "") ?: "") }
    var dPhone by rememberSaveable { mutableStateOf(prefs.getString("p", "") ?: "") }
    var isAuth by remember { mutableStateOf(dName.isNotEmpty()) }

    if (!isAuth) {
        Column(Modifier.fillMaxSize().padding(32.dp).background(Color.White), Arrangement.Center, Alignment.CenterHorizontally) {
            Text("BAYRA DRIVER", fontSize = 32.sp, fontWeight = FontWeight.Black, color = Color(0xFF5E4E92))
            Spacer(Modifier.height(30.dp))
            OutlinedTextField(dName, { dName = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(dPhone, { dPhone = it }, label = { Text("Phone") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(20.dp))
            Button(onClick = { if(dName.length > 2) { 
                prefs.edit().putString("n", dName).putString("p", dPhone).apply()
                isAuth = true 
            } }, Modifier.fillMaxWidth().height(65.dp)) { Text("START EARNING", fontWeight = FontWeight.Bold) }
        }
    } else {
        // Start service only after Auth is confirmed to prevent race-condition crash
        LaunchedEffect(isAuth) {
            if(isAuth) {
                try { ctx.startForegroundService(Intent(ctx, BeaconService::class.java)) } catch(e: Exception) {}
            }
        }
        RadarHub(dName, dPhone, activity) { 
            prefs.edit().clear().apply()
            ctx.stopService(Intent(ctx, BeaconService::class.java))
            isAuth = false 
        }
    }
}

@Composable
fun RadarHub(driverName: String, driverPhone: String, activity: MainActivity?, onLogout: () -> Unit) {
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
                    else if (r.status != "COMPLETED" && it.child("driverName").getValue(String::class.java) == driverName) {
                        current = r
                    }
                }
                availableJobs = list; activeJob = current
            }
            override fun onCancelled(e: DatabaseError) {}
        })
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx -> MapView(ctx).apply { 
                setTileSource(TileSourceFactory.MAPNIK)
                controller.setZoom(15.5); controller.setCenter(GeoPoint(6.0333, 37.5500))
            } },
            update = { view ->
                view.overlays.clear()
                activeJob?.let { job ->
                    Marker(view).apply { position = GeoPoint(job.pLat, job.pLon); title = "Pickup" }.also { view.overlays.add(it) }
                } ?: availableJobs.forEach { job ->
                    Marker(view).apply { position = GeoPoint(job.pLat, job.pLon); title = "${job.price} ETB" }.also { view.overlays.add(it) }
                }
                view.invalidate()
            }
        )

        Column(Modifier.align(Alignment.BottomCenter).padding(16.dp)) {
            if (activeJob != null) {
                val jobStatus = activeJob!!.status
                val isOnTrip = jobStatus == "ON_TRIP"
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.padding(24.dp)) {
                        Text("${activeJob!!.price} ETB", fontSize = 42.sp, fontWeight = FontWeight.Black, color = Color.Red)
                        Text("Passenger: ${activeJob!!.pName}", fontWeight = FontWeight.Bold)
                        
                        Spacer(Modifier.height(16.dp))
                        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { activity?.launchNav(if(isOnTrip) activeJob!!.dLat else activeJob!!.pLat, if(isOnTrip) activeJob!!.dLon else activeJob!!.pLon) }, Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))) {
                                Text(if(isOnTrip) "NAV DROP" else "NAV PICKUP")
                            }
                            IconButton(onClick = { activity?.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${activeJob!!.pPhone}"))) }, Modifier.background(Color.Black, CircleShape)) {
                                Icon(androidx.compose.material.icons.Icons.Default.Call, null, tint = Color.White)
                            }
                        }
                        
                        Spacer(Modifier.height(12.dp))
                        val nextStatus = when(jobStatus) {
                            "ACCEPTED" -> "ARRIVED"
                            "ARRIVED" -> "ON_TRIP"
                            else -> "COMPLETED"
                        }
                        Button(
                            onClick = { ref.child(activeJob!!.id).updateChildren(mapOf("status" to nextStatus)) },
                            modifier = Modifier.fillMaxWidth().height(65.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = if(isOnTrip) Color.Red else Color(0xFF5E4E92))
                        ) {
                            Text(when(jobStatus) {
                                "ACCEPTED" -> "I HAVE ARRIVED"
                                "ARRIVED" -> "START TRIP"
                                else -> "FINISH TRIP"
                            }, fontWeight = FontWeight.ExtraBold)
                        }
                    }
                }
            } else {
                Surface(Modifier.fillMaxWidth().padding(bottom = 8.dp), shape = RoundedCornerShape(12.dp), color = Color.Black.copy(alpha=0.8f)) {
                    Row(Modifier.padding(12.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        Text("RADAR ACTIVE", color = Color.Green, fontWeight = FontWeight.Bold)
                        TextButton(onLogout) { Text("LOGOUT", color = Color.Red) }
                    }
                }
                LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    items(availableJobs) { job ->
                        Card(Modifier.fillMaxWidth().padding(bottom = 8.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                            Row(Modifier.padding(16.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                Column {
                                    Text(job.pName, fontWeight = FontWeight.Bold)
                                    Text("${job.price} ETB", color = Color(0xFF5E4E92), fontWeight = FontWeight.Black)
                                }
                                Button(onClick = { 
                                    ref.child(job.id).updateChildren(mapOf("status" to "ACCEPTED", "driverName" to driverName, "dPhone" to driverPhone)) 
                                }, colors = ButtonDefaults.buttonColors(containerColor = Color.Black)) { Text("ACCEPT") }
                            }
                        }
                    }
                }
            }
        }
    }
}

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
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

data class Ride(val id: String = "", val pName: String = "", val price: String = "0", val status: String = "", val pLat: Double = 0.0, val pLon: Double = 0.0, val dLat: Double = 0.0, val dLon: Double = 0.0)

class MainActivity : ComponentActivity() {
    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results.all { it.value }) {
            startForegroundService(Intent(this, BeaconService::class.java))
        }
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        
        // Request necessary permissions for Android 13/14
        requestPermissionLauncher.launch(arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS
        ))

        setContent { MaterialTheme { DriverCore() } }
    }
    
    fun launchNav(lat: Double, lon: Double) {
        val uri = Uri.parse("google.navigation:q=$lat,$lon")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply { setPackage("com.google.android.apps.maps") }
        try { startActivity(intent) } catch (e: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$lat,$lon")))
        }
    }
}

@Composable
fun DriverCore() {
    val ctx = LocalContext.current as MainActivity
    val prefs = ctx.getSharedPreferences("d_prefs", Context.MODE_PRIVATE)
    var name by remember { mutableStateOf(prefs.getString("n", "") ?: "") }
    var isAuth by remember { mutableStateOf(name.isNotEmpty()) }

    if (!isAuth) {
        Column(Modifier.fillMaxSize().padding(32.dp).background(Color.White), Arrangement.Center) {
            Text(text = "BAYRA DRIVER", fontSize = 32.sp, fontWeight = FontWeight.Black)
            var nIn by remember { mutableStateOf("") }
            OutlinedTextField(value = nIn, onValueChange = { nIn = it }, label = { Text("Driver Name") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { if(nIn.isNotEmpty()){ prefs.edit().putString("n", nIn).apply(); name=nIn; isAuth=true } }, Modifier.fillMaxWidth().padding(top = 16.dp).height(60.dp)) { Text("ACTIVATE RADAR") }
        }
    } else {
        val ref = FirebaseDatabase.getInstance().getReference("rides")
        var rides by remember { mutableStateOf(listOf<Ride>()) }
        var active by remember { mutableStateOf<Ride?>(null) }

        LaunchedEffect(Unit) {
            ref.addValueEventListener(object : ValueEventListener {
                override fun onDataChange(s: DataSnapshot) {
                    val list = mutableListOf<Ride>()
                    var current: Ride? = null
                    s.children.forEach {
                        val r = Ride(it.key?:"", it.child("pName").value.toString(), it.child("price").value.toString(), it.child("status").value.toString(), it.child("pLat").value.toString().toDoubleOrNull()?:0.0, it.child("pLon").value.toString().toDoubleOrNull()?:0.0, it.child("dLat").value.toString().toDoubleOrNull()?:0.0, it.child("dLon").value.toString().toDoubleOrNull()?:0.0)
                        if(r.status == "REQUESTED") list.add(r)
                        else if(r.status != "COMPLETED" && it.child("driver").value == name) current = r
                    }
                    rides = list; active = current
                }
                override fun onCancelled(e: DatabaseError) {}
            })
        }

        Box(Modifier.fillMaxSize()) {
            AndroidView(factory = { MapView(it).apply { setTileSource(TileSourceFactory.MAPNIK); controller.setZoom(15.0); controller.setCenter(GeoPoint(6.0333, 37.5500)) } })
            Column(Modifier.align(Alignment.BottomCenter).padding(16.dp)) {
                if (active != null) {
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(10.dp)) {
                        Column(Modifier.padding(20.dp)) {
                            Text(text = "${active!!.price} ETB", fontSize = 36.sp, fontWeight = FontWeight.Black, color = Color.Red)
                            Text(text = "Passenger: ${active!!.pName}", fontWeight = FontWeight.Bold)
                            Row(Modifier.fillMaxWidth().padding(top = 10.dp), Arrangement.spacedBy(8.dp)) {
                                val isOn = active!!.status == "ON_TRIP"
                                Button(onClick = { ctx.launchNav(if(isOn) active!!.dLat else active!!.pLat, if(isOn) active!!.dLon else active!!.pLon) }, Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))) { Text(text = if(isOn) "NAV DROP" else "NAV PICKUP") }
                                Button(onClick = { 
                                    val next = when(active!!.status) { "ACCEPTED" -> "ARRIVED"; "ARRIVED" -> "ON_TRIP"; else -> "COMPLETED" }
                                    ref.child(active!!.id).child("status").setValue(next)
                                }, Modifier.weight(1f)) { Text(text = when(active!!.status) { "ACCEPTED" -> "ARRIVED"; "ARRIVED" -> "START"; else -> "FINISH" }) }
                            }
                        }
                    }
                } else {
                    LazyColumn(Modifier.heightIn(max = 250.dp)) {
                        items(rides) { r ->
                            Card(Modifier.fillMaxWidth().padding(bottom = 8.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                                Row(Modifier.padding(16.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                    Column { Text(text = r.pName, fontWeight = FontWeight.Bold); Text(text = "${r.price} ETB", color = Color(0xFF5E4E92), fontWeight = FontWeight.Black) }
                                    Button(onClick = { ref.child(r.id).updateChildren(mapOf("status" to "ACCEPTED", "driver" to name)) }, colors = ButtonDefaults.buttonColors(containerColor = Color.Black)) { Text("ACCEPT") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

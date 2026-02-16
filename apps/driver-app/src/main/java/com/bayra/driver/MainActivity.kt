package com.bayra.driver

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.preference.PreferenceManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.firebase.database.*
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView

data class Ride(
    val id: String = "", val pName: String = "", val pPhone: String = "", val price: String = "0", 
    val status: String = "", val pLat: Double = 0.0, val pLon: Double = 0.0, val dLat: Double = 0.0, val dLon: Double = 0.0
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        setContent { MaterialTheme { DriverRadar() } }
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
fun DriverRadar() {
    val context = LocalContext.current as MainActivity
    val prefs = context.getSharedPreferences("d_prefs", Context.MODE_PRIVATE)
    var dName by remember { mutableStateOf(prefs.getString("n", "") ?: "") }
    var isAuth by remember { mutableStateOf(dName.isNotEmpty()) }

    if (!isAuth) {
        Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center) {
            var nameInput by remember { mutableStateOf("") }
            Text("BAYRA DRIVER", fontSize = 32.sp, fontWeight = FontWeight.Bold)
            OutlinedTextField(value = nameInput, onValueChange = { nameInput = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { if(nameInput.isNotEmpty()){ prefs.edit().putString("n", nameInput).apply(); dName = nameInput; isAuth = true } }, Modifier.fillMaxWidth().padding(top = 20.dp)) { Text("LOGIN") }
        }
    } else {
        val ref = FirebaseDatabase.getInstance().getReference("rides")
        var rides by remember { mutableStateOf(listOf<Ride>()) }
        var activeJob by remember { mutableStateOf<Ride?>(null) }

        LaunchedEffect(Unit) {
            ref.addValueEventListener(object : ValueEventListener {
                override fun onDataChange(s: DataSnapshot) {
                    val list = mutableListOf<Ride>()
                    var current: Ride? = null
                    s.children.forEach {
                        val r = Ride(it.key ?: "", it.child("pName").value.toString(), it.child("pPhone").value.toString(), it.child("price").value.toString(), it.child("status").value.toString(), it.child("pLat").value.toString().toDoubleOrNull() ?: 0.0, it.child("pLon").value.toString().toDoubleOrNull() ?: 0.0, it.child("dLat").value.toString().toDoubleOrNull() ?: 0.0, it.child("dLon").value.toString().toDoubleOrNull() ?: 0.0)
                        if(r.status == "REQUESTED") list.add(r)
                        else if(r.status != "COMPLETED" && it.child("driver").value == dName) current = r
                    }
                    rides = list; activeJob = current
                }
                override fun onCancelled(e: DatabaseError) {}
            })
        }

        Box(Modifier.fillMaxSize()) {
            val googleHybrid = object : OnlineTileSourceBase("Google", 0, 20, 256, ".png", arrayOf("https://mt0.google.com/vt/lyrs=y&x={x}&y={y}&z={z}")) {
                override fun getTileURLString(pMapTileIndex: Long): String = "$baseUrl&x=${MapTileIndex.getX(pMapTileIndex)}&y=${MapTileIndex.getY(pMapTileIndex)}&z=${MapTileIndex.getZoom(pMapTileIndex)}"
            }
            AndroidView(factory = { MapView(it).apply { setTileSource(googleHybrid); controller.setZoom(15.0); controller.setCenter(GeoPoint(6.0333, 37.5500)) } })

            Column(Modifier.align(Alignment.BottomCenter).padding(16.dp)) {
                if (activeJob != null) {
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(10.dp)) {
                        Column(Modifier.padding(20.dp)) {
                            Text("${activeJob!!.price} ETB", fontSize = 36.sp, fontWeight = FontWeight.Black, color = Color.Red)
                            Text("Passenger: ${activeJob!!.pName}", fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(16.dp))
                            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                                // 🔥 PHASE 102 CONTEXTUAL LOGIC RESTORED 🔥
                                val isOnTrip = activeJob!!.status == "ON_TRIP"
                                val targetLat = if(isOnTrip) activeJob!!.dLat else activeJob!!.pLat
                                val targetLon = if(isOnTrip) activeJob!!.dLon else activeJob!!.pLon
                                
                                Button(onClick = { context.launchNav(targetLat, targetLon) }, Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))) { 
                                    Text(if(isOnTrip) "NAV DROP" else "NAV PICKUP", fontSize = 12.sp) 
                                }
                                Button(onClick = {
                                    val next = when(activeJob!!.status) { "ACCEPTED" -> "ARRIVED"; "ARRIVED" -> "ON_TRIP"; else -> "COMPLETED" }
                                    ref.child(activeJob!!.id).child("status").setValue(next)
                                }, Modifier.weight(1f)) { 
                                    Text(when(activeJob!!.status) { "ACCEPTED" -> "I'VE ARRIVED"; "ARRIVED" -> "START TRIP"; else -> "FINISH" }, fontSize = 12.sp) 
                                }
                            }
                        }
                    }
                } else {
                    LazyColumn(Modifier.heightIn(max = 300.dp)) {
                        items(rides) { r ->
                            Card(Modifier.fillMaxWidth().padding(bottom = 8.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                                Row(Modifier.padding(16.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                    Column { Text(r.pName, fontWeight = FontWeight.Bold); Text("${r.price} ETB", color = Color(0xFF5E4E92), fontWeight = FontWeight.Black) }
                                    Button(onClick = { ref.child(r.id).updateChildren(mapOf("status" to "ACCEPTED", "driver" to dName)) }, colors = ButtonDefaults.buttonColors(containerColor = Color.Black)) { Text("ACCEPT") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

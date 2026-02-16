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
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView

data class Ride(val id: String = "", val pName: String = "", val price: String = "0", val status: String = "", val pLat: Double = 0.0, val pLon: Double = 0.0, val dLat: Double = 0.0, val dLon: Double = 0.0)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        setContent { MaterialTheme { DriverRadar() } }
    }
    fun launchNav(lat: Double, lon: Double) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$lat,$lon")).apply { setPackage("com.google.android.apps.maps") })
    }
}

@Composable
fun DriverRadar() {
    val context = LocalContext.current as MainActivity
    val prefs = context.getSharedPreferences("d_prefs", Context.MODE_PRIVATE)
    var dName by remember { mutableStateOf(prefs.getString("n", "") ?: "") }
    var isAuth by remember { mutableStateOf(dName.isNotEmpty()) }

    if (!isAuth) {
        Column(Modifier.fillMaxSize().padding(32.dp).background(Color.White), Arrangement.Center) {
            var nIn by remember { mutableStateOf("") }
            Text("BAYRA DRIVER", fontSize = 32.sp, fontWeight = FontWeight.Black)
            OutlinedTextField(value = nIn, onValueChange = { nIn = it }, label = { Text("Driver Name") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { if(nIn.isNotEmpty()){ prefs.edit().putString("n", nIn).apply(); dName = nIn; isAuth = true } }, Modifier.fillMaxWidth().height(60.dp).padding(top = 10.dp)) { Text("START RADAR") }
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
                        val r = Ride(it.key?:"", it.child("pName").value.toString(), it.child("price").value.toString(), it.child("status").value.toString(), it.child("pLat").value.toString().toDoubleOrNull()?:0.0, it.child("pLon").value.toString().toDoubleOrNull()?:0.0, it.child("dLat").value.toString().toDoubleOrNull()?:0.0, it.child("dLon").value.toString().toDoubleOrNull()?:0.0)
                        if(r.status == "REQUESTED") list.add(r)
                        else if(r.status != "COMPLETED" && it.child("driver").value == dName) current = r
                    }
                    rides = list; activeJob = current
                }
                override fun onCancelled(e: DatabaseError) {}
            })
        }

        Box(Modifier.fillMaxSize()) {
            AndroidView(factory = { MapView(it).apply { setTileSource(TileSourceFactory.MAPNIK); controller.setZoom(15.5); controller.setCenter(GeoPoint(6.0333, 37.5500)) } })
            Column(Modifier.align(Alignment.BottomCenter).padding(16.dp)) {
                if (activeJob != null) {
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                        Column(Modifier.padding(20.dp)) {
                            Text("${activeJob!!.price} ETB", fontSize = 36.sp, fontWeight = FontWeight.Black, color = Color.Red)
                            Text("Passenger: ${activeJob!!.pName}")
                            Row(Modifier.fillMaxWidth().padding(top = 10.dp), Arrangement.spacedBy(8.dp)) {
                                val isOn = activeJob!!.status == "ON_TRIP"
                                Button(onClick = { context.launchNav(if(isOn) activeJob!!.dLat else activeJob!!.pLat, if(isOn) activeJob!!.dLon else activeJob!!.pLon) }, Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))) { Text(if(isOn) "NAV DROP" else "NAV PICKUP") }
                                Button(onClick = { 
                                    val next = when(activeJob!!.status) { "ACCEPTED" -> "ARRIVED"; "ARRIVED" -> "ON_TRIP"; else -> "COMPLETED" }
                                    ref.child(activeJob!!.id).child("status").setValue(next)
                                }, Modifier.weight(1f)) { Text(when(activeJob!!.status) { "ACCEPTED" -> "ARRIVED"; "ARRIVED" -> "START"; else -> "FINISH" }) }
                            }
                        }
                    }
                } else {
                    LazyColumn(Modifier.heightIn(max = 250.dp)) {
                        items(rides) { r ->
                            Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                                Row(Modifier.padding(16.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                    Column { Text(r.pName, fontWeight = FontWeight.Bold); Text("${r.price} ETB") }
                                    Button(onClick = { ref.child(r.id).updateChildren(mapOf("status" to "ACCEPTED", "driver" to dName)) }) { Text("ACCEPT") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

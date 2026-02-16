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
import org.osmdroid.views.overlay.Marker

data class Ride(
    val id: String = "", val pName: String = "", val pPhone: String = "", val price: String = "0", 
    val tier: String = "", val status: String = "", 
    val pLat: Double = 0.0, val pLon: Double = 0.0, 
    val dLat: Double = 0.0, val dLon: Double = 0.0
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        setContent { MaterialTheme { DriverEngine() } }
    }
    
    fun launchNav(lat: Double, lon: Double) {
        if (lat == 0.0) return
        val uri = Uri.parse("google.navigation:q=$lat,$lon&mode=d")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply { setPackage("com.google.android.apps.maps") }
        try { startActivity(intent) } catch (e: Exception) { 
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$lat,$lon"))) 
        }
    }
    
    fun dial(n: String) = startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$n")))
}

@Composable
fun DriverEngine() {
    val context = LocalContext.current as MainActivity
    val prefs = context.getSharedPreferences("bayra_d_final", Context.MODE_PRIVATE)
    var name by remember { mutableStateOf(prefs.getString("n", "") ?: "") }
    var phone by remember { mutableStateOf(prefs.getString("p", "") ?: "") }
    var isAuth by remember { mutableStateOf(name.isNotEmpty()) }

    if (!isAuth) {
        Column(modifier = Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "BAYRA DRIVER", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color(0xFF5E4E92))
            Spacer(modifier = Modifier.height(30.dp))
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(text = "Name") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text(text = "Phone") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { if(name.isNotEmpty()){ prefs.edit().putString("n", name).putString("p", phone).apply(); isAuth = true } }, modifier = Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5E4E92))) { Text(text = "LOGIN") }
        }
    } else { RadarView(name, phone, context) { prefs.edit().clear().apply(); isAuth = false } }
}

@Composable
fun RadarView(dName: String, dPh: String, activity: MainActivity, onLogout: () -> Unit) {
    val ref = FirebaseDatabase.getInstance().getReference("rides")
    var rides by remember { mutableStateOf(listOf<Ride>()) }
    var activeJob by remember { mutableStateOf<Ride?>(null) }

    LaunchedEffect(Unit) {
        ref.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val list = mutableListOf<Ride>()
                var current: Ride? = null
                s.children.forEach {
                    val status = it.child("status").getValue(String::class.java) ?: ""
                    val r = Ride(
                        id = it.child("id").getValue(String::class.java) ?: "",
                        pName = it.child("pName").getValue(String::class.java) ?: "User",
                        pPhone = it.child("pPhone").getValue(String::class.java) ?: "",
                        price = it.child("price").value?.toString() ?: "0",
                        tier = it.child("tier").getValue(String::class.java) ?: "",
                        status = status,
                        pLat = it.child("pLat").getValue(Double::class.java) ?: 0.0,
                        pLon = it.child("pLon").getValue(Double::class.java) ?: 0.0,
                        dLat = it.child("dLat").getValue(Double::class.java) ?: 0.0,
                        dLon = it.child("dLon").getValue(Double::class.java) ?: 0.0
                    )
                    if (status == "REQUESTED") list.add(r)
                    else if (status != "COMPLETED" && it.child("driverName").getValue(String::class.java) == dName) current = r
                }
                rides = list; activeJob = current
            }
            override fun onCancelled(e: DatabaseError) {}
        })
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx -> MapView(ctx).apply { setTileSource(TileSourceFactory.MAPNIK); controller.setZoom(15.0); controller.setCenter(GeoPoint(6.0333, 37.5500)) } }, update = { view ->
            view.overlays.clear()
            if(activeJob != null) { view.overlays.add(Marker(view).apply { position = GeoPoint(activeJob!!.pLat, activeJob!!.pLon); title = "Pickup" }) }
            else rides.forEach { r -> view.overlays.add(Marker(view).apply { position = GeoPoint(r.pLat, r.pLon); title = "${r.price} ETB" }) }
            view.invalidate()
        })
        
        Column(Modifier.align(Alignment.BottomCenter).padding(16.dp)) {
            if (activeJob != null) {
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(8.dp)) {
                    Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = "PASSENGER: ${activeJob!!.pName}", fontWeight = FontWeight.Bold)
                        Text(text = "${activeJob!!.price} ETB", fontSize = 40.sp, color = Color.Red, fontWeight = FontWeight.Black)
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // 🔥 BUTTON 1: CONTEXTUAL NAVIGATION 🔥
                            val isOnTrip = activeJob!!.status == "ON_TRIP"
                            val navLabel = if (isOnTrip) "NAV DROP-OFF" else "NAV PICKUP"
                            val targetLat = if (isOnTrip) activeJob!!.dLat else activeJob!!.pLat
                            val targetLon = if (isOnTrip) activeJob!!.dLon else activeJob!!.pLon
                            
                            Button(onClick = { activity.launchNav(targetLat, targetLon) }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))) {
                                Text(text = navLabel, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            
                            Button(onClick = { activity.dial(activeJob!!.pPhone) }, modifier = Modifier.weight(0.7f), colors = ButtonDefaults.buttonColors(containerColor = Color.Black)) {
                                Text(text = "CALL", fontSize = 11.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        
                        // 🔥 BUTTON 2: CONTEXTUAL STATUS 🔥
                        val statusLabel = when(activeJob!!.status) {
                            "ACCEPTED" -> "I HAVE ARRIVED"
                            "ARRIVED" -> "START TRIP"
                            "ON_TRIP" -> "FINISH TRIP"
                            else -> "UPDATE"
                        }
                        
                        Button(
                            onClick = {
                                val next = when(activeJob!!.status) { 
                                    "ACCEPTED" -> "ARRIVED" 
                                    "ARRIVED" -> "ON_TRIP" 
                                    else -> "COMPLETED" 
                                }
                                if(next == "COMPLETED") ref.child(activeJob!!.id).child("status").setValue("COMPLETED")
                                else ref.child(activeJob!!.id).child("status").setValue(next)
                            }, 
                            modifier = Modifier.fillMaxWidth().height(60.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = if(activeJob!!.status == "ON_TRIP") Color.Red else Color(0xFF5E4E92))
                        ) {
                            Text(text = statusLabel, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth().background(Color.White.copy(alpha=0.8f)).padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "RADAR ACTIVE", fontWeight = FontWeight.Bold)
                    TextButton(onClick = onLogout) { Text(text = "LOGOUT", color = Color.Red) }
                }
                LazyColumn {
                    items(rides) { ride ->
                        Card(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                            Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                Column { Text(text = ride.pName, fontWeight = FontWeight.Bold); Text(text = "${ride.price} ETB") }
                                Button({ ref.child(ride.id).updateChildren(mapOf("status" to "ACCEPTED", "driverName" to dName, "dPhone" to dPh)) }) { Text(text = "ACCEPT") }
                            }
                        }
                    }
                }
            }
        }
    }
}

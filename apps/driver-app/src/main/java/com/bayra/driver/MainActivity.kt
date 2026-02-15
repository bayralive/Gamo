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
import kotlinx.coroutines.delay

data class Ride(
    val id: String = "", val pName: String = "", val price: String = "0", 
    val tier: String = "", val status: String = "", val pPhone: String = "",
    val pLat: Double = 0.0, val pLon: Double = 0.0, val dLat: Double = 0.0, val dLon: Double = 0.0
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        setContent { MaterialTheme { DriverEngine() } }
    }
    
    fun launchGoogleMaps(lat: Double, lon: Double) {
        if (lat == 0.0) return
        val uri = Uri.parse("google.navigation:q=$lat,$lon")
        val intent = Intent(Intent.ACTION_VIEW, uri)
        intent.setPackage("com.google.android.apps.maps")
        try { startActivity(intent) } catch (e: Exception) { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
    }
    
    fun dial(phone: String) { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))) }
}

@Composable
fun DriverEngine() {
    val context = LocalContext.current as MainActivity
    val prefs = context.getSharedPreferences("bayra_d_vFinal", Context.MODE_PRIVATE)
    var name by remember { mutableStateOf(prefs.getString("n", "") ?: "") }
    var isAuth by remember { mutableStateOf(name.isNotEmpty()) }

    if (!isAuth) {
        Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
            Text("BAYRA DRIVER", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color(0xFF5E4E92))
            Spacer(Modifier.height(30.dp))
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
            Button({ if(name.isNotEmpty()){ prefs.edit().putString("n", name).apply(); isAuth = true } }, Modifier.fillMaxWidth().height(60.dp)) { Text("LOGIN") }
        }
    } else {
        RadarView(name, context) { prefs.edit().clear().apply(); isAuth = false }
    }
}

@Composable
fun RadarView(driverName: String, activity: MainActivity, onLogout: () -> Unit) {
    val ref = FirebaseDatabase.getInstance().getReference("rides")
    var rides by remember { mutableStateOf(listOf<Ride>()) }
    var activeJob by remember { mutableStateOf<Ride?>(null) }
    var mapCenter by remember { mutableStateOf(GeoPoint(6.0333, 37.5500)) }

    LaunchedEffect(Unit) {
        ref.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val list = mutableListOf<Ride>()
                var current: Ride? = null
                s.children.forEach {
                    val st = it.child("status").getValue(String::class.java) ?: ""
                    val r = Ride(
                        it.child("id").getValue(String::class.java) ?: "",
                        it.child("pName").getValue(String::class.java) ?: "User",
                        it.child("price").value?.toString() ?: "0",
                        it.child("tier").getValue(String::class.java) ?: "",
                        st,
                        it.child("pPhone").getValue(String::class.java) ?: "",
                        it.child("pLat").getValue(Double::class.java) ?: 0.0,
                        it.child("pLon").getValue(Double::class.java) ?: 0.0,
                        it.child("dLat").getValue(Double::class.java) ?: 0.0,
                        it.child("dLon").getValue(Double::class.java) ?: 0.0
                    )
                    if (st == "REQUESTED") list.add(r)
                    else if (st != "COMPLETED" && it.child("driverName").getValue(String::class.java) == driverName) current = r
                }
                rides = list; activeJob = current
            }
            override fun onCancelled(e: DatabaseError) {}
        })
    }

    Box(Modifier.fillMaxSize()) {
        // --- 🗺️ DRIVER MAP RESTORED ---
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx ->
            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                controller.setZoom(15.0)
                controller.setCenter(mapCenter)
            }
        }, update = { view ->
            view.overlays.clear()
            if (activeJob != null) {
                val p = Marker(view).apply { position = GeoPoint(activeJob!!.pLat, activeJob!!.pLon); title = "Pickup"; icon = view.context.getDrawable(android.R.drawable.ic_menu_myplaces) }
                view.overlays.add(p)
                view.controller.setCenter(p.position)
                if (activeJob!!.dLat != 0.0) {
                    val d = Marker(view).apply { position = GeoPoint(activeJob!!.dLat, activeJob!!.dLon); title = "Drop"; icon = view.context.getDrawable(android.R.drawable.ic_menu_directions) }
                    view.overlays.add(d)
                }
            } else {
                rides.forEach { r ->
                    val m = Marker(view).apply { position = GeoPoint(r.pLat, r.pLon); title = "${r.price} ETB" }
                    view.overlays.add(m)
                }
            }
            view.invalidate()
        })

        // --- TOP BAR ---
        Row(Modifier.fillMaxWidth().background(Color.White.copy(alpha=0.9f)).padding(16.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text("RADAR: $driverName", fontWeight = FontWeight.Bold)
            TextButton(onLogout) { Text("LOGOUT", color = Color.Red) }
        }

        // --- BOTTOM CARD ---
        Column(Modifier.align(Alignment.BottomCenter).padding(16.dp)) {
            if (activeJob != null) {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.padding(20.dp)) {
                        Text("PASSENGER: ${activeJob!!.pName}", fontWeight = FontWeight.Bold)
                        Text("${activeJob!!.price} ETB", fontSize = 32.sp, color = Color.Red, fontWeight = FontWeight.Black)
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                            // 🛰️ NAVIGATE BUTTON RESTORED
                            val navLat = if(activeJob!!.status == "ON_TRIP") activeJob!!.dLat else activeJob!!.pLat
                            val navLon = if(activeJob!!.status == "ON_TRIP") activeJob!!.dLon else activeJob!!.pLon
                            if (navLat != 0.0) {
                                Button({ activity.launchGoogleMaps(navLat, navLon) }, Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))) { Text("NAVIGATE") }
                            }
                            Button({ activity.dial(activeJob!!.pPhone) }, Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color.Black)) { Text("CALL") }
                        }
                        Spacer(Modifier.height(10.dp))
                        Button({
                            val next = when(activeJob!!.status) { "ACCEPTED" -> "ARRIVED"; "ARRIVED" -> "ON_TRIP"; else -> "COMPLETED" }
                            if (next == "COMPLETED") ref.child(activeJob!!.id).child("status").setValue("COMPLETED")
                            else ref.child(activeJob!!.id).child("status").setValue(next)
                        }, Modifier.fillMaxWidth().height(55.dp), colors = ButtonDefaults.buttonColors(containerColor = if(activeJob!!.status == "ON_TRIP") Color.Red else Color(0xFF5E4E92))) {
                            Text(if(activeJob!!.status == "ON_TRIP") "FINISH TRIP" else "UPDATE STATUS: ${activeJob!!.status}")
                        }
                    }
                }
            } else {
                LazyColumn {
                    items(rides) { ride ->
                        Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                            Row(Modifier.padding(16.dp).fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                Column { Text(ride.pName, fontWeight = FontWeight.Bold); Text("${ride.price} ETB", color = Color.Red) }
                                Button({ ref.child(ride.id).updateChildren(mapOf("status" to "ACCEPTED", "driverName" to driverName)) }) { Text("ACCEPT") }
                            }
                        }
                    }
                }
            }
        }
    }
}

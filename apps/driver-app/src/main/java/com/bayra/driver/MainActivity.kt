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
    val id: String = "", val pName: String = "", val pPhone: String = "",
    val price: String = "0", val tier: String = "", val status: String = "",
    val pLat: Double = 0.0, val pLon: Double = 0.0,
    val dLat: Double = 0.0, val dLon: Double = 0.0
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        setContent { MaterialTheme { DriverApp() } }
    }
    
    fun launchNav(lat: Double, lon: Double) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$lat,$lon&mode=d"))
        intent.setPackage("com.google.android.apps.maps")
        try { startActivity(intent) } catch (e: Exception) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$lat,$lon"))) }
    }
    
    fun dial(phone: String) { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))) }
}

@Composable
fun DriverApp() {
    val context = LocalContext.current as MainActivity
    val prefs = context.getSharedPreferences("bayra_driver_vFinal", Context.MODE_PRIVATE)
    var name by remember { mutableStateOf(prefs.getString("n", "") ?: "") }
    var plate by remember { mutableStateOf(prefs.getString("p", "") ?: "") }
    var isAuth by remember { mutableStateOf(name.isNotEmpty()) }
    var currentTab by remember { mutableStateOf("RADAR") }
    var earnings by remember { mutableStateOf(prefs.getInt("e", 0)) }

    if (!isAuth) {
        Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
            Text("BAYRA DRIVER", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color(0xFF5E4E92))
            Spacer(Modifier.height(30.dp))
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(plate, { plate = it }, label = { Text("Plate Number") }, modifier = Modifier.fillMaxWidth())
            Button({ if(name.length > 2){ 
                prefs.edit().putString("n", name).putString("p", plate).apply()
                isAuth = true 
            } }, Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5E4E92))) { Text("LOGIN") }
        }
    } else {
        Scaffold(
            bottomBar = {
                NavigationBar(containerColor = Color.White) {
                    NavigationBarItem(selected = currentTab == "RADAR", onClick = { currentTab = "RADAR" }, icon = { Text("📡") }, label = { Text("Radar") })
                    NavigationBarItem(selected = currentTab == "VAULT", onClick = { currentTab = "VAULT" }, icon = { Text("💰") }, label = { Text("Vault") })
                }
            }
        ) { p ->
            Box(Modifier.padding(p)) {
                if (currentTab == "RADAR") RadarView(name, plate, context) { earned ->
                    earnings += earned
                    prefs.edit().putInt("e", earnings).apply()
                } else VaultView(earnings, name) { 
                    prefs.edit().clear().apply()
                    isAuth = false 
                }
            }
        }
    }
}

@Composable
fun RadarView(dName: String, dPlate: String, activity: MainActivity, onFinish: (Int) -> Unit) {
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
                    val ride = Ride(
                        it.child("id").getValue(String::class.java) ?: "",
                        it.child("pName").getValue(String::class.java) ?: "User",
                        it.child("pPhone").getValue(String::class.java) ?: "",
                        it.child("price").value?.toString() ?: "0",
                        it.child("tier").getValue(String::class.java) ?: "",
                        status,
                        it.child("pLat").getValue(Double::class.java) ?: 0.0,
                        it.child("pLon").getValue(Double::class.java) ?: 0.0,
                        it.child("dLat").getValue(Double::class.java) ?: 0.0,
                        it.child("dLon").getValue(Double::class.java) ?: 0.0
                    )
                    if (status == "REQUESTED") list.add(ride)
                    else if (it.child("driverName").getValue(String::class.java) == dName && status != "COMPLETED") current = ride
                }
                rides = list; activeJob = current
            }
            override fun onCancelled(e: DatabaseError) {}
        })
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(Modifier.fillMaxSize(), factory = { ctx -> MapView(ctx).apply { 
            setTileSource(TileSourceFactory.MAPNIK)
            controller.setZoom(15.0); controller.setCenter(GeoPoint(6.0333, 37.5500)) 
        } }, update = { view ->
            view.overlays.clear()
            if (activeJob != null) {
                view.overlays.add(Marker(view).apply { position = GeoPoint(activeJob!!.pLat, activeJob!!.pLon); title = "Pickup" })
            } else {
                rides.forEach { r -> view.overlays.add(Marker(view).apply { position = GeoPoint(r.pLat, r.pLon); title = "${r.price} ETB" }) }
            }
            view.invalidate()
        })

        Column(Modifier.align(Alignment.BottomCenter).padding(16.dp)) {
            if (activeJob != null) {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("ACTIVE: ${activeJob!!.pName}", fontWeight = FontWeight.Bold)
                        Text("${activeJob!!.price} ETB", fontSize = 32.sp, color = Color.Red, fontWeight = FontWeight.Black)
                        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                            Button({ activity.launchNav(activeJob!!.pLat, activeJob!!.pLon) }, Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))) { Text("NAVIGATE") }
                            Button({ activity.dial(activeJob!!.pPhone) }, Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color.Black)) { Text("CALL") }
                        }
                        Spacer(Modifier.height(10.dp))
                        Button({
                            val next = when(activeJob!!.status) { "ACCEPTED" -> "ARRIVED"; "ARRIVED" -> "ON_TRIP"; else -> "COMPLETED" }
                            if (next == "COMPLETED") {
                                val p = activeJob!!.price.toIntOrNull() ?: 0
                                ref.child(activeJob!!.id).child("status").setValue("COMPLETED").addOnSuccessListener { onFinish(p) }
                            } else { ref.child(activeJob!!.id).child("status").setValue(next) }
                        }, Modifier.fillMaxWidth().height(55.dp)) { Text("UPDATE: ${activeJob!!.status}") }
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(rides) { ride ->
                        Card(Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(16.dp).fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                Column { Text(ride.pName, fontWeight = FontWeight.Bold); Text(ride.tier, color = Color.Gray) }
                                Button({ ref.child(ride.id).updateChildren(mapOf("status" to "ACCEPTED", "driverName" to dName, "driverPlate" to dPlate)) }) { Text("ACCEPT") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun VaultView(total: Int, name: String, onLogout: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("EARNINGS: $name", fontWeight = FontWeight.Bold)
        Text("$total ETB", fontSize = 60.sp, color = Color(0xFF4CAF50), fontWeight = FontWeight.Black)
        Spacer(Modifier.weight(1f))
        Button(onLogout, colors = ButtonDefaults.buttonColors(containerColor = Color.Red), modifier = Modifier.fillMaxWidth().height(60.dp)) { Text("GO OFFLINE") }
    }
}

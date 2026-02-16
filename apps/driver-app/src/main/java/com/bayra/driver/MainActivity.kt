package com.bayra.driver

import android.content.Context // 🛡️ THE MISSING WELD
import android.os.Bundle
import android.preference.PreferenceManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

data class Ride(val id: String = "", val pName: String = "", val price: String = "0", val tier: String = "", val status: String = "")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        setContent { MaterialTheme { DriverEngine() } }
    }
}

@Composable
fun DriverEngine() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("bayra_d_vFinal", Context.MODE_PRIVATE)
    var name by remember { mutableStateOf(prefs.getString("n", "") ?: "") }
    var isAuth by remember { mutableStateOf(name.isNotEmpty()) }

    if (!isAuth) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp), 
            verticalArrangement = Arrangement.Center, 
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = "BAYRA DRIVER", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color(0xFF5E4E92))
            Spacer(modifier = Modifier.height(30.dp))
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { if(name.isNotEmpty()){ prefs.edit().putString("n", name).apply(); isAuth = true } }, modifier = Modifier.fillMaxWidth().height(60.dp)) { Text("LOGIN") }
        }
    } else {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(text = "RADAR: $name", fontWeight = FontWeight.Bold)
                TextButton(onClick = { prefs.edit().clear().apply(); isAuth = false }) { Text(text = "LOGOUT", color = Color.Red) }
            }
            Spacer(modifier = Modifier.height(16.dp)); RadarView(name)
        }
    }
}

@Composable
fun RadarView(dName: String) {
    val ref = FirebaseDatabase.getInstance().getReference("rides")
    var rides by remember { mutableStateOf(listOf<Ride>()) }
    var activeJob by remember { mutableStateOf<Ride?>(null) }

    LaunchedEffect(Unit) {
        ref.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val list = mutableListOf<Ride>()
                var current: Ride? = null
                s.children.forEach {
                    val st = it.child("status").getValue(String::class.java) ?: ""
                    val r = Ride(id = it.child("id").getValue(String::class.java) ?: "", pName = it.child("pName").getValue(String::class.java) ?: "User", price = it.child("price").value?.toString() ?: "0", tier = it.child("tier").getValue(String::class.java) ?: "", status = st)
                    if (st == "REQUESTED") list.add(r)
                    else if (st != "COMPLETED" && it.child("driverName").getValue(String::class.java) == dName) current = r
                }
                rides = list; activeJob = current
            }
            override fun onCancelled(e: DatabaseError) {}
        })
    }

    if (activeJob != null) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "ACTIVE: ${activeJob!!.pName}", fontWeight = FontWeight.Bold)
                Text(text = "${activeJob!!.price} ETB", fontSize = 48.sp, color = Color.Red, fontWeight = FontWeight.Black)
                Button(onClick = { ref.child(activeJob!!.id).child("status").setValue("COMPLETED") }, modifier = Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text(text = "FINISH TRIP") }
            }
        }
    } else {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(rides) { ride ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(text = "Passenger: ${ride.pName}", fontWeight = FontWeight.Bold)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(text = ride.tier); Text(text = "${ride.price} ETB", color = Color.Red, fontWeight = FontWeight.Black)
                        }
                        Button(onClick = { ref.child(ride.id).updateChildren(mapOf("status" to "ACCEPTED", "driverName" to dName)) }, modifier = Modifier.fillMaxWidth()) { Text(text = "ACCEPT") }
                    }
                }
            }
        }
    }
}

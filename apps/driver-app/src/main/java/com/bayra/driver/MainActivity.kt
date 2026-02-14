package com.bayra.driver

import android.os.Bundle
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.database.*

data class Ride(val id: String = "", val pName: String = "", val price: String = "0", val tier: String = "", val status: String = "")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { DriverEngine() } }
    }
}

@Composable
fun DriverEngine() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("bayra_d_vFinal", 0)
    var name by remember { mutableStateOf(prefs.getString("n", "") ?: "") }
    var isAuth by remember { mutableStateOf(name.isNotEmpty()) }

    if (!isAuth) {
        Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
            Text("BAYRA DRIVER", fontSize = 28.sp, color = Color(0xFF5E4E92))
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
            Button({ if(name.isNotEmpty()){ prefs.edit().putString("n", name).apply(); isAuth = true } }, Modifier.fillMaxWidth()) { Text("LOGIN") }
        }
    } else {
        RadarView(name)
    }
}

@Composable
fun RadarView(dName: String) {
    val ref = FirebaseDatabase.getInstance().getReference("rides")
    var rides by remember { mutableStateOf(listOf<Ride>()) }
    var activeRide by remember { mutableStateOf<Ride?>(null) }

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
                        it.child("price").value?.toString() ?: "0",
                        it.child("tier").getValue(String::class.java) ?: "Standard",
                        status
                    )
                    // 🔥 PIPING FIX: List all REQUESTED rides
                    if (status == "REQUESTED") list.add(ride)
                    else if (it.child("driverName").getValue(String::class.java) == dName && status != "COMPLETED") {
                        current = ride
                    }
                }
                rides = list; activeRide = current
            }
            override fun onCancelled(e: DatabaseError) {}
        })
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("RADAR: $dName", fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        if (activeRide != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("ACTIVE: ${activeRide!!.pName}", fontWeight = FontWeight.Bold)
                    Text("${activeRide!!.price} ETB", fontSize = 32.sp, color = Color.Red)
                    Button({ ref.child(activeRide!!.id).child("status").setValue("COMPLETED") }, Modifier.fillMaxWidth()) { Text("FINISH TRIP") }
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(rides) { ride ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Passenger: ${ride.pName}", fontWeight = FontWeight.Bold)
                            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                                Text(ride.tier); Text("${ride.price} ETB", color = Color.Red, fontWeight = FontWeight.Black)
                            }
                            Button({ ref.child(ride.id).updateChildren(mapOf("status" to "ACCEPTED", "driverName" to dName)) }, Modifier.fillMaxWidth()) { Text("ACCEPT") }
                        }
                    }
                }
            }
        }
    }
}

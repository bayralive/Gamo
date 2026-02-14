package com.bayra.driver

import android.content.Context
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.database.*

data class Ride(val id: String = "", val pName: String = "", val price: String = "0", val tier: String = "", val status: String = "", val pPhone: String = "")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { DriverEngine() } }
    }
}

@Composable
fun DriverEngine() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("bayra_d_vFinal", Context.MODE_PRIVATE)
    var name by remember { mutableStateOf(prefs.getString("n", "") ?: "") }
    var phone by remember { mutableStateOf(prefs.getString("p", "") ?: "") }
    var isAuth by remember { mutableStateOf(name.isNotEmpty()) }

    if (!isAuth) {
        Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
            Text("BAYRA DRIVER", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color(0xFF5E4E92))
            Spacer(Modifier.height(30.dp))
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(phone, { phone = it }, label = { Text("Phone") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(20.dp))
            Button({ if(name.isNotEmpty() && phone.isNotEmpty()){ prefs.edit().putString("n", name).putString("p", phone).apply(); isAuth = true } }, Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5E4E92))) { Text("LOGIN") }
        }
    } else {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Text("RADAR: $name", fontWeight = FontWeight.Bold); TextButton({ prefs.edit().clear().apply(); isAuth = false }) { Text("LOGOUT", color = Color.Red) }
            }
            Spacer(Modifier.height(16.dp)); RadarView(name, phone)
        }
    }
}

@Composable
fun RadarView(dName: String, dPhone: String) {
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
                    val r = Ride(it.child("id").getValue(String::class.java) ?: "", it.child("pName").getValue(String::class.java) ?: "User", it.child("price").value?.toString() ?: "0", it.child("tier").getValue(String::class.java) ?: "", status, it.child("pPhone").getValue(String::class.java) ?: "")
                    if (status == "REQUESTED") list.add(r)
                    else if (it.child("driverName").getValue(String::class.java) == dName && status != "COMPLETED") current = r
                }
                rides = list; activeRide = current
            }
            override fun onCancelled(e: DatabaseError) {}
        })
    }

    if (activeRide != null) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("ACTIVE: ${activeRide!!.pName}", fontWeight = FontWeight.Bold)
                Text("${activeRide!!.price} ETB", fontSize = 32.sp, color = Color.Red, fontWeight = FontWeight.Black)
                Button({ ref.child(activeRide!!.id).child("status").setValue("COMPLETED") }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red), modifier = Modifier.fillMaxWidth()) { Text("FINISH TRIP") }
            }
        }
    } else {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(rides) { ride ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Passenger: ${ride.pName}", fontWeight = FontWeight.Bold)
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                            Text(ride.tier); Text("${ride.price} ETB", color = Color.Red, fontWeight = FontWeight.Black)
                        }
                        Button({ ref.child(ride.id).updateChildren(mapOf("status" to "ACCEPTED", "driverName" to dName, "dPhone" to dPhone)) }, Modifier.fillMaxWidth()) { Text("ACCEPT") }
                    }
                }
            }
        }
    }
}

package com.bayra.driver

import android.content.Context
import android.os.Bundle
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
import com.google.firebase.database.*

data class Ride(val id: String = "", val pName: String = "", val price: String = "0", val status: String = "")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { DriverTreasuryApp() } }
    }
}

@Composable
fun DriverTreasuryApp() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("bayra_vault_v1", Context.MODE_PRIVATE)
    var totalEarned by remember { mutableStateOf(prefs.getInt("total", 0)) }
    var currentTab by remember { mutableStateOf("RADAR") }
    var name by remember { mutableStateOf(prefs.getString("n", "") ?: "") }

    if (name.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center) {
            Text("DRIVER LOGIN", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
            Button({ prefs.edit().putString("n", name).apply() }, Modifier.fillMaxWidth()) { Text("START") }
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
                if (currentTab == "RADAR") RadarTab(name) { earned ->
                    totalEarned += earned
                    prefs.edit().putInt("total", totalEarned).apply()
                } else VaultTab(totalEarned)
            }
        }
    }
}

@Composable
fun RadarTab(dName: String, onFinish: (Int) -> Unit) {
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
                    val ride = Ride(it.child("id").getValue(String::class.java) ?: "", it.child("pName").getValue(String::class.java) ?: "User", it.child("price").value?.toString() ?: "0", status)
                    if (status == "REQUESTED") list.add(ride)
                    else if (it.child("driverName").getValue(String::class.java) == dName && status != "COMPLETED") current = ride
                }
                rides = list; activeRide = current
            }
            override fun onCancelled(e: DatabaseError) {}
        })
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        if (activeRide != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp), Alignment.CenterHorizontally) {
                    Text("ACTIVE TRIP", fontWeight = FontWeight.Bold)
                    Text("${activeRide!!.price} ETB", fontSize = 48.sp, color = Color.Red, fontWeight = FontWeight.Black)
                    Button({ 
                        val p = activeRide!!.price.toIntOrNull() ?: 0
                        ref.child(activeRide!!.id).child("status").setValue("COMPLETED").addOnSuccessListener { onFinish(p) }
                    }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red), modifier = Modifier.fillMaxWidth()) { Text("FINISH TRIP") }
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(rides) { ride ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp).fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                            Column { Text(ride.pName, fontWeight = FontWeight.Bold); Text("${ride.price} ETB", color = Color.Red) }
                            Button({ ref.child(ride.id).updateChildren(mapOf("status" to "ACCEPTED", "driverName" to dName)) }) { Text("ACCEPT") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun VaultTab(total: Int) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("TOTAL EARNED", color = Color.Gray, fontWeight = FontWeight.Bold)
        Text("$total ETB", fontSize = 64.sp, fontWeight = FontWeight.Black, color = Color(0xFF4CAF50))
        Spacer(Modifier.height(20.dp))
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5))) {
            Column(Modifier.padding(16.dp)) {
                val commission = (total * 0.15).toInt()
                Text("Platform Fee (15%): $commission ETB", fontWeight = FontWeight.Bold, color = Color.Red)
                Text("Net Income: ${total - commission} ETB", fontWeight = FontWeight.Bold, color = Color(0xFF5E4E92))
            }
        }
    }
}

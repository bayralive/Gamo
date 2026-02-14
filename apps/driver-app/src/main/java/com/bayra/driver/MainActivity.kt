package com.bayra.driver

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.database.*
import kotlinx.coroutines.delay

data class Ride(val id: String = "", val pName: String = "", val price: String = "0", val tier: String = "", val status: String = "")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { DriverEngine() } }
    }
}

@Composable
fun DriverEngine() {
    val ridesRef = FirebaseDatabase.getInstance().getReference("rides")
    var rides by remember { mutableStateOf(listOf<Ride>()) }
    var activeJob by remember { mutableStateOf<Ride?>(null) }

    LaunchedEffect(Unit) {
        ridesRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val list = mutableListOf<Ride>()
                var current: Ride? = null
                s.children.forEach {
                    val st = it.child("status").getValue(String::class.java) ?: ""
                    val r = Ride(it.child("id").getValue(String::class.java) ?: "", it.child("pName").getValue(String::class.java) ?: "User", it.child("price").value?.toString() ?: "0", it.child("tier").getValue(String::class.java) ?: "", st)
                    if (st == "REQUESTED") list.add(r)
                    else if (st != "COMPLETED") current = r
                }
                rides = list; activeJob = current
            }
            override fun onCancelled(e: DatabaseError) {}
        })
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("BAYRA COMMAND", fontWeight = FontWeight.Black, fontSize = 24.sp, color = Color(0xFF5E4E92))
        Spacer(Modifier.height(16.dp))
        if (activeJob != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("ACTIVE TRIP: ${activeJob!!.pName}", fontWeight = FontWeight.Bold)
                    Text("${activeJob!!.price} ETB", fontSize = 48.sp, color = Color.Red, fontWeight = FontWeight.Black)
                    Button({ ridesRef.child(activeJob!!.id).child("status").setValue("COMPLETED") }, Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text("FINISH TRIP") }
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(rides) { ride ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp).fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                            Column { Text(ride.pName, fontWeight = FontWeight.Bold); Text("${ride.price} ETB", color = Color.Red) }
                            Button({ ridesRef.child(ride.id).updateChildren(mapOf("status" to "ACCEPTED", "driverName" to "Sovereign")) }) { Text("ACCEPT") }
                        }
                    }
                }
            }
        }
    }
}

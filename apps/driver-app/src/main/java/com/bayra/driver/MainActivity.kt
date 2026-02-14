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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.database.*

data class Ride(val id: String = "", val pName: String = "", val price: String = "0")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { DriverRadar() } }
    }
}

@Composable
fun DriverRadar() {
    val ridesRef = FirebaseDatabase.getInstance().getReference("rides")
    var rides by remember { mutableStateOf(listOf<Ride>()) }

    LaunchedEffect(Unit) {
        ridesRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val list = mutableListOf<Ride>()
                s.children.forEach {
                    if (it.child("status").getValue(String::class.java) == "REQUESTED") {
                        val ride = Ride(
                            it.child("id").getValue(String::class.java) ?: "",
                            it.child("pName").getValue(String::class.java) ?: "User",
                            // 🔥 DATA FIX: Pulling the correctly mapped price string
                            it.child("price").value?.toString() ?: "120"
                        )
                        list.add(ride)
                    }
                }
                rides = list
            }
            override fun onCancelled(e: DatabaseError) {}
        })
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("BAYRA RADAR", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF5E4E92))
        Spacer(Modifier.height(20.dp))
        if (rides.isEmpty()) { Box(Modifier.fillMaxSize(), Alignment.Center) { Text("📡 Watching Arba Minch...") } }
        else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(rides) { ride ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Passenger: ${ride.pName}", fontWeight = FontWeight.Bold)
                            Text("${ride.price} ETB", color = Color.Red, fontSize = 24.sp, fontWeight = FontWeight.Black)
                            Button({ ridesRef.child(ride.id).child("status").setValue("ACCEPTED") }, Modifier.fillMaxWidth()) { Text("ACCEPT JOB") }
                        }
                    }
                }
            }
        }
    }
}

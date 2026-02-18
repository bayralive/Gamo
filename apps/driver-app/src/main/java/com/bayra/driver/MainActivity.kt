package com.bayra.driver

import android.Manifest
import android.content.*
import android.net.Uri
import android.os.Bundle
import android.preference.PreferenceManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import com.google.firebase.database.*
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView

const val DB_URL = "https://bayra-84ecf-default-rtdb.europe-west1.firebasedatabase.app"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        setContent { MaterialTheme { RadarHub() } }
    }
}

@Composable
fun RadarHub() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("d_prefs", Context.MODE_PRIVATE) }
    var dName by remember { mutableStateOf(prefs.getString("n", "") ?: "") }
    
    // 🔥 THE VAULT: Tracking Commission
    var balance by remember { mutableStateOf(0) }
    val ref = FirebaseDatabase.getInstance(DB_URL).getReference("rides")
    val balanceRef = FirebaseDatabase.getInstance(DB_URL).getReference("drivers").child(dName).child("balance")

    var jobs by remember { mutableStateOf(listOf<DataSnapshot>()) }
    var activeJob by remember { mutableStateOf<DataSnapshot?>(null) }

    LaunchedEffect(dName) {
        if(dName.isNotEmpty()) {
            balanceRef.addValueEventListener(object : ValueEventListener {
                override fun onDataChange(s: DataSnapshot) { balance = s.value?.toString()?.toInt() ?: 0 }
                override fun onCancelled(e: DatabaseError) {}
            })
        }
    }

    LaunchedEffect(Unit) {
        ref.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val list = mutableListOf<DataSnapshot>()
                var current: DataSnapshot? = null
                s.children.forEach { 
                    if(it.child("status").value == "REQUESTED") list.add(it)
                    else if(it.child("status").value != "COMPLETED" && it.child("driverName").value == dName) current = it
                }
                jobs = list; activeJob = current
            }
            override fun onCancelled(e: DatabaseError) {}
        })
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { context -> MapView(context).apply { setTileSource(TileSourceFactory.MAPNIK); controller.setZoom(15.0); controller.setCenter(GeoPoint(6.0333, 37.5500)) } })
        
        Column(Modifier.align(Alignment.BottomCenter).padding(16.dp)) {
            // 🔥 THE LEDGER HUD
            Surface(Modifier.fillMaxWidth().padding(bottom = 8.dp), shape = RoundedCornerShape(12.dp), color = Color.Black) {
                Row(Modifier.padding(16.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Column {
                        Text("COMMISSION OWED", color = Color.Gray, fontSize = 10.sp)
                        Text("$balance ETB", color = if(balance > 500) Color.Red else Color.Green, fontWeight = FontWeight.Black, fontSize = 20.sp)
                    }
                    if(dName.isEmpty()) {
                        Button(onClick = { /* Simple Login Logic */ }) { Text("LOGIN") }
                    } else {
                        Text(dName, color = Color.White)
                    }
                }
            }

            if (activeJob != null) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp)) {
                        val price = activeJob!!.child("price").value.toString().toInt()
                        Text("${price} ETB", fontSize = 32.sp, fontWeight = FontWeight.Black)
                        val status = activeJob!!.child("status").value.toString()
                        
                        Button(onClick = { 
                            val next = when(status) { "ACCEPTED" -> "ARRIVED"; "ARRIVED" -> "ON_TRIP"; else -> "COMPLETED" }
                            
                            // 🔥 CALCULATION: When finishing, add 15% to "Owed Balance"
                            if(next == "COMPLETED") {
                                val commission = (price * 0.15).toInt()
                                balanceRef.setValue(balance + commission)
                            }
                            
                            ref.child(activeJob!!.key!!).child("status").setValue(next)
                        }, Modifier.fillMaxWidth()) { Text(next) }
                    }
                }
            } else {
                LazyColumn(Modifier.heightIn(max = 200.dp)) {
                    items(jobs) { snap ->
                        Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                            Row(Modifier.padding(16.dp), Arrangement.SpaceBetween) {
                                Text(snap.child("pName").value.toString())
                                Button(onClick = { ref.child(snap.key!!).updateChildren(mapOf("status" to "ACCEPTED", "driverName" to dName)) }) { Text("ACCEPT") }
                            }
                        }
                    }
                }
            }
        }
    }
}
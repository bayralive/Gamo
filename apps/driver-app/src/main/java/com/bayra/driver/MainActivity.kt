package com.bayra.driver

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
    
    var debt by remember { mutableStateOf(0) }
    var credit by remember { mutableStateOf(0) }
    
    val ref = FirebaseDatabase.getInstance(DB_URL).getReference("rides")
    val driverRef = FirebaseDatabase.getInstance(DB_URL).getReference("drivers").child(dName)

    var jobs by remember { mutableStateOf(listOf<DataSnapshot>()) }
    var activeJob by remember { mutableStateOf<DataSnapshot?>(null) }

    LaunchedEffect(dName) {
        if(dName.isNotEmpty()) {
            driverRef.addValueEventListener(object : ValueEventListener {
                override fun onDataChange(s: DataSnapshot) { 
                    debt = s.child("debt").value?.toString()?.toInt() ?: 0 
                    credit = s.child("credit").value?.toString()?.toInt() ?: 0
                }
                override fun onCancelled(e: DatabaseError) {}
            })
        }
    }

    LaunchedEffect(Unit) {
        ref.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val list = mutableListOf<DataSnapshot>()
                var current: Ride? = null
                s.children.forEach { 
                    if(it.child("status").value == "REQUESTED") list.add(it)
                    else if(it.child("status").value != "COMPLETED" && it.child("driverName").value == dName) activeJob = it
                }
                jobs = list
            }
            override fun onCancelled(e: DatabaseError) {}
        })
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { context -> MapView(context).apply { setTileSource(TileSourceFactory.MAPNIK); controller.setZoom(15.0); controller.setCenter(GeoPoint(6.0333, 37.5500)) } })
        
        Column(Modifier.align(Alignment.BottomCenter).padding(16.dp)) {
            // HUD
            Surface(Modifier.fillMaxWidth().padding(bottom = 8.dp), shape = RoundedCornerShape(16.dp), color = Color.Black) {
                Row(Modifier.padding(16.dp), Arrangement.SpaceBetween) {
                    Column {
                        Text("CREDIT (85%)", color = Color.Gray, fontSize = 10.sp)
                        Text("$credit ETB", color = Color.Cyan, fontWeight = FontWeight.Bold)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("DEBT (15%)", color = Color.Gray, fontSize = 10.sp)
                        Text("$debt ETB", color = Color.Red, fontWeight = FontWeight.Bold)
                    }
                }
            }

            val netDebt = debt - credit

            if (netDebt >= 500) {
                // 🔥 THE RESTRICTION SCREEN
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 100.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Red.copy(alpha = 0.9f))
                ) {
                    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("ACCOUNT RESTRICTED", color = Color.White, fontWeight = FontWeight.Black, fontSize = 20.sp)
                        Text("Your debt exceeds 500 ETB.", color = Color.White, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(16.dp))
                        Text("Please pay your commission to the Bayra Office to reactivate.", color = Color.Yellow, fontSize = 12.sp, textAlign = TextAlign.Center)
                    }
                }
            } else if (activeJob != null) {
                // ACTIVE JOB UI
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp)) {
                        val price = activeJob!!.child("price").value.toString().toInt()
                        val payType = activeJob!!.child("pay").value?.toString() ?: "CASH"
                        Text("${price} ETB ($payType)", fontSize = 28.sp, fontWeight = FontWeight.Black)
                        
                        Button(onClick = { 
                            val status = activeJob!!.child("status").value.toString()
                            val next = when(status) { "ACCEPTED" -> "ARRIVED"; "ARRIVED" -> "ON_TRIP"; else -> "COMPLETED" }
                            if(next == "COMPLETED") {
                                if(payType == "CHAPA") driverRef.child("credit").setValue(credit + (price * 0.85).toInt())
                                else driverRef.child("debt").setValue(debt + (price * 0.15).toInt())
                                activeJob = null
                            }
                            ref.child(activeJob!!.key!!).child("status").setValue(next)
                        }, Modifier.fillMaxWidth().height(55.dp)) { Text(status) }
                    }
                }
            } else {
                // NORMAL RADAR LIST
                LazyColumn(Modifier.heightIn(max = 250.dp)) {
                    items(jobs) { snap ->
                        Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                            Row(Modifier.padding(16.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                Text(snap.child("pName").value.toString(), fontWeight = FontWeight.Bold)
                                Button(onClick = { ref.child(snap.key!!).updateChildren(mapOf("status" to "ACCEPTED", "driverName" to dName)) }) { Text("ACCEPT") }
                            }
                        }
                    }
                }
            }
        }
    }
}
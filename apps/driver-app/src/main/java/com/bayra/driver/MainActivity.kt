package com.bayra.driver

import android.Manifest
import android.content.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.preference.PreferenceManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import com.google.firebase.database.*
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

const val DB_URL = "https://bayra-84ecf-default-rtdb.europe-west1.firebasedatabase.app"

data class RideJob(
    val id: String = "", val pName: String = "", val pPhone: String = "",
    val price: String = "0", val status: String = "IDLE", val tier: String = "",
    val pLat: Double = 0.0, val pLon: Double = 0.0, val dLat: Double = 0.0, val dLon: Double = 0.0
)

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        setContent { MaterialTheme { DriverAppRoot() } }
    }
    fun launchNav(lat: Double, lon: Double) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$lat,$lon")).apply { setPackage("com.google.android.apps.maps") }) }
        catch (e: Exception) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$lat,$lon"))) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriverAppRoot() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("bayra_guard_v176", Context.MODE_PRIVATE) }
    var dName by rememberSaveable { mutableStateOf(prefs.getString("n", "") ?: "") }
    var isAuth by remember { mutableStateOf(dName.isNotEmpty()) }
    var nIn by remember { mutableStateOf("") }
    var pIn by remember { mutableStateOf("") }

    if (!isAuth) {
        Column(modifier = Modifier.fillMaxSize().padding(32.dp).background(Color.White), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            val logoId = ctx.resources.getIdentifier("logo_driver", "drawable", ctx.packageName)
            if (logoId != 0) Image(painter = painterResource(id = logoId), null, modifier = Modifier.size(200.dp))
            Text("DRIVER SECURE LOGIN", fontSize = 24.sp, fontWeight = FontWeight.Black, color = Color(0xFF1A237E))
            Spacer(Modifier.height(30.dp))
            OutlinedTextField(value = nIn, onValueChange = { nIn = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(value = pIn, onValueChange = { pIn = it }, label = { Text("PIN") }, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation())
            Spacer(Modifier.height(20.dp))
            Button(onClick = {
                if (nIn.isNotEmpty() && pIn.isNotEmpty()) {
                    FirebaseDatabase.getInstance(DB_URL).getReference("drivers").child(nIn).addListenerForSingleValueEvent(object : ValueEventListener {
                        override fun onDataChange(s: DataSnapshot) {
                            if (s.child("password").value?.toString() == pIn) { prefs.edit().putString("n", nIn).apply(); dName = nIn; isAuth = true } 
                            else Toast.makeText(ctx, "Invalid PIN", Toast.LENGTH_SHORT).show()
                        }
                        override fun onCancelled(e: DatabaseError) {}
                    })
                }
            }, modifier = Modifier.fillMaxWidth().height(60.dp)) { Text("UNLOCK RADAR") }
        }
    } else {
        Box(Modifier.fillMaxSize()) {
            RadarHub(dName) { prefs.edit().clear().apply(); isAuth = false }
        }
    }
}

@Composable
fun RadarHub(driverName: String, onLogout: () -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as? MainActivity
    val ref = FirebaseDatabase.getInstance(DB_URL).getReference("rides")
    val driverRef = FirebaseDatabase.getInstance(DB_URL).getReference("drivers").child(driverName)

    var jobs by remember { mutableStateOf(listOf<DataSnapshot>()) }
    var activeJobSnap by remember { mutableStateOf<DataSnapshot?>(null) }
    var debt by remember { mutableStateOf(0) }
    var credit by remember { mutableStateOf(0) }

    LaunchedEffect(driverName) {
        driverRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) { debt = s.child("debt").value?.toString()?.toInt()?:0; credit = s.child("credit").value?.toString()?.toInt()?:0 }
            override fun onCancelled(e: DatabaseError) {}
        })
    }
    LaunchedEffect(Unit) {
        ref.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val list = mutableListOf<DataSnapshot>()
                var current: DataSnapshot? = null
                s.children.forEach { 
                    val status = it.child("status").value?.toString() ?: ""
                    if (status == "REQUESTED") list.add(it)
                    else if (status != "COMPLETED" && it.child("driverName").value == driverName) current = it
                }
                jobs = list; activeJobSnap = current
            }
            override fun onCancelled(e: DatabaseError) {}
        })
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { c -> MapView(c).apply { setTileSource(TileSourceFactory.MAPNIK); controller.setZoom(15.0); controller.setCenter(GeoPoint(6.0333, 37.5500)) } })
        
        Column(Modifier.align(Alignment.BottomCenter).padding(16.dp)) {
            Surface(Modifier.fillMaxWidth().padding(bottom = 8.dp), shape = RoundedCornerShape(12.dp), color = Color.Black) {
                Row(Modifier.padding(16.dp), Arrangement.SpaceBetween) {
                    Column { Text("CREDIT: $credit", color = Color.Cyan); Text("DEBT: $debt", color = Color.Red) }
                    TextButton(onLogout) { Text("LOGOUT", color = Color.White) }
                }
            }

            if (debt - credit >= 500) {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.Red)) { Text("ACCOUNT LOCKED", Modifier.padding(20.dp), color = Color.White, fontWeight = FontWeight.Bold) }
            } else if (activeJobSnap != null) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp)) {
                        val price = activeJobSnap!!.child("price").value?.toString() ?: "0"
                        Text("$price ETB", fontSize = 32.sp, fontWeight = FontWeight.Bold)
                        val status = activeJobSnap!!.child("status").value?.toString() ?: ""
                        
                        // 🔥 PAYMENT HANDOVER LOGIC
                        if (status == "ARRIVED_DEST" || status == "WAITING_PAYMENT") {
                            Text("WAITING FOR PAYMENT...", color = Color.Magenta, fontWeight = FontWeight.Bold)
                        } else if (status.startsWith("PAID_")) {
                            Text("PAYMENT RECEIVED!", color = Color.Green, fontWeight = FontWeight.Bold)
                            Button(onClick = {
                                val p = price.replace("[^0-9]".toRegex(), "").toInt()
                                if (status == "PAID_CHAPA") driverRef.child("credit").setValue(credit + (p * 0.85).toInt())
                                else driverRef.child("debt").setValue(debt + (p * 0.15).toInt())
                                ref.child(activeJobSnap!!.key!!).child("status").setValue("COMPLETED")
                            }, Modifier.fillMaxWidth().padding(top = 10.dp)) { Text("CLOSE RIDE") }
                        } else {
                            val next = when(status) { "ACCEPTED" -> "ARRIVED"; "ARRIVED" -> "ON_TRIP"; else -> "ARRIVED_DEST" }
                            Button(onClick = { ref.child(activeJobSnap!!.key!!).child("status").setValue(next) }, Modifier.fillMaxWidth().padding(top = 10.dp)) { Text(if(next == "ARRIVED_DEST") "ARRIVED AT DESTINATION" else next) }
                        }
                    }
                }
            } else {
                LazyColumn(Modifier.heightIn(max = 200.dp)) {
                    items(jobs) { snap ->
                        Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                            Row(Modifier.padding(16.dp), Arrangement.SpaceBetween) {
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

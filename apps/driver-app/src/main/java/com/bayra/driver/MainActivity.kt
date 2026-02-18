package com.bayra.driver

import android.Manifest
import android.content.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.preference.PreferenceManager
import android.util.Log
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.firebase.database.*
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

const val DB_URL = "https://bayra-84ecf-default-rtdb.europe-west1.firebasedatabase.app"

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        setContent { MaterialTheme { DriverAppRoot() } }
    }
    fun launchNav(lat: Double, lon: Double) {
        try {
            val uri = Uri.parse("google.navigation:q=$lat,$lon")
            startActivity(Intent(Intent.ACTION_VIEW, uri).apply { setPackage("com.google.android.apps.maps") })
        } catch (e: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$lat,$lon")))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriverAppRoot() {
    val ctx = LocalContext.current
    val activity = ctx as? MainActivity
    val prefs = remember { ctx.getSharedPreferences("bayra_driver_v169", Context.MODE_PRIVATE) }
    
    var dName by rememberSaveable { mutableStateOf(prefs.getString("n", "") ?: "") }
    var isAuth by remember { mutableStateOf(dName.isNotEmpty()) }
    var isRadarOn by remember { mutableStateOf(false) }

    if (!isAuth) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp).background(Color.White),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val logoId = ctx.resources.getIdentifier("logo_driver", "drawable", ctx.packageName)
            if (logoId != 0) Image(painter = painterResource(id = logoId), contentDescription = null, modifier = Modifier.size(200.dp))
            Text(text = "BAYRA DRIVER", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color(0xFF1A237E))
            var nIn by remember { mutableStateOf("") }
            OutlinedTextField(value = nIn, onValueChange = { nIn = it }, label = { Text(text = "Name") }, modifier = Modifier.fillMaxWidth())
            Button(
                onClick = { if (nIn.length > 2) { prefs.edit().putString("n", nIn).apply(); dName = nIn; isAuth = true } },
                modifier = Modifier.fillMaxWidth().height(60.dp).padding(top = 16.dp)
            ) { Text(text = "LOGIN") }
        }
    } else {
        Box(modifier = Modifier.fillMaxSize()) {
            RadarHub(dName, isRadarOn) { isAuth = false; prefs.edit().clear().apply() }
            if (!isRadarOn) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black.copy(alpha = 0.7f)) {
                    Column(verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Button(onClick = { isRadarOn = true }, modifier = Modifier.size(150.dp), shape = CircleShape, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))) {
                            Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(50.dp))
                        }
                        Text(text = "ACTIVATE RADAR", color = Color.White, modifier = Modifier.padding(top = 10.dp))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadarHub(driverName: String, isRadarOn: Boolean, onLogout: () -> Unit) {
    val ref = FirebaseDatabase.getInstance(DB_URL).getReference("rides")
    val driverRef = FirebaseDatabase.getInstance(DB_URL).getReference("drivers").child(driverName)

    var jobs by remember { mutableStateOf(listOf<DataSnapshot>()) }
    var activeJob by remember { mutableStateOf<DataSnapshot?>(null) }
    var debt by remember { mutableStateOf(0) }
    var credit by remember { mutableStateOf(0) }

    LaunchedEffect(driverName) {
        driverRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                debt = s.child("debt").value?.toString()?.toInt() ?: 0
                credit = s.child("credit").value?.toString()?.toInt() ?: 0
            }
            override fun onCancelled(e: DatabaseError) {}
        })
    }

    LaunchedEffect(Unit) {
        ref.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val list = mutableListOf<DataSnapshot>()
                var current: DataSnapshot? = null
                s.children.forEach { 
                    if (it.child("status").value == "REQUESTED") list.add(it)
                    else if (it.child("status").value != "COMPLETED" && it.child("driverName").value == driverName) current = it
                }
                jobs = list; activeJob = current
            }
            override fun onCancelled(e: DatabaseError) {}
        })
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(factory = { c -> MapView(c).apply { setTileSource(TileSourceFactory.MAPNIK); controller.setZoom(15.0); controller.setCenter(GeoPoint(6.0333, 37.5500)) } })
        
        Column(modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)) {
            Surface(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), shape = RoundedCornerShape(12.dp), color = Color.Black) {
                Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column { Text(text = "CREDIT: $credit", color = Color.Cyan, fontSize = 12.sp); Text(text = "DEBT: $debt", color = Color.Red, fontSize = 12.sp) }
                    TextButton(onClick = onLogout) { Text(text = "LOGOUT", color = Color.White) }
                }
            }

            val netDebt = debt - credit
            if (netDebt >= 500) {
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.Red)) {
                    Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = "ACCOUNT LOCKED", fontWeight = FontWeight.Bold, color = Color.White)
                        Text(text = "Pay commission to unlock.", color = Color.White, fontSize = 12.sp)
                    }
                }
            } else if (activeJob != null) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        val price = activeJob!!.child("price").value?.toString() ?: "0"
                        Text(text = "$price ETB", fontSize = 32.sp, fontWeight = FontWeight.Black)
                        
                        // 🔥 LOGIC MOVED OUTSIDE CLICK BLOCK TO FIX SCOPE ERROR
                        val currentStatus = activeJob!!.child("status").value?.toString() ?: ""
                        val label = when(currentStatus) { "ACCEPTED" -> "ARRIVED"; "ARRIVED" -> "ON_TRIP"; else -> "FINISH" }
                        
                        Button(onClick = { 
                            val nextStatus = when(currentStatus) { "ACCEPTED" -> "ARRIVED"; "ARRIVED" -> "ON_TRIP"; else -> "COMPLETED" }
                            if (nextStatus == "COMPLETED") {
                                val p = price.toInt()
                                if (activeJob!!.child("pay").value == "CHAPA") driverRef.child("credit").setValue(credit + (p * 0.85).toInt())
                                else driverRef.child("debt").setValue(debt + (p * 0.15).toInt())
                            }
                            ref.child(activeJob!!.key!!).child("status").setValue(nextStatus)
                        }, modifier = Modifier.fillMaxWidth()) { Text(text = label) }
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                    items(jobs) { snap ->
                        Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                            Row(modifier = Modifier.padding(16.dp), Arrangement.SpaceBetween) {
                                Text(text = snap.child("pName").value.toString(), fontWeight = FontWeight.Bold)
                                Button(onClick = { ref.child(snap.key!!).updateChildren(mapOf("status" to "ACCEPTED", "driverName" to driverName)) }) { Text(text = "ACCEPT") }
                            }
                        }
                    }
                }
            }
        }
    }
}

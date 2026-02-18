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
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
    val prefs = remember { ctx.getSharedPreferences("bayra_guard_v174", Context.MODE_PRIVATE) }
    var dName by rememberSaveable { mutableStateOf(value = prefs.getString("n", "") ?: "") }
    var isAuth by remember { mutableStateOf(value = dName.isNotEmpty()) }
    var isRadarOn by remember { mutableStateOf(value = false) }

    if (!isAuth) {
        var nIn by remember { mutableStateOf(value = "") }
        var pIn by remember { mutableStateOf(value = "") }
        var isVerifying by remember { mutableStateOf(value = false) }

        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp).background(Color.White).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val logoId = ctx.resources.getIdentifier("logo_driver", "drawable", ctx.packageName)
            if (logoId != 0) Image(painter = painterResource(id = logoId), null, modifier = Modifier.size(200.dp))
            Text(text = "DRIVER SECURE LOGIN", fontSize = 24.sp, fontWeight = FontWeight.Black, color = Color(0xFF1A237E))
            Spacer(modifier = Modifier.height(30.dp))
            OutlinedTextField(value = nIn, onValueChange = { nIn = it }, label = { Text(text = "Driver Name") }, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(value = pIn, onValueChange = { pIn = it }, label = { Text(text = "PIN") }, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation())
            Spacer(modifier = Modifier.height(20.dp))
            if (isVerifying) { CircularProgressIndicator() } else {
                Button(onClick = {
                    if (nIn.isNotEmpty()) {
                        isVerifying = true
                        FirebaseDatabase.getInstance(DB_URL).getReference("drivers").child(nIn)
                            .addListenerForSingleValueEvent(object : ValueEventListener {
                                override fun onDataChange(s: DataSnapshot) {
                                    if (s.child("password").value?.toString() == pIn) {
                                        prefs.edit().putString("n", nIn).apply()
                                        dName = nIn; isAuth = true
                                    } else { Toast.makeText(ctx, "Invalid PIN", Toast.LENGTH_SHORT).show() }
                                    isVerifying = false
                                }
                                override fun onCancelled(e: DatabaseError) { isVerifying = false }
                            })
                    }
                }, modifier = Modifier.fillMaxWidth().height(60.dp)) { Text(text = "UNLOCK RADAR") }
            }
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
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadarHub(driverName: String, isRadarOn: Boolean, onLogout: () -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as? MainActivity
    val ref = FirebaseDatabase.getInstance(DB_URL).getReference("rides")
    val driverRef = FirebaseDatabase.getInstance(DB_URL).getReference("drivers").child(driverName)

    var jobs by remember { mutableStateOf(value = listOf<DataSnapshot>()) }
    var activeJobSnap by remember { mutableStateOf<DataSnapshot?>(value = null) }
    var debt by remember { mutableStateOf(value = 0) }
    var credit by remember { mutableStateOf(value = 0) }

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
                jobs = list; activeJobSnap = current
            }
            override fun onCancelled(e: DatabaseError) {}
        })
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(factory = { c -> MapView(c).apply { setTileSource(TileSourceFactory.MAPNIK); controller.setZoom(15.0); controller.setCenter(GeoPoint(6.0333, 37.5500)) } })
        
        Column(modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)) {
            Surface(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), shape = RoundedCornerShape(12.dp), color = Color.Black) {
                Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column { 
                        Text(text = "CREDIT: $credit", color = Color.Cyan, fontSize = 12.sp)
                        Text(text = "DEBT: $debt", color = Color.Red, fontSize = 12.sp) 
                    }
                    TextButton(onClick = onLogout) { Text(text = "LOGOUT", color = Color.White) }
                }
            }

            if (debt - credit >= 500) {
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.Red)) {
                    Text(text = "ACCOUNT LOCKED - PAY COMMISSION", modifier = Modifier.padding(20.dp), color = Color.White, fontWeight = FontWeight.Bold)
                }
            } else if (activeJobSnap != null) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        val price = activeJobSnap!!.child("price").value?.toString() ?: "0"
                        val payMethod = activeJobSnap!!.child("pay").value?.toString() ?: "CASH"
                        
                        Text(text = "$price ETB", fontSize = 32.sp, fontWeight = FontWeight.Bold)
                        // 🔥 SHOW PAYMENT METHOD FOR TRANSPARENCY
                        Text(text = "Payment Method: $payMethod", color = if(payMethod == "CHAPA") Color(0xFF2E7D32) else Color.Black, fontWeight = FontWeight.Bold)
                        
                        val status = activeJobSnap!!.child("status").value?.toString() ?: ""
                        val label = when(status) { "ACCEPTED" -> "ARRIVED"; "ARRIVED" -> "ON_TRIP"; else -> "FINISH TRIP" }
                        
                        Button(onClick = { 
                            val next = when(status) { "ACCEPTED" -> "ARRIVED"; "ARRIVED" -> "ON_TRIP"; else -> "COMPLETED" }
                            if (next == "COMPLETED") {
                                val p = price.replace("[^0-9]".toRegex(), "").toInt()
                                // 🔥 THE LEDGER FIX: Identify payMethod before updating
                                if (payMethod == "CHAPA") {
                                    driverRef.child("credit").setValue(credit + (p * 0.85).toInt())
                                } else {
                                    driverRef.child("debt").setValue(debt + (p * 0.15).toInt())
                                }
                            }
                            ref.child(activeJobSnap!!.key!!).child("status").setValue(next)
                        }, modifier = Modifier.fillMaxWidth().height(55.dp).padding(top = 10.dp)) { Text(text = label) }
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                    items(jobs) { snap ->
                        Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                            Row(modifier = Modifier.padding(16.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                Column {
                                    Text(snap.child("pName").value.toString(), fontWeight = FontWeight.Bold)
                                    Text("${snap.child("price").value} ETB")
                                }
                                Button(onClick = { ref.child(snap.key!!).updateChildren(mapOf("status" to "ACCEPTED", "driverName" to driverName)) }) { Text("ACCEPT") }
                            }
                        }
                    }
                }
            }
        }
    }
}

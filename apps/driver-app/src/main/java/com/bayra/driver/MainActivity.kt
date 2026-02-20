package com.bayra.driver

import android.Manifest
import android.content.*
import android.net.Uri
import android.os.*
import android.preference.PreferenceManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
    val prefs = remember { ctx.getSharedPreferences("bayra_d_v198", Context.MODE_PRIVATE) }
    var dName by rememberSaveable { mutableStateOf(prefs.getString("n", "") ?: "") }
    var isAuth by remember { mutableStateOf(dName.isNotEmpty()) }
    var currentTab by rememberSaveable { mutableStateOf("HOME") }
    var showHistory by remember { mutableStateOf(false) }

    if (!isAuth) {
        LoginScreen { n -> dName = n; isAuth = true; prefs.edit().putString("n", n).apply() }
    } else {
        if (showHistory) {
            HistoryPage(dName) { showHistory = false }
        } else {
            Scaffold(
                bottomBar = {
                    NavigationBar(containerColor = Color.Black) {
                        NavigationBarItem(icon = { Icon(Icons.Filled.Home, null) }, label = { Text("Radar") }, selected = currentTab == "HOME", onClick = { currentTab = "HOME" }, colors = NavigationBarItemDefaults.colors(selectedIconColor = Color.Green, indicatorColor = Color.DarkGray))
                        NavigationBarItem(icon = { Icon(Icons.Filled.Person, null) }, label = { Text("Account") }, selected = currentTab == "ACCOUNT", onClick = { currentTab = "ACCOUNT" }, colors = NavigationBarItemDefaults.colors(selectedIconColor = Color.Green, indicatorColor = Color.DarkGray))
                    }
                }
            ) { p ->
                Box(Modifier.padding(p)) {
                    if (currentTab == "HOME") RadarHub(dName) { isAuth = false; prefs.edit().clear().apply() }
                    else DriverAccountView(dName, onHistory = { showHistory = true }) { isAuth = false; prefs.edit().clear().apply() }
                }
            }
        }
    }
}

@Composable
fun HistoryPage(driverName: String, onBack: () -> Unit) {
    val ref = FirebaseDatabase.getInstance(DB_URL).getReference("rides")
    var trips by remember { mutableStateOf(listOf<DataSnapshot>()) }
    
    LaunchedEffect(Unit) {
        ref.orderByChild("driverName").equalTo(driverName).addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                trips = s.children.filter { it.child("status").value == "COMPLETED" }.reversed()
            }
            override fun onCancelled(e: DatabaseError) {}
        })
    }

    Column(Modifier.fillMaxSize().background(Color.White)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, null) }
                Text("TRIP HISTORY", fontSize = 20.sp, fontWeight = FontWeight.Black)
            }
            // 🔥 CLEAR HISTORY BUTTON
            if (trips.isNotEmpty()) {
                IconButton(onClick = { 
                    trips.forEach { it.ref.removeValue() }
                }) { Icon(Icons.Filled.Delete, null, tint = Color.Red) }
            }
        }
        LazyColumn(Modifier.padding(16.dp)) {
            if (trips.isEmpty()) item { Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) { Text("No trips found.") } }
            items(trips) { t ->
                Card(Modifier.fillMaxWidth().padding(bottom = 12.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5))) {
                    Row(Modifier.padding(16.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        Column {
                            Text(text = "To: Arba Minch", fontWeight = FontWeight.Bold)
                            Text(text = "Passenger: ${t.child("pName").value}", fontSize = 12.sp, color = Color.Gray)
                        }
                        Text(text = "${t.child("price").value} ETB", fontWeight = FontWeight.Black, color = Color(0xFF2E7D32))
                    }
                }
            }
        }
    }
}

@Composable
fun DriverAccountView(driverName: String, onHistory: () -> Unit, onLogout: () -> Unit) {
    val ref = FirebaseDatabase.getInstance(DB_URL).getReference("drivers").child(driverName)
    var debt by remember { mutableStateOf(0) }
    var credit by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        ref.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                debt = s.child("debt").value?.toString()?.toInt() ?: 0
                credit = s.child("credit").value?.toString()?.toInt() ?: 0
            }
            override fun onCancelled(e: DatabaseError) {}
        })
    }
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(40.dp))
        Icon(Icons.Filled.Person, null, Modifier.size(80.dp), tint = Color.LightGray)
        Text(text = driverName, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(40.dp))
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.padding(24.dp)) {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                    Column { Text("OWED TO YOU", fontSize = 12.sp); Text("$credit ETB", color = Color.Green, fontWeight = FontWeight.Black) }
                    Column { Text("OWED TO EMPIRE", fontSize = 12.sp); Text("$debt ETB", color = Color.Red, fontWeight = FontWeight.Black) }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Button(onClick = onHistory, Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A237E))) { Text("VIEW TRIP HISTORY") }
        Spacer(Modifier.weight(1f))
        Button(onClick = onLogout, colors = ButtonDefaults.buttonColors(containerColor = Color.Red), modifier = Modifier.fillMaxWidth()) { Text("LOGOUT") }
    }
}

@Composable fun LoginScreen(onLogin: (String) -> Unit) { 
    var n by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        Text("BAYRA DRIVER", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(value = n, onValueChange = { n = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { if(n.isNotEmpty()) onLogin(n) }, Modifier.fillMaxWidth().padding(top = 20.dp)) { Text("LOGIN") }
    }
}

@Composable fun RadarHub(name: String, onLogout: () -> Unit) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Radar Active: $name") } }

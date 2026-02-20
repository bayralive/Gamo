package com.bayra.customer

import android.Manifest
import android.content.*
import android.net.Uri
import android.os.*
import android.preference.PreferenceManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import com.google.firebase.database.*
import org.osmdroid.config.Configuration

const val DB_URL = "https://bayra-84ecf-default-rtdb.europe-west1.firebasedatabase.app"

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        setContent { MaterialTheme { PassengerRoot() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PassengerRoot() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("bayra_p_v198", Context.MODE_PRIVATE) }
    var pName by rememberSaveable { mutableStateOf(prefs.getString("n", "") ?: "") }
    var isAuth by remember { mutableStateOf(pName.isNotEmpty()) }
    var currentTab by rememberSaveable { mutableStateOf("HOME") }
    var showHistory by remember { mutableStateOf(false) }

    if (!isAuth) {
        LoginView { n -> pName = n; isAuth = true; prefs.edit().putString("n", n).apply() }
    } else {
        if (showHistory) {
            PassengerHistory(pName) { showHistory = false }
        } else {
            Scaffold(
                bottomBar = {
                    NavigationBar(containerColor = Color.White) {
                        NavigationBarItem(icon = { Icon(Icons.Filled.Home, null) }, label = { Text("Map") }, selected = currentTab == "HOME", onClick = { currentTab = "HOME" })
                        NavigationBarItem(icon = { Icon(Icons.Filled.Person, null) }, label = { Text("Account") }, selected = currentTab == "ACCOUNT", onClick = { currentTab = "ACCOUNT" })
                    }
                }
            ) { p ->
                Box(Modifier.padding(p)) {
                    if (currentTab == "HOME") Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Map Active") }
                    else AccountView(pName, onLogout = { isAuth = false; prefs.edit().clear().apply() }, onShowHistory = { showHistory = true })
                }
            }
        }
    }
}

@Composable
fun PassengerHistory(name: String, onBack: () -> Unit) {
    val ref = FirebaseDatabase.getInstance(DB_URL).getReference("rides")
    var history by remember { mutableStateOf(listOf<DataSnapshot>()) }

    LaunchedEffect(Unit) {
        ref.orderByChild("pName").equalTo(name).addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                history = s.children.filter { it.child("status").value == "COMPLETED" }.reversed()
            }
            override fun onCancelled(e: DatabaseError) {}
        })
    }

    Column(Modifier.fillMaxSize().background(Color.White)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, null) }
                Text("MY TRIPS", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            }
            // 🔥 CLEAR HISTORY BUTTON
            if (history.isNotEmpty()) {
                IconButton(onClick = { history.forEach { it.ref.removeValue() } }) { Icon(Icons.Filled.Delete, null, tint = Color.Red) }
            }
        }
        LazyColumn(Modifier.padding(16.dp)) {
            if (history.isEmpty()) item { Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) { Text("No trips yet.") } }
            items(history) { h ->
                Card(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                    Row(Modifier.padding(16.dp), Arrangement.SpaceBetween) {
                        Column { Text("Trip Finished", fontWeight = FontWeight.Bold); Text("${h.child("tier").value}", fontSize = 12.sp) }
                        Text("${h.child("price").value} ETB", fontWeight = FontWeight.Black)
                    }
                }
            }
        }
    }
}

@Composable
fun AccountView(name: String, onLogout: () -> Unit, onShowHistory: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(40.dp))
        Text("Account Settings", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(name, color = Color.Gray)
        Spacer(Modifier.height(40.dp))
        Button(onClick = onShowHistory, Modifier.fillMaxWidth().height(60.dp)) { Text("VIEW TRIP HISTORY") }
        Spacer(Modifier.weight(1f))
        Button(onClick = onLogout, colors = ButtonDefaults.buttonColors(containerColor = Color.Red), modifier = Modifier.fillMaxWidth()) { Text("LOGOUT") }
    }
}

@Composable fun LoginView(onLogin: (String) -> Unit) { 
    var n by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        Text("BAYRA PASSENGER", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(value = n, onValueChange = { n = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { if(n.isNotEmpty()) onLogin(n) }, Modifier.fillMaxWidth().padding(top = 20.dp)) { Text("START") }
    }
}

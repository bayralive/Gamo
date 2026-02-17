package com.bayra.driver

import android.Manifest
import android.content.*
import android.os.Bundle
import android.preference.PreferenceManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import com.google.firebase.database.*
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        setContent { MaterialTheme { DriverApp() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriverApp() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("bayra_d_v143", Context.MODE_PRIVATE) }
    var dName by rememberSaveable { mutableStateOf(prefs.getString("n", "") ?: "") }
    var isAuth by remember { mutableStateOf(dName.isNotEmpty()) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
        if (perms[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            if (dName.length > 2) { prefs.edit().putString("n", dName).apply(); isAuth = true }
        }
    }

    if (!isAuth) {
        Column(modifier = Modifier.fillMaxSize().padding(32.dp).background(Color.White), Arrangement.Center, Alignment.CenterHorizontally) {
            Image(painter = painterResource(id = R.drawable.logo_driver), contentDescription = null, modifier = Modifier.size(240.dp))
            Spacer(modifier = Modifier.height(10.dp))
            Text(text = "BAYRA DRIVER", fontSize = 32.sp, fontWeight = FontWeight.Black, color = Color(0xFF1A237E))
            var nIn by remember { mutableStateOf("") }
            OutlinedTextField(value = nIn, onValueChange = { nIn = it }, label = { Text(text = "Name") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { 
                dName = nIn
                launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.POST_NOTIFICATIONS))
            }, modifier = Modifier.fillMaxWidth().height(60.dp).padding(top = 16.dp)) { Text(text = "ACTIVATE RADAR") }
        }
    } else {
        RadarCore(dName)
    }
}

@Composable
fun RadarCore(driverName: String) {
    val ref = FirebaseDatabase.getInstance().getReference("rides")
    var jobs by remember { mutableStateOf(listOf<DataSnapshot>()) }

    LaunchedEffect(Unit) {
        ref.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val list = mutableListOf<DataSnapshot>()
                s.children.forEach { if(it.child("status").value == "REQUESTED") list.add(it) }
                jobs = list
            }
            override fun onCancelled(e: DatabaseError) {}
        })
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(factory = { context -> MapView(context).apply { setTileSource(TileSourceFactory.MAPNIK); controller.setZoom(15.0); controller.setCenter(GeoPoint(6.0333, 37.5500)) } })
        Column(modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)) {
            Surface(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), shape = RoundedCornerShape(12.dp), color = Color.Black.copy(alpha=0.8f)) {
                Text(text = "RADAR ONLINE", color = Color.Green, modifier = Modifier.padding(16.dp), fontWeight = FontWeight.Bold)
            }
            LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                items(jobs) { snap ->
                    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        Row(modifier = Modifier.padding(16.dp), Arrangement.SpaceBetween) {
                            Column { Text(snap.child("pName").value.toString(), fontWeight = FontWeight.Bold); Text("${snap.child("price").value} ETB") }
                            Button(onClick = { ref.child(snap.key!!).child("status").setValue("ACCEPTED") }) { Text(text = "ACCEPT") }
                        }
                    }
                }
            }
        }
    }
}

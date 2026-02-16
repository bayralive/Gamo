package com.bayra.customer

import android.content.Context
import android.os.Bundle
import android.preference.PreferenceManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.firebase.database.*
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        setContent { MaterialTheme { CustomerApp() } }
    }
}

@Composable
fun CustomerApp() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("p_prefs", Context.MODE_PRIVATE)
    var name by remember { mutableStateOf(prefs.getString("n", "") ?: "") }
    var isAuth by remember { mutableStateOf(name.isNotEmpty()) }
    var status by remember { mutableStateOf("IDLE") }

    if (!isAuth) {
        Column(Modifier.fillMaxSize().padding(32.dp).background(Color.White), Arrangement.Center) {
            var nIn by remember { mutableStateOf("") }
            Text("BAYRA", fontSize = 40.sp, fontWeight = FontWeight.Black, color = Color(0xFF5E4E92))
            Text("Passenger Login", fontSize = 14.sp, color = Color.Gray)
            Spacer(Modifier.height(30.dp))
            OutlinedTextField(value = nIn, onValueChange = { nIn = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { if(nIn.isNotEmpty()){ prefs.edit().putString("n", nIn).apply(); name = nIn; isAuth = true } }, Modifier.fillMaxWidth().height(60.dp).padding(top = 10.dp)) { Text("ENTER") }
        }
    } else {
        Box(Modifier.fillMaxSize()) {
            val googleHybrid = object : OnlineTileSourceBase("GoogleHybrid", 0, 20, 256, ".png", arrayOf("https://mt0.google.com/vt/lyrs=y&x={x}&y={y}&z={z}")) {
                override fun getTileURLString(pMapTileIndex: Long): String = "$baseUrl&x=${MapTileIndex.getX(pMapTileIndex)}&y=${MapTileIndex.getY(pMapTileIndex)}&z=${MapTileIndex.getZoom(pMapTileIndex)}"
            }
            var mapRef by remember { mutableStateOf<MapView?>(null) }
            AndroidView(factory = { MapView(it).apply { setTileSource(googleHybrid); controller.setZoom(17.0); controller.setCenter(GeoPoint(6.0333, 37.5500)); mapRef = this } })

            if (status == "IDLE") {
                Box(Modifier.fillMaxSize(), Alignment.Center) { Text("📍", fontSize = 44.sp, modifier = Modifier.padding(bottom = 40.dp)) }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.White, RoundedCornerShape(topStart = 28.dp)).padding(24.dp)) {
                    Button(onClick = { 
                        val id = "R_${System.currentTimeMillis()}"
                        val pt = mapRef?.mapCenter as GeoPoint
                        FirebaseDatabase.getInstance().getReference("rides/$id").setValue(mapOf("id" to id, "pName" to name, "status" to "REQUESTED", "pLat" to pt.latitude, "pLon" to pt.longitude, "price" to "328"))
                        status = "SEARCHING"
                    }, Modifier.fillMaxWidth().height(65.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Black)) { Text("BOOK BAYRA RIDE", fontWeight = FontWeight.Bold) }
                }
            } else {
                Box(Modifier.fillMaxSize().background(Color.White), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color(0xFF5E4E92))
                        Text("FINDING YOUR DRIVER...", Modifier.padding(top = 20.dp), fontWeight = FontWeight.Bold)
                        Button(onClick = { status = "IDLE" }, Modifier.padding(top = 40.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text("CANCEL") }
                    }
                }
            }
        }
    }
}

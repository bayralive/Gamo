package com.bayra.customer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.preference.PreferenceManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.firebase.database.FirebaseDatabase
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import java.net.URL
import kotlin.concurrent.thread

enum class ServiceTier(val label: String, val base: Int, val kmRate: Double) {
    POOL("Pool", 80, 11.0), COMFORT("Comfort", 120, 11.0), CODE_3("Code 3", 280, 27.5)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        setContent { MaterialTheme { PassengerEngine() } }
    }
}

@Composable
fun PassengerEngine() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = context.getSharedPreferences("bayra_p_vFINAL", 0)
    var name by remember { mutableStateOf(prefs.getString("n", "") ?: "") }
    var isAuth by remember { mutableStateOf(name.isNotEmpty()) }

    if (!isAuth) {
        Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
            Text("BAYRA", fontSize = 48.sp, fontWeight = FontWeight.Black, color = Color(0xFF5E4E92))
            Spacer(Modifier.height(30.dp))
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
            Button({ if(name.length > 2){ prefs.edit().putString("n", name).apply(); isAuth = true } }, Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5E4E92))) { Text("ENTER") }
        }
    } else { BookingHub(name) }
}

@Composable
fun BookingHub(pName: String) {
    var step by remember { mutableStateOf("PICKUP") }
    var roadDistance by remember { mutableStateOf(0.0) }
    var selectedTier by remember { mutableStateOf(ServiceTier.COMFORT) }
    var paymentMethod by remember { mutableStateOf("CASH") } // CASH or CHAPA
    var isSearching by remember { mutableStateOf(false) }
    var mapView: MapView? by remember { mutableStateOf(null) }

    val fare = (selectedTier.base + (roadDistance * selectedTier.kmRate * 1.15)).toInt()

    Box(Modifier.fillMaxSize()) {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx -> MapView(ctx).apply { setTileSource(TileSourceFactory.MAPNIK); setMultiTouchControls(true); controller.setZoom(16.0); controller.setCenter(GeoPoint(6.0238, 37.5532)); mapView = this } })

        if (isSearching) {
            Box(Modifier.fillMaxSize().background(Color.White), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color(0xFF5E4E92))
                    Text("SEARCHING...", Modifier.padding(top = 10.dp))
                    Button({ isSearching = false }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text("CANCEL") }
                }
            }
        } else {
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.White, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).padding(24.dp)) {
                Text("PAYMENT METHOD", fontSize = 12.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                    listOf("CASH", "CHAPA").forEach { method ->
                        val isSel = paymentMethod == method
                        Surface(Modifier.weight(1f).clickable { paymentMethod = method }, color = if(isSel) Color(0xFF4CAF50) else Color(0xFFF0F0F0), shape = RoundedCornerShape(8.dp)) {
                            Text(method, Modifier.padding(10.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontWeight = FontWeight.Bold, color = if(isSel) Color.White else Color.Black)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Text("$fare ETB", fontSize = 32.sp, fontWeight = FontWeight.Black, color = Color.Red)
                    Button(onClick = { isSearching = true }, Modifier.height(60.dp).width(160.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5E4E92))) { Text("BOOK NOW") }
                }
            }
        }
    }
}

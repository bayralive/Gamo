package com.bayra.customer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.preference.PreferenceManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.firebase.database.*
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.net.URL
import java.net.HttpURLConnection
import kotlin.concurrent.thread
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        setContent { MaterialTheme { PassengerApp() } }
    }
}

@Composable
fun PassengerApp() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("bayra_p_vFINAL", 0)
    
    var pName by rememberSaveable { mutableStateOf(prefs.getString("n", "") ?: "") }
    var pPhone by rememberSaveable { mutableStateOf(prefs.getString("p", "") ?: "") }
    var isAuth by remember { mutableStateOf(pName.isNotEmpty()) }
    var isSearching by rememberSaveable { mutableStateOf(false) }
    var rideStatus by rememberSaveable { mutableStateOf("IDLE") }
    var ridePrice by rememberSaveable { mutableStateOf("0") }

    if (!isAuth) {
        Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
            Text(text = "BAYRA", fontSize = 48.sp, fontWeight = FontWeight.Black, color = Color(0xFF5E4E92))
            OutlinedTextField(value = pName, onValueChange = { pName = it }, label = { Text(text = "Name") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = pPhone, onValueChange = { pPhone = it }, label = { Text(text = "Phone") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { if(pName.isNotEmpty()){ prefs.edit().putString("n", pName).putString("p", pPhone).apply(); isAuth = true } }, Modifier.fillMaxWidth().height(60.dp)) { Text(text = "ENTER") }
        }
    } else {
        BookingHub(pName, pPhone, isSearching, rideStatus, ridePrice, 
            { isSearching = it }, { rideStatus = it }, { ridePrice = it })
    }
}

@Composable
fun BookingHub(name: String, phone: String, isSearching: Boolean, status: String, price: String, onSearch: (Boolean) -> Unit, onStatus: (String) -> Unit, onPrice: (String) -> Unit) {
    val context = LocalContext.current
    val backendUrl = "https://bayra-backend-eu.onrender.com/initialize-payment"

    Box(modifier = Modifier.fillMaxSize().background(Color.White)) {
        if (status == "COMPLETED") {
            Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                Text(text = "ARRIVED!", fontSize = 32.sp, fontWeight = FontWeight.Black, color = Color(0xFF4CAF50))
                Text(text = "$price ETB", fontSize = 48.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(modifier = Modifier.height(40.dp))
                Button(
                    onClick = {
                        // 🛰️ CALL THE RENDER BRAIN FOR A REAL LINK
                        thread {
                            try {
                                val conn = URL(backendUrl).openConnection() as HttpURLConnection
                                conn.requestMethod = "POST"
                                conn.setRequestProperty("Content-Type", "application/json")
                                conn.doOutput = true
                                val body = JSONObject().put("amount", price).put("email", "user@bayra.et").toString()
                                conn.outputStream.write(body.toByteArray())
                                
                                val response = conn.inputStream.bufferedReader().readText()
                                val chapaLink = JSONObject(response).getString("checkout_url")
                                
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(chapaLink)))
                            } catch (e: Exception) {
                                // Fallback if Backend is sleeping
                                val fallback = "https://checkout.chapa.co/checkout/web/payment/CHAPUBK-GTviouToMOe9vOg5t1dNR9paQ1M62jOX"
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fallback)))
                            }
                        }
                        onStatus("IDLE"); onSearch(false)
                    },
                    modifier = Modifier.fillMaxWidth().height(65.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5E4E92))
                ) { Text(text = "PAY WITH CHAPA", fontWeight = FontWeight.Bold) }
                TextButton(onClick = { onStatus("IDLE"); onSearch(false) }) { Text(text = "PAID WITH CASH", color = Color.Gray) }
            }
        } else if (isSearching) {
            Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color(0xFF5E4E92))
                Spacer(modifier = Modifier.height(20.dp))
                Text(text = "SEARCHING...", fontWeight = FontWeight.Bold)
                Button(onClick = { onSearch(false) }, modifier = Modifier.padding(top = 40.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text(text = "CANCEL") }
            }
        } else {
            // BOOKING UI
            Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
                Text(text = "📍", fontSize = 60.sp)
                Button(onClick = {
                    val id = "R_${System.currentTimeMillis()}"
                    FirebaseDatabase.getInstance().getReference("rides/$id").setValue(mapOf("id" to id, "pName" to name, "status" to "REQUESTED", "price" to "120"))
                    onPrice("120"); onSearch(true)
                    FirebaseDatabase.getInstance().getReference("rides/$id").addValueEventListener(object : ValueEventListener {
                        override fun onDataChange(s: DataSnapshot) {
                            if (!s.exists() && isSearching) { onSearch(false); onStatus("COMPLETED"); return }
                            onStatus(s.child("status").getValue(String::class.java) ?: "REQUESTED")
                        }
                        override fun onCancelled(e: DatabaseError) {}
                    })
                }, Modifier.padding(24.dp).fillMaxWidth().height(65.dp)) { Text(text = "CONFIRM 120 ETB") }
            }
        }
    }
}

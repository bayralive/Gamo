package com.bayra.customer

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
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import com.google.firebase.database.*
import org.json.JSONObject
import java.net.URL
import java.net.HttpURLConnection
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
    var isGeneratingLink by remember { mutableStateOf(false) }

    if (!isAuth) {
        Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
            Text(text = "BAYRA", fontSize = 48.sp, fontWeight = FontWeight.Black, color = Color(0xFF5E4E92))
            OutlinedTextField(value = pName, onValueChange = { pName = it }, label = { Text(text = "Name") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = pPhone, onValueChange = { pPhone = it }, label = { Text(text = "Phone") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { if(pName.isNotEmpty()){ prefs.edit().putString("n", pName).putString("p", pPhone).apply(); isAuth = true } }, Modifier.fillMaxWidth().height(60.dp)) { Text(text = "ENTER") }
        }
    } else {
        Box(Modifier.fillMaxSize().background(Color.White)) {
            if (rideStatus == "COMPLETED") {
                Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                    Text(text = "TRIP FINISHED", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color(0xFF4CAF50))
                    Text(text = "$ridePrice ETB", fontSize = 48.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(modifier = Modifier.height(40.dp))
                    
                    if (isGeneratingLink) {
                        CircularProgressIndicator(color = Color(0xFF5E4E92))
                        Text(text = "Opening Secure Vault...", fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
                    } else {
                        Button(
                            onClick = { 
                                isGeneratingLink = true
                                thread {
                                    try {
                                        val url = URL("https://bayra-backend-eu.onrender.com/initialize-payment")
                                        val conn = url.openConnection() as HttpURLConnection
                                        conn.requestMethod = "POST"
                                        conn.setRequestProperty("Content-Type", "application/json")
                                        conn.doOutput = true
                                        val body = JSONObject().put("amount", ridePrice).put("email", "customer@bayra.et").toString()
                                        conn.outputStream.write(body.toByteArray())
                                        
                                        val response = conn.inputStream.bufferedReader().readText()
                                        val chapaUrl = JSONObject(response).getString("checkout_url")
                                        
                                        isGeneratingLink = false
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(chapaUrl)))
                                        rideStatus = "IDLE"; isSearching = false 
                                    } catch (e: Exception) {
                                        isGeneratingLink = false
                                        // FINAL FALLBACK TO DIRECT LINK IF SERVER LAGS
                                        val fallback = "https://checkout.chapa.co/checkout/web/payment/CHAPUBK-GTviouToMOe9vOg5t1dNR9paQ1M62jOX"
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fallback)))
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(65.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5E4E92))
                        ) { Text(text = "PAY WITH CHAPA", fontWeight = FontWeight.Bold) }
                        TextButton(onClick = { rideStatus = "IDLE"; isSearching = false }) { Text(text = "PAID WITH CASH", color = Color.Gray) }
                    }
                }
            } else if (isSearching) {
                Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color(0xFF5E4E92))
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(text = "SEARCHING...", fontWeight = FontWeight.Bold)
                    Button(onClick = { isSearching = false }, modifier = Modifier.padding(top = 40.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text(text = "CANCEL") }
                }
            } else {
                Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
                    Text(text = "📍", fontSize = 60.sp)
                    Button(onClick = {
                        val id = "R_${System.currentTimeMillis()}"
                        FirebaseDatabase.getInstance().getReference("rides/$id").setValue(mapOf("id" to id, "pName" to pName, "status" to "REQUESTED", "price" to "120"))
                        ridePrice = "120"; isSearching = true
                        FirebaseDatabase.getInstance().getReference("rides/$id").addValueEventListener(object : ValueEventListener {
                            override fun onDataChange(s: DataSnapshot) {
                                if (!s.exists() && isSearching) { isSearching = false; rideStatus = "COMPLETED"; return }
                                rideStatus = s.child("status").getValue(String::class.java) ?: "REQUESTED"
                            }
                            override fun onCancelled(e: DatabaseError) {}
                        })
                    }, Modifier.padding(24.dp).fillMaxWidth().height(65.dp)) { Text(text = "CONFIRM 120 ETB") }
                }
            }
        }
    }
}

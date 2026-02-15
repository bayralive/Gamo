package com.bayra.customer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
    var isAuth by remember { mutableStateOf(pName.isNotEmpty() && pPhone.isNotEmpty()) }
    var isSearching by rememberSaveable { mutableStateOf(false) }
    var rideStatus by rememberSaveable { mutableStateOf("IDLE") }
    var ridePrice by rememberSaveable { mutableStateOf("120") }
    var activeRideId by rememberSaveable { mutableStateOf("") }
    var isGeneratingLink by remember { mutableStateOf(false) }

    if (!isAuth) {
        Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
            Text("BAYRA", fontSize = 48.sp, fontWeight = FontWeight.Bold, color = Color(0xFF5E4E92))
            OutlinedTextField(pName, { pName = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(pPhone, { pPhone = it }, label = { Text("Phone Number") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { if(pName.isNotEmpty() && pPhone.length > 8){ 
                prefs.edit().putString("n", pName).putString("p", pPhone).apply()
                isAuth = true 
            } }, Modifier.fillMaxWidth().height(60.dp)) { Text("ENTER") }
        }
    } else {
        Box(Modifier.fillMaxSize().background(Color.White)) {
            if (rideStatus == "COMPLETED") {
                Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                    Text("TRIP FINISHED", fontSize = 32.sp, color = Color(0xFF4CAF50))
                    Text("$ridePrice ETB", fontSize = 48.sp, fontWeight = FontWeight.Black)
                    Spacer(modifier = Modifier.height(40.dp))
                    
                    if (isGeneratingLink) {
                        CircularProgressIndicator(color = Color(0xFF5E4E92))
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
                                        
                                        // 🔥 THE FIX: Sending Phone and RideID correctly
                                        val body = JSONObject()
                                            .put("amount", ridePrice)
                                            .put("name", pName)
                                            .put("phone", pPhone) 
                                            .put("rideId", activeRideId)
                                            .toString()
                                            
                                        conn.outputStream.write(body.toByteArray())
                                        val response = conn.inputStream.bufferedReader().readText()
                                        val chapaUrl = JSONObject(response).getString("checkout_url")
                                        
                                        isGeneratingLink = false
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(chapaUrl)))
                                        rideStatus = "IDLE"; isSearching = false 
                                    } catch (e: Exception) {
                                        isGeneratingLink = false
                                        // Final Fallback to avoid user getting stuck
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://chapa.co")))
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(65.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5E4E92))
                        ) { Text("PAY NOW", fontWeight = FontWeight.Bold) }
                    }
                }
            } else {
                Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
                    if (isSearching) {
                        CircularProgressIndicator(color = Color(0xFF5E4E92))
                        Text("SEARCHING...", Modifier.padding(top = 20.dp))
                    } else {
                        Text("📍", fontSize = 60.sp)
                        Button(onClick = {
                            val id = "R_${System.currentTimeMillis()}"
                            activeRideId = id
                            FirebaseDatabase.getInstance().getReference("rides/$id").setValue(mapOf("id" to id, "pName" to pName, "pPhone" to pPhone, "status" to "REQUESTED", "price" to "120"))
                            isSearching = true
                            FirebaseDatabase.getInstance().getReference("rides/$id").addValueEventListener(object : ValueEventListener {
                                override fun onDataChange(s: DataSnapshot) {
                                    if (!s.exists() && isSearching) { isSearching = false; rideStatus = "COMPLETED"; return }
                                    rideStatus = s.child("status").getValue(String::class.java) ?: "REQUESTED"
                                }
                                override fun onCancelled(e: DatabaseError) {}
                            })
                        }, Modifier.padding(24.dp).fillMaxWidth().height(65.dp)) { Text("BOOK RIDE") }
                    }
                }
            }
        }
    }
}

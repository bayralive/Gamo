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
    var isAuth by remember { mutableStateOf(pName.isNotEmpty()) }
    var isSearching by rememberSaveable { mutableStateOf(false) }
    var rideStatus by rememberSaveable { mutableStateOf("IDLE") }
    var ridePrice by rememberSaveable { mutableStateOf("120") }
    var isGeneratingLink by remember { mutableStateOf(false) }

    if (!isAuth) {
        Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
            Text("BAYRA LOGIN", fontSize = 28.sp, color = Color(0xFF5E4E92))
            OutlinedTextField(pName, { pName = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
            Button({ if(pName.isNotEmpty()){ prefs.edit().putString("n", pName).apply(); isAuth = true } }, Modifier.fillMaxWidth().height(60.dp)) { Text("ENTER") }
        }
    } else {
        Box(Modifier.fillMaxSize().background(Color.White)) {
            if (rideStatus == "COMPLETED") {
                Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                    Text("ARRIVED!", fontSize = 32.sp, color = Color(0xFF4CAF50))
                    Text("$ridePrice ETB", fontSize = 48.sp)
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
                                        val body = JSONObject().put("amount", ridePrice).put("name", pName).toString()
                                        conn.outputStream.write(body.toByteArray())
                                        
                                        val response = conn.inputStream.bufferedReader().readText()
                                        val chapaUrl = JSONObject(response).getString("checkout_url")
                                        
                                        isGeneratingLink = false
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(chapaUrl)))
                                        rideStatus = "IDLE"; isSearching = false 
                                    } catch (e: Exception) {
                                        isGeneratingLink = false
                                        // Final Hard Fallback
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://chapa.co")))
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(65.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5E4E92))
                        ) { Text("PAY NOW", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) }
                    }
                }
            } else {
                // BOOKING HUB
                Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
                    Text("📍", fontSize = 60.sp)
                    Button(onClick = {
                        val id = "R_${System.currentTimeMillis()}"
                        FirebaseDatabase.getInstance().getReference("rides/$id").setValue(mapOf("id" to id, "pName" to pName, "status" to "REQUESTED", "price" to "120"))
                        isSearching = true
                        FirebaseDatabase.getInstance().getReference("rides/$id").addValueEventListener(object : ValueEventListener {
                            override fun onDataChange(s: DataSnapshot) {
                                if (!s.exists() && isSearching) { isSearching = false; rideStatus = "COMPLETED"; return }
                                rideStatus = s.child("status").getValue(String::class.java) ?: "REQUESTED"
                            }
                            override fun onCancelled(e: DatabaseError) {}
                        })
                    }, Modifier.padding(24.dp).fillMaxWidth().height(65.dp)) { Text("BOOK FOR 120 ETB") }
                }
            }
        }
    }
}

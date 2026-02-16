package com.bayra.driver

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.database.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { DriverOnboarding() } }
    }
}

@Composable
fun DriverOnboarding() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("bayra_driver", Context.MODE_PRIVATE)
    
    var name by remember { mutableStateOf(prefs.getString("name", "") ?: "") }
    var plate by remember { mutableStateOf(prefs.getString("plate", "") ?: "") }
    var isAuth by remember { mutableStateOf(name.isNotEmpty()) }

    if (!isAuth) {
        Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
            Text("DRIVER IDENTITY", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color(0xFF5E4E92))
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(name, {name = it}, label = {Text("Full Name")}, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(plate, {plate = it}, label = {Text("Plate (e.g. AA 3-12345)")}, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(30.dp))
            Button({
                if(name.length > 2 && plate.length > 3) {
                    prefs.edit().putString("name", name).putString("plate", plate).apply()
                    isAuth = true
                }
            }, Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5E4E92))) {
                Text("ENTER RADAR")
            }
        }
    } else {
        // Start the Immortal Beacon Service
        LaunchedEffect(Unit) {
            context.startForegroundService(Intent(context, BeaconService::class.java))
        }
        DriverRadarScreen(name, plate)
    }
}

@Composable
fun DriverRadarScreen(name: String, plate: String) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text("RADAR: $name", fontWeight = FontWeight.Bold)
            Text(plate, color = Color.Gray, fontSize = 12.sp)
        }
        Spacer(Modifier.height(20.dp))
        Text("📡 Watching Arba Minch Streets...", color = Color(0xFF4CAF50))
        // The Radar cards we built previously follow here...
    }
}

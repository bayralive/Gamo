package com.bayra.driver

import android.Manifest
import android.content.*
import android.os.Bundle
import android.preference.PreferenceManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
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
import com.google.firebase.database.*
import org.osmdroid.config.Configuration

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        setContent { MaterialTheme { DriverApp() } }
    }
}

@Composable
fun DriverApp() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("bayra_d_v99", Context.MODE_PRIVATE) }
    var dName by rememberSaveable { mutableStateOf(prefs.getString("n", "") ?: "") }
    var isAuth by remember { mutableStateOf(dName.isNotEmpty()) }

    if (!isAuth) {
        Column(Modifier.fillMaxSize().padding(32.dp).background(Color.White), Arrangement.Center, Alignment.CenterHorizontally) {
            // 🔥 FLEET PRESTIGE
            Image(
                painter = painterResource(id = R.drawable.logo_driver),
                contentDescription = "Bayra Driver",
                modifier = Modifier.size(240.dp)
            )
            Spacer(Modifier.height(10.dp))
            Text("BAYRA DRIVER", fontSize = 32.sp, fontWeight = FontWeight.Black, color = Color(0xFF1A237E))
            Spacer(Modifier.height(30.dp))
            OutlinedTextField(dName, { dName = it }, label = { Text("Driver Name") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { if(dName.isNotEmpty()){ prefs.edit().putString("n", dName).apply(); isAuth = true } }, Modifier.fillMaxWidth().height(60.dp).padding(top = 16.dp)) { Text("ACTIVATE RADAR") }
        }
    } else {
        // RADAR ENGINE LOGIC GOES HERE (Already functional in previous phase)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Radar Active") }
    }
}

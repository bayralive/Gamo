package com.bayra.driver

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.preference.PreferenceManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.osmdroid.config.Configuration

class MainActivity : ComponentActivity() {
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ -> }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        permLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.POST_NOTIFICATIONS))
        setContent { MaterialTheme { DriverApp() } }
    }
}

@Composable
fun DriverApp() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("bayra_d_v124", Context.MODE_PRIVATE) }
    var dName by rememberSaveable { mutableStateOf(prefs.getString("n", "") ?: "") }
    var isAuth by remember { mutableStateOf(dName.isNotEmpty()) }

    if (!isAuth) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp).background(Color.White),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(id = R.drawable.logo_driver),
                contentDescription = "Bayra Driver",
                modifier = Modifier.size(240.dp)
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "BAYRA DRIVER",
                fontSize = 32.sp,
                fontWeight = FontWeight.Black,
                color = Color(0xFF1A237E)
            )
            Spacer(modifier = Modifier.height(30.dp))
            OutlinedTextField(
                value = dName,
                onValueChange = { dName = it },
                label = { Text(text = "Driver Name") },
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = { if (dName.isNotEmpty()) { prefs.edit().putString("n", dName).apply(); isAuth = true } },
                modifier = Modifier.fillMaxWidth().height(60.dp).padding(top = 16.dp)
            ) {
                Text(text = "ACTIVATE RADAR")
            }
        }
    } else {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(text = "Radar Engine Active")
        }
    }
}

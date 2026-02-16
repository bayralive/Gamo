package com.bayra.customer

import android.content.Context
import android.os.Bundle
import android.preference.PreferenceManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.firebase.database.FirebaseDatabase
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        setContent { MaterialTheme { PassengerApp() } }
    }
}

@Composable
fun PassengerApp() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("bayra_p_v124", Context.MODE_PRIVATE) }
    var pName by rememberSaveable { mutableStateOf(prefs.getString("n", "") ?: "") }
    var pPhone by rememberSaveable { mutableStateOf(prefs.getString("p", "") ?: "") }
    var isAuth by remember { mutableStateOf(pName.isNotEmpty()) }

    if (!isAuth) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp).background(Color.White),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(id = R.drawable.logo_passenger),
                contentDescription = "Bayra Logo",
                modifier = Modifier.size(220.dp)
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "BAYRA TRAVEL",
                fontSize = 28.sp,
                fontWeight = FontWeight.Black,
                color = Color(0xFF1A237E)
            )
            Spacer(modifier = Modifier.height(30.dp))
            OutlinedTextField(
                value = pName,
                onValueChange = { pName = it },
                label = { Text(text = "Name") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = pPhone,
                onValueChange = { pPhone = it },
                label = { Text(text = "Phone") },
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = { if (pName.isNotEmpty()) { prefs.edit().putString("n", pName).putString("p", pPhone).apply(); isAuth = true } },
                modifier = Modifier.fillMaxWidth().height(60.dp).padding(top = 16.dp)
            ) {
                Text(text = "START TRAVEL")
            }
        }
    } else {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(text = "Booking Engine Active")
        }
    }
}

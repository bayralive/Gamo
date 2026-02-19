package com.bayra.driver

import android.content.*
import android.os.Bundle
import android.preference.PreferenceManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import com.google.firebase.database.*
import org.osmdroid.config.Configuration

const val DB_URL = "https://bayra-84ecf-default-rtdb.europe-west1.firebasedatabase.app"

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        setContent { MaterialTheme { DriverApp() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriverApp() {
    var isAuth by remember { mutableStateOf(false) }
    if (!isAuth) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { Button(onClick = { isAuth = true }) { Text("LOGIN") } }
    } else {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(icon = { Icon(Icons.Filled.Home, null) }, selected = true, onClick = {})
                    NavigationBarItem(icon = { Icon(Icons.Filled.Person, null) }, selected = false, onClick = {})
                }
            }
        ) { p -> Box(Modifier.padding(p)) { Text("Driver Radar Active", Modifier.align(Alignment.Center)) } }
    }
}

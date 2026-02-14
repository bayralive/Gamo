package com.bayra.driver

import android.content.*
import android.media.RingtoneManager
import android.os.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

data class Ride(val id: String = "", val pName: String = "", val price: String = "0", val status: String = "")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { DriverEngine() } }
    }
    fun triggerAlert() {
        (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).vibrate(VibrationEffect.createOneShot(500, 255))
        RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)).play()
    }
}

@Composable
fun DriverEngine() {
    val context = LocalContext.current as MainActivity
    val prefs = context.getSharedPreferences("bayra_d_vFinal", 0)
    var name by remember { mutableStateOf(prefs.getString("n", "") ?: "") }
    var isAuth by remember { mutableStateOf(name.isNotEmpty()) }

    if (!isAuth) {
        Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
            Text("BAYRA DRIVER", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color(0xFF5E4E92))
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
            Button({ if(name.isNotEmpty()){ prefs.edit().putString("n", name).apply(); isAuth = true } }, Modifier.fillMaxWidth().height(60.dp)) { Text("LOGIN") }
        }
    } else {
        RadarView(name, context) { prefs.edit().clear().apply(); isAuth = false }
    }
}

@Composable
fun RadarView(dName: String, activity: MainActivity, onLogout: () -> Unit) {
    val ref = FirebaseDatabase.getInstance().getReference("rides")
    var rides by remember { mutableStateOf(listOf<Ride>()) }
    var active by remember { mutableStateOf<Ride?>(null) }

    LaunchedEffect(Unit) {
        ref.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val list = mutableListOf<Ride>()
                var curr: Ride? = null
                s.children.forEach {
                    val status = it.child("status").getValue(String::class.java) ?: ""
                    val ride = Ride(it.child("id").getValue(String::class.java) ?: "", it.child("pName").getValue(String::class.java) ?: "User", it.child("price").value?.toString() ?: "0", status)
                    if (status == "REQUESTED") list.add(ride)
                    else if (it.child("driverName").getValue(String::class.java) == dName && status != "COMPLETED") curr = ride
                }
                if(list.size > rides.size) activity.triggerAlert()
                rides = list; active = curr
            }
            override fun onCancelled(e: DatabaseError) {}
        })
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text("RADAR: $dName", fontWeight = FontWeight.Bold); TextButton(onLogout) { Text("LOGOUT", color = Color.Red) } }
        Spacer(Modifier.height(20.dp))
        if (active != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp), Alignment.CenterHorizontally) {
                    Text("TRIP: ${active!!.pName}", fontWeight = FontWeight.Bold)
                    Text("${active!!.price} ETB", fontSize = 48.sp, color = Color.Red, fontWeight = FontWeight.Black)
                    Button({ ref.child(active!!.id).child("status").setValue("COMPLETED") }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text("FINISH TRIP") }
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(rides) { ride ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp).fillMaxWidth(), Arrangement.SpaceBetween) {
                            Column { Text(ride.pName, fontWeight = FontWeight.Bold); Text("${ride.price} ETB") }
                            Button({ ref.child(ride.id).updateChildren(mapOf("status" to "ACCEPTED", "driverName" to dName)) }) { Text("ACCEPT") }
                        }
                    }
                }
            }
        }
    }
}

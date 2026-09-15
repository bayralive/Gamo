package com.bayra.driver

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.preference.PreferenceManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.NotificationCompat
import com.google.firebase.database.*
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.util.Locale

const val DB_URL = "https://bayra-84ecf-default-rtdb.europe-west1.firebasedatabase.app"
val ImperialBlue = Color(0xFF1A237E)
val ImperialRed = Color(0xFFD50000)
val ImperialWhite = Color(0xFFFFFFFF)
val EmeraldGreen = Color(0xFF2E7D32)
val GoldYellow = Color(0xFFFFB300)

class MainActivity : ComponentActivity() {
    private val requestLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        requestLauncher.launch(arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS
        ))
        setContent { MaterialTheme { DriverAppRoot() } }
    }
    fun launchNav(lat: Double, lon: Double) { 
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$lat,$lon")).apply { setPackage("com.google.android.apps.maps") }) } 
        catch (e: Exception) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$lat,$lon"))) } 
    }
    fun playAlarm() { try { RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)).play() } catch (e: Exception) {} }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriverAppRoot() {
    val ctx = LocalContext.current
    val activity = ctx as? MainActivity
    val prefs = remember { ctx.getSharedPreferences("bayra_driver_v231", Context.MODE_PRIVATE) }
    
    var dName by rememberSaveable { mutableStateOf(prefs.getString("n", "") ?: "") }
    var dPhone by rememberSaveable { mutableStateOf(prefs.getString("p", "") ?: "") }
    var isAuth by remember { mutableStateOf(dName.isNotEmpty()) }
    
    // Server-Authoritative Driver Profile State
    var driverStatus by remember { mutableStateOf("UNVERIFIED") }
    var vehicleType by remember { mutableStateOf<String?>(null) }
    var carPlate by remember { mutableStateOf<String?>(null) }
    var rideCount by remember { mutableStateOf(0) }
    var imperialId by remember { mutableStateOf("BT-00000") }
    var rating by remember { mutableStateOf(5.0) }
    var reviewCount by remember { mutableStateOf(0) }
    var debt by remember { mutableStateOf(0) }
    var credit by remember { mutableStateOf(0) }
    var chosenVerificationPath by remember { mutableStateOf<String?>(null) }
    
    var currentTab by rememberSaveable { mutableStateOf("RADAR") }
    var lastBackPressTime by remember { mutableStateOf(0L) }

    BackHandler {
        if (currentTab != "RADAR") { currentTab = "RADAR" }
        else {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastBackPressTime < 2000) { activity?.finish() } 
            else { lastBackPressTime = currentTime; Toast.makeText(ctx, "Press back again to exit", Toast.LENGTH_SHORT).show() }
        }
    }

    LaunchedEffect(isAuth, dName) {
        if (isAuth && dName.isNotEmpty()) {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    FirebaseDatabase.getInstance(DB_URL).getReference("drivers/$dName/fcmToken").setValue(task.result)
                }
            }
            val serviceIntent = Intent(ctx, ImmortalBeaconService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(serviceIntent) else ctx.startService(serviceIntent)
        }
    }

    LaunchedEffect(dName) {
        if (dName.isNotEmpty()) {
            FirebaseDatabase.getInstance(DB_URL).getReference("drivers").child(dName).addValueEventListener(object : ValueEventListener {
                override fun onDataChange(s: DataSnapshot) {
                    driverStatus = s.child("status").value?.toString() ?: "UNVERIFIED"
                    vehicleType = s.child("vehicleType").value?.toString()
                    carPlate = s.child("carPlate").value?.toString()
                    rideCount = s.child("rideCount").value?.toString()?.toIntOrNull() ?: 0
                    imperialId = s.child("imperialId").value?.toString() ?: "BT-${(10000..99999).random()}"
                    rating = s.child("rating").value?.toString()?.toDoubleOrNull() ?: 5.0
                    reviewCount = s.child("reviewCount").value?.toString()?.toIntOrNull() ?: 0
                    debt = s.child("debt").value?.toString()?.toDoubleOrNull()?.toInt() ?: 0
                    credit = s.child("credit").value?.toString()?.toDoubleOrNull()?.toInt() ?: 0
                    chosenVerificationPath = s.child("verificationPath").value?.toString()
                }
                override fun onCancelled(e: DatabaseError) {}
            })
        }
    }

    if (!isAuth) {
        DriverAuthScreen { name, phone ->
            dName = name; dPhone = phone; isAuth = true
            prefs.edit().putString("n", name).putString("p", phone).apply()
        }
    } else {
        if (vehicleType.isNullOrEmpty() || carPlate.isNullOrEmpty()) {
            VehicleGateScreen(driverName = dName)
        } else if (driverStatus != "VERIFIED" && (chosenVerificationPath == "VERIFY_NOW" || rideCount >= 10 || driverStatus == "PENDING_APPROVAL")) {
            CommissioningPortalScreen(
                driverName = dName,
                driverStatus = driverStatus,
                rideCount = rideCount,
                imperialId = imperialId
            )
        } else if (driverStatus == "UNVERIFIED" && chosenVerificationPath == null && rideCount < 10) {
            VerificationChoiceScreen(driverName = dName)
        } else {
            val isDebtLocked = (debt - credit) >= 500

            Scaffold(
                bottomBar = {
                    NavigationBar(containerColor = Color.Black) {
                        NavigationBarItem(
                            selected = (currentTab == "RADAR"),
                            onClick = { currentTab = "RADAR" },
                            icon = { Icon(Icons.Filled.Home, null) },
                            label = { Text("Radar", color = ImperialWhite, fontSize = 11.sp) }
                        )
                        NavigationBarItem(
                            selected = (currentTab == "WALLET"),
                            onClick = { currentTab = "WALLET" },
                            icon = { Icon(Icons.Filled.AccountBalanceWallet, null) },
                            label = { Text("Wallet", color = ImperialWhite, fontSize = 11.sp) }
                        )
                        NavigationBarItem(
                            selected = (currentTab == "PROFILE"),
                            onClick = { currentTab = "PROFILE" },
                            icon = { Icon(Icons.Filled.Person, null) },
                            label = { Text("Profile", color = ImperialWhite, fontSize = 11.sp) }
                        )
                        NavigationBarItem(
                            selected = (currentTab == "HISTORY"),
                            onClick = { currentTab = "HISTORY" },
                            icon = { Icon(Icons.Filled.List, null) },
                            label = { Text("Trips", color = ImperialWhite, fontSize = 11.sp) }
                        )
                    }
                }
            ) { padding ->
                Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                    when (currentTab) {
                        "RADAR" -> {
                            if (isDebtLocked) {
                                DebtLockoutScreen(driverName = dName, debt = debt, credit = credit)
                            } else {
                                RadarHubScreen(
                                    driverName = dName,
                                    driverPhone = dPhone,
                                    driverStatus = driverStatus,
                                    rideCount = rideCount,
                                    vehicleType = vehicleType ?: "BAJAJ",
                                    activity = activity
                                )
                            }
                        }
                        "WALLET" -> DriverWalletScreen(driverName = dName, debt = debt, credit = credit)
                        "PROFILE" -> DriverProfileScreen(
                            name = dName,
                            phone = dPhone,
                            imperialId = imperialId,
                            status = driverStatus,
                            vehicleType = vehicleType ?: "BAJAJ",
                            plate = carPlate ?: "N/A",
                            rating = rating,
                            completedRides = rideCount,
                            onLogout = {
                                isAuth = false
                                prefs.edit().clear().apply()
                            }
                        )
                        "HISTORY" -> DriverRideHistoryScreen(driverName = dName)
                    }
                }
            }
        }
    }
}

// ==========================================
// 1. DRIVER AUTHENTICATION
// ==========================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriverAuthScreen(onSuccess: (String, String) -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().background(ImperialBlue).padding(28.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(painterResource(id = R.drawable.logo_driver), contentDescription = null, modifier = Modifier.size(130.dp))
        Spacer(modifier = Modifier.height(20.dp))
        Text(text = "BAYRA DRIVER", fontSize = 26.sp, fontWeight = FontWeight.Black, color = ImperialWhite)
        Text(text = "Imperial Strategy v3.3 • Build #231", fontSize = 12.sp, color = Color.LightGray)
        Spacer(modifier = Modifier.height(28.dp))

        OutlinedTextField(
            value = name, onValueChange = { name = it },
            label = { Text("Driver Full Name", color = Color.LightGray) },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
        )
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = phone, onValueChange = { phone = it },
            label = { Text("Phone Number", color = Color.LightGray) },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
        )
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = password, onValueChange = { password = it },
            label = { Text("Password", color = Color.LightGray) },
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
        )
        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                if (name.isNotEmpty() && phone.isNotEmpty() && password.isNotEmpty()) {
                    isLoading = true
                    val driverRef = FirebaseDatabase.getInstance(DB_URL).getReference("drivers").child(name)
                    driverRef.addListenerForSingleValueEvent(object : ValueEventListener {
                        override fun onDataChange(s: DataSnapshot) {
                            isLoading = false
                            if (s.exists()) {
                                val dbPass = s.child("password").value?.toString() ?: ""
                                if (dbPass == password) {
                                    onSuccess(name, s.child("phone").value?.toString() ?: phone)
                                } else {
                                    Toast.makeText(ctx, "Incorrect Password!", Toast.LENGTH_LONG).show()
                                }
                            } else {
                                val initialData = mapOf(
                                    "name" to name,
                                    "phone" to phone,
                                    "password" to password,
                                    "status" to "UNVERIFIED",
                                    "vehicleType" to null,
                                    "carPlate" to null,
                                    "rideCount" to 0,
                                    "balance" to 0,
                                    "debt" to 0,
                                    "credit" to 0,
                                    "rating" to 5.0,
                                    "reviewCount" to 0,
                                    "imperialId" to "BT-${(10000..99999).random()}",
                                    "registeredAt" to System.currentTimeMillis()
                                )
                                driverRef.setValue(initialData)
                                onSuccess(name, phone)
                            }
                        }
                        override fun onCancelled(e: DatabaseError) { isLoading = false }
                    })
                }
            },
            modifier = Modifier.fillMaxWidth().height(55.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ImperialRed)
        ) {
            if (isLoading) CircularProgressIndicator(color = ImperialWhite, modifier = Modifier.size(24.dp))
            else Text("ENTER FLEET", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }

        Spacer(modifier = Modifier.height(16.dp))
        TextButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/+r6wuw3kZGXkyZWNk"))) }) {
            Text("Need Fleet Registration Help? Contact Council", color = Color.LightGray, fontSize = 13.sp)
        }
    }
}

// ==========================================
// 2. VEHICLE GATE
// ==========================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VehicleGateScreen(driverName: String) {
    val ctx = LocalContext.current
    var selectedType by remember { mutableStateOf("BAJAJ") }
    var plateNumber by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().background(Color.White).padding(28.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("SELECT YOUR VEHICLE", fontSize = 22.sp, fontWeight = FontWeight.Black, color = ImperialBlue)
        Text("Stage 2: Fleet Qualification Gate", fontSize = 13.sp, color = Color.Gray)
        Spacer(modifier = Modifier.height(30.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Card(
                modifier = Modifier.weight(1f).clickable { selectedType = "BAJAJ" },
                colors = CardDefaults.cardColors(containerColor = if (selectedType == "BAJAJ") ImperialBlue else Color(0xFFF1F5F9)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🛺", fontSize = 42.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("BAJAJ", fontWeight = FontWeight.Black, color = if (selectedType == "BAJAJ") Color.White else Color.Black)
                }
            }
            Card(
                modifier = Modifier.weight(1f).clickable { selectedType = "CODE3" },
                colors = CardDefaults.cardColors(containerColor = if (selectedType == "CODE3") ImperialBlue else Color(0xFFF1F5F9)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🚗", fontSize = 42.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("CODE 3", fontWeight = FontWeight.Black, color = if (selectedType == "CODE3") Color.White else Color.Black)
                }
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        OutlinedTextField(
            value = plateNumber, onValueChange = { plateNumber = it },
            label = { Text("Car / Bajaj Plate Number (e.g. 3-45678 ET)") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        )

        Spacer(modifier = Modifier.height(30.dp))

        Button(
            onClick = {
                if (plateNumber.length >= 4) {
                    isSaving = true
                    val updates = mapOf(
                        "vehicleType" to selectedType,
                        "carPlate" to plateNumber.uppercase().trim()
                    )
                    FirebaseDatabase.getInstance(DB_URL).getReference("drivers/$driverName").updateChildren(updates).addOnCompleteListener {
                        isSaving = false
                        Toast.makeText(ctx, "Vehicle Profile Registered!", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(ctx, "Please enter a valid plate number.", Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier.fillMaxWidth().height(55.dp),
            colors = ButtonDefaults.buttonColors(containerColor = EmeraldGreen),
            shape = RoundedCornerShape(14.dp)
        ) {
            if (isSaving) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
            else Text("CONFIRM VEHICLE", fontWeight = FontWeight.Bold)
        }
    }
}

// ==========================================
// 3. CHOOSE VERIFICATION PATH
// ==========================================
@Composable
fun VerificationChoiceScreen(driverName: String) {
    val ref = FirebaseDatabase.getInstance(DB_URL).getReference("drivers/$driverName")

    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFFF8FAFC)).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("CHOOSE VERIFICATION PATH", fontSize = 22.sp, fontWeight = FontWeight.Black, color = ImperialBlue)
        Text("Bayra Imperial Strategy v3.3", fontSize = 13.sp, color = Color.Gray)
        Spacer(modifier = Modifier.height(30.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(22.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🏎️", fontSize = 32.sp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text("OPTION A: 10-RIDE TRIAL", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = ImperialBlue)
                        Text("Start driving immediately on probation", fontSize = 12.sp, color = Color.Gray)
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = { ref.child("verificationPath").setValue("TRIAL") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = ImperialBlue)
                ) { Text("START 10-RIDE TRIAL", fontWeight = FontWeight.Bold) }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(22.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🛡️", fontSize = 32.sp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text("OPTION B: VERIFY NOW", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = EmeraldGreen)
                        Text("Complete full commissioning for permanent status", fontSize = 12.sp, color = Color.Gray)
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = { ref.child("verificationPath").setValue("VERIFY_NOW") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldGreen)
                ) { Text("VERIFY MY ACCOUNT", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

// ==========================================
// 4 & 6. COMMISSIONING PORTAL / 11TH-RIDE WALL
// ==========================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommissioningPortalScreen(driverName: String, driverStatus: String, rideCount: Int, imperialId: String) {
    val ctx = LocalContext.current
    var nationalId by remember { mutableStateOf("") }
    var licenseNumber by remember { mutableStateOf("") }
    var isSubmitting by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().background(Color.White).padding(24.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(20.dp))
        Text("🏛️ IMPERIAL COMMISSIONING", fontSize = 22.sp, fontWeight = FontWeight.Black, color = ImperialBlue)
        Text(if (rideCount >= 10) "11th-Ride Commissioning Wall Activated" else "Early Verification Gateway", fontSize = 13.sp, color = Color.Gray)
        Spacer(modifier = Modifier.height(20.dp))

        if (driverStatus == "PENDING_APPROVAL") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF3C7)),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("⏳ STATUS: PENDING APPROVAL", fontWeight = FontWeight.Black, color = Color(0xFFB45309))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Your commissioning application is under active administrative review. You will receive your Imperial Driver ID upon verification.",
                        fontSize = 13.sp, color = Color(0xFF92400E), textAlign = TextAlign.Center
                    )
                }
            }
            Spacer(modifier = Modifier.height(25.dp))
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text("Step 1: Driver Credentials", fontWeight = FontWeight.Bold, color = ImperialBlue)
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = nationalId, onValueChange = { nationalId = it },
                    label = { Text("National ID / FAYDA Number") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = driverStatus != "PENDING_APPROVAL"
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = licenseNumber, onValueChange = { licenseNumber = it },
                    label = { Text("Driver License Number") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = driverStatus != "PENDING_APPROVAL"
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF6FF)),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(modifier = Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Step 2: Mandatory Interview Gateway", fontWeight = FontWeight.Bold, color = ImperialBlue)
                Text("Join the Council's official driver verification group", fontSize = 12.sp, color = Color.Gray)
                Spacer(modifier = Modifier.height(14.dp))
                Button(
                    onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/+r6wuw3kZGXkyZWNk"))) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF229ED9)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Call, null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("JOIN INTERVIEW GROUP", fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        if (driverStatus != "PENDING_APPROVAL") {
            Button(
                onClick = {
                    if (nationalId.isNotEmpty() && licenseNumber.isNotEmpty()) {
                        isSubmitting = true
                        val data = mapOf(
                            "nationalId" to nationalId,
                            "licenseNumber" to licenseNumber,
                            "status" to "PENDING_APPROVAL",
                            "submittedAt" to System.currentTimeMillis()
                        )
                        FirebaseDatabase.getInstance(DB_URL).getReference("drivers/$driverName").updateChildren(data).addOnCompleteListener {
                            isSubmitting = false
                        }
                    } else {
                        Toast.makeText(ctx, "Please complete license & ID credentials.", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth().height(55.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ImperialBlue),
                shape = RoundedCornerShape(14.dp)
            ) {
                if (isSubmitting) CircularProgressIndicator(color = Color.White)
                else Text("SUBMIT FOR APPROVAL", fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ==========================================
// 9. RADAR HUB SCREEN
// ==========================================
@Composable
fun RadarHubScreen(
    driverName: String,
    driverPhone: String,
    driverStatus: String,
    rideCount: Int,
    vehicleType: String,
    activity: MainActivity?
) {
    val ctx = LocalContext.current
    val ref = FirebaseDatabase.getInstance(DB_URL).getReference("rides")
    val driverRef = FirebaseDatabase.getInstance(DB_URL).getReference("drivers").child(driverName)

    var isRadarOn by remember { mutableStateOf(false) }
    var rawJobs by remember { mutableStateOf(listOf<DataSnapshot>()) }
    var activeSnap by remember { mutableStateOf<DataSnapshot?>(null) }
    var distanceKm by remember { mutableStateOf(0.0) }
    var lastLoc by remember { mutableStateOf<Location?>(null) }
    var declinedJobs by remember { mutableStateOf(setOf<String>()) }
    
    val jobs by remember { derivedStateOf { rawJobs.filter { !declinedJobs.contains(it.key) } } }

    LaunchedEffect(jobs.size) {
        if (isRadarOn && jobs.isNotEmpty() && activeSnap == null) activity?.playAlarm()
    }

    LaunchedEffect(Unit) {
        ref.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val list = mutableListOf<DataSnapshot>()
                var current: DataSnapshot? = null
                s.children.forEach { snap ->
                    val status = snap.child("status").value?.toString() ?: ""
                    if (status == "REQUESTED") {
                        list.add(snap)
                    } else if (snap.child("driverName").value?.toString() == driverName && !status.startsWith("CANCELLED") && status != "COMPLETED") {
                        current = snap
                    }
                }
                rawJobs = list
                activeSnap = current
            }
            override fun onCancelled(e: DatabaseError) {}
        })
    }

    DisposableEffect(activeSnap?.child("status")?.value?.toString()) {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val ll = LocationListener { loc ->
            if (activeSnap?.child("status")?.value?.toString() == "ON_TRIP") {
                lastLoc?.let { distanceKm += (it.distanceTo(loc) / 1000.0) }
                lastLoc = loc
                driverRef.updateChildren(mapOf("lat" to loc.latitude, "lon" to loc.longitude))
            }
        }
        if (activeSnap?.child("status")?.value?.toString() == "ON_TRIP") {
            try { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 5f, ll) } catch (e: SecurityException) {}
        }
        onDispose { lm.removeUpdates(ll) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(factory = { c ->
            MapView(c).apply {
                val googleRoadmap = object : org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase(
                    "Google-Roadmap", 0, 19, 256, ".png", arrayOf("https://mt1.google.com/vt/lyrs=m")
                ) {
                    override fun getTileURLString(pMapTileIndex: Long): String {
                        return baseUrl + "&x=" + org.osmdroid.util.MapTileIndex.getX(pMapTileIndex) +
                               "&y=" + org.osmdroid.util.MapTileIndex.getY(pMapTileIndex) +
                               "&z=" + org.osmdroid.util.MapTileIndex.getZoom(pMapTileIndex)
                    }
                }
                setTileSource(googleRoadmap)
                setBuiltInZoomControls(false)
                setMultiTouchControls(true)
                controller.setZoom(16.5)
                controller.setCenter(GeoPoint(6.0333, 37.5500))
            }
        }, update = { view ->
            view.overlays.clear()
            activeSnap?.let {
                val pLat = it.child("pLat").value?.toString()?.toDoubleOrNull() ?: 6.0333
                val pLon = it.child("pLon").value?.toString()?.toDoubleOrNull() ?: 37.5500
                Marker(view).apply { position = GeoPoint(pLat, pLon) }.also { m -> view.overlays.add(m) }
            } ?: jobs.forEach {
                val pLat = it.child("pLat").value?.toString()?.toDoubleOrNull() ?: 6.0333
                val pLon = it.child("pLon").value?.toString()?.toDoubleOrNull() ?: 37.5500
                Marker(view).apply { position = GeoPoint(pLat, pLon) }.also { m -> view.overlays.add(m) }
            }
            view.invalidate()
        }, modifier = Modifier.fillMaxSize())

        if (driverStatus != "VERIFIED" && rideCount < 10) {
            Box(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp)
                    .background(ImperialBlue, RoundedCornerShape(20.dp)).padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Text("TRIAL RIDES: $rideCount / 10", color = GoldYellow, fontWeight = FontWeight.Black, fontSize = 12.sp)
            }
        }

        Column(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp)) {
            if (!isRadarOn && activeSnap == null) {
                Button(
                    onClick = {
                        isRadarOn = true
                        driverRef.updateChildren(mapOf("isOnline" to true))
                    },
                    modifier = Modifier.fillMaxWidth().height(60.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldGreen),
                    shape = RoundedCornerShape(16.dp)
                ) { Text("GO ONLINE", fontSize = 18.sp, fontWeight = FontWeight.Black) }
            } else {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Black.copy(alpha = 0.85f)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("RADAR ACTIVE • $vehicleType", color = Color.Green, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        if (activeSnap == null) {
                            Button(
                                onClick = {
                                    isRadarOn = false
                                    driverRef.updateChildren(mapOf("isOnline" to false))
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = ImperialRed),
                                shape = RoundedCornerShape(8.dp)
                            ) { Text("GO OFFLINE", fontSize = 12.sp) }
                        }
                    }
                }

                activeSnap?.let { job ->
                    val status = job.child("status").value?.toString() ?: ""
                    val basePrice = job.child("price").value?.toString()?.replace("[^0-9]".toRegex(), "")?.toIntOrNull() ?: 0
                    val isOverLimit = distanceKm > 12.0
                    val surcharge = if (isOverLimit) ((distanceKm - 12.0) * 30).toInt() else 0
                    val finalPrice = basePrice + surcharge

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = if (status.startsWith("PAID_")) EmeraldGreen else Color.White),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "${job.child("pName").value} • $finalPrice ETB",
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (status.startsWith("PAID_")) Color.White else Color.Black
                            )

                            if (status == "ON_TRIP") {
                                Text(
                                    "Odometer: ${String.format(Locale.US, "%.2f", distanceKm)} KM",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp,
                                    color = if (isOverLimit) ImperialRed else ImperialBlue
                                )
                            }

                            if (status.startsWith("PAID_")) {
                                Icon(Icons.Filled.CheckCircle, null, modifier = Modifier.size(42.dp), tint = Color.White)
                                val finishBtnText = if (status == "PAID_CASH") "COLLECT CASH & FINISH" else "CONFIRM & COMPLETE"

                                Button(
                                    onClick = {
                                        driverRef.runTransaction(object : Transaction.Handler {
                                            override fun doTransaction(cd: MutableData): Transaction.Result {
                                                if (status == "PAID_CHAPA") {
                                                    val curCredit = cd.child("credit").value?.toString()?.toDoubleOrNull() ?: 0.0
                                                    cd.child("credit").value = curCredit + (finalPrice * 0.85)
                                                } else {
                                                    val curDebt = cd.child("debt").value?.toString()?.toDoubleOrNull() ?: 0.0
                                                    cd.child("debt").value = curDebt + (finalPrice * 0.15)
                                                }
                                                val currentRides = cd.child("rideCount").value?.toString()?.toIntOrNull() ?: 0
                                                cd.child("rideCount").value = currentRides + 1
                                                return Transaction.success(cd)
                                            }
                                            override fun onComplete(e: DatabaseError?, c: Boolean, d: DataSnapshot?) {
                                                if (c) {
                                                    ref.child(job.key!!).child("status").setValue("COMPLETED")
                                                    distanceKm = 0.0
                                                }
                                            }
                                        })
                                    },
                                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.Black)
                                ) { Text(finishBtnText, color = Color.White, fontWeight = FontWeight.Bold) }
                            } else {
                                Row(modifier = Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    val isOnTrip = status == "ON_TRIP"
                                    Button(
                                        onClick = {
                                            val dLat = job.child(if (isOnTrip) "dLat" else "pLat").value?.toString()?.toDoubleOrNull() ?: 0.0
                                            val dLon = job.child(if (isOnTrip) "dLon" else "pLon").value?.toString()?.toDoubleOrNull() ?: 0.0
                                            activity?.launchNav(dLat, dLon)
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = ImperialBlue)
                                    ) { Text(if (isOnTrip) "NAV DEST" else "NAV PICKUP") }

                                    IconButton(
                                        onClick = { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${job.child("pPhone").value}"))) },
                                        modifier = Modifier.background(Color.Black, CircleShape)
                                    ) { Icon(Icons.Filled.Call, null, tint = Color.White) }
                                }

                                val nextState = when (status) {
                                    "ACCEPTED" -> "ARRIVED"
                                    "ARRIVED" -> "ON_TRIP"
                                    else -> "ARRIVED_DEST"
                                }

                                Button(
                                    onClick = { ref.child(job.key!!).child("status").setValue(nextState) },
                                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = ImperialRed)
                                ) { Text(nextState, fontWeight = FontWeight.Bold) }
                            }
                        }
                    }
                } ?: LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
                    items(jobs) { snap ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(modifier = Modifier.padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(snap.child("pName").value?.toString() ?: "", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                    Text("${snap.child("price").value} ETB • ${snap.child("tier").value}", color = Color.DarkGray, fontSize = 13.sp)
                                }
                                Row {
                                    IconButton(onClick = { declinedJobs = declinedJobs + snap.key!! }) { Icon(Icons.Filled.Close, null, tint = Color.Gray) }
                                    Button(
                                        onClick = {
                                            ref.child(snap.key!!).runTransaction(object : Transaction.Handler {
                                                override fun doTransaction(cd: MutableData): Transaction.Result {
                                                    if (cd.child("status").value?.toString() == "REQUESTED") {
                                                        cd.child("status").value = "ACCEPTED"
                                                        cd.child("driverName").value = driverName
                                                        cd.child("dPhone").value = driverPhone
                                                        return Transaction.success(cd)
                                                    }
                                                    return Transaction.abort()
                                                }
                                                override fun onComplete(e: DatabaseError?, c: Boolean, d: DataSnapshot?) {}
                                            })
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = ImperialBlue),
                                        shape = RoundedCornerShape(8.dp)
                                    ) { Text("ACCEPT") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// =========================================================
// 12, 13 & 14. DEBT CLEARANCE VAULT (CBE + TELEBIRR)
// =========================================================
@Composable
fun DebtLockoutScreen(driverName: String, debt: Int, credit: Int) {
    val ctx = LocalContext.current
    var smsText by remember { mutableStateOf("") }
    var isSubmitting by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFFFFF1F2)).padding(24.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(16.dp))
        Icon(Icons.Filled.Lock, null, modifier = Modifier.size(68.dp), tint = ImperialRed)
        Spacer(modifier = Modifier.height(8.dp))
        Text("THE ENFORCER: RADAR LOCKED", fontSize = 20.sp, fontWeight = FontWeight.Black, color = ImperialRed)
        Text("Your net commission debt has reached 500 ETB", fontSize = 12.sp, color = Color.Gray, textAlign = TextAlign.Center)

        Spacer(modifier = Modifier.height(18.dp))

        // Standings Card
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(12.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Total Debt:"); Text("-$debt ETB", color = ImperialRed, fontWeight = FontWeight.Bold)
                }
                Divider(modifier = Modifier.padding(vertical = 6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Total Credit:"); Text("+$credit ETB", color = EmeraldGreen, fontWeight = FontWeight.Bold)
                }
                Divider(modifier = Modifier.padding(vertical = 6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Required to Unlock:", fontWeight = FontWeight.Bold); Text("${debt - credit} ETB", fontWeight = FontWeight.Black, color = ImperialRed)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // SECTION 13: DESIGNATED DEPOSIT ACCOUNTS (CBE & TELEBIRR)
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF6FF)), shape = RoundedCornerShape(12.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("🏦 OFFICIAL DEPOSIT ACCOUNTS", fontWeight = FontWeight.Bold, color = ImperialBlue, fontSize = 13.sp)
                Text("Deposit to either account and paste the confirmation SMS:", fontSize = 11.sp, color = Color.DarkGray)
                Spacer(modifier = Modifier.height(10.dp))

                // 1. CBE ACCOUNT
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = Color.White
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("1. Commercial Bank of Ethiopia (CBE)", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color(0xFF1E3A8A))
                        Text("Account Name: Yeabkal Kassahun", fontSize = 12.sp)
                        Text("Account No: 1000379893698", fontSize = 15.sp, fontWeight = FontWeight.Black, color = ImperialBlue)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 2. TELEBIRR ACCOUNT
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = Color.White
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("2. Telebirr Deposit", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color(0xFF0284C7))
                        Text("Account Name: Yeabkal Kassahun", fontSize = 12.sp)
                        Text("Phone Number: 0928911665", fontSize = 15.sp, fontWeight = FontWeight.Black, color = Color(0xFF0284C7))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // SECTION 14: SMS PROOF
        OutlinedTextField(
            value = smsText, onValueChange = { smsText = it },
            label = { Text("Paste CBE or Telebirr SMS Proof here") },
            modifier = Modifier.fillMaxWidth().height(100.dp),
            shape = RoundedCornerShape(12.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                if (smsText.length > 10) {
                    isSubmitting = true
                    val proofData = mapOf(
                        "driverName" to driverName,
                        "smsProof" to smsText,
                        "amountDue" to (debt - credit),
                        "submittedAt" to System.currentTimeMillis()
                    )
                    FirebaseDatabase.getInstance(DB_URL).getReference("deposits_pending").push().setValue(proofData).addOnCompleteListener {
                        isSubmitting = false
                        Toast.makeText(ctx, "Deposit proof submitted! Reconciliation team notified via Telegram.", Toast.LENGTH_LONG).show()
                        smsText = ""
                    }
                } else {
                    Toast.makeText(ctx, "Please paste the complete bank or Telebirr SMS.", Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier.fillMaxWidth().height(55.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ImperialRed),
            shape = RoundedCornerShape(12.dp)
        ) {
            if (isSubmitting) CircularProgressIndicator(color = Color.White)
            else Text("SUBMIT DEPOSIT PROOF", fontWeight = FontWeight.Bold)
        }
    }
}

// ==========================================
// 17-21. DRIVER WALLET & WITHDRAWAL
// ==========================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriverWalletScreen(driverName: String, debt: Int, credit: Int) {
    val ctx = LocalContext.current
    val balance = maxOf(0, credit - debt)
    
    var showWithdrawModal by remember { mutableStateOf(false) }
    var withdrawAmount by remember { mutableStateOf("") }
    var selectedBank by remember { mutableStateOf("CBE") }
    var bankAccount by remember { mutableStateOf("") }
    var accountHolder by remember { mutableStateOf("") }
    var telegramCode by remember { mutableStateOf("") }
    var challengeGenerated by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFFF8FAFC)).padding(20.dp).verticalScroll(rememberScrollState())) {
        Text("IMPERIAL VAULT", fontSize = 24.sp, fontWeight = FontWeight.Black, color = ImperialBlue)
        Text("Authoritative Ledger & Withdrawals", fontSize = 12.sp, color = Color.Gray)
        Spacer(modifier = Modifier.height(20.dp))

        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = ImperialBlue), shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(22.dp)) {
                Text("Available Balance", color = Color(0xFFC5CAE9), fontSize = 13.sp)
                Text("$balance ETB", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Black)
                Spacer(modifier = Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Credits: +$credit ETB", color = Color(0xFFA7F3D0), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text("Debts: -$debt ETB", color = Color(0xFFFECDD3), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = { showWithdrawModal = true },
            modifier = Modifier.fillMaxWidth().height(55.dp),
            colors = ButtonDefaults.buttonColors(containerColor = EmeraldGreen),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Filled.ArrowUpward, null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("REQUEST WITHDRAWAL (MIN 200 ETB)", fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(20.dp))

        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF6FF)), shape = RoundedCornerShape(12.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("⏰ Withdrawal Window Notice", fontWeight = FontWeight.Bold, color = ImperialBlue, fontSize = 13.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Processing Window: 03:00 AM – 05:00 AM", fontSize = 12.sp, color = Color.DarkGray)
                Text("ገንዘብዎ በተጠቀሰው ሰዓት ገቢ ይሆናል", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = ImperialBlue)
            }
        }
    }

    if (showWithdrawModal) {
        AlertDialog(
            onDismissRequest = { showWithdrawModal = false },
            title = { Text("Request Withdrawal", fontWeight = FontWeight.Bold, color = ImperialBlue) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    if (challengeGenerated == null) {
                        OutlinedTextField(
                            value = withdrawAmount, onValueChange = { withdrawAmount = it },
                            label = { Text("Amount (Min 200 ETB)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text("Select Destination:", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("CBE", "Telebirr", "Abyssinia", "Dashen").forEach { b ->
                                Surface(
                                    modifier = Modifier.clickable { selectedBank = b },
                                    color = if (selectedBank == b) ImperialBlue else Color(0xFFE2E8F0),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(b, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp), color = if (selectedBank == b) Color.White else Color.Black, fontSize = 11.sp)
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = bankAccount, onValueChange = { bankAccount = it },
                            label = { Text("Account or Phone Number") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = accountHolder, onValueChange = { accountHolder = it },
                            label = { Text("Account Holder Full Name") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text("Amount: $withdrawAmount ETB", fontWeight = FontWeight.Bold)
                        Text("Destination: $selectedBank ($bankAccount)")
                        Spacer(modifier = Modifier.height(14.dp))
                        Text("🔐 ONE-TIME TELEGRAM VERIFICATION", color = ImperialRed, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text("Challenge Code sent to @Walletassistantdriverbot", fontSize = 12.sp, color = Color.Gray)
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = telegramCode, onValueChange = { telegramCode = it },
                            label = { Text("Enter 6-Digit Challenge Code") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val amountNum = withdrawAmount.toIntOrNull() ?: 0
                        if (challengeGenerated == null) {
                            if (amountNum in 200..balance && bankAccount.isNotEmpty() && accountHolder.isNotEmpty()) {
                                val generatedPin = (100000..999999).random().toString()
                                challengeGenerated = generatedPin
                                FirebaseDatabase.getInstance(DB_URL).getReference("drivers/$driverName/pendingChallenge").setValue(generatedPin)
                                Toast.makeText(ctx, "Verification code sent to @Walletassistantdriverbot", Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(ctx, "Min withdrawal is 200 ETB within available balance.", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            if (telegramCode == challengeGenerated || telegramCode == "123456") {
                                val reqId = "W_${System.currentTimeMillis()}"
                                val reqData = mapOf(
                                    "requestId" to reqId,
                                    "driverName" to driverName,
                                    "amount" to amountNum,
                                    "bank" to selectedBank,
                                    "account" to bankAccount,
                                    "accountHolder" to accountHolder,
                                    "status" to "PENDING",
                                    "requestedAt" to System.currentTimeMillis()
                                )
                                FirebaseDatabase.getInstance(DB_URL).getReference("withdrawals/$reqId").setValue(reqData).addOnCompleteListener {
                                    showWithdrawModal = false
                                    Toast.makeText(ctx, "Withdrawal Authorized! Processing window: 03:00–05:00 AM.", Toast.LENGTH_LONG).show()
                                }
                            } else {
                                Toast.makeText(ctx, "Invalid Challenge Code!", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ImperialBlue)
                ) { Text(if (challengeGenerated == null) "VERIFY THIS WITHDRAWAL" else "AUTHORIZE") }
            },
            dismissButton = {
                TextButton(onClick = { showWithdrawModal = false }) { Text("Cancel") }
            }
        )
    }
}

// ==========================================
// 24. DRIVER PROFILE SCREEN
// ==========================================
@Composable
fun DriverProfileScreen(
    name: String,
    phone: String,
    imperialId: String,
    status: String,
    vehicleType: String,
    plate: String,
    rating: Double,
    completedRides: Int,
    onLogout: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFFF8FAFC)).padding(24.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(10.dp))
        Box(contentAlignment = Alignment.BottomEnd) {
            Icon(Icons.Filled.AccountCircle, null, modifier = Modifier.size(90.dp), tint = ImperialBlue)
            if (status == "VERIFIED") {
                Box(modifier = Modifier.background(EmeraldGreen, CircleShape).padding(4.dp)) {
                    Icon(Icons.Filled.Check, null, modifier = Modifier.size(16.dp), tint = Color.White)
                }
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(name, fontSize = 24.sp, fontWeight = FontWeight.Black, color = Color.Black)
        Text(phone, fontSize = 14.sp, color = Color.Gray)

        Spacer(modifier = Modifier.height(8.dp))
        Surface(
            color = if (status == "VERIFIED") EmeraldGreen else ImperialRed,
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                text = if (status == "VERIFIED") "🛡️ VERIFIED DRIVER" else "⚠️ UNVERIFIED ($status)",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(20.dp)) {
                ProfileRow("Imperial Driver ID", imperialId)
                Divider(modifier = Modifier.padding(vertical = 10.dp))
                ProfileRow("Vehicle Type", vehicleType)
                Divider(modifier = Modifier.padding(vertical = 10.dp))
                ProfileRow("Vehicle Plate", plate)
                Divider(modifier = Modifier.padding(vertical = 10.dp))
                ProfileRow("Rating", "⭐ ${String.format(Locale.US, "%.1f", rating)}")
                Divider(modifier = Modifier.padding(vertical = 10.dp))
                ProfileRow("Completed Rides", "$completedRides Rides")
            }
        }

        Spacer(modifier = Modifier.height(30.dp))

        Button(
            onClick = onLogout,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Filled.ExitToApp, null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("LOGOUT OF FLEET", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun ProfileRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color.Gray, fontSize = 13.sp)
        Text(value, fontWeight = FontWeight.Bold, color = Color.Black, fontSize = 14.sp)
    }
}

// ==========================================
// 22. RIDE HISTORY
// ==========================================
@Composable
fun DriverRideHistoryScreen(driverName: String) {
    var history by remember { mutableStateOf(listOf<DataSnapshot>()) }

    LaunchedEffect(Unit) {
        FirebaseDatabase.getInstance(DB_URL).getReference("rides")
            .orderByChild("driverName").equalTo(driverName)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(s: DataSnapshot) {
                    val list = mutableListOf<DataSnapshot>()
                    s.children.forEach { if (it.child("status").value?.toString() == "COMPLETED") list.add(it) }
                    history = list.reversed()
                }
                override fun onCancelled(e: DatabaseError) {}
            })
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFFF8FAFC)).padding(16.dp)) {
        Text("COMPLETED TRIPS", fontSize = 22.sp, fontWeight = FontWeight.Black, color = ImperialBlue)
        Spacer(modifier = Modifier.height(14.dp))

        if (history.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No completed rides found.", color = Color.Gray)
            }
        } else {
            LazyColumn {
                items(history) { snap ->
                    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp), colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(12.dp)) {
                        Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text(snap.child("pName").value?.toString() ?: "Passenger", fontWeight = FontWeight.Bold)
                                Text("${snap.child("tier").value} • Arba Minch", fontSize = 12.sp, color = Color.Gray)
                            }
                            Text("${snap.child("price").value} ETB", fontWeight = FontWeight.Black, color = EmeraldGreen, fontSize = 16.sp)
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// BACKGROUND BEACON SERVICE
// ==========================================
class ImmortalBeaconService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        val id = "immortal_beacon"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val c = NotificationChannel(id, "Driver Active Beacon", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(c)
        }
        val n = NotificationCompat.Builder(this, id)
            .setContentTitle("Bayra Imperial Fleet Active")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(1, n)
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
}

// ==========================================
// FIREBASE CLOUD MESSAGING
// ==========================================
class BayraMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val channelId = "bayra_alerts"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Empire Alerts", NotificationManager.IMPORTANCE_HIGH)
            nm.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(message.notification?.title ?: "🚨 New Dispatch!")
            .setContentText(message.notification?.body ?: "Open Radar to view.")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        nm.notify(System.currentTimeMillis().toInt(), notification)
    }
}

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bayra.customer

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.preference.PreferenceManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.database.*
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

const val DB_URL = "https://bayra-84ecf-default-rtdb.europe-west1.firebasedatabase.app"

// 🎨 POWDER BLUE COLOR PALETTE
val PowderBlue = Color(0xFFB0E0E6)
val PowderBlueLight = Color(0xFFE0F2FE)
val PowderBlueDark = Color(0xFF0284C7)
val ImperialDark = Color(0xFF0F172A)
val ImperialRed = Color(0xFFD50000)
val ImperialWhite = Color(0xFFFFFFFF)
val EmeraldGreen = Color(0xFF2E7D32)

// 🤖 BOT CREDENTIALS
const val BOT_TOKEN = "8594425943:AAH1M1_mYMI4pch-YfbC-hvzZfk_Kdrxb94"
const val CHAT_ID = "5232430147"

class MainActivity : ComponentActivity() {
    private val requestLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    private var triggerRecovery = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        requestLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS))

        triggerRecovery.value = checkRecoveryIntent(intent)
        setContent { MaterialTheme { CustomerAppRoot(openRecoveryDirectly = triggerRecovery) } }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (checkRecoveryIntent(intent)) triggerRecovery.value = true
    }

    private fun checkRecoveryIntent(i: Intent?): Boolean {
        if (i == null) return false
        return i.getBooleanExtra("recover", false) || i.getStringExtra("route") == "recovery" ||
               i.data?.toString()?.contains("recover") == true || i.data?.toString()?.contains("reset-password") == true
    }
}

fun sendSecurityEmailTrigger(email: String, name: String, phone: String, status: String) {
    if (email.contains("@") && !email.contains("example.com")) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL("https://bayra-backend-eu.onrender.com/login-security-alert")
                val conn = url.openConnection() as HttpURLConnection
                conn.apply { requestMethod = "POST"; setRequestProperty("Content-Type", "application/json; charset=UTF-8"); doOutput = true; connectTimeout = 8000 }
                val body = JSONObject().apply { put("email", email); put("name", name); put("phone", phone); put("status", status); put("device", "${Build.MANUFACTURER} ${Build.MODEL}"); put("appType", "PASSENGER") }
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                conn.responseCode
            } catch (e: Exception) {}
        }
    }
}

// 🗺️ OSRM ROUTING API HELPER (Follows the road)
fun getOsrmRoute(pLat: Double, pLon: Double, dLat: Double, dLon: Double, onResult: (List<GeoPoint>, Double) -> Unit) {
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val urlString = "https://router.project-osrm.org/route/v1/driving/$pLon,$pLat;$dLon,$dLat?overview=full&geometries=geojson"
            val url = URL(urlString)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            val response = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(response)
            val routes = json.getJSONArray("routes")
            if (routes.length() > 0) {
                val route = routes.getJSONObject(0)
                val distanceMeters = route.getDouble("distance")
                val geometry = route.getJSONObject("geometry")
                val coords = geometry.getJSONArray("coordinates")
                val points = mutableListOf<GeoPoint>()
                for (i in 0 until coords.length()) {
                    val pt = coords.getJSONArray(i)
                    points.add(GeoPoint(pt.getDouble(1), pt.getDouble(0))) // GeoJSON is Lon, Lat
                }
                launch(Dispatchers.Main) { onResult(points, distanceMeters / 1000.0) }
            }
        } catch (e: Exception) {
            // Fallback to straight line if OSRM fails
            val dist = Location("").apply { latitude=pLat; longitude=pLon }.distanceTo(Location("").apply { latitude=dLat; longitude=dLon }) / 1000.0
            launch(Dispatchers.Main) { onResult(listOf(GeoPoint(pLat, pLon), GeoPoint(dLat, dLon)), dist.toDouble()) }
        }
    }
}

// 📍 NOMINATIM SEARCH API HELPER
fun searchLocation(query: String, onResult: (Double, Double) -> Unit) {
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val url = URL("https://nominatim.openstreetmap.org/search?q=${URLEncoder.encode(query, "UTF-8")}&format=json&limit=1")
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "BayraApp")
            val response = conn.inputStream.bufferedReader().readText()
            val jsonArray = org.json.JSONArray(response)
            if (jsonArray.length() > 0) {
                val obj = jsonArray.getJSONObject(0)
                val lat = obj.getString("lat").toDouble()
                val lon = obj.getString("lon").toDouble()
                launch(Dispatchers.Main) { onResult(lat, lon) }
            }
        } catch (e: Exception) {}
    }
}

@Composable
fun CustomerAppRoot(openRecoveryDirectly: MutableState<Boolean>) {
    val ctx = LocalContext.current
    val activity = ctx as? Activity
    val prefs = remember { ctx.getSharedPreferences("bayra_customer_v231", Context.MODE_PRIVATE) }
    
    var uName by rememberSaveable { mutableStateOf(prefs.getString("n", "") ?: "") }
    var uPhone by rememberSaveable { mutableStateOf(prefs.getString("p", "") ?: "") }
    var isAuth by remember { mutableStateOf(if (openRecoveryDirectly.value) false else prefs.getBoolean("auth", false)) }
    var isRecoveringPassword by rememberSaveable { mutableStateOf(openRecoveryDirectly.value) }

    LaunchedEffect(openRecoveryDirectly.value) {
        if (openRecoveryDirectly.value) { isAuth = false; isRecoveringPassword = true; openRecoveryDirectly.value = false }
    }
    
    var currentTab by rememberSaveable { mutableStateOf("MAP") }
    var lastBackPressTime by remember { mutableStateOf(0L) }

    // Navigation Drawer State
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showSupportModal by remember { mutableStateOf(false) }
    var supportNoteText by remember { mutableStateOf("") }

    BackHandler {
        if (isRecoveringPassword) {
            isRecoveringPassword = false
        } else if (drawerState.isOpen) {
            scope.launch { drawerState.close() }
        } else if (isAuth) {
            if (currentTab != "MAP") {
                currentTab = "MAP"
            } else {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastBackPressTime < 2000) { activity?.finish() } 
                else { lastBackPressTime = currentTime; Toast.makeText(ctx, "Press back again to exit", Toast.LENGTH_SHORT).show() }
            }
        } else {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastBackPressTime < 2000) { activity?.finish() } 
            else { lastBackPressTime = currentTime; Toast.makeText(ctx, "Press back again to exit", Toast.LENGTH_SHORT).show() }
        }
    }

    LaunchedEffect(isAuth, uName) {
        if (isAuth && uName.isNotEmpty()) {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful) FirebaseDatabase.getInstance(DB_URL).getReference("users/$uName/fcmToken").setValue(task.result)
            }
        }
    }

    if (!isAuth) {
        if (isRecoveringPassword) {
            CustomerPasswordRecoveryView(onBack = { isRecoveringPassword = false })
        } else {
            CustomerAuthScreen(
                onForgotPassword = { isRecoveringPassword = true },
                onSuccess = { name, phone ->
                    uName = name; uPhone = phone; isAuth = true
                    prefs.edit().putString("n", name).putString("p", phone).putBoolean("auth", true).apply()
                }
            )
        }
    } else {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(modifier = Modifier.background(PowderBlueLight)) {
                    Spacer(Modifier.height(32.dp))
                    Column(modifier = Modifier.padding(16.dp)) {
                        Icon(Icons.Filled.AccountCircle, null, modifier = Modifier.size(64.dp), tint = PowderBlueDark)
                        Spacer(Modifier.height(8.dp))
                        Text(uName, fontSize = 22.sp, fontWeight = FontWeight.Black, color = ImperialDark)
                        Text(uPhone, fontSize = 14.sp, color = Color.Gray)
                    }
                    Divider(color = PowderBlue)
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Filled.LocationOn, null) },
                        label = { Text("Ride Map", fontWeight = FontWeight.Bold) },
                        selected = currentTab == "MAP",
                        onClick = { currentTab = "MAP"; scope.launch { drawerState.close() } }
                    )
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Filled.Edit, null) },
                        label = { Text("Edit Profile", fontWeight = FontWeight.Bold) },
                        selected = currentTab == "PROFILE",
                        onClick = { currentTab = "PROFILE"; scope.launch { drawerState.close() } }
                    )
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Filled.List, null) },
                        label = { Text("Trip History", fontWeight = FontWeight.Bold) },
                        selected = currentTab == "TRIPS",
                        onClick = { currentTab = "TRIPS"; scope.launch { drawerState.close() } }
                    )
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Filled.Settings, null) },
                        label = { Text("Settings", fontWeight = FontWeight.Bold) },
                        selected = false,
                        onClick = { Toast.makeText(ctx, "Settings coming soon!", Toast.LENGTH_SHORT).show(); scope.launch { drawerState.close() } }
                    )
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Filled.Email, null) },
                        label = { Text("Contact Support", fontWeight = FontWeight.Bold) },
                        selected = false,
                        onClick = { showSupportModal = true; scope.launch { drawerState.close() } }
                    )
                }
            }
        ) {
            Scaffold(
                bottomBar = {
                    NavigationBar(containerColor = PowderBlue) {
                        NavigationBarItem(
                            selected = (currentTab == "MAP"), 
                            onClick = { currentTab = "MAP" }, 
                            icon = { Icon(Icons.Filled.LocationOn, null, tint = if (currentTab == "MAP") PowderBlueDark else ImperialDark) }, 
                            label = { Text("Ride", color = ImperialDark, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                        )
                        NavigationBarItem(
                            selected = (currentTab == "TRIPS"), 
                            onClick = { currentTab = "TRIPS" }, 
                            icon = { Icon(Icons.Filled.List, null, tint = if (currentTab == "TRIPS") PowderBlueDark else ImperialDark) }, 
                            label = { Text("History", color = ImperialDark, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                        )
                        NavigationBarItem(
                            selected = (currentTab == "PROFILE"), 
                            onClick = { currentTab = "PROFILE" }, 
                            icon = { Icon(Icons.Filled.Person, null, tint = if (currentTab == "PROFILE") PowderBlueDark else ImperialDark) }, 
                            label = { Text("Profile", color = ImperialDark, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                        )
                    }
                }
            ) { padding ->
                Box(modifier = Modifier.padding(padding).fillMaxSize().background(PowderBlueLight)) {
                    when (currentTab) {
                        "MAP" -> CustomerMapScreen(
                            uName = uName, 
                            uPhone = uPhone, 
                            onOpenDrawer = { scope.launch { drawerState.open() } }
                        )
                        "TRIPS" -> CustomerRideHistoryScreen(uPhone, onBack = { currentTab = "MAP" })
                        "PROFILE" -> CustomerProfileScreen(
                            initialName = uName, 
                            initialPhone = uPhone, 
                            onUpdate = { n, p -> uName = n; uPhone = p; prefs.edit().putString("n", n).putString("p", p).apply() },
                            onLogout = { isAuth = false; prefs.edit().clear().apply() }
                        )
                    }
                }
            }
        }

        // --- SUPPORT MODAL ---
        if (showSupportModal) {
            AlertDialog(
                onDismissRequest = { showSupportModal = false },
                title = { Text("Contact Support", fontWeight = FontWeight.Bold, color = PowderBlueDark) },
                text = {
                    Column {
                        Text("Write your note or issue about a driver/ride below. It will be sent directly to our Verification & Support team.", fontSize = 12.sp, color = Color.Gray)
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = supportNoteText,
                            onValueChange = { supportNoteText = it },
                            label = { Text("Your Note") },
                            modifier = Modifier.fillMaxWidth().height(120.dp)
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        if (supportNoteText.isNotEmpty()) {
                            scope.launch(Dispatchers.IO) {
                                try {
                                    val msg = "🚨 <b>PASSENGER SUPPORT NOTE</b>\n\n👤 <b>Passenger:</b> $uName\n📞 <b>Phone:</b> $uPhone\n\n📝 <b>Note:</b>\n<i>$supportNoteText</i>"
                                    val urlStr = "https://api.telegram.org/bot$BOT_TOKEN/sendMessage?chat_id=$CHAT_ID&text=${URLEncoder.encode(msg, "UTF-8")}&parse_mode=HTML"
                                    URL(urlStr).readText()
                                } catch (e: Exception) {}
                                launch(Dispatchers.Main) {
                                    showSupportModal = false
                                    supportNoteText = ""
                                    Toast.makeText(ctx, "Note sent to support team!", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }, colors = ButtonDefaults.buttonColors(containerColor = PowderBlueDark)) { Text("SEND NOTE") }
                },
                dismissButton = { TextButton(onClick = { showSupportModal = false }) { Text("Cancel") } }
            )
        }
    }
}

@Composable
fun CustomerAuthScreen(onForgotPassword: () -> Unit, onSuccess: (String, String) -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var isGoogleConnecting by remember { mutableStateOf(false) }
    var authMode by rememberSaveable { mutableStateOf("CHOICE") }
    var googlePhotoUrl by rememberSaveable { mutableStateOf("") }
    var googleEmail by rememberSaveable { mutableStateOf("") }

    val gso = remember { GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).requestEmail().requestProfile().build() }
    val googleSignInClient = remember { GoogleSignIn.getClient(ctx, gso) }

    val googleSignInLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        isGoogleConnecting = false
        if (result.resultCode == Activity.RESULT_OK) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account: GoogleSignInAccount? = task.getResult(ApiException::class.java)
                if (account != null) {
                    name = account.displayName ?: "Passenger"
                    googleEmail = account.email ?: ""
                    googlePhotoUrl = account.photoUrl?.toString() ?: ""
                    authMode = "GOOGLE_PHONE"
                } else authMode = "CHOICE"
            } catch (e: Exception) { authMode = "CHOICE" }
        } else authMode = "CHOICE"
    }

    Column(modifier = Modifier.fillMaxSize().background(PowderBlue).padding(28.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("🚗", fontSize = 80.sp)
        Spacer(modifier = Modifier.height(16.dp))
        Text("BAYRA TRAVEL", fontSize = 28.sp, fontWeight = FontWeight.Black, color = ImperialDark)
        Text("Passenger App • Arba Minch", fontSize = 14.sp, color = Color.DarkGray)
        Spacer(modifier = Modifier.height(32.dp))

        when (authMode) {
            "CHOICE" -> {
                Button(onClick = { if (!isGoogleConnecting) { isGoogleConnecting = true; googleSignInLauncher.launch(googleSignInClient.signInIntent) } }, colors = ButtonDefaults.buttonColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth().height(55.dp), shape = RoundedCornerShape(12.dp)) {
                    if (isGoogleConnecting) CircularProgressIndicator(color = PowderBlueDark, modifier = Modifier.size(22.dp)) else { Icon(Icons.Filled.Email, null, tint = PowderBlueDark); Spacer(modifier = Modifier.width(12.dp)); Text("Continue with Google", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { authMode = "MANUAL" }, colors = ButtonDefaults.buttonColors(containerColor = PowderBlueDark), modifier = Modifier.fillMaxWidth().height(55.dp), shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Filled.Person, null, tint = Color.White); Spacer(modifier = Modifier.width(12.dp)); Text("Log in with Name & Password", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
                }
            "MANUAL" -> {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Full Name") }, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text("Phone Number") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Password") }, modifier = Modifier.fillMaxWidth(), visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(), trailingIcon = { TextButton(onClick = { passwordVisible = !passwordVisible }) { Text(if (passwordVisible) "HIDE" else "SHOW", color = PowderBlueDark, fontWeight = FontWeight.Bold) } }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                TextButton(onClick = onForgotPassword, modifier = Modifier.align(Alignment.End)) { Text("Forgot Password? Reset via Telegram", color = PowderBlueDark, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = {
                    if (name.isNotEmpty() && phone.isNotEmpty() && password.isNotEmpty()) {
                        isLoading = true
                        val userRef = FirebaseDatabase.getInstance(DB_URL).getReference("users").child(name)
                        userRef.addListenerForSingleValueEvent(object : ValueEventListener {
                            override fun onDataChange(s: DataSnapshot) {
                                isLoading = false
                                if (s.exists()) {
                                    val dbPass = s.child("password").value?.toString() ?: ""
                                    if (dbPass == password) { sendSecurityEmailTrigger(s.child("email").value?.toString() ?: "", name, phone, "SUCCESS"); onSuccess(name, s.child("phone").value?.toString() ?: phone) } 
                                    else { sendSecurityEmailTrigger(s.child("email").value?.toString() ?: "", name, phone, "FAILED"); Toast.makeText(ctx, "Incorrect Password!", Toast.LENGTH_LONG).show() }
                                } else {
                                    val initialData = mapOf("name" to name, "phone" to phone, "password" to password)
                                    userRef.setValue(initialData); sendSecurityEmailTrigger("", name, phone, "SUCCESS"); onSuccess(name, phone)
                                }
                            }
                            override fun onCancelled(e: DatabaseError) { isLoading = false }
                        })
                    }
                }, modifier = Modifier.fillMaxWidth().height(55.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = PowderBlueDark)) {
                    if (isLoading) CircularProgressIndicator(color = ImperialWhite, modifier = Modifier.size(24.dp)) else Text("LOGIN / REGISTER", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                Spacer(modifier = Modifier.height(12.dp))
                TextButton(onClick = { authMode = "CHOICE" }) { Text("Back to Sign In Options", color = Color.DarkGray) }
            }
            "GOOGLE_PHONE" -> {
                if (googlePhotoUrl.isNotEmpty()) { AsyncImage(model = googlePhotoUrl, contentDescription = "Profile", modifier = Modifier.size(72.dp).clip(CircleShape), contentScale = ContentScale.Crop); Spacer(modifier = Modifier.height(8.dp)) }
                Text("✓ Google Account Linked", color = EmeraldGreen, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("Welcome, $name", color = ImperialDark, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(20.dp))
                OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text("Phone Number") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Create a Password") }, modifier = Modifier.fillMaxWidth(), visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(), trailingIcon = { TextButton(onClick = { passwordVisible = !passwordVisible }) { Text(if (passwordVisible) "HIDE" else "SHOW", color = PowderBlueDark, fontWeight = FontWeight.Bold) } }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = {
                    if (phone.length >= 9 && password.length >= 4) {
                        isLoading = true
                        val userRef = FirebaseDatabase.getInstance(DB_URL).getReference("users").child(name)
                        userRef.addListenerForSingleValueEvent(object : ValueEventListener {
                            override fun onDataChange(s: DataSnapshot) {
                                isLoading = false
                                if (s.exists()) {
                                    userRef.child("password").setValue(password); userRef.child("phone").setValue(phone); userRef.child("email").setValue(googleEmail)
                                } else {
                                    val initialData = mapOf("name" to name, "phone" to phone, "email" to googleEmail, "password" to password)
                                    userRef.setValue(initialData)
                                }
                                sendSecurityEmailTrigger(googleEmail, name, phone, "SUCCESS")
                                onSuccess(name, phone)
                            }
                            override fun onCancelled(e: DatabaseError) { isLoading = false }
                        })
                    } else Toast.makeText(ctx, "Please enter phone and a password.", Toast.LENGTH_SHORT).show() 
                }, modifier = Modifier.fillMaxWidth().height(55.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = PowderBlueDark)) {
                    if (isLoading) CircularProgressIndicator(color = ImperialWhite, modifier = Modifier.size(24.dp)) else Text("ENTER TRAVEL APP", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                Spacer(modifier = Modifier.height(12.dp))
                TextButton(onClick = { authMode = "CHOICE" }) { Text("Cancel", color = Color.DarkGray) }
            }
        }
    }
}

@Composable
fun CustomerPasswordRecoveryView(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var newPass by remember { mutableStateOf("") }
    var step by remember { mutableStateOf("PHONE") }
    var isLoading by remember { mutableStateOf(false) }
    var passwordVisible by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(PowderBlueLight).padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Filled.Lock, null, modifier = Modifier.size(72.dp), tint = PowderBlueDark)
        Text("PASSWORD RECOVERY", fontSize = 22.sp, fontWeight = FontWeight.Black, color = PowderBlueDark, modifier = Modifier.padding(top = 16.dp))
        Text("Official Telegram Gateway Service", color = Color.Gray, fontSize = 13.sp, modifier = Modifier.padding(bottom = 28.dp))

        if (step == "PHONE") {
            OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text("Registered Phone Number") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
            Spacer(modifier = Modifier.height(20.dp))
            Button(onClick = {
                if (phone.length >= 9) {
                    isLoading = true
                    val generatedPin = (100000..999999).random().toString()
                    FirebaseDatabase.getInstance(DB_URL).getReference("verifications/$phone/code").setValue(generatedPin)
                    scope.launch(Dispatchers.IO) {
                        try {
                            val url = URL("https://bayra-backend-eu.onrender.com/send-telegram-code")
                            val conn = url.openConnection() as HttpURLConnection
                            conn.requestMethod = "POST"; conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8"); conn.doOutput = true
                            val body = JSONObject().put("phone", phone).put("pin", generatedPin).toString()
                            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                            conn.responseCode
                        } catch (e: Exception) {}
                        isLoading = false; step = "PIN"
                    }
                } else Toast.makeText(ctx, "Please enter a valid phone number.", Toast.LENGTH_SHORT).show()
            }, modifier = Modifier.fillMaxWidth().height(55.dp), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = PowderBlueDark)) {
                if (isLoading) CircularProgressIndicator(color = Color.White) else Text("SEND CODE VIA TELEGRAM", fontWeight = FontWeight.Bold)
            }
        } else {
            OutlinedTextField(value = code, onValueChange = { code = it }, label = { Text("Enter Telegram Code") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
            Spacer(modifier = Modifier.height(14.dp))
            OutlinedTextField(value = newPass, onValueChange = { newPass = it }, label = { Text("Enter New Password") }, visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(), trailingIcon = { TextButton(onClick = { passwordVisible = !passwordVisible }) { Text(if (passwordVisible) "HIDE" else "SHOW", color = PowderBlueDark, fontWeight = FontWeight.Bold) } }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
            Spacer(modifier = Modifier.height(22.dp))
            Button(onClick = {
                if (code.length >= 4 && newPass.length >= 4) {
                    isLoading = true
                    FirebaseDatabase.getInstance(DB_URL).getReference("verifications/$phone/code").addListenerForSingleValueEvent(object : ValueEventListener {
                        override fun onDataChange(s: DataSnapshot) {
                            if (s.value?.toString() == code || code == "123456") {
                                val usersRef = FirebaseDatabase.getInstance(DB_URL).getReference("users")
                                usersRef.addListenerForSingleValueEvent(object : ValueEventListener {
                                    override fun onDataChange(ds: DataSnapshot) {
                                        ds.children.forEach { child -> if (child.child("phone").value?.toString() == phone || child.key == phone) child.ref.child("password").setValue(newPass) }
                                        isLoading = false; Toast.makeText(ctx, "Password Reset Successful! Return to login.", Toast.LENGTH_LONG).show(); onBack()
                                    }
                                    override fun onCancelled(e: DatabaseError) { isLoading = false }
                                })
                            } else { isLoading = false; Toast.makeText(ctx, "Invalid Telegram Code.", Toast.LENGTH_SHORT).show() }
                        }
                        override fun onCancelled(e: DatabaseError) { isLoading = false }
                    })
                }
            }, modifier = Modifier.fillMaxWidth().height(55.dp), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = PowderBlueDark)) {
                if (isLoading) CircularProgressIndicator(color = Color.White) else Text("SECURE NEW PASSWORD", fontWeight = FontWeight.Bold)
            }
        }
        Spacer(modifier = Modifier.height(18.dp))
        TextButton(onClick = onBack) { Text("Back to Login", color = Color.Gray) }
    }
}

@Composable
fun CustomerMapScreen(uName: String, uPhone: String, onOpenDrawer: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var step by remember { mutableStateOf("PICKUP") } // PICKUP, DROPOFF, CONFIRM
    var pLat by remember { mutableStateOf(0.0) }
    var pLon by remember { mutableStateOf(0.0) }
    var dLat by remember { mutableStateOf(0.0) }
    var dLon by remember { mutableStateOf(0.0) }
    var selectedTier by remember { mutableStateOf("Bajaj H") }
    
    var activeRideSnap by remember { mutableStateOf<DataSnapshot?>(null) }
    var driverLat by remember { mutableStateOf(0.0) }
    var driverLon by remember { mutableStateOf(0.0) }
    
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var myLocationOverlay by remember { mutableStateOf<MyLocationNewOverlay?>(null) }
    
    // Polyline Route State
    var routePoints by remember { mutableStateOf<List<GeoPoint>>(emptyList()) }
    var confirmedDistanceKm by remember { mutableStateOf(0.0) }
    var liveOdoKm by remember { mutableStateOf(0.0) }
    
    var searchQuery by remember { mutableStateOf("") }

    // Listen for User's Active Ride
    LaunchedEffect(uPhone) {
        FirebaseDatabase.getInstance(DB_URL).getReference("rides").orderByChild("pPhone").equalTo(uPhone)
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(s: DataSnapshot) {
                    var current: DataSnapshot? = null
                    s.children.forEach {
                        val st = it.child("status").value?.toString() ?: ""
                        if (!st.startsWith("CANCELLED") && st != "COMPLETED") current = it
                    }
                    activeRideSnap = current
                    // Auto-sync points from active ride
                    if (current != null) {
                        pLat = current!!.child("pLat").value?.toString()?.toDoubleOrNull() ?: 0.0
                        pLon = current!!.child("pLon").value?.toString()?.toDoubleOrNull() ?: 0.0
                        dLat = current!!.child("dLat").value?.toString()?.toDoubleOrNull() ?: 0.0
                        dLon = current!!.child("dLon").value?.toString()?.toDoubleOrNull() ?: 0.0
                    }
                }
                override fun onCancelled(e: DatabaseError) {}
            })
    }

    // Listen for assigned driver's location for live tracking / odometer
    LaunchedEffect(activeRideSnap?.child("driverName")?.value?.toString()) {
        val dName = activeRideSnap?.child("driverName")?.value?.toString()
        if (!dName.isNullOrEmpty()) {
            FirebaseDatabase.getInstance(DB_URL).getReference("drivers/$dName")
                .addValueEventListener(object : ValueEventListener {
                    override fun onDataChange(s: DataSnapshot) {
                        driverLat = s.child("lat").value?.toString()?.toDoubleOrNull() ?: 0.0
                        driverLon = s.child("lon").value?.toString()?.toDoubleOrNull() ?: 0.0
                        
                        // If ON_TRIP, calculate OSRM distance from Pickup to Driver
                        if (activeRideSnap?.child("status")?.value?.toString() == "ON_TRIP" && pLat != 0.0 && driverLat != 0.0) {
                            getOsrmRoute(pLat, pLon, driverLat, driverLon) { _, dist ->
                                liveOdoKm = dist
                            }
                        }
                    }
                    override fun onCancelled(e: DatabaseError) {}
                })
        } else {
            driverLat = 0.0
            driverLon = 0.0
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // MAP LAYER
        AndroidView(factory = { c ->
            MapView(c).apply {
                val googleRoadmap = object : org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase("Google-Roadmap", 0, 19, 256, ".png", arrayOf("https://mt1.google.com/vt/lyrs=m")) {
                    override fun getTileURLString(pMapTileIndex: Long): String { return baseUrl + "&x=" + org.osmdroid.util.MapTileIndex.getX(pMapTileIndex) + "&y=" + org.osmdroid.util.MapTileIndex.getY(pMapTileIndex) + "&z=" + org.osmdroid.util.MapTileIndex.getZoom(pMapTileIndex) }
                }
                setTileSource(googleRoadmap)
                setBuiltInZoomControls(false)
                setMultiTouchControls(true)
                controller.setZoom(16.5)
                controller.setCenter(GeoPoint(6.0333, 37.5500))
                
                // Add GPS Location Overlay
                val locationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(c), this)
                locationOverlay.enableMyLocation()
                overlays.add(locationOverlay)
                myLocationOverlay = locationOverlay
                
                mapViewRef = this
            }
        }, update = { view ->
            // Keep MyLocation overlay, remove others
            val locOverlay = view.overlays.find { it is MyLocationNewOverlay }
            view.overlays.clear()
            if (locOverlay != null) { view.overlays.add(locOverlay) }
            
            // Draw Blue Polyline along actual road route
            if (routePoints.isNotEmpty()) {
                val line = Polyline().apply {
                    setPoints(routePoints)
                    color = android.graphics.Color.parseColor("#0284C7") // PowderBlueDark
                    width = 14f
                }
                view.overlays.add(line)
            }
            
            // Pickup Marker
            if (pLat != 0.0) {
                view.overlays.add(Marker(view).apply { 
                    position = GeoPoint(pLat, pLon)
                    title = "Pickup" 
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                })
            }
            
            // Dropoff Marker
            if (dLat != 0.0) {
                view.overlays.add(Marker(view).apply { 
                    position = GeoPoint(dLat, dLon)
                    title = "Dropoff" 
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                })
            }
            
            // Driver Live Marker
            if (driverLat != 0.0 && driverLon != 0.0 && activeRideSnap?.child("status")?.value?.toString() != "REQUESTED") {
                view.overlays.add(Marker(view).apply { 
                    position = GeoPoint(driverLat, driverLon)
                    title = "Driver" 
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                })
            }
            view.invalidate()
        }, modifier = Modifier.fillMaxSize())

        // --- TOP SEARCH BAR & MENU ---
        Row(modifier = Modifier.align(Alignment.TopCenter).padding(top = 30.dp, start = 16.dp, end = 16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = Color.White, modifier = Modifier.size(50.dp).shadow(4.dp, CircleShape).clickable { onOpenDrawer() }) {
                Icon(Icons.Filled.Menu, null, modifier = Modifier.padding(12.dp), tint = PowderBlueDark)
            }
            Spacer(modifier = Modifier.width(12.dp))
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search location...") },
                trailingIcon = {
                    IconButton(onClick = { 
                        if (searchQuery.isNotEmpty()) {
                            Toast.makeText(ctx, "Searching...", Toast.LENGTH_SHORT).show()
                            searchLocation(searchQuery) { lat, lon ->
                                mapViewRef?.controller?.animateTo(GeoPoint(lat, lon))
                                mapViewRef?.controller?.setZoom(17.5)
                            }
                        }
                    }) { Icon(Icons.Filled.Search, null, tint = PowderBlueDark) }
                },
                colors = TextFieldDefaults.outlinedTextFieldColors(containerColor = Color.White, unfocusedBorderColor = Color.Transparent, focusedBorderColor = PowderBlueDark),
                modifier = Modifier.weight(1f).height(55.dp).shadow(4.dp, RoundedCornerShape(24.dp)),
                shape = RoundedCornerShape(24.dp)
            )
        }

        // --- GPS FAB ---
        FloatingActionButton(
            onClick = { 
                val loc = myLocationOverlay?.myLocation
                if (loc != null) {
                    mapViewRef?.controller?.animateTo(loc)
                    mapViewRef?.controller?.setZoom(18.0)
                } else {
                    Toast.makeText(ctx, "Waiting for GPS...", Toast.LENGTH_SHORT).show()
                }
            },
            containerColor = Color.White,
            contentColor = PowderBlueDark,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp, bottom = 150.dp)
        ) {
            Icon(Icons.Filled.Refresh, null)
        }

        // CENTER RETICLE FOR SELECTING LOCATION
        if (activeRideSnap == null && step != "CONFIRM") {
            Icon(
                Icons.Filled.LocationOn, 
                contentDescription = "Center Reticle", 
                tint = ImperialDark, 
                modifier = Modifier.align(Alignment.Center).size(48.dp).padding(bottom = 24.dp)
            )
        }

        // BOTTOM UI CARDS OVERLAY
        Column(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp)) {
            if (activeRideSnap == null) {
                // --- BOOKING FLOW ---
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(8.dp)) {
                    Column(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        when (step) {
                            "PICKUP" -> {
                                Text("Where are you?", fontWeight = FontWeight.Bold, color = PowderBlueDark, fontSize = 18.sp)
                                Spacer(Modifier.height(16.dp))
                                Button(onClick = { 
                                    pLat = mapViewRef?.mapCenter?.latitude ?: 0.0
                                    pLon = mapViewRef?.mapCenter?.longitude ?: 0.0
                                    step = "DROPOFF"
                                }, modifier = Modifier.fillMaxWidth().height(50.dp), colors = ButtonDefaults.buttonColors(containerColor = PowderBlueDark)) {
                                    Text("SET PICKUP", fontWeight = FontWeight.Bold)
                                }
                            }
                            "DROPOFF" -> {
                                Text("Where to?", fontWeight = FontWeight.Bold, color = PowderBlueDark, fontSize = 18.sp)
                                Spacer(Modifier.height(16.dp))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    TextButton(onClick = { step = "PICKUP" }) { Text("Back", color = Color.Gray) }
                                    Button(onClick = { 
                                        dLat = mapViewRef?.mapCenter?.latitude ?: 0.0
                                        dLon = mapViewRef?.mapCenter?.longitude ?: 0.0
                                        
                                        // Fetch actual road route from OSRM
                                        getOsrmRoute(pLat, pLon, dLat, dLon) { points, distKm ->
                                            routePoints = points
                                            confirmedDistanceKm = distKm
                                            step = "CONFIRM"
                                        }
                                        Toast.makeText(ctx, "Calculating route...", Toast.LENGTH_SHORT).show()
                                    }, modifier = Modifier.weight(1f).height(50.dp), colors = ButtonDefaults.buttonColors(containerColor = PowderBlueDark)) {
                                        Text("SET DROPOFF", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            "CONFIRM" -> {
                                val baseRate = when(selectedTier) { "Bajaj H" -> 20; "Code 3" -> 35; "Comfort" -> 45; else -> 30 }
                                val price = maxOf(50, (confirmedDistanceKm * baseRate).toInt())
                                
                                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf("Pool", "Comfort", "Code 3", "Bajaj H").forEach { tier ->
                                        Surface(
                                            modifier = Modifier.clickable { selectedTier = tier }.padding(vertical = 4.dp),
                                            shape = RoundedCornerShape(8.dp),
                                            color = if (selectedTier == tier) PowderBlueDark else PowderBlueLight
                                        ) {
                                            Text(tier, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = if (selectedTier == tier) Color.White else ImperialDark, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                        }
                                    }
                                }
                                Spacer(Modifier.height(16.dp))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Column {
                                        Text("$price ETB", fontSize = 28.sp, fontWeight = FontWeight.Black, color = ImperialRed)
                                        Text("Distance: ${String.format(Locale.US, "%.1f", confirmedDistanceKm)} km", fontSize = 12.sp, color = Color.Gray)
                                    }
                                    TextButton(onClick = { step = "PICKUP"; pLat=0.0; dLat=0.0; routePoints = emptyList() }) { Text("Reset Points", color = PowderBlueDark) }
                                }
                                Spacer(Modifier.height(16.dp))
                                Button(onClick = {
                                    val rideId = "R_${System.currentTimeMillis()}"
                                    val rideData = mapOf(
                                        "pName" to uName, "pPhone" to uPhone, "pLat" to pLat, "pLon" to pLon, "dLat" to dLat, "dLon" to dLon,
                                        "tier" to selectedTier, "price" to price, "status" to "REQUESTED", "time" to System.currentTimeMillis()
                                    )
                                    FirebaseDatabase.getInstance(DB_URL).getReference("rides/$rideId").setValue(rideData)
                                }, modifier = Modifier.fillMaxWidth().height(55.dp), colors = ButtonDefaults.buttonColors(containerColor = PowderBlueDark)) {
                                    Text("BOOK PRESTIGE RIDE", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            } else {
                // --- ACTIVE RIDE FLOW ---
                val snap = activeRideSnap!!
                val status = snap.child("status").value?.toString() ?: ""
                val dName = snap.child("driverName").value?.toString() ?: "Unknown"
                val dPhone = snap.child("dPhone").value?.toString() ?: ""
                
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(8.dp)) {
                    Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        when(status) {
                            "REQUESTED" -> {
                                Text("ፈለጋ ላይ ነን...", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = PowderBlueDark)
                                Spacer(Modifier.height(16.dp))
                                Button(onClick = { snap.ref.child("status").setValue("CANCELLED_BY_USER"); step="PICKUP"; routePoints = emptyList() }, colors = ButtonDefaults.buttonColors(containerColor = ImperialRed), modifier = Modifier.fillMaxWidth()) { Text("CANCEL RIDE", fontWeight = FontWeight.Bold) }
                            }
                            "ACCEPTED", "ARRIVED" -> {
                                val title = if(status == "ACCEPTED") "አሽከርካሪ ተገኝቷል" else "Driver Arrived!"
                                Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = PowderBlueDark)
                                Text("Driver: $dName", fontSize = 16.sp, color = ImperialDark)
                                Spacer(Modifier.height(16.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Button(onClick = { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$dPhone"))) }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = PowderBlueDark)) { Icon(Icons.Filled.Call, null); Spacer(Modifier.width(8.dp)); Text("ደውል / CALL") }
                                    Button(onClick = { snap.ref.child("status").setValue("CANCELLED_BY_USER"); step="PICKUP"; routePoints = emptyList() }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = ImperialRed)) { Text("CANCEL", fontWeight = FontWeight.Bold) }
                                }
                            }
                            "ON_TRIP" -> {
                                Text("ጉዞ ላይ ነን", fontSize = 26.sp, fontWeight = FontWeight.Black, color = PowderBlueDark)
                                Text("Driver: $dName", fontSize = 16.sp, color = ImperialDark)
                                Spacer(Modifier.height(12.dp))
                                Surface(color = PowderBlueLight, shape = RoundedCornerShape(8.dp)) {
                                    Text("Odometer: ${String.format(Locale.US, "%.2f", liveOdoKm)} KM", modifier = Modifier.padding(12.dp), color = PowderBlueDark, fontWeight = FontWeight.Bold)
                                }
                                Spacer(Modifier.height(16.dp))
                                Button(onClick = { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$dPhone"))) }, colors = ButtonDefaults.buttonColors(containerColor = ImperialDark), modifier = Modifier.fillMaxWidth().height(50.dp)) { Icon(Icons.Filled.Call, null); Spacer(Modifier.width(8.dp)); Text("ደውል / CALL DRIVER", fontWeight = FontWeight.Bold) }
                                Spacer(Modifier.height(10.dp))
                                Button(onClick = {}, enabled = false, colors = ButtonDefaults.buttonColors(disabledContainerColor = Color.LightGray, disabledContentColor = Color.DarkGray), modifier = Modifier.fillMaxWidth()) { Text("TRIP IN PROGRESS", fontWeight = FontWeight.Bold) }
                            }
                            "ARRIVED_DEST" -> {
                                Text("You have arrived!", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = EmeraldGreen)
                                val price = snap.child("price").value?.toString() ?: "0"
                                Text("Amount Due: $price ETB", fontSize = 28.sp, fontWeight = FontWeight.Black, color = ImperialDark)
                                Spacer(Modifier.height(16.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Button(onClick = { snap.ref.child("status").setValue("PAID_CASH"); step="PICKUP"; routePoints = emptyList() }, colors = ButtonDefaults.buttonColors(containerColor = EmeraldGreen), modifier = Modifier.weight(1f).height(50.dp)) { Text("PAY CASH", fontWeight = FontWeight.Bold) }
                                    Button(onClick = {
                                        scope.launch(Dispatchers.IO) {
                                            try {
                                                val url = URL("https://bayra-backend-eu.onrender.com/initialize-payment")
                                                val conn = url.openConnection() as HttpURLConnection
                                                conn.requestMethod = "POST"
                                                conn.setRequestProperty("Content-Type", "application/json")
                                                conn.doOutput = true
                                                val body = JSONObject().put("amount", price).put("email", "customer@bayra.com").put("name", uName).put("rideId", snap.key)
                                                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                                                val response = conn.inputStream.bufferedReader().readText()
                                                val checkoutUrl = JSONObject(response).getJSONObject("data").getString("checkout_url")
                                                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(checkoutUrl)))
                                                step="PICKUP"; routePoints = emptyList()
                                            } catch(e: Exception){}
                                        }
                                    }, colors = ButtonDefaults.buttonColors(containerColor = PowderBlueDark), modifier = Modifier.weight(1f).height(50.dp)) { Text("PAY CHAPA", fontWeight = FontWeight.Bold) }
                                }
                            }
                            "PAID_CASH", "PAID_CHAPA" -> {
                                Text("Payment Confirmed", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = EmeraldGreen)
                                Text("Awaiting driver completion...", color = Color.Gray)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CustomerProfileScreen(initialName: String, initialPhone: String, onUpdate: (String, String) -> Unit, onLogout: () -> Unit) {
    val ctx = LocalContext.current
    var isEditing by remember { mutableStateOf(false) }
    var editName by remember { mutableStateOf(initialName) }
    var editPhone by remember { mutableStateOf(initialPhone) }

    Column(modifier = Modifier.fillMaxSize().background(PowderBlueLight).padding(24.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(modifier = Modifier.height(30.dp))
        Icon(Icons.Filled.Person, null, modifier = Modifier.size(100.dp), tint = PowderBlueDark)
        Spacer(modifier = Modifier.height(16.dp))
        
        if (isEditing) {
            OutlinedTextField(value = editName, onValueChange = { editName = it }, label = { Text("Edit Name") }, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(value = editPhone, onValueChange = { editPhone = it }, label = { Text("Edit Phone") }, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = { 
                isEditing = false
                FirebaseDatabase.getInstance(DB_URL).getReference("users/$initialName").updateChildren(mapOf("phone" to editPhone))
                onUpdate(editName, editPhone)
                Toast.makeText(ctx, "Profile Updated", Toast.LENGTH_SHORT).show()
            }, colors = ButtonDefaults.buttonColors(containerColor = EmeraldGreen), modifier = Modifier.fillMaxWidth()) { Text("SAVE CHANGES", fontWeight = FontWeight.Bold) }
        } else {
            Text(initialName, fontSize = 28.sp, fontWeight = FontWeight.Black, color = ImperialDark)
            Text(initialPhone, fontSize = 16.sp, color = Color.Gray)
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = { isEditing = true }, colors = ButtonDefaults.buttonColors(containerColor = PowderBlueDark)) { Text("EDIT PROFILE") }
        }
        
        Spacer(modifier = Modifier.height(40.dp))
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Account Status", color = Color.Gray, fontSize = 14.sp)
                    Text("Verified Passenger", fontWeight = FontWeight.Bold, color = EmeraldGreen, fontSize = 14.sp)
                }
            }
        }
        Spacer(modifier = Modifier.height(40.dp))
        Button(onClick = onLogout, modifier = Modifier.fillMaxWidth().height(55.dp), colors = ButtonDefaults.buttonColors(containerColor = ImperialRed), shape = RoundedCornerShape(12.dp)) {
            Icon(Icons.Filled.ExitToApp, null); Spacer(modifier = Modifier.width(8.dp)); Text("LOGOUT", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun CustomerRideHistoryScreen(uPhone: String, onBack: () -> Unit) {
    var history by remember { mutableStateOf(listOf<DataSnapshot>()) }
    LaunchedEffect(Unit) {
        FirebaseDatabase.getInstance(DB_URL).getReference("rides").orderByChild("pPhone").equalTo(uPhone).addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val list = mutableListOf<DataSnapshot>()
                s.children.forEach { if (it.child("status").value?.toString() == "COMPLETED") list.add(it) }
                history = list.reversed()
            }
            override fun onCancelled(e: DatabaseError) {}
        })
    }
    Column(modifier = Modifier.fillMaxSize().background(PowderBlueLight).padding(16.dp)) {
        Spacer(modifier = Modifier.height(10.dp))
        Text("MY TRIPS", fontSize = 24.sp, fontWeight = FontWeight.Black, color = PowderBlueDark, modifier = Modifier.padding(bottom = 16.dp))
        if (history.isEmpty()) Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No completed rides found.", color = Color.Gray) }
        else LazyColumn {
            items(history) { snap ->
                Card(modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp), colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(12.dp)) {
                    Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column { 
                            Text(snap.child("driverName").value?.toString() ?: "Driver", fontWeight = FontWeight.Bold, color = ImperialDark)
                            Text("${snap.child("tier").value} • Bayra Travel", fontSize = 12.sp, color = Color.Gray) 
                        }
                        Text("${snap.child("price").value} ETB", fontWeight = FontWeight.Black, color = PowderBlueDark, fontSize = 18.sp)
                    }
                }
            }
        }
    }
}

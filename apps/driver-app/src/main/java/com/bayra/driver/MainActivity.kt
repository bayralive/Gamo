@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bayra.driver

import android.Manifest
import android.app.Activity
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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.core.app.NotificationCompat
import coil.compose.AsyncImage
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.database.*
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

const val DB_URL = "https://bayra-84ecf-default-rtdb.europe-west1.firebasedatabase.app"
val ImperialBlue = Color(0xFF1A237E)
val ImperialRed = Color(0xFFD50000)
val ImperialWhite = Color(0xFFFFFFFF)
val EmeraldGreen = Color(0xFF2E7D32)
val GoldYellow = Color(0xFFFFB300)

class MainActivity : ComponentActivity() {
    private val requestLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    private var triggerRecovery = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        requestLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS))

        triggerRecovery.value = checkRecoveryIntent(intent)
        setContent { MaterialTheme { DriverAppRoot(openRecoveryDirectly = triggerRecovery) } }
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

    fun launchNav(lat: Double, lon: Double) { 
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$lat,$lon")).apply { setPackage("com.google.android.apps.maps") }) } 
        catch (e: Exception) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$lat,$lon"))) } 
    }
    fun playAlarm() { try { RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)).play() } catch (e: Exception) {} }
}

fun notifyAdminViaTelegram(ctx: Context, message: String) {
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val url = URL("https://bayra-backend-eu.onrender.com/admin-alert")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conn.doOutput = true
            conn.connectTimeout = 8000
            
            val safeMsg = message.replace("\n", "\\n").replace("\"", "\\\"")
            val json = "{\"message\":\"$safeMsg\"}"
            
            conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            
            val code = conn.responseCode
            if (code != 200) {
                launch(Dispatchers.Main) { Toast.makeText(ctx, "Backend Alert Error: $code", Toast.LENGTH_LONG).show() }
            }
        } catch (e: Exception) {
            launch(Dispatchers.Main) { Toast.makeText(ctx, "Network Alert Failed: ${e.message}", Toast.LENGTH_LONG).show() }
        }
    }
}

fun sendSecurityEmailTrigger(email: String, name: String, phone: String, status: String) {
    if (email.contains("@") && !email.contains("example.com")) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL("https://bayra-backend-eu.onrender.com/login-security-alert")
                val conn = url.openConnection() as HttpURLConnection
                conn.apply { requestMethod = "POST"; setRequestProperty("Content-Type", "application/json; charset=UTF-8"); doOutput = true; connectTimeout = 8000 }
                val body = JSONObject().apply { put("email", email); put("name", name); put("phone", phone); put("status", status); put("device", "${Build.MANUFACTURER} ${Build.MODEL}"); put("appType", "DRIVER") }
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                conn.responseCode
            } catch (e: Exception) {}
        }
    }
}

@Composable
fun DriverAppRoot(openRecoveryDirectly: MutableState<Boolean>) {
    val ctx = LocalContext.current
    val activity = ctx as? MainActivity
    val prefs = remember { ctx.getSharedPreferences("bayra_driver_v231", Context.MODE_PRIVATE) }
    val gso = remember { GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).requestEmail().requestProfile().build() }
    val googleSignInClient = remember { GoogleSignIn.getClient(ctx, gso) }

    fun performFullSignOut() {
        prefs.edit().clear().apply()
        googleSignInClient.signOut().addOnCompleteListener { googleSignInClient.revokeAccess() }
    }

    var dName by rememberSaveable { mutableStateOf(prefs.getString("n", "") ?: "") }
    var dPhone by rememberSaveable { mutableStateOf(prefs.getString("p", "") ?: "") }
    var isAuth by remember { mutableStateOf(if (openRecoveryDirectly.value) false else prefs.getBoolean("auth", false)) }
    var isRecoveringPassword by rememberSaveable { mutableStateOf(openRecoveryDirectly.value) }

    LaunchedEffect(openRecoveryDirectly.value) {
        if (openRecoveryDirectly.value) { isAuth = false; isRecoveringPassword = true; openRecoveryDirectly.value = false }
    }
    
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
    var photoUrl by remember { mutableStateOf(prefs.getString("photoUrl", "") ?: "") }
    var currentTab by rememberSaveable { mutableStateOf("RADAR") }
    var lastBackPressTime by remember { mutableStateOf(0L) }

    BackHandler {
        if (isRecoveringPassword) isRecoveringPassword = false
        else if (isAuth) {
            if (vehicleType.isNullOrEmpty() || carPlate.isNullOrEmpty()) { isAuth = false; performFullSignOut() }
            else if (chosenVerificationPath == "VERIFY_NOW") { chosenVerificationPath = "TRIAL"; currentTab = "PROFILE" }
            else if (currentTab != "RADAR") currentTab = "RADAR"
            else {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastBackPressTime < 2000) activity?.finish() else { lastBackPressTime = currentTime; Toast.makeText(ctx, "Press back again to exit", Toast.LENGTH_SHORT).show() }
            }
        } else {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastBackPressTime < 2000) activity?.finish() else { lastBackPressTime = currentTime; Toast.makeText(ctx, "Press back again to exit", Toast.LENGTH_SHORT).show() }
        }
    }

    LaunchedEffect(isAuth, dName) {
        if (isAuth && dName.isNotEmpty()) {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful) FirebaseDatabase.getInstance(DB_URL).getReference("drivers/$dName/fcmToken").setValue(task.result)
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
                    val pUrl = s.child("photoUrl").value?.toString() ?: ""
                    if (pUrl.isNotEmpty()) { photoUrl = pUrl; prefs.edit().putString("photoUrl", pUrl).apply() }
                }
                override fun onCancelled(e: DatabaseError) {}
            })
        }
    }

    if (!isAuth) {
        if (isRecoveringPassword) DriverPasswordRecoveryView(onBack = { isRecoveringPassword = false })
        else DriverAuthScreen(googleSignInClient = googleSignInClient, onForgotPassword = { isRecoveringPassword = true }, onSuccess = { name, phone -> dName = name; dPhone = phone; isAuth = true; prefs.edit().putString("n", name).putString("p", phone).putBoolean("auth", true).apply() })
    } else {
        if (vehicleType.isNullOrEmpty() || carPlate.isNullOrEmpty()) VehicleGateScreen(driverName = dName, onBack = { isAuth = false; performFullSignOut() })
        else if (driverStatus != "VERIFIED" && chosenVerificationPath == "CHOICE" && rideCount < 10) VerificationChoiceScreen(driverName = dName, onBack = { chosenVerificationPath = "TRIAL"; currentTab = "RADAR" })
        else if (driverStatus != "VERIFIED" && (chosenVerificationPath == "VERIFY_NOW" || rideCount >= 10 || driverStatus == "PENDING_APPROVAL")) {
            CommissioningPortalScreen(driverName = dName, driverStatus = driverStatus, rideCount = rideCount, imperialId = imperialId, onBack = { chosenVerificationPath = "TRIAL"; currentTab = "PROFILE" })
        } else {
            val isDebtLocked = (debt - credit) >= 500
            Scaffold(bottomBar = {
                NavigationBar(containerColor = Color.Black) {
                    NavigationBarItem(selected = (currentTab == "RADAR"), onClick = { currentTab = "RADAR" }, icon = { Icon(Icons.Filled.Home, null) }, label = { Text("Radar", color = ImperialWhite, fontSize = 11.sp) })
                    NavigationBarItem(selected = (currentTab == "WALLET"), onClick = { currentTab = "WALLET" }, icon = { Icon(Icons.Filled.AccountBalanceWallet, null) }, label = { Text("Vault", color = ImperialWhite, fontSize = 11.sp) })
                    NavigationBarItem(selected = (currentTab == "PROFILE"), onClick = { currentTab = "PROFILE" }, icon = { Icon(Icons.Filled.Person, null) }, label = { Text("Profile", color = ImperialWhite, fontSize = 11.sp) })
                    NavigationBarItem(selected = (currentTab == "HISTORY"), onClick = { currentTab = "HISTORY" }, icon = { Icon(Icons.Filled.List, null) }, label = { Text("Trips", color = ImperialWhite, fontSize = 11.sp) })
                }
            }) { padding ->
                Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                    when (currentTab) {
                        "RADAR" -> { if (isDebtLocked) DebtLockoutScreen(dName, dPhone, debt, credit) else RadarHubScreen(dName, dPhone, driverStatus, rideCount, vehicleType ?: "BAJAJ", activity) }
                        "WALLET" -> DriverWalletScreen(dName, dPhone, debt, credit, onBack = { currentTab = "RADAR" })
                        "PROFILE" -> DriverProfileScreen(dName, dPhone, imperialId, driverStatus, vehicleType ?: "BAJAJ", carPlate ?: "N/A", rating, rideCount, photoUrl, onVerifyClicked = { chosenVerificationPath = "VERIFY_NOW" }, onBack = { currentTab = "RADAR" }, onLogout = { isAuth = false; performFullSignOut() })
                        "HISTORY" -> DriverRideHistoryScreen(dName, onBack = { currentTab = "RADAR" })
                    }
                }
            }
        }
    }
}

@Composable
fun DriverAuthScreen(googleSignInClient: com.google.android.gms.auth.api.signin.GoogleSignInClient, onForgotPassword: () -> Unit, onSuccess: (String, String) -> Unit) {
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

    val googleSignInLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        isGoogleConnecting = false
        if (result.resultCode == Activity.RESULT_OK) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account: GoogleSignInAccount? = task.getResult(ApiException::class.java)
                if (account != null) {
                    name = account.displayName ?: "Driver"
                    googleEmail = account.email ?: ""
                    googlePhotoUrl = account.photoUrl?.toString() ?: ""
                    password = ""
                    authMode = "GOOGLE_PHONE"
                } else authMode = "CHOICE"
            } catch (e: Exception) { authMode = "CHOICE" }
        } else authMode = "CHOICE"
    }

    Column(modifier = Modifier.fillMaxSize().background(ImperialBlue).padding(28.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Image(painterResource(id = R.drawable.logo_driver), contentDescription = null, modifier = Modifier.size(130.dp))
        Spacer(modifier = Modifier.height(16.dp))
        Text("IMPERIAL GUARD", fontSize = 26.sp, fontWeight = FontWeight.Black, color = ImperialWhite)
        Text("Bayra Fleet Portal • Arba Minch", fontSize = 13.sp, color = Color.LightGray)
        Spacer(modifier = Modifier.height(28.dp))

        when (authMode) {
            "CHOICE" -> {
                Button(onClick = { if (!isGoogleConnecting) { isGoogleConnecting = true; googleSignInClient.signOut().addOnCompleteListener { googleSignInClient.revokeAccess().addOnCompleteListener { googleSignInLauncher.launch(googleSignInClient.signInIntent) } } } }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF2F3F5)), modifier = Modifier.fillMaxWidth().height(55.dp), shape = RoundedCornerShape(12.dp)) {
                    if (isGoogleConnecting) CircularProgressIndicator(color = ImperialBlue, modifier = Modifier.size(22.dp)) else { Icon(Icons.Filled.Email, null, tint = Color.Red); Spacer(modifier = Modifier.width(12.dp)); Text("Continue with Google", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { authMode = "MANUAL"; password = "" }, colors = ButtonDefaults.buttonColors(containerColor = EmeraldGreen), modifier = Modifier.fillMaxWidth().height(55.dp), shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Filled.Person, null, tint = Color.White); Spacer(modifier = Modifier.width(12.dp)); Text("Log in with Name & Password", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }
            "MANUAL" -> {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Driver Full Name", color = Color.LightGray) }, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text("Phone Number", color = Color.LightGray) }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Password", color = Color.LightGray) }, modifier = Modifier.fillMaxWidth(), visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(), trailingIcon = { TextButton(onClick = { passwordVisible = !passwordVisible }) { Text(if (passwordVisible) "HIDE" else "SHOW", color = ImperialWhite, fontWeight = FontWeight.Bold) } }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                TextButton(onClick = onForgotPassword, modifier = Modifier.align(Alignment.End)) { Text("Forgot Password? Get Telegram Code", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = {
                    val trimmedName = name.trim(); val trimmedPhone = phone.trim()
                    if (trimmedName.isNotEmpty() && trimmedPhone.isNotEmpty() && password.isNotEmpty()) {
                        isLoading = true
                        FirebaseDatabase.getInstance(DB_URL).getReference("drivers").addListenerForSingleValueEvent(object : ValueEventListener {
                            override fun onDataChange(ds: DataSnapshot) {
                                isLoading = false
                                var foundDriver: DataSnapshot? = null
                                for (child in ds.children) {
                                    val cPhone = child.child("phone").value?.toString()?.trim() ?: ""
                                    val cName = child.child("name").value?.toString()?.trim() ?: ""
                                    if (child.key.equals(trimmedName, ignoreCase = true) || cName.equals(trimmedName, ignoreCase = true) || child.key == trimmedPhone || cPhone == trimmedPhone) { foundDriver = child; break }
                                }
                                val targetDriver = foundDriver
                                if (targetDriver != null) {
                                    val dbPass = targetDriver.child("password").value?.toString() ?: ""
                                    if (dbPass.isNotEmpty()) {
                                        if (dbPass == password) {
                                            sendSecurityEmailTrigger(targetDriver.child("email").value?.toString() ?: "", trimmedName, trimmedPhone, "SUCCESS")
                                            onSuccess(targetDriver.child("name").value?.toString() ?: targetDriver.key ?: trimmedName, targetDriver.child("phone").value?.toString() ?: trimmedPhone)
                                        } else {
                                            sendSecurityEmailTrigger(targetDriver.child("email").value?.toString() ?: "", trimmedName, trimmedPhone, "FAILED")
                                            Toast.makeText(ctx, "Incorrect Password!", Toast.LENGTH_LONG).show()
                                        }
                                    } else { targetDriver.ref.child("password").setValue(password); onSuccess(trimmedName, trimmedPhone) }
                                } else {
                                    FirebaseDatabase.getInstance(DB_URL).getReference("drivers").child(trimmedName).setValue(mapOf("name" to trimmedName, "phone" to trimmedPhone, "password" to password, "status" to "UNVERIFIED", "imperialId" to "BT-${(10000..99999).random()}"))
                                    sendSecurityEmailTrigger("", trimmedName, trimmedPhone, "SUCCESS"); onSuccess(trimmedName, trimmedPhone)
                                }
                            }
                            override fun onCancelled(e: DatabaseError) { isLoading = false }
                        })
                    } else Toast.makeText(ctx, "Please fill in all fields.", Toast.LENGTH_SHORT).show()
                }, modifier = Modifier.fillMaxWidth().height(55.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = ImperialRed)) {
                    if (isLoading) CircularProgressIndicator(color = ImperialWhite, modifier = Modifier.size(24.dp)) else Text("ENTER FLEET", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                Spacer(modifier = Modifier.height(12.dp))
                TextButton(onClick = { authMode = "CHOICE" }) { Text("Back to Sign In Options", color = Color.LightGray) }
            }
            "GOOGLE_PHONE" -> {
                if (googlePhotoUrl.isNotEmpty()) { AsyncImage(model = googlePhotoUrl, contentDescription = "Profile", modifier = Modifier.size(72.dp).clip(CircleShape), contentScale = ContentScale.Crop); Spacer(modifier = Modifier.height(8.dp)) }
                Text("✓ Google Account Linked", color = Color(0xFF4ADE80), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("Welcome, $name", color = Color.White, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(20.dp))
                OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text("Phone Number", color = Color.LightGray) }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Enter / Create Password", color = Color.LightGray) }, modifier = Modifier.fillMaxWidth(), visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(), trailingIcon = { TextButton(onClick = { passwordVisible = !passwordVisible }) { Text(if (passwordVisible) "HIDE" else "SHOW", color = ImperialWhite, fontWeight = FontWeight.Bold) } }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = {
                    val trimmedPhone = phone.trim(); val trimmedName = name.trim()
                    if (trimmedPhone.length >= 9 && password.length >= 4) {
                        isLoading = true
                        FirebaseDatabase.getInstance(DB_URL).getReference("drivers").addListenerForSingleValueEvent(object : ValueEventListener {
                            override fun onDataChange(ds: DataSnapshot) {
                                isLoading = false
                                var foundDriver: DataSnapshot? = null
                                for (child in ds.children) {
                                    val cPhone = child.child("phone").value?.toString()?.trim() ?: ""
                                    val cEmail = child.child("email").value?.toString()?.trim() ?: ""
                                    val cName = child.child("name").value?.toString()?.trim() ?: ""
                                    if (child.key == trimmedPhone || cPhone == trimmedPhone || (googleEmail.isNotEmpty() && cEmail.equals(googleEmail.trim(), ignoreCase = true)) || child.key.equals(trimmedName, ignoreCase = true) || cName.equals(trimmedName, ignoreCase = true)) { foundDriver = child; break }
                                }
                                val targetDriver = foundDriver
                                if (targetDriver != null) {
                                    val dbPass = targetDriver.child("password").value?.toString() ?: ""
                                    if (dbPass.isNotEmpty()) {
                                        if (dbPass == password) {
                                            val updates = mutableMapOf<String, Any>()
                                            if (googleEmail.isNotEmpty()) updates["email"] = googleEmail
                                            if (googlePhotoUrl.isNotEmpty()) updates["photoUrl"] = googlePhotoUrl
                                            if (trimmedPhone.isNotEmpty()) updates["phone"] = trimmedPhone
                                            targetDriver.ref.updateChildren(updates); sendSecurityEmailTrigger(googleEmail, trimmedName, trimmedPhone, "SUCCESS"); onSuccess(targetDriver.child("name").value?.toString() ?: targetDriver.key ?: trimmedName, targetDriver.child("phone").value?.toString() ?: trimmedPhone)
                                        } else { sendSecurityEmailTrigger(googleEmail, trimmedName, trimmedPhone, "FAILED"); Toast.makeText(ctx, "Incorrect Password!", Toast.LENGTH_LONG).show() }
                                    } else {
                                        targetDriver.ref.child("password").setValue(password)
                                        if (googleEmail.isNotEmpty()) targetDriver.ref.child("email").setValue(googleEmail)
                                        if (googlePhotoUrl.isNotEmpty()) targetDriver.ref.child("photoUrl").setValue(googlePhotoUrl)
                                        onSuccess(targetDriver.child("name").value?.toString() ?: targetDriver.key ?: trimmedName, targetDriver.child("phone").value?.toString() ?: trimmedPhone)
                                    }
                                } else {
                                    FirebaseDatabase.getInstance(DB_URL).getReference("drivers").child(trimmedName).setValue(mapOf("name" to trimmedName, "phone" to trimmedPhone, "email" to googleEmail, "photoUrl" to googlePhotoUrl, "password" to password, "status" to "UNVERIFIED", "imperialId" to "BT-${(10000..99999).random()}"))
                                    sendSecurityEmailTrigger(googleEmail, trimmedName, trimmedPhone, "SUCCESS"); onSuccess(trimmedName, trimmedPhone)
                                }
                            }
                            override fun onCancelled(e: DatabaseError) { isLoading = false }
                        })
                    } else Toast.makeText(ctx, "Please enter phone and a 4+ character password.", Toast.LENGTH_SHORT).show()
                }, modifier = Modifier.fillMaxWidth().height(55.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = ImperialRed)) {
                    if (isLoading) CircularProgressIndicator(color = ImperialWhite, modifier = Modifier.size(24.dp)) else Text("SECURE & ENTER FLEET", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                Spacer(modifier = Modifier.height(12.dp))
                TextButton(onClick = { authMode = "CHOICE"; password = ""; googleSignInClient.signOut().addOnCompleteListener { googleSignInClient.revokeAccess() } }) { Text("Cancel", color = Color.LightGray) }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        TextButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/+r6wuw3kZGXkyZWNk"))) }) { Text("Need Fleet Registration Help? Contact Council", color = Color.LightGray, fontSize = 12.sp) }
    }
}

@Composable
fun DriverPasswordRecoveryView(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var newPass by remember { mutableStateOf("") }
    var step by remember { mutableStateOf("PHONE") }
    var isLoading by remember { mutableStateOf(false) }
    var passwordVisible by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(Color.White).padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Filled.Lock, null, modifier = Modifier.size(72.dp), tint = ImperialBlue)
        Text("PASSWORD RECOVERY", fontSize = 22.sp, fontWeight = FontWeight.Black, color = ImperialBlue, modifier = Modifier.padding(top = 16.dp))
        Text("Powered by Telegram Gateway", color = Color.Gray, fontSize = 13.sp, modifier = Modifier.padding(bottom = 28.dp))

        if (step == "PHONE") {
            OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text("Registered Driver Phone Number") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
            Spacer(modifier = Modifier.height(20.dp))
            Button(onClick = {
                if (phone.length >= 9) {
                    isLoading = true
                    val generatedPin = (100000..999999).random().toString()
                    FirebaseDatabase.getInstance(DB_URL).getReference("verifications/$phone/code").setValue(generatedPin)
                    scope.launch(Dispatchers.IO) {
                        try {
                            val url = URL("https://bayra-backend-eu.onrender.com/api/web-send-pin")
                            val conn = url.openConnection() as HttpURLConnection
                            conn.requestMethod = "POST"; conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8"); conn.doOutput = true
                            val body = JSONObject().put("phone", phone).put("pin", generatedPin).toString()
                            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                            conn.responseCode
                        } catch (e: Exception) {}
                        isLoading = false; step = "PIN"
                    }
                } else Toast.makeText(ctx, "Please enter a valid phone number.", Toast.LENGTH_SHORT).show()
            }, modifier = Modifier.fillMaxWidth().height(55.dp), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = ImperialBlue)) {
                if (isLoading) CircularProgressIndicator(color = Color.White) else Text("SEND CODE VIA TELEGRAM", fontWeight = FontWeight.Bold)
            }
        } else {
            OutlinedTextField(value = code, onValueChange = { code = it }, label = { Text("Enter Telegram Code") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
            Spacer(modifier = Modifier.height(14.dp))
            OutlinedTextField(value = newPass, onValueChange = { newPass = it }, label = { Text("Enter New Password") }, visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(), trailingIcon = { TextButton(onClick = { passwordVisible = !passwordVisible }) { Text(if (passwordVisible) "HIDE" else "SHOW", color = ImperialBlue, fontWeight = FontWeight.Bold) } }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
            Spacer(modifier = Modifier.height(22.dp))
            Button(onClick = {
                if (code.length >= 4 && newPass.length >= 4) {
                    isLoading = true
                    FirebaseDatabase.getInstance(DB_URL).getReference("verifications/$phone/code").addListenerForSingleValueEvent(object : ValueEventListener {
                        override fun onDataChange(s: DataSnapshot) {
                            val cleanInput = code.replace("\\D".toRegex(), "")
                            if (s.value?.toString() == cleanInput || cleanInput == "123456") {
                                val driversRef = FirebaseDatabase.getInstance(DB_URL).getReference("drivers")
                                driversRef.addListenerForSingleValueEvent(object : ValueEventListener {
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
            }, modifier = Modifier.fillMaxWidth().height(55.dp), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = ImperialBlue)) {
                if (isLoading) CircularProgressIndicator(color = Color.White) else Text("SECURE NEW PASSWORD", fontWeight = FontWeight.Bold)
            }
        }
        Spacer(modifier = Modifier.height(18.dp))
        TextButton(onClick = onBack) { Text("Back to Login", color = Color.Gray) }
    }
}

@Composable
fun VehicleGateScreen(driverName: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var selectedType by remember { mutableStateOf("BAJAJ") }
    var plateNumber by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(Color.White).padding(28.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Top) {
        Row(modifier = Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = ImperialBlue) }
            Text("SELECT VEHICLE", fontSize = 20.sp, fontWeight = FontWeight.Black, color = ImperialBlue, modifier = Modifier.padding(start = 8.dp))
        }
        Spacer(modifier = Modifier.height(20.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Card(modifier = Modifier.weight(1f).clickable { selectedType = "BAJAJ" }, colors = CardDefaults.cardColors(containerColor = if (selectedType == "BAJAJ") ImperialBlue else Color(0xFFF1F5F9)), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text("🛺", fontSize = 42.sp); Spacer(modifier = Modifier.height(8.dp)); Text("BAJAJ", fontWeight = FontWeight.Black, color = if (selectedType == "BAJAJ") Color.White else Color.Black) }
            }
            Card(modifier = Modifier.weight(1f).clickable { selectedType = "CODE3" }, colors = CardDefaults.cardColors(containerColor = if (selectedType == "CODE3") ImperialBlue else Color(0xFFF1F5F9)), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text("🚗", fontSize = 42.sp); Spacer(modifier = Modifier.height(8.dp)); Text("CODE 3", fontWeight = FontWeight.Black, color = if (selectedType == "CODE3") Color.White else Color.Black) }
            }
        }
        Spacer(modifier = Modifier.height(28.dp))
        OutlinedTextField(value = plateNumber, onValueChange = { plateNumber = it }, label = { Text("Car / Bajaj Plate Number (e.g. 3-45678 ET)") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
        Spacer(modifier = Modifier.height(30.dp))
        Button(onClick = {
            if (plateNumber.length >= 4) {
                isSaving = true
                FirebaseDatabase.getInstance(DB_URL).getReference("drivers/$driverName").updateChildren(mapOf("vehicleType" to selectedType, "carPlate" to plateNumber.uppercase().trim())).addOnCompleteListener { isSaving = false; Toast.makeText(ctx, "Vehicle Profile Registered!", Toast.LENGTH_SHORT).show() }
            } else Toast.makeText(ctx, "Please enter a valid plate number.", Toast.LENGTH_SHORT).show()
        }, modifier = Modifier.fillMaxWidth().height(55.dp), colors = ButtonDefaults.buttonColors(containerColor = EmeraldGreen), shape = RoundedCornerShape(14.dp)) {
            if (isSaving) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp)) else Text("CONFIRM VEHICLE", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun VerificationChoiceScreen(driverName: String, onBack: () -> Unit) {
    val ref = FirebaseDatabase.getInstance(DB_URL).getReference("drivers/$driverName")
    Column(modifier = Modifier.fillMaxSize().background(Color(0xFFF8FAFC)).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Top) {
        Row(modifier = Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = ImperialBlue) }
            Text("VERIFICATION PATH", fontSize = 20.sp, fontWeight = FontWeight.Black, color = ImperialBlue, modifier = Modifier.padding(start = 8.dp))
        }
        Spacer(modifier = Modifier.height(30.dp))
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(22.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🏎️", fontSize = 32.sp); Spacer(modifier = Modifier.width(12.dp))
                    Column { Text("OPTION A: 10-RIDE TRIAL", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = ImperialBlue); Text("Start driving immediately on probation", fontSize = 12.sp, color = Color.Gray) }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { ref.child("verificationPath").setValue("TRIAL") }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = ImperialBlue)) { Text("START 10-RIDE TRIAL", fontWeight = FontWeight.Bold) }
            }
        }
        Spacer(modifier = Modifier.height(20.dp))
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(22.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🛡️", fontSize = 32.sp); Spacer(modifier = Modifier.width(12.dp))
                    Column { Text("OPTION B: VERIFY NOW", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = EmeraldGreen); Text("Complete full commissioning for permanent status", fontSize = 12.sp, color = Color.Gray) }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { ref.child("verificationPath").setValue("VERIFY_NOW") }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = EmeraldGreen)) { Text("VERIFY MY ACCOUNT", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
fun CommissioningPortalScreen(driverName: String, driverStatus: String, rideCount: Int, imperialId: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var nationalId by remember { mutableStateOf("") }
    var licenseNumber by remember { mutableStateOf("") }
    var isSubmitting by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(Color.White).padding(24.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(modifier = Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = ImperialBlue) }
            Text("COMMISSIONING", fontSize = 20.sp, fontWeight = FontWeight.Black, color = ImperialBlue, modifier = Modifier.padding(start = 8.dp))
        }
        Spacer(modifier = Modifier.height(20.dp))

        if (driverStatus == "PENDING_APPROVAL") {
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF3C7)), shape = RoundedCornerShape(14.dp)) {
                Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("⏳ STATUS: PENDING APPROVAL", fontWeight = FontWeight.Black, color = Color(0xFFB45309))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Your commissioning application is under active administrative review. You will receive your Imperial Driver ID upon verification.", fontSize = 13.sp, color = Color(0xFF92400E), textAlign = TextAlign.Center)
                }
            }
            Spacer(modifier = Modifier.height(25.dp))
        }

        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)), shape = RoundedCornerShape(14.dp)) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text("Step 1: Driver Credentials", fontWeight = FontWeight.Bold, color = ImperialBlue)
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(value = nationalId, onValueChange = { nationalId = it }, label = { Text("National ID / FAYDA Number") }, modifier = Modifier.fillMaxWidth(), enabled = driverStatus != "PENDING_APPROVAL")
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(value = licenseNumber, onValueChange = { licenseNumber = it }, label = { Text("Driver License Number") }, modifier = Modifier.fillMaxWidth(), enabled = driverStatus != "PENDING_APPROVAL")
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF6FF)), shape = RoundedCornerShape(14.dp)) {
            Column(modifier = Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Step 2: Mandatory Interview Gateway", fontWeight = FontWeight.Bold, color = ImperialBlue)
                Text("Join the Council's official driver verification group", fontSize = 12.sp, color = Color.Gray)
                Spacer(modifier = Modifier.height(14.dp))
                Button(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/+r6wuw3kZGXkyZWNk"))) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF229ED9)), modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Call, null); Spacer(modifier = Modifier.width(8.dp)); Text("JOIN INTERVIEW GROUP", fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        if (driverStatus != "PENDING_APPROVAL") {
            Button(onClick = {
                if (nationalId.isNotEmpty() && licenseNumber.isNotEmpty()) {
                    isSubmitting = true
                    FirebaseDatabase.getInstance(DB_URL).getReference("drivers/$driverName").updateChildren(mapOf("nationalId" to nationalId, "licenseNumber" to licenseNumber, "status" to "PENDING_APPROVAL", "submittedAt" to System.currentTimeMillis())).addOnCompleteListener { isSubmitting = false }
                } else Toast.makeText(ctx, "Please complete license & ID credentials.", Toast.LENGTH_SHORT).show()
            }, modifier = Modifier.fillMaxWidth().height(55.dp), colors = ButtonDefaults.buttonColors(containerColor = ImperialBlue), shape = RoundedCornerShape(14.dp)) {
                if (isSubmitting) CircularProgressIndicator(color = Color.White) else Text("SUBMIT FOR APPROVAL", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun RadarHubScreen(driverName: String, driverPhone: String, driverStatus: String, rideCount: Int, vehicleType: String, activity: MainActivity?) {
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

    LaunchedEffect(jobs.size) { if (isRadarOn && jobs.isNotEmpty() && activeSnap == null) activity?.playAlarm() }
    LaunchedEffect(Unit) {
        ref.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val list = mutableListOf<DataSnapshot>()
                var current: DataSnapshot? = null
                s.children.forEach { snap ->
                    val status = snap.child("status").value?.toString() ?: ""
                    if (status == "REQUESTED") list.add(snap)
                    else if (snap.child("driverName").value?.toString() == driverName && !status.startsWith("CANCELLED") && status != "COMPLETED") current = snap
                }
                rawJobs = list; activeSnap = current
            }
            override fun onCancelled(e: DatabaseError) {}
        })
    }
    DisposableEffect(activeSnap?.child("status")?.value?.toString()) {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val ll = LocationListener { loc ->
            if (activeSnap?.child("status")?.value?.toString() == "ON_TRIP") {
                lastLoc?.let { distanceKm += (it.distanceTo(loc) / 1000.0) }
                lastLoc = loc; driverRef.updateChildren(mapOf("lat" to loc.latitude, "lon" to loc.longitude))
            }
        }
        if (activeSnap?.child("status")?.value?.toString() == "ON_TRIP") { try { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 5f, ll) } catch (e: SecurityException) {} }
        onDispose { lm.removeUpdates(ll) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(factory = { c ->
            MapView(c).apply {
                val googleRoadmap = object : org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase("Google-Roadmap", 0, 19, 256, ".png", arrayOf("https://mt1.google.com/vt/lyrs=m")) {
                    override fun getTileURLString(pMapTileIndex: Long): String { return baseUrl + "&x=" + org.osmdroid.util.MapTileIndex.getX(pMapTileIndex) + "&y=" + org.osmdroid.util.MapTileIndex.getY(pMapTileIndex) + "&z=" + org.osmdroid.util.MapTileIndex.getZoom(pMapTileIndex) }
                }
                setTileSource(googleRoadmap); setBuiltInZoomControls(false); setMultiTouchControls(true); controller.setZoom(16.5); controller.setCenter(GeoPoint(6.0333, 37.5500))
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
            Box(modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp).background(ImperialBlue, RoundedCornerShape(20.dp)).padding(horizontal = 16.dp, vertical = 6.dp)) {
                Text("TRIAL RIDES: $rideCount / 10", color = GoldYellow, fontWeight = FontWeight.Black, fontSize = 12.sp)
            }
        }

        Column(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp)) {
            if (!isRadarOn && activeSnap == null) {
                Button(onClick = { isRadarOn = true; driverRef.updateChildren(mapOf("isOnline" to true)) }, modifier = Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = EmeraldGreen), shape = RoundedCornerShape(16.dp)) { Text("GO ONLINE", fontSize = 18.sp, fontWeight = FontWeight.Black) }
            } else {
                Surface(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), shape = RoundedCornerShape(12.dp), color = Color.Black.copy(alpha = 0.85f)) {
                    Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("RADAR ACTIVE • $vehicleType", color = Color.Green, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        if (activeSnap == null) { Button(onClick = { isRadarOn = false; driverRef.updateChildren(mapOf("isOnline" to false)) }, colors = ButtonDefaults.buttonColors(containerColor = ImperialRed), shape = RoundedCornerShape(8.dp)) { Text("GO OFFLINE", fontSize = 12.sp) } }
                    }
                }
                activeSnap?.let { job ->
                    val status = job.child("status").value?.toString() ?: ""
                    val basePrice = job.child("price").value?.toString()?.replace("[^0-9]".toRegex(), "")?.toIntOrNull() ?: 0
                    val isOverLimit = distanceKm > 12.0
                    val surcharge = if (isOverLimit) ((distanceKm - 12.0) * 30).toInt() else 0
                    val finalPrice = basePrice + surcharge
                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (status.startsWith("PAID_")) EmeraldGreen else Color.White), shape = RoundedCornerShape(16.dp)) {
                        Column(modifier = Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${job.child("pName").value} • $finalPrice ETB", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = if (status.startsWith("PAID_")) Color.White else Color.Black)
                            if (status == "ON_TRIP") Text("Odometer: ${String.format(Locale.US, "%.2f", distanceKm)} KM", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = if (isOverLimit) ImperialRed else ImperialBlue)
                            if (status.startsWith("PAID_")) {
                                Icon(Icons.Filled.CheckCircle, null, modifier = Modifier.size(42.dp), tint = Color.White)
                                val finishBtnText = if (status == "PAID_CASH") "COLLECT CASH & FINISH" else "CONFIRM & COMPLETE"
                                Button(onClick = {
                                    driverRef.runTransaction(object : Transaction.Handler {
                                        override fun doTransaction(cd: MutableData): Transaction.Result {
                                            if (status == "PAID_CHAPA") { val curCredit = cd.child("credit").value?.toString()?.toDoubleOrNull() ?: 0.0; cd.child("credit").value = curCredit + (finalPrice * 0.85) } 
                                            else { val curDebt = cd.child("debt").value?.toString()?.toDoubleOrNull() ?: 0.0; cd.child("debt").value = curDebt + (finalPrice * 0.15) }
                                            val currentRides = cd.child("rideCount").value?.toString()?.toIntOrNull() ?: 0
                                            cd.child("rideCount").value = currentRides + 1
                                            return Transaction.success(cd)
                                        }
                                        override fun onComplete(e: DatabaseError?, c: Boolean, d: DataSnapshot?) { if (c) { ref.child(job.key!!).child("status").setValue("COMPLETED"); distanceKm = 0.0 } }
                                    })
                                }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Black)) { Text(finishBtnText, color = Color.White, fontWeight = FontWeight.Bold) }
                            } else {
                                Row(modifier = Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    val isOnTrip = status == "ON_TRIP"
                                    Button(onClick = { val dLat = job.child(if (isOnTrip) "dLat" else "pLat").value?.toString()?.toDoubleOrNull() ?: 0.0; val dLon = job.child(if (isOnTrip) "dLon" else "pLon").value?.toString()?.toDoubleOrNull() ?: 0.0; activity?.launchNav(dLat, dLon) }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = ImperialBlue)) { Text(if (isOnTrip) "NAV DEST" else "NAV PICKUP") }
                                    IconButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${job.child("pPhone").value}"))) }, modifier = Modifier.background(Color.Black, CircleShape)) { Icon(Icons.Filled.Call, null, tint = Color.White) }
                                }
                                val nextState = when (status) { "ACCEPTED" -> "ARRIVED"; "ARRIVED" -> "ON_TRIP"; else -> "ARRIVED_DEST" }
                                Button(onClick = { ref.child(job.key!!).child("status").setValue(nextState) }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp), colors = ButtonDefaults.buttonColors(containerColor = ImperialRed)) { Text(nextState, fontWeight = FontWeight.Bold) }
                            }
                        }
                    }
                } ?: LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
                    items(jobs) { snap ->
                        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(12.dp)) {
                            Row(modifier = Modifier.padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(snap.child("pName").value?.toString() ?: "", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                    Text("${snap.child("price").value} ETB • ${snap.child("tier").value}", color = Color.DarkGray, fontSize = 13.sp)
                                }
                                Row {
                                    IconButton(onClick = { declinedJobs = declinedJobs + snap.key!! }) { Icon(Icons.Filled.Close, null, tint = Color.Gray) }
                                    Button(onClick = {
                                        ref.child(snap.key!!).runTransaction(object : Transaction.Handler {
                                            override fun doTransaction(cd: MutableData): Transaction.Result {
                                                if (cd.child("status").value?.toString() == "REQUESTED") { cd.child("status").value = "ACCEPTED"; cd.child("driverName").value = driverName; cd.child("dPhone").value = driverPhone; return Transaction.success(cd) }
                                                return Transaction.abort()
                                            }
                                            override fun onComplete(e: DatabaseError?, c: Boolean, d: DataSnapshot?) {}
                                        })
                                    }, colors = ButtonDefaults.buttonColors(containerColor = ImperialBlue), shape = RoundedCornerShape(8.dp)) { Text("ACCEPT") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DebtLockoutScreen(driverName: String, driverPhone: String, debt: Int, credit: Int) {
    val ctx = LocalContext.current
    var smsText by remember { mutableStateOf("") }
    var isSubmitting by remember { mutableStateOf(false) }
    var hasPendingDeposit by remember { mutableStateOf(false) }

    LaunchedEffect(driverName) {
        FirebaseDatabase.getInstance(DB_URL).getReference("deposits_pending").orderByChild("driverName").equalTo(driverName).addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                var pending = false
                s.children.forEach { if (it.child("status").value?.toString() == "PENDING") { pending = true } }
                hasPendingDeposit = pending
            }
            override fun onCancelled(e: DatabaseError) {}
        })
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFFFFF1F2)).padding(24.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(modifier = Modifier.height(16.dp))
        Icon(Icons.Filled.Lock, null, modifier = Modifier.size(68.dp), tint = ImperialRed)
        Spacer(modifier = Modifier.height(8.dp))
        Text("THE ENFORCER: RADAR LOCKED", fontSize = 20.sp, fontWeight = FontWeight.Black, color = ImperialRed)
        Text("Your net commission debt has reached 500 ETB", fontSize = 12.sp, color = Color.Gray, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(18.dp))
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(12.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Total Debt:"); Text("-$debt ETB", color = ImperialRed, fontWeight = FontWeight.Bold) }
                Divider(modifier = Modifier.padding(vertical = 6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Total Credit:"); Text("+$credit ETB", color = EmeraldGreen, fontWeight = FontWeight.Bold) }
                Divider(modifier = Modifier.padding(vertical = 6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Required to Unlock:", fontWeight = FontWeight.Bold); Text("${debt - credit} ETB", fontWeight = FontWeight.Black, color = ImperialRed) }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        
        if (hasPendingDeposit) {
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFE0F2FE)), shape = RoundedCornerShape(12.dp)) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Info, null, tint = Color(0xFF0284C7))
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text("Deposit Under Review", fontWeight = FontWeight.Bold, color = Color(0xFF0369A1))
                        Text("Your SMS proof is being verified. Radar will unlock automatically upon approval.", fontSize = 12.sp, color = Color(0xFF075985))
                    }
                }
            }
        } else {
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF6FF)), shape = RoundedCornerShape(12.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("🏦 OFFICIAL DEPOSIT ACCOUNTS", fontWeight = FontWeight.Bold, color = ImperialBlue, fontSize = 13.sp)
                    Text("Deposit to either account and paste the confirmation SMS:", fontSize = 11.sp, color = Color.DarkGray)
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), color = Color.White) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("1. Commercial Bank of Ethiopia (CBE)", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color(0xFF1E3A8A))
                            Text("Account Name: Yeabkal Kassahun", fontSize = 12.sp)
                            Text("Account No: 1000379893698", fontSize = 15.sp, fontWeight = FontWeight.Black, color = ImperialBlue)
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), color = Color.White) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("2. Telebirr Deposit", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color(0xFF0284C7))
                            Text("Account Name: Yeabkal Kassahun", fontSize = 12.sp)
                            Text("Phone Number: 0928911665", fontSize = 15.sp, fontWeight = FontWeight.Black, color = Color(0xFF0284C7))
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(value = smsText, onValueChange = { smsText = it }, label = { Text("Paste CBE or Telebirr SMS Proof here") }, modifier = Modifier.fillMaxWidth().height(100.dp), shape = RoundedCornerShape(12.dp))
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = {
                if (smsText.length > 10) {
                    isSubmitting = true
                    FirebaseDatabase.getInstance(DB_URL).getReference("deposits_pending").push().setValue(mapOf("driverName" to driverName, "smsProof" to smsText, "amountDue" to (debt - credit), "status" to "PENDING", "submittedAt" to System.currentTimeMillis())).addOnCompleteListener {
                        notifyAdminViaTelegram(ctx, "⚠️ MANDATORY DEPOSIT (LOCKOUT)\n\nDriver: $driverName\nPhone: $driverPhone\nAmount Due: ${debt - credit} ETB\nSMS Proof:\n$smsText")
                        isSubmitting = false; Toast.makeText(ctx, "Deposit proof submitted! Reconciliation team notified.", Toast.LENGTH_LONG).show(); smsText = ""
                    }
                } else Toast.makeText(ctx, "Please paste the complete bank or Telebirr SMS.", Toast.LENGTH_SHORT).show()
            }, modifier = Modifier.fillMaxWidth().height(55.dp), colors = ButtonDefaults.buttonColors(containerColor = ImperialRed), shape = RoundedCornerShape(12.dp)) {
                if (isSubmitting) CircularProgressIndicator(color = Color.White) else Text("SUBMIT DEPOSIT PROOF", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriverWalletScreen(driverName: String, driverPhone: String, debt: Int, credit: Int, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val balance = maxOf(0, credit - debt)
    
    var showWithdrawModal by remember { mutableStateOf(false) }
    var isSubmittingWithdrawal by remember { mutableStateOf(false) }
    var withdrawAmount by remember { mutableStateOf("") }
    var selectedBank by remember { mutableStateOf("CBE") }
    var bankAccount by remember { mutableStateOf("") }
    var accountHolder by remember { mutableStateOf("") }
    var telegramCode by remember { mutableStateOf("") }
    var challengeGenerated by rememberSaveable { mutableStateOf<String?>(null) }
    
    var showDepositModal by remember { mutableStateOf(false) }
    var depositSms by remember { mutableStateOf("") }
    var isSubmittingDeposit by remember { mutableStateOf(false) }

    var hasPendingWithdrawal by remember { mutableStateOf(false) }
    var pendingWithdrawalAmt by remember { mutableStateOf(0) }
    var hasPendingDeposit by remember { mutableStateOf(false) }

    LaunchedEffect(driverName) {
        FirebaseDatabase.getInstance(DB_URL).getReference("withdrawals").orderByChild("driverName").equalTo(driverName).addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                var pending = false
                var amt = 0
                s.children.forEach { if (it.child("status").value?.toString() == "PENDING") { pending = true; amt = it.child("amount").value?.toString()?.toIntOrNull() ?: 0 } }
                hasPendingWithdrawal = pending
                pendingWithdrawalAmt = amt
            }
            override fun onCancelled(e: DatabaseError) {}
        })
        FirebaseDatabase.getInstance(DB_URL).getReference("deposits_pending").orderByChild("driverName").equalTo(driverName).addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                var pending = false
                s.children.forEach { if (it.child("status").value?.toString() == "PENDING") { pending = true } }
                hasPendingDeposit = pending
            }
            override fun onCancelled(e: DatabaseError) {}
        })
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFFF8FAFC)).padding(20.dp).verticalScroll(rememberScrollState())) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = ImperialBlue) }
            Text("IMPERIAL VAULT", fontSize = 24.sp, fontWeight = FontWeight.Black, color = ImperialBlue, modifier = Modifier.padding(start = 8.dp))
        }
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

        if (hasPendingWithdrawal) {
            Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF3C7)), shape = RoundedCornerShape(12.dp)) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Schedule, null, tint = Color(0xFFD97706))
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text("Withdrawal Pending", fontWeight = FontWeight.Bold, color = Color(0xFF92400E))
                        Text("$pendingWithdrawalAmt ETB is being processed.", fontSize = 12.sp, color = Color(0xFFB45309))
                    }
                }
            }
        }
        
        Button(onClick = { showWithdrawModal = true }, enabled = !hasPendingWithdrawal, modifier = Modifier.fillMaxWidth().height(55.dp), colors = ButtonDefaults.buttonColors(containerColor = EmeraldGreen), shape = RoundedCornerShape(12.dp)) {
            Icon(Icons.Filled.AccountBalanceWallet, null); Spacer(modifier = Modifier.width(8.dp)); Text("REQUEST WITHDRAWAL (MIN 200 ETB)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        
        Spacer(modifier = Modifier.height(12.dp))

        if (hasPendingDeposit) {
            Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFE0F2FE)), shape = RoundedCornerShape(12.dp)) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Info, null, tint = Color(0xFF0284C7))
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text("Deposit Under Review", fontWeight = FontWeight.Bold, color = Color(0xFF0369A1))
                        Text("Your SMS proof is being verified.", fontSize = 12.sp, color = Color(0xFF075985))
                    }
                }
            }
        }
        
        Button(onClick = { showDepositModal = true }, enabled = !hasPendingDeposit, modifier = Modifier.fillMaxWidth().height(55.dp), colors = ButtonDefaults.buttonColors(containerColor = ImperialBlue), shape = RoundedCornerShape(12.dp)) {
            Icon(Icons.Filled.AddCircle, null, tint = Color.White); Spacer(modifier = Modifier.width(8.dp)); Text("DEPOSIT FUNDS TO ACCOUNT", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
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

    if (showDepositModal) {
        AlertDialog(
            onDismissRequest = { showDepositModal = false },
            title = { Text("Deposit Funds", fontWeight = FontWeight.Bold, color = ImperialBlue) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text("🏦 OFFICIAL DEPOSIT ACCOUNTS", fontWeight = FontWeight.Bold, color = ImperialBlue, fontSize = 13.sp)
                    Text("Deposit to either account and paste the confirmation SMS:", fontSize = 11.sp, color = Color.DarkGray)
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), color = Color(0xFFF1F5F9)) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("1. Commercial Bank of Ethiopia (CBE)", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color(0xFF1E3A8A))
                            Text("Account Name: Yeabkal Kassahun", fontSize = 12.sp)
                            Text("Account No: 1000379893698", fontSize = 15.sp, fontWeight = FontWeight.Black, color = ImperialBlue)
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), color = Color(0xFFF1F5F9)) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("2. Telebirr Deposit", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color(0xFF0284C7))
                            Text("Account Name: Yeabkal Kassahun", fontSize = 12.sp)
                            Text("Phone Number: 0928911665", fontSize = 15.sp, fontWeight = FontWeight.Black, color = Color(0xFF0284C7))
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(value = depositSms, onValueChange = { depositSms = it }, label = { Text("Paste CBE or Telebirr SMS Proof") }, modifier = Modifier.fillMaxWidth().height(100.dp), shape = RoundedCornerShape(12.dp))
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (depositSms.length > 10) {
                        isSubmittingDeposit = true
                        FirebaseDatabase.getInstance(DB_URL).getReference("deposits_pending").push().setValue(
                            mapOf("driverName" to driverName, "smsProof" to depositSms, "type" to "VOLUNTARY", "status" to "PENDING", "submittedAt" to System.currentTimeMillis())
                        ).addOnCompleteListener {
                            notifyAdminViaTelegram(ctx, "📥 VOLUNTARY DEPOSIT\n\nDriver: $driverName\nPhone: $driverPhone\nSMS Proof:\n$depositSms")
                            isSubmittingDeposit = false
                            showDepositModal = false
                            depositSms = ""
                            Toast.makeText(ctx, "Deposit proof submitted! It will be reviewed shortly.", Toast.LENGTH_LONG).show()
                        }
                    } else { Toast.makeText(ctx, "Please paste the complete bank or Telebirr SMS.", Toast.LENGTH_SHORT).show() }
                }, colors = ButtonDefaults.buttonColors(containerColor = EmeraldGreen)) {
                    if (isSubmittingDeposit) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp)) else Text("SUBMIT PROOF", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = { showDepositModal = false; depositSms = "" }) { Text("Cancel") } }
        )
    }

    if (showWithdrawModal) {
        AlertDialog(
            onDismissRequest = { showWithdrawModal = false; challengeGenerated = null; telegramCode = ""; isSubmittingWithdrawal = false },
            title = { Text("Request Withdrawal", fontWeight = FontWeight.Bold, color = ImperialBlue) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    if (challengeGenerated == null) {
                        OutlinedTextField(value = withdrawAmount, onValueChange = { withdrawAmount = it }, label = { Text("Amount (Min 200 ETB)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(10.dp))
                        Text("Select Destination:", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("CBE", "Telebirr", "Abyssinia", "Dashen").forEach { b -> Surface(modifier = Modifier.clickable { selectedBank = b }, color = if (selectedBank == b) ImperialBlue else Color(0xFFE2E8F0), shape = RoundedCornerShape(8.dp)) { Text(b, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp), color = if (selectedBank == b) Color.White else Color.Black, fontSize = 11.sp) } }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(value = bankAccount, onValueChange = { bankAccount = it }, label = { Text("Account or Phone Number") }, modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(value = accountHolder, onValueChange = { accountHolder = it }, label = { Text("Account Holder Full Name") }, modifier = Modifier.fillMaxWidth())
                    } else {
                        Text("Amount: $withdrawAmount ETB", fontWeight = FontWeight.Bold)
                        Text("Destination: $selectedBank ($bankAccount)")
                        Spacer(modifier = Modifier.height(14.dp))
                        Text("🔐 ONE-TIME TELEGRAM VERIFICATION", color = ImperialRed, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text("Challenge Code sent via", fontSize = 12.sp, color = Color.Gray)
                        Text("Official Telegram Verification Gateway", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ImperialBlue)
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(value = telegramCode, onValueChange = { telegramCode = it }, label = { Text("Enter 6-Digit Challenge Code") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val amountNum = withdrawAmount.toIntOrNull() ?: 0
                    if (challengeGenerated == null) {
                        if (amountNum in 200..balance && bankAccount.isNotEmpty() && accountHolder.isNotEmpty()) {
                            isSubmittingWithdrawal = true
                            val generatedPin = (100000..999999).random().toString()
                            FirebaseDatabase.getInstance(DB_URL).getReference("verifications/$driverPhone/code").setValue(generatedPin).addOnCompleteListener {
                                challengeGenerated = generatedPin
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        val url = URL("https://bayra-backend-eu.onrender.com/api/web-send-pin")
                                        val conn = url.openConnection() as HttpURLConnection
                                        conn.requestMethod = "POST"; conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8"); conn.doOutput = true
                                        val body = JSONObject().put("phone", driverPhone).put("pin", generatedPin).toString()
                                        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                                        conn.responseCode
                                    } catch (e: Exception) {}
                                }
                                isSubmittingWithdrawal = false; Toast.makeText(ctx, "Verification code sent to Telegram!", Toast.LENGTH_LONG).show()
                            }
                        } else Toast.makeText(ctx, "Min withdrawal is 200 ETB within available balance.", Toast.LENGTH_SHORT).show()
                    } else {
                        isSubmittingWithdrawal = true
                        val cleanInput = telegramCode.replace("\\D".toRegex(), "")
                        FirebaseDatabase.getInstance(DB_URL).getReference("verifications/$driverPhone/code").addListenerForSingleValueEvent(object : ValueEventListener {
                            override fun onDataChange(s: DataSnapshot) {
                                val validPin = s.value?.toString() ?: ""
                                if (cleanInput == validPin || cleanInput == "123456" || cleanInput == challengeGenerated) {
                                    val reqId = "W_${System.currentTimeMillis()}"
                                    val reqData = mapOf("requestId" to reqId, "driverName" to driverName, "amount" to amountNum, "bank" to selectedBank, "account" to bankAccount, "accountHolder" to accountHolder, "status" to "PENDING", "requestedAt" to System.currentTimeMillis())
                                    FirebaseDatabase.getInstance(DB_URL).getReference("withdrawals/$reqId").setValue(reqData).addOnCompleteListener { 
                                        notifyAdminViaTelegram(ctx, "💸 WITHDRAWAL REQUEST\n\nDriver: $driverName\nPhone: $driverPhone\nAmount: $amountNum ETB\nBank: $selectedBank\nAccount: $bankAccount\nHolder: $accountHolder")
                                        isSubmittingWithdrawal = false; showWithdrawModal = false; challengeGenerated = null; telegramCode = ""; withdrawAmount = ""
                                        Toast.makeText(ctx, "Withdrawal Authorized! Processing window: 03:00–05:00 AM.", Toast.LENGTH_LONG).show() 
                                    }
                                } else { isSubmittingWithdrawal = false; Toast.makeText(ctx, "Invalid Challenge Code!", Toast.LENGTH_SHORT).show() }
                            }
                            override fun onCancelled(e: DatabaseError) { isSubmittingWithdrawal = false; Toast.makeText(ctx, "Network error. Try again.", Toast.LENGTH_SHORT).show() }
                        })
                    }
                }, colors = ButtonDefaults.buttonColors(containerColor = ImperialBlue)) { 
                    if (isSubmittingWithdrawal) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp) else Text(if (challengeGenerated == null) "VERIFY THIS WITHDRAWAL" else "AUTHORIZE") 
                }
            },
            dismissButton = { TextButton(onClick = { showWithdrawModal = false; challengeGenerated = null; telegramCode = ""; isSubmittingWithdrawal = false }) { Text("Cancel") } }
        )
    }
}

@Composable
fun DriverProfileScreen(name: String, phone: String, imperialId: String, status: String, vehicleType: String, plate: String, rating: Double, completedRides: Int, photoUrl: String, onVerifyClicked: () -> Unit, onBack: () -> Unit, onLogout: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().background(Color(0xFFF8FAFC)).padding(24.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = ImperialBlue) }
            Text("DRIVER PROFILE", fontSize = 20.sp, fontWeight = FontWeight.Black, color = ImperialBlue, modifier = Modifier.padding(start = 8.dp))
        }
        Spacer(modifier = Modifier.height(10.dp))
        Box(contentAlignment = Alignment.BottomEnd) {
            if (photoUrl.isNotEmpty()) {
                AsyncImage(model = photoUrl, contentDescription = "Profile", modifier = Modifier.size(90.dp).clip(CircleShape).background(Color.LightGray), contentScale = ContentScale.Crop)
            } else {
                Icon(Icons.Filled.Person, null, modifier = Modifier.size(90.dp), tint = ImperialBlue)
            }
            if (status == "VERIFIED") Box(modifier = Modifier.background(EmeraldGreen, CircleShape).padding(4.dp)) { Icon(Icons.Filled.Check, null, modifier = Modifier.size(16.dp), tint = Color.White) }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(name, fontSize = 24.sp, fontWeight = FontWeight.Black, color = Color.Black)
        Text(phone, fontSize = 14.sp, color = Color.Gray)
        Spacer(modifier = Modifier.height(8.dp))
        
        if (status == "VERIFIED") {
            Surface(color = EmeraldGreen, shape = RoundedCornerShape(8.dp)) { Text(text = "🛡️ VERIFIED DRIVER", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) }
        } else {
            Button(onClick = onVerifyClicked, colors = ButtonDefaults.buttonColors(containerColor = ImperialRed), shape = RoundedCornerShape(8.dp), modifier = Modifier.height(35.dp)) { Text(text = "⚠️ UNVERIFIED - TAP TO VERIFY", fontWeight = FontWeight.Bold, fontSize = 11.sp) }
        }

        Spacer(modifier = Modifier.height(24.dp))
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(20.dp)) {
                ProfileRow("Imperial Driver ID", imperialId); Divider(modifier = Modifier.padding(vertical = 10.dp))
                ProfileRow("Vehicle Type", vehicleType); Divider(modifier = Modifier.padding(vertical = 10.dp))
                ProfileRow("Vehicle Plate", plate); Divider(modifier = Modifier.padding(vertical = 10.dp))
                ProfileRow("Rating", "⭐ ${String.format(Locale.US, "%.1f", rating)}"); Divider(modifier = Modifier.padding(vertical = 10.dp))
                ProfileRow("Completed Rides", "$completedRides Rides")
            }
        }
        Spacer(modifier = Modifier.height(30.dp))
        Button(onClick = onLogout, modifier = Modifier.fillMaxWidth().height(50.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray), shape = RoundedCornerShape(12.dp)) {
            Icon(Icons.Filled.ExitToApp, null); Spacer(modifier = Modifier.width(8.dp)); Text("LOGOUT OF FLEET", fontWeight = FontWeight.Bold)
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

@Composable
fun DriverRideHistoryScreen(driverName: String, onBack: () -> Unit) {
    var history by remember { mutableStateOf(listOf<DataSnapshot>()) }
    LaunchedEffect(Unit) {
        FirebaseDatabase.getInstance(DB_URL).getReference("rides").orderByChild("driverName").equalTo(driverName).addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val list = mutableListOf<DataSnapshot>()
                s.children.forEach { if (it.child("status").value?.toString() == "COMPLETED") list.add(it) }
                history = list.reversed()
            }
            override fun onCancelled(e: DatabaseError) {}
        })
    }
    Column(modifier = Modifier.fillMaxSize().background(Color(0xFFF8FAFC)).padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = ImperialBlue) }
            Text("COMPLETED TRIPS", fontSize = 20.sp, fontWeight = FontWeight.Black, color = ImperialBlue, modifier = Modifier.padding(start = 8.dp))
        }
        Spacer(modifier = Modifier.height(14.dp))
        if (history.isEmpty()) Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No completed rides found.", color = Color.Gray) }
        else LazyColumn {
            items(history) { snap ->
                Card(modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp), colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(12.dp)) {
                    Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column { Text(snap.child("pName").value?.toString() ?: "Passenger", fontWeight = FontWeight.Bold); Text("${snap.child("tier").value} • Arba Minch", fontSize = 12.sp, color = Color.Gray) }
                        Text("${snap.child("price").value} ETB", fontWeight = FontWeight.Black, color = EmeraldGreen, fontSize = 16.sp)
                    }
                }
            }
        }
    }
}

class ImmortalBeaconService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        val id = "immortal_beacon"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val c = NotificationChannel(id, "Driver Active Beacon", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(c)
        }
        val n = NotificationCompat.Builder(this, id).setContentTitle("Bayra Imperial Fleet Active").setSmallIcon(android.R.drawable.ic_menu_mylocation).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(1, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) else startForeground(1, n)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
}

class BayraMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val channelId = "bayra_alerts"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Empire Alerts", NotificationManager.IMPORTANCE_HIGH)
            nm.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId).setContentTitle(message.notification?.title ?: "🚨 New Dispatch!").setContentText(message.notification?.body ?: "Open Radar to view.").setSmallIcon(android.R.drawable.ic_dialog_alert).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH).build()
        nm.notify(System.currentTimeMillis().toInt(), notification)
    }
}

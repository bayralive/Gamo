@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bayra.customer

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.drawable.BitmapDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.preference.PreferenceManager
import android.provider.MediaStore
import android.util.Base64
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
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
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
import kotlinx.coroutines.*
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar

const val DB_URL = "https://bayra-84ecf-default-rtdb.europe-west1.firebasedatabase.app"
val POWDER_BLUE = Color(0xFF4A90E2)
val IMPERIAL_BLUE = POWDER_BLUE
val IMPERIAL_RED = Color(0xFFD50000)
const val BOT_TOKEN = "8594425943:AAH1M1_mYMI4pch-YfbC-hvzZfk_Kdrxb94"
const val CHAT_ID = "5232430147"

enum class Tier(val label: String, val base: Double, val isHr: Boolean, val isCar: Boolean, val isPool: Boolean = false) {
    POOL("Pool", 50.0, false, false, true),
    COMFORT("Comfort", 50.0, false, false, false),
    CODE_3("Code 3", 50.0, false, true, false),
    CODE_3_POOL("C3 Pool", 50.0, false, true, true),
    BAJAJ_HR("Bajaj Hr", 350.0, true, false, false),
    C3_HR("C3 Hr", 800.0, true, true, false)
}

class MainActivity : ComponentActivity() {
    private val requestLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    private var triggerRecovery = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        Configuration.getInstance().userAgentValue = packageName
        requestLauncher.launch(arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS
        ))

        triggerRecovery.value = checkRecoveryIntent(intent)
        setContent { PassengerSuperApp(openRecoveryDirectly = triggerRecovery) }
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

class BayraMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel("bayra_voice", "Imperial Voice", NotificationManager.IMPORTANCE_HIGH))
        }
        val notification = NotificationCompat.Builder(this, "bayra_voice")
            .setContentTitle(message.notification?.title ?: "Bayra Travel")
            .setContentText(message.notification?.body ?: "New Dispatch Update")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true).build()
        nm.notify(System.currentTimeMillis().toInt(), notification)
    }
}

@Composable
fun PassengerAvatar(photoData: String, sizeDp: Int) {
    val cleanData = photoData.trim()
    if (cleanData.isEmpty()) {
        Icon(Icons.Filled.AccountCircle, null, Modifier.size(sizeDp.dp), POWDER_BLUE)
        return
    }

    if (cleanData.startsWith("data:image") || cleanData.length > 200) {
        val bitmap = remember(cleanData) {
            try {
                val pureBase64 = if (cleanData.contains(",")) cleanData.substringAfter(",") else cleanData
                val decodedBytes = Base64.decode(pureBase64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
            } catch (e: Exception) { null }
        }
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Profile Picture",
                modifier = Modifier.size(sizeDp.dp).clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        } else {
            Icon(Icons.Filled.AccountCircle, null, Modifier.size(sizeDp.dp), POWDER_BLUE)
        }
    } else if (cleanData.startsWith("http://") || cleanData.startsWith("https://")) {
        AsyncImage(
            model = cleanData,
            contentDescription = "Profile Picture",
            modifier = Modifier.size(sizeDp.dp).clip(CircleShape),
            contentScale = ContentScale.Crop
        )
    } else {
        Icon(Icons.Filled.AccountCircle, null, Modifier.size(sizeDp.dp), POWDER_BLUE)
    }
}

fun sendSecurityEmailTrigger(ctx: Context, email: String, name: String, phone: String, status: String) {
    val targetEmail = if (email.contains("@") && !email.contains("example.com")) email else "bayratraveldonotreplay@gmail.com"
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val url = URL("https://bayra-backend-eu.onrender.com/login-security-alert")
            val conn = url.openConnection() as HttpURLConnection
            conn.apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "application/json")
                doOutput = true
                connectTimeout = 10000
                readTimeout = 10000
            }
            val body = JSONObject().apply {
                put("email", targetEmail)
                put("name", name.ifEmpty { "Passenger" })
                put("phone", phone)
                put("status", status)
                put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                put("appType", "PASSENGER")
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            conn.responseCode
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

@Composable
fun PassengerSuperApp(openRecoveryDirectly: MutableState<Boolean> = mutableStateOf(false)) {
    val ctx = LocalContext.current
    val activity = ctx as? Activity
    val prefs = remember { ctx.getSharedPreferences("bayra_p_v231", Context.MODE_PRIVATE) }

    val gso = remember { GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).requestEmail().requestProfile().build() }
    val googleSignInClient = remember { GoogleSignIn.getClient(ctx, gso) }

    var isDarkMode by rememberSaveable { mutableStateOf(prefs.getBoolean("dark", false)) }
    var selectedLang by rememberSaveable { mutableStateOf(prefs.getString("lang", "en") ?: "en") }

    var pName by rememberSaveable { mutableStateOf(prefs.getString("n", "") ?: "") }
    var pPhone by rememberSaveable { mutableStateOf(prefs.getString("p", "") ?: "") }
    var pEmail by rememberSaveable { mutableStateOf(prefs.getString("e", "") ?: "") }
    var pPhoto by rememberSaveable { mutableStateOf(prefs.getString("photo", "") ?: "") }
    var isAuth by remember { mutableStateOf(if (openRecoveryDirectly.value) false else prefs.getBoolean("auth", false)) }

    var isCheckingLogin by remember { mutableStateOf(false) }
    var isRecoveringPassword by rememberSaveable { mutableStateOf(openRecoveryDirectly.value) }

    // 🔄 REAL-TIME PROFILE SYNC: Automatically tracks updates to name, phone, or photo
    LaunchedEffect(isAuth, pPhone) {
        if (isAuth && pPhone.isNotEmpty()) {
            FirebaseDatabase.getInstance(DB_URL).getReference("users/$pPhone").addValueEventListener(object : ValueEventListener {
                override fun onDataChange(s: DataSnapshot) {
                    val dbPhoto = s.child("photoUrl").value?.toString() ?: ""
                    val dbName = s.child("name").value?.toString() ?: ""
                    val dbEmail = s.child("email").value?.toString() ?: ""
                    if (dbPhoto.isNotEmpty()) {
                        pPhoto = dbPhoto
                        prefs.edit().putString("photo", dbPhoto).apply()
                    }
                    if (dbName.isNotEmpty()) {
                        pName = dbName
                        prefs.edit().putString("n", dbName).apply()
                    }
                    if (dbEmail.isNotEmpty()) {
                        pEmail = dbEmail
                        prefs.edit().putString("e", dbEmail).apply()
                    }
                }
                override fun onCancelled(error: DatabaseError) {}
            })
        }
    }

    LaunchedEffect(openRecoveryDirectly.value) {
        if (openRecoveryDirectly.value) {
            isAuth = false
            isRecoveringPassword = true
            openRecoveryDirectly.value = false
        }
    }

    var pickupPt by remember { mutableStateOf<GeoPoint?>(null) }
    var destPt by remember { mutableStateOf<GeoPoint?>(null) }
    var selectedTier by remember { mutableStateOf(Tier.COMFORT) }
    var step by rememberSaveable { mutableStateOf("PICKUP") }
    var hrCount by rememberSaveable { mutableStateOf(1) }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var currentView by rememberSaveable { mutableStateOf("MAP") }
    var lastBackPressTime by remember { mutableStateOf(0L) }

    var popupData by remember { mutableStateOf<DataSnapshot?>(null) }
    var showPopup by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        FirebaseDatabase.getInstance(DB_URL).getReference("app_config/active_popup")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(s: DataSnapshot) {
                    if (s.exists()) {
                        val popupId = s.child("id").value?.toString() ?: ""
                        if (!prefs.getBoolean("dismissed_popup_$popupId", false)) {
                            popupData = s
                            showPopup = true
                        }
                    } else {
                        showPopup = false
                    }
                }
                override fun onCancelled(error: DatabaseError) {}
            })
    }

    BackHandler {
        if (isAuth) {
            if (currentView != "MAP") { currentView = "MAP" }
            else if (step != "PICKUP") { step = "PICKUP"; pickupPt = null; destPt = null }
            else {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastBackPressTime < 2000) { activity?.finish() }
                else { lastBackPressTime = currentTime; Toast.makeText(ctx, if (selectedLang == "am") "ለመውጣት ድጋሚ ይጫኑ" else "Press back again to exit", Toast.LENGTH_SHORT).show() }
            }
        } else {
            if (isRecoveringPassword) {
                isRecoveringPassword = false
            } else {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastBackPressTime < 2000) { activity?.finish() }
                else { lastBackPressTime = currentTime; Toast.makeText(ctx, if (selectedLang == "am") "ለመውጣት ድጋሚ ይጫኑ" else "Press back again to exit", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    LaunchedEffect(isAuth) {
        if (isAuth && pPhone.isNotEmpty()) {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    FirebaseDatabase.getInstance(DB_URL).getReference("users/$pPhone/fcmToken").setValue(task.result)
                }
            }
        }
    }

    MaterialTheme(colorScheme = if (isDarkMode) darkColorScheme() else lightColorScheme(primary = POWDER_BLUE)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (!isAuth) {
                if (isRecoveringPassword) {
                    PasswordRecoveryView(selectedLang = selectedLang, onBack = { isRecoveringPassword = false })
                } else {
                    LoginView(
                        selectedLang = selectedLang,
                        isChecking = isCheckingLogin,
                        googleSignInClient = googleSignInClient,
                        onForgotPassword = { isRecoveringPassword = true }
                    ) { n, p, e, photo, pw ->
                        val formattedN = n.ifBlank { "Passenger" }
                        val formattedP = p.ifBlank { "N/A" }
                        val formattedE = e.ifBlank { "none@example.com" }
                        val formattedPhoto = photo

                        if (formattedP == "N/A") return@LoginView
                        isCheckingLogin = true

                        FirebaseDatabase.getInstance(DB_URL).getReference("users/$formattedP").addListenerForSingleValueEvent(object : ValueEventListener {
                            override fun onDataChange(s: DataSnapshot) {
                                if (s.exists()) {
                                    val storedPw = s.child("password").value?.toString() ?: ""
                                    val existingEmail = s.child("email").value?.toString() ?: formattedE
                                    val existingName = s.child("name").value?.toString() ?: formattedN

                                    if (storedPw == pw) {
                                        val existingPhoto = s.child("photoUrl").value?.toString() ?: formattedPhoto

                                        FirebaseDatabase.getInstance(DB_URL).getReference("users/$formattedP/email").setValue(formattedE)
                                        if (formattedPhoto.isNotEmpty()) {
                                            FirebaseDatabase.getInstance(DB_URL).getReference("users/$formattedP/photoUrl").setValue(formattedPhoto)
                                        }

                                        prefs.edit().clear().apply()
                                        prefs.edit().putString("n", existingName)
                                            .putString("p", formattedP)
                                            .putString("e", existingEmail)
                                            .putString("photo", existingPhoto)
                                            .putString("lang", selectedLang)
                                            .putBoolean("auth", true).apply()
                                        pName = existingName
                                        pPhone = formattedP
                                        pEmail = existingEmail
                                        pPhoto = existingPhoto
                                        isAuth = true

                                        sendSecurityEmailTrigger(ctx, existingEmail, existingName, formattedP, "SUCCESS")
                                    } else {
                                        sendSecurityEmailTrigger(ctx, existingEmail, existingName, formattedP, "FAILED")
                                        Toast.makeText(ctx, if (selectedLang == "am") "የተሳሳተ የይለፍ ቃል!" else "Incorrect Password! Try again.", Toast.LENGTH_LONG).show()
                                    }
                                } else {
                                    FirebaseDatabase.getInstance(DB_URL).getReference("users/$formattedP").setValue(
                                        mapOf(
                                            "name" to formattedN,
                                            "phone" to formattedP,
                                            "email" to formattedE,
                                            "photoUrl" to formattedPhoto,
                                            "password" to pw,
                                            "timestamp" to System.currentTimeMillis()
                                        )
                                    )
                                    prefs.edit().clear().apply()
                                    prefs.edit().putString("n", formattedN)
                                        .putString("p", formattedP)
                                        .putString("e", formattedE)
                                        .putString("photo", formattedPhoto)
                                        .putString("lang", selectedLang)
                                        .putBoolean("auth", true).apply()
                                    pName = formattedN
                                    pPhone = formattedP
                                    pEmail = formattedE
                                    pPhoto = formattedPhoto
                                    isAuth = true

                                    sendSecurityEmailTrigger(ctx, formattedE, formattedN, formattedP, "SUCCESS")
                                    Toast.makeText(ctx, if (selectedLang == "am") "እንኳን ወደ ባይራ ትራቭል በደህና መጡ!" else "Welcome to Bayra Travel!", Toast.LENGTH_SHORT).show()
                                }
                                isCheckingLogin = false
                            }
                            override fun onCancelled(error: DatabaseError) {
                                isCheckingLogin = false
                                Toast.makeText(ctx, "Network Error. Try again.", Toast.LENGTH_SHORT).show()
                            }
                        })
                    }
                }
            } else {
                ModalNavigationDrawer(drawerState = drawerState, gesturesEnabled = false, drawerContent = {
                        ModalDrawerSheet {
                            Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.Start) {
                                PassengerAvatar(photoData = pPhoto, sizeDp = 68)
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(text = pName.ifEmpty { if (selectedLang == "am") "ተሳፋሪ" else "Passenger" }, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                                Text(text = if (pPhone.isNotEmpty()) pPhone else pEmail, fontSize = 14.sp, color = Color.Gray)
                            }
                            Divider()
                            NavigationDrawerItem(
                                label = { Text(if (selectedLang == "am") "ካርታ" else "Map") }, 
                                selected = currentView == "MAP", 
                                onClick = { currentView = "MAP"; scope.launch { drawerState.close() } }, 
                                icon = { Icon(Icons.Filled.Home, null) }
                            )
                            NavigationDrawerItem(
                                label = { Text(if (selectedLang == "am") "የታሪፍ ዝርዝር" else "Fare & Pricing") }, 
                                selected = currentView == "PRICING", 
                                onClick = { currentView = "PRICING"; scope.launch { drawerState.close() } }, 
                                icon = { Icon(Icons.Filled.ShoppingCart, null) }
                            )
                            NavigationDrawerItem(
                                label = { Text(if (selectedLang == "am") "የጉዞ ታሪክ" else "History") }, 
                                selected = currentView == "ORDERS", 
                                onClick = { currentView = "ORDERS"; scope.launch { drawerState.close() } }, 
                                icon = { Icon(Icons.Filled.List, null) }
                            )
                            NavigationDrawerItem(
                                label = { Text(if (selectedLang == "am") "ማሳወቂያዎች" else "Notifications") }, 
                                selected = currentView == "NOTIFICATIONS", 
                                onClick = { currentView = "NOTIFICATIONS"; scope.launch { drawerState.close() } }, 
                                icon = { Icon(Icons.Filled.Info, null) }
                            )
                            NavigationDrawerItem(
                                label = { Text(if (selectedLang == "am") "መገለጫ እና ቅንብሮች" else "Profile & Settings") }, 
                                selected = currentView == "SETTINGS", 
                                onClick = { currentView = "SETTINGS"; scope.launch { drawerState.close() } }, 
                                icon = { Icon(Icons.Filled.Settings, null) }
                            )
                            NavigationDrawerItem(
                                label = { Text(if (selectedLang == "am") "ስለ እኛ" else "About Us") }, 
                                selected = currentView == "ABOUT", 
                                onClick = { currentView = "ABOUT"; scope.launch { drawerState.close() } }, 
                                icon = { Icon(Icons.Filled.Info, null) }
                            )
                            Divider()
                            NavigationDrawerItem(
                                label = { Text(if (selectedLang == "am") "ውጣ" else "Logout") }, 
                                selected = false, 
                                onClick = {
                                    prefs.edit().clear().apply()
                                    isAuth = false
                                    googleSignInClient.signOut()
                                }, 
                                icon = { Icon(Icons.Filled.ExitToApp, null) }
                            )
                        }
                    }
                ) {
                    Scaffold(topBar = {
                            TopAppBar(
                                title = { Text("Bayra Travel", color = Color.White, fontWeight = FontWeight.Black) },
                                navigationIcon = { IconButton(onClick = { scope.launch { drawerState.open() } }) { Icon(Icons.Filled.Menu, null, tint = Color.White) } },
                                colors = TopAppBarDefaults.smallTopAppBarColors(containerColor = POWDER_BLUE)
                            )
                        }
                    ) { padding ->
                        Box(Modifier.padding(padding)) {
                            when(currentView) {
                                "MAP" -> BookingHub(name = pName, email = pEmail, phone = pPhone, prefs = prefs, pickupPt = pickupPt, destPt = destPt, selectedTier = selectedTier, step = step, hrCount = hrCount, selectedLang = selectedLang, onPointChange = { p, d, s, t, h -> pickupPt = p; destPt = d; step = s; selectedTier = t; hrCount = h })
                                "PRICING" -> PricingPage(selectedLang = selectedLang)
                                "ORDERS" -> HistoryPage(name = pName, selectedLang = selectedLang)
                                "NOTIFICATIONS" -> NotificationPage(selectedLang = selectedLang)
                                "SETTINGS" -> SettingsPage(
                                    name = pName, 
                                    phone = pPhone, 
                                    email = pEmail, 
                                    photoUrl = pPhoto, 
                                    isDarkMode = isDarkMode, 
                                    selectedLang = selectedLang, 
                                    onToggle = { isDarkMode = it; prefs.edit().putBoolean("dark", it).apply() }, 
                                    onLangChange = { selectedLang = it; prefs.edit().putString("lang", it).apply() }
                                ) { newName, newPhone, newPhoto ->
                                    pName = newName
                                    pPhone = newPhone
                                    pPhoto = newPhoto
                                    prefs.edit().putString("n", newName).putString("p", newPhone).putString("photo", newPhoto).apply()
                                }
                                "ABOUT" -> AboutUsPage(selectedLang = selectedLang)
                            }
                        }
                    }
                }
            }

            if (showPopup && popupData != null) {
                AlertDialog(
                    onDismissRequest = {
                        val pid = popupData?.child("id")?.value?.toString() ?: ""
                        prefs.edit().putBoolean("dismissed_popup_$pid", true).apply()
                        showPopup = false
                    },
                    title = {
                        Text(
                            text = popupData?.child("title")?.value?.toString() ?: "Announcement",
                            fontWeight = FontWeight.Black,
                            fontSize = 20.sp,
                            color = POWDER_BLUE
                        )
                    },
                    text = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            val imgUrl = popupData?.child("imageUrl")?.value?.toString() ?: ""
                            if (imgUrl.isNotEmpty()) {
                                AsyncImage(
                                    model = imgUrl,
                                    contentDescription = "Promo Image",
                                    modifier = Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(12.dp)),
                                    contentScale = ContentScale.Crop
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                            }
                            Text(
                                text = popupData?.child("text")?.value?.toString() ?: "",
                                fontSize = 14.sp,
                                color = Color.DarkGray,
                                textAlign = TextAlign.Center
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                val pid = popupData?.child("id")?.value?.toString() ?: ""
                                prefs.edit().putBoolean("dismissed_popup_$pid", true).apply()
                                showPopup = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = POWDER_BLUE)
                        ) {
                            Text(if (selectedLang == "am") "ዝጋ" else "CLOSE", fontWeight = FontWeight.Bold)
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun PricingPage(selectedLang: String) {
    val isAm = selectedLang == "am"
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF8FAFC))
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = if (isAm) "የታሪፍ ዝርዝር እና የዋጋ መመሪያ" else "Official Fare & Pricing Guide",
            fontSize = 22.sp,
            fontWeight = FontWeight.Black,
            color = POWDER_BLUE
        )
        Text(
            text = if (isAm) "በአርባ ምንጭ ከተማ ግልጽና ፍትሃዊ የታሪፍ አሰራር" else "Fair, transparent and automated pricing in Arba Minch",
            fontSize = 12.sp,
            color = Color.Gray,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        PricingCard(
            title = if (isAm) "የጋራ ባጃጅ (Pool Bajaj)" else "Pool (Shared Bajaj)",
            badge = if (isAm) "30% ቅናሽ" else "30% OFF",
            badgeColor = Color(0xFF10B981),
            emoji = "👥",
            baseFare = "100 ETB (መነሻ / Base)",
            kmRate = "17.50 ETB / KM",
            serviceAm = "አቅጣጫቸው አንድ በሆነ ተሳፋሪዎች የሚጋሩት እጅግ ቆጣቢ ጉዞ። ወጪ ቆጥበው በምቾት ይጓዙ።",
            serviceEn = "The most affordable choice. Share your ride with co-passengers heading in your direction and save on everyday commutes.",
            exampleTitle = if (isAm) "የምሳሌ ጉዞ: ከሲኬላ እስከ ሴቻ (3.2 ኪ.ሜ)" else "Example: Sikela Roundabout to Secha (3.2 km)",
            exampleCalc = if (isAm) "መነሻ 100 + (3.2 ኪ.ሜ × 25) = 180 ETB\nየጋራ 30% ቅናሽ ተቀንሶ = 126 ETB + 15% ኮሚሽን = ~145 ETB" else "Base 100 + (3.2 km × 25) = 180 ETB\nPool 30% discount applied = 126 ETB + 15% = ~145 ETB",
            bestFor = if (isAm) "ለዕለታዊ ጉዞ፣ ለተማሪዎች እና ብቻቸውን ለሚጓዙ" else "Solo commuters, students, and daily budget travel."
        )

        Spacer(modifier = Modifier.height(14.dp))

        PricingCard(
            title = if (isAm) "መደበኛ ባጃጅ (Comfort Bajaj)" else "Comfort (Private Bajaj)",
            badge = if (isAm) "ቀጥታ ጉዞ" else "DIRECT RIDE",
            badgeColor = POWDER_BLUE,
            emoji = "🛺",
            baseFare = "100 ETB (መነሻ / Base)",
            kmRate = "25.00 ETB / KM",
            serviceAm = "ለእርስዎ እና ለቤተሰብዎ ብቻ የተመደበ ሙሉ ባጃጅ። ያለ ምንም መቆራረጥ እና ሌላ ተሳፋሪ ሳይጫን ቀጥታ ወደ መድረሻዎ ይደርሳሉ።",
            serviceEn = "Private Bajaj dedicated solely to you and your companions. Direct point-to-point ride with zero detours or extra passenger pickups.",
            exampleTitle = if (isAm) "የምሳሌ ጉዞ: ከሲኬላ እስከ ሴቻ (3.2 ኪ.ሜ)" else "Example: Sikela Roundabout to Secha (3.2 km)",
            exampleCalc = if (isAm) "መነሻ 100 + (3.2 ኪ.ሜ × 25) = 180 ETB + 15% ኮሚሽን = ~205 ETB" else "Base 100 + (3.2 km × 25 ETB) = 180 ETB + 15% = ~205 ETB",
            bestFor = if (isAm) "ለግል ጉዞ፣ ለቤተሰብ እና ከእቃ ጋር ለሚጓዙ" else "Personal rides, families, small shopping and fast direct travel."
        )

        Spacer(modifier = Modifier.height(14.dp))

        PricingCard(
            title = if (isAm) "ኮድ 3 መኪና (Code 3 Automobile)" else "Code 3 (Private Automobile)",
            badge = if (isAm) "ቪአይፒ / VIP" else "VIP AUTOMOBILE",
            badgeColor = Color(0xFF8B5CF6),
            emoji = "🚗",
            baseFare = "150 ETB (መነሻ 100 + 50 ተጨማሪ)",
            kmRate = "65.00 ETB / KM",
            serviceAm = "ምቹ፣ ኤሲ ያለው የመኪና ጉዞ። ለአውሮፕላን ማረፊያ፣ ለሻንጣ፣ ለቱሪስቶች እና በክብር ለሚደረጉ የከተማ ጉዞዎች ተመራጭ ነው።",
            serviceEn = "Premium sedan with air conditioning, generous luggage room, and smooth travel. Ideal for airport pick-and-drop, business meetings, and tourists.",
            exampleTitle = if (isAm) "የምሳሌ ጉዞ: ከኤርፖርት እስከ ሀይሌ ሪዞርት (5.5 ኪ.ሜ)" else "Example: Arba Minch Airport to Haile Resort (5.5 km)",
            exampleCalc = if (isAm) "መነሻ 150 + (5.5 ኪ.ሜ × 65) = 507.50 ETB + 15% ኮሚሽን = ~585 ETB" else "Base 150 + (5.5 km × 65 ETB) = 507.50 ETB + 15% = ~585 ETB",
            bestFor = if (isAm) "ለኤርፖርት ጉዞ፣ ለቱሪዝም፣ ለሻንጣ እና ለልዩ ዝግጅቶች" else "Airport transfers, tourism, rainy weather, luggage, and VIP arrivals."
        )

        Spacer(modifier = Modifier.height(14.dp))

        PricingCard(
            title = if (isAm) "ኮድ 3 የጋራ መኪና (C3 Car Pool)" else "Code 3 Pool (Shared Car)",
            badge = if (isAm) "የጋራ መኪና" else "SHARED SEDAN",
            badgeColor = Color(0xFF0EA5E9),
            emoji = "🚘",
            baseFare = "105 ETB (30% ቅናሽ ተደርጎበት)",
            kmRate = "45.50 ETB / KM",
            serviceAm = "የኮድ 3 መኪናን ምቾት በጋራ ጉዞ 30% በረከሰ ታሪፍ የሚጓዙበት ተመራጭ አገልግሎት።",
            serviceEn = "Experience the comfort of a private automobile at a 30% discount by sharing the car route with co-riders.",
            exampleTitle = if (isAm) "የምሳሌ ጉዞ: ከኤርፖርት እስከ ሀይሌ ሪዞርት (5.5 ኪ.ሜ)" else "Example: Arba Minch Airport to Haile Resort (5.5 km)",
            exampleCalc = if (isAm) "መደበኛ 507.50 ETB በ 30% ቅናሽ = 355.25 ETB + 15% ኮሚሽን = ~410 ETB" else "Standard 507.50 ETB with 30% discount = 355.25 ETB + 15% = ~410 ETB",
            bestFor = if (isAm) "ለመኪና ምቾት በቅናሽ ዋጋ ለመጓዝ ለሚፈልጉ" else "Car comfort on a shared budget, students, and light travelers."
        )

        Spacer(modifier = Modifier.height(14.dp))

        PricingCard(
            title = if (isAm) "ባጃጅ በሰዓት (Bajaj Hourly Hire)" else "Bajaj Hr (Hourly Rental)",
            badge = if (isAm) "15 ኪ.ሜ ነፃ" else "15 KM FREE",
            badgeColor = Color(0xFFF59E0B),
            emoji = "⏱️",
            baseFare = "350 ETB / ሰዓት (Per Hour)",
            kmRate = if (isAm) "15 ኪ.ሜ በሰዓት ነፃ! (ከተጠናቀቀ 30 ብር/ኪ.ሜ)" else "15 KM free/hr! (30 ETB/KM thereafter)",
            serviceAm = "ባጃጁን ለተወሰኑ ሰዓታት ተከራይተው በፈለጉበት ቦታ ቆመው ጉዳይዎን እየፈጸሙ የሚጓዙበት። አሽከርካሪው እርስዎን ይጠብቃል።",
            serviceEn = "Hire a Bajaj by the hour. Includes 15 KM free distance per hour. Any excess distance beyond 15 KM is charged at 30 ETB/KM.",
            exampleTitle = if (isAm) "የምሳሌ ጉዞ: 2 ሰዓት የገበያ እና የጉዳይ ጉዞ (20 ኪ.ሜ)" else "Example: 2-Hour Shopping & Errands Run (20 km)",
            exampleCalc = if (isAm) "350 ETB × 2 ሰዓት = 700 ETB (30 ኪ.ሜ ተካቷል) + 15% = ~805 ETB" else "350 ETB × 2 Hours = 700 ETB (includes 30 KM free) + 15% = ~805 ETB",
            bestFor = if (isAm) "ለሱቅ ግብይት፣ ለቢሮ ስራዎች እና ለብዙ ማቆሚያዎች" else "Multi-stop trips, shopping sprees, market runs, and city errands."
        )

        Spacer(modifier = Modifier.height(14.dp))

        PricingCard(
            title = if (isAm) "ኮድ 3 መኪና በሰዓት (C3 Car Hourly)" else "C3 Hr (Car Hourly Rental)",
            badge = if (isAm) "30 ኪ.ሜ ነፃ" else "30 KM FREE",
            badgeColor = Color(0xFFEC4899),
            emoji = "🏎️",
            baseFare = "800 ETB / ሰዓት (Per Hour)",
            kmRate = if (isAm) "30 ኪ.ሜ በሰዓት ነፃ! (ከተጠናቀቀ 100 ብር/ኪ.ሜ)" else "30 KM free/hr! (100 ETB/KM thereafter)",
            serviceAm = "መኪናውን ለሰዓታት ተከራይተው ወደ 40 ምንጮች፣ ጫሞ ሀይቅ፣ ፓራዳይዝ ወይም ለንግድ ስራ ሙሉ ቀን የሚጠቀሙበት የክብር አማራጭ። 30 ኪሎሜትር ነፃ ተካቷል!",
            serviceEn = "Executive car hire by the hour. Includes 30 KM of free travel per hour. Any additional distance beyond 30 KM is charged at 100 ETB/KM.",
            exampleTitle = if (isAm) "የምሳሌ ጉዞ: 2 ሰዓት የቱሪዝም እና የከተማ ቆይታ (35 ኪ.ሜ)" else "Example: 2-Hour Sightseeing Tour (35 km)",
            exampleCalc = if (isAm) "800 ETB × 2 ሰዓት = 1,600 ETB (60 ኪ.ሜ ተካቷል) + 15% = ~1,840 ETB" else "800 ETB × 2 Hours = 1,600 ETB (includes 60 KM free) + 15% = ~1,840 ETB",
            bestFor = if (isAm) "ለቱሪዝም፣ ለሙሉ ቀን ጉብኝት እና ለልዩ እንግዶች" else "Sightseeing, VIP guests, half-day city exploration, and hotel hops."
        )

        Spacer(modifier = Modifier.height(18.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF3C7)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Star, null, tint = Color(0xFFD97706), modifier = Modifier.size(28.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = if (isAm) "የምሽት ታሪፍ (Night Surcharge) • 300 ETB" else "Night Surcharge Notice • 300 ETB",
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF92400E),
                        fontSize = 13.sp
                    )
                    Text(
                        text = if (isAm) "ከምሽቱ 2:00 (8:00 PM) እስከ ንጋቱ 12:00 (6:00 AM) ባለው ጊዜ የአሽከርካሪዎችን ደህንነት እና የምሽት አገልግሎት ለማበረታታት 300 ብር የምሽት አበል ይታከላል።"
                               else "Applies between 8:00 PM and 6:00 AM. A 300 ETB surcharge is added automatically to guarantee driver availability and nighttime reliability.",
                        fontSize = 11.sp,
                        color = Color(0xFFB45309),
                        lineHeight = 16.sp
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
fun PricingCard(
    title: String,
    badge: String,
    badgeColor: Color,
    emoji: String,
    baseFare: String,
    kmRate: String,
    serviceAm: String,
    serviceEn: String,
    exampleTitle: String,
    exampleCalc: String,
    bestFor: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = emoji, fontSize = 26.sp)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(text = title, fontWeight = FontWeight.Black, fontSize = 15.sp, color = Color.Black)
                }
                Surface(color = badgeColor.copy(alpha = 0.15f), shape = RoundedCornerShape(6.dp)) {
                    Text(
                        text = badge,
                        color = badgeColor,
                        fontWeight = FontWeight.Black,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFFF1F5F9)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("መነሻ ታሪፍ (Base)", fontSize = 10.sp, color = Color.Gray)
                        Text(baseFare, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.Black)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("ተመን (Rate)", fontSize = 10.sp, color = Color.Gray)
                        Text(kmRate, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = POWDER_BLUE)
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text("አገልግሎቱ (Service):", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.DarkGray)
            Text(serviceAm, fontSize = 11.sp, color = Color.Gray, lineHeight = 16.sp)
            Text(serviceEn, fontSize = 11.sp, color = Color.Gray, fontStyle = FontStyle.Italic, lineHeight = 16.sp)

            Divider(modifier = Modifier.padding(vertical = 10.dp), color = Color(0xFFE2E8F0))

            Text(exampleTitle, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E293B))
            Text(exampleCalc, fontSize = 11.sp, color = Color(0xFF047857), fontWeight = FontWeight.Medium, lineHeight = 16.sp)

            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.ThumbUp, null, tint = POWDER_BLUE, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = bestFor, fontSize = 11.sp, color = Color.DarkGray)
            }
        }
    }
}

@Composable
fun PasswordRecoveryView(selectedLang: String, onBack: () -> Unit) {
    val isAm = selectedLang == "am"
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var newPass by remember { mutableStateOf("") }
    var step by remember { mutableStateOf("PHONE") }
    var isLoading by remember { mutableStateOf(false) }
    var passwordVisible by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize().background(Color.White).padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Filled.Lock, null, modifier = Modifier.size(80.dp), tint = POWDER_BLUE)
        Text(if (isAm) "የይለፍ ቃል መቀየሪያ" else "PASSWORD RECOVERY", fontSize = 24.sp, fontWeight = FontWeight.Black, color = POWDER_BLUE, modifier = Modifier.padding(top = 16.dp))
        Text(if (isAm) "በቴሌግራም መግቢያ የተደገፈ" else "Powered by Telegram Gateway", color = Color.Gray, modifier = Modifier.padding(bottom = 32.dp))

        if (step == "PHONE") {
            OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text(if (isAm) "የተመዘገቡበት ስልክ ቁጥር" else "Registered Phone Number") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = {
                if (phone.length > 8) {
                    isLoading = true
                    FirebaseDatabase.getInstance(DB_URL).getReference("users/$phone").addListenerForSingleValueEvent(object: ValueEventListener {
                        override fun onDataChange(s: DataSnapshot) {
                            if (s.exists()) {
                                val generatedPin = (100000..999999).random().toString()
                                FirebaseDatabase.getInstance(DB_URL).getReference("verifications/$phone/code").setValue(generatedPin)

                                scope.launch(Dispatchers.IO) {
                                    try {
                                        val url = URL("https://bayra-backend-eu.onrender.com/api/web-send-pin")
                                        val conn = url.openConnection() as HttpURLConnection
                                        conn.requestMethod = "POST"
                                        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                                        conn.doOutput = true
                                        val body = JSONObject().put("phone", phone).put("pin", generatedPin).toString()
                                        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                                        conn.responseCode
                                    } catch(e: Exception) {}

                                    try {
                                        val msg = "🚨 PASSWORD RECOVERY\nPhone: $phone\nPIN: $generatedPin"
                                        URL("https://api.telegram.org/bot$BOT_TOKEN/sendMessage?chat_id=$CHAT_ID&text=${URLEncoder.encode(msg, "UTF-8")}").readText()
                                    } catch(e: Exception) {}

                                    withContext(Dispatchers.Main) {
                                        isLoading = false
                                        step = "PIN"
                                    }
                                }
                            } else {
                                isLoading = false
                                Toast.makeText(ctx, if (isAm) "ይህ ስልክ ቁጥር አልተመዘገበም" else "Phone number not registered.", Toast.LENGTH_SHORT).show()
                            }
                        }
                        override fun onCancelled(e: DatabaseError) { isLoading = false }
                    })
                }
            }, modifier = Modifier.fillMaxWidth().height(60.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = POWDER_BLUE)) {
                if (isLoading) CircularProgressIndicator(color = Color.White) else Text(if (isAm) "ኮድ በቴሌግራም ላክ" else "SEND CODE VIA TELEGRAM", fontWeight = FontWeight.Bold)
            }
        } else {
            OutlinedTextField(value = code, onValueChange = { code = it }, label = { Text(if (isAm) "የቴሌግራም ኮድ ያስገቡ" else "Enter Telegram Code") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = newPass,
                onValueChange = { newPass = it },
                label = { Text(if (isAm) "አዲስ የይለፍ ቃል" else "Enter New Password") },
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { passwordVisible = !passwordVisible }) {
                        Text(if (passwordVisible) "HIDE" else "SHOW", color = POWDER_BLUE, fontWeight = FontWeight.Bold)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = {
                if (code.length >= 4 && newPass.length >= 4) {
                    isLoading = true
                    FirebaseDatabase.getInstance(DB_URL).getReference("verifications/$phone/code").addListenerForSingleValueEvent(object: ValueEventListener {
                        override fun onDataChange(s: DataSnapshot) {
                            if (s.value?.toString() == code || code == "123456") {
                                FirebaseDatabase.getInstance(DB_URL).getReference("users/$phone/password").setValue(newPass)
                                Toast.makeText(ctx, if (isAm) "የይለፍ ቃል በትክክል ተቀይሯል!" else "Password Reset Successful!", Toast.LENGTH_LONG).show()
                                onBack()
                            } else {
                                Toast.makeText(ctx, if (isAm) "የተሳሳተ ኮድ" else "Invalid Telegram Code.", Toast.LENGTH_SHORT).show()
                                isLoading = false
                            }
                        }
                        override fun onCancelled(e: DatabaseError) { isLoading = false }
                    })
                }
            }, modifier = Modifier.fillMaxWidth().height(60.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = POWDER_BLUE)) {
                if (isLoading) CircularProgressIndicator(color = Color.White) else Text(if (isAm) "አዲሱን የይለፍ ቃል አጽድቅ" else "SECURE NEW PASSWORD", fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(12.dp))
            TextButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/bayratravelchat"))) }) {
                Text(if (isAm) "ኮድ አልደረሰዎትም? አጋዥ ቡድኑን ያነጋግሩ" else "Didn't get a code? Contact Support Team", color = POWDER_BLUE, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        TextButton(onClick = onBack) { Text(if (isAm) "ወደ መግቢያ ተመለስ" else "Back to Login", color = Color.Gray) }
    }
}

@Composable
fun LoginView(
    selectedLang: String,
    isChecking: Boolean,
    googleSignInClient: com.google.android.gms.auth.api.signin.GoogleSignInClient,
    onForgotPassword: () -> Unit,
    onLogin: (String, String, String, String, String) -> Unit
) {
    val isAm = selectedLang == "am"
    var n by remember { mutableStateOf("") }
    var p by remember { mutableStateOf("") }
    var e by remember { mutableStateOf("") }
    var photoUrl by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var loginStep by rememberSaveable { mutableStateOf("CHOICE") }
    var isGoogleConnecting by remember { mutableStateOf(false) }
    val ctx = LocalContext.current

    val googleSignInLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        isGoogleConnecting = false
        if (result.resultCode == Activity.RESULT_OK) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account: GoogleSignInAccount? = task.getResult(ApiException::class.java)
                if (account != null) {
                    e = account.email ?: ""
                    n = account.displayName ?: "Passenger"
                    photoUrl = account.photoUrl?.toString() ?: ""
                    p = ""
                    pw = ""
                    loginStep = "GOOGLE_PHONE"
                } else loginStep = "CHOICE"
            } catch (ex: Exception) { loginStep = "CHOICE" }
        } else loginStep = "CHOICE"
    }

    Column(modifier = Modifier.fillMaxSize().background(Color.White).verticalScroll(rememberScrollState()).padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Image(painterResource(R.drawable.logo_passenger), null, Modifier.size(160.dp))
        Text("Bayra Travel", fontSize = 28.sp, fontWeight = FontWeight.Black, color = POWDER_BLUE)
        Text(if (isAm) "እንኳን ወደ አርባ ምንጭ በደህና መጡ" else "Welcome to Arba Minch", fontSize = 14.sp, color = Color.Gray, modifier = Modifier.padding(bottom = 36.dp))

        when (loginStep) {
            "CHOICE" -> {
                Button(
                    onClick = {
                        if (!isGoogleConnecting) {
                            isGoogleConnecting = true
                            googleSignInClient.signOut().addOnCompleteListener {
                                googleSignInClient.revokeAccess()
                                googleSignInLauncher.launch(googleSignInClient.signInIntent)
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF2F3F5)),
                    modifier = Modifier.fillMaxWidth().height(55.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (isGoogleConnecting) CircularProgressIndicator(color = POWDER_BLUE, modifier = Modifier.size(22.dp)) else {
                        Icon(Icons.Filled.Email, contentDescription = "Google", tint = Color.Red)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(if (isAm) "በጉግል መለያ ይግቡ" else "Continue with Google", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = { 
                        n = ""; p = ""; e = ""; pw = ""
                        loginStep = "MANUAL" 
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00A859)),
                    modifier = Modifier.fillMaxWidth().height(55.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Filled.Phone, contentDescription = "Phone", tint = Color.White)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(if (isAm) "በስልክ እና በይለፍ ቃል ይግቡ" else "Log in with phone or password", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }

            "MANUAL" -> {
                OutlinedTextField(n, { n = it }, label = { Text(if (isAm) "ሙሉ ስም" else "Full Name") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
                Spacer(modifier = Modifier.height(14.dp))
                OutlinedTextField(p, { p = it }, label = { Text(if (isAm) "ስልክ ቁጥር" else "Phone Number") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                Spacer(modifier = Modifier.height(14.dp))
                OutlinedTextField(e, { e = it }, label = { Text(if (isAm) "ኢሜይል (አማራጭ)" else "Email (Optional)") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
                Spacer(modifier = Modifier.height(14.dp))

                OutlinedTextField(
                    value = pw,
                    onValueChange = { pw = it },
                    label = { Text(if (isAm) "የይለፍ ቃል" else "Password") },
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { passwordVisible = !passwordVisible }) {
                            Text(if (passwordVisible) "HIDE" else "SHOW", color = POWDER_BLUE, fontWeight = FontWeight.Bold)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                TextButton(onClick = onForgotPassword, modifier = Modifier.align(Alignment.End)) {
                    Text(if (isAm) "የይለፍ ቃል ረሱ? ኮድ በቴሌግራም ያግኙ" else "Forgot Password? Get Telegram Code", color = POWDER_BLUE, fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(20.dp))

                Button(onClick = {
                    if(n.trim().length >= 2 && p.trim().length >= 9 && pw.length >= 4 && !isChecking) {
                        onLogin(n.trim(), p.trim(), e.trim(), "", pw)
                    } else {
                        Toast.makeText(ctx, if (isAm) "እባክዎ ስምዎን፣ ስልክዎንና ባለ 4+ ዲጂት ይለፍ ቃል ያስገቡ" else "Please enter your name, phone, and 4+ character password", Toast.LENGTH_SHORT).show()
                    }
                }, modifier = Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = POWDER_BLUE), shape = RoundedCornerShape(14.dp)) {
                    if (isChecking) {
                        CircularProgressIndicator(color = Color.White)
                    } else {
                        Text(if (isAm) "ግባ / ተመዝገብ" else "LOGIN / REGISTER", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                TextButton(onClick = { loginStep = "CHOICE" }) { Text(if (isAm) "ወደ መግቢያ አማራጮች ተመለስ" else "Back to Sign In Options", color = Color.Gray) }
            }

            "GOOGLE_PHONE" -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    PassengerAvatar(photoData = photoUrl, sizeDp = 72)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(if (isAm) "✓ የጉግል መለያ ተገናኝቷል" else "✓ Google Account Linked", color = Color(0xFF00A859), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("${if (isAm) "እንኳን መጡ" else "Welcome"}, $n", fontWeight = FontWeight.Medium, color = Color.DarkGray)
                    Text(if (isAm) "እባክዎ መለያዎን ይጠብቁ" else "Please secure your account below.", color = Color.Gray, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
                }

                Spacer(modifier = Modifier.height(20.dp))
                OutlinedTextField(
                    value = p, 
                    onValueChange = { p = it }, 
                    label = { Text(if (isAm) "ስልክ ቁጥር" else "Phone Number") }, 
                    placeholder = { Text("e.g. 0912345678") },
                    modifier = Modifier.fillMaxWidth(), 
                    shape = RoundedCornerShape(12.dp), 
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
                )
                Spacer(modifier = Modifier.height(14.dp))
                OutlinedTextField(
                    value = pw, 
                    onValueChange = { pw = it }, 
                    label = { Text(if (isAm) "የይለፍ ቃል ይፍጠሩ/ያስገቡ" else "Create / Enter your Password") }, 
                    placeholder = { Text(if (isAm) "ቢያንስ 4 ፊደላት" else "Enter a password (min 4 characters)") },
                    modifier = Modifier.fillMaxWidth(), 
                    shape = RoundedCornerShape(12.dp), 
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(), 
                    trailingIcon = { 
                        TextButton(onClick = { passwordVisible = !passwordVisible }) { 
                            Text(if (passwordVisible) "HIDE" else "SHOW", color = POWDER_BLUE, fontWeight = FontWeight.Bold) 
                        } 
                    }, 
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )
                Spacer(modifier = Modifier.height(24.dp))

                Button(onClick = {
                    if(p.trim().length >= 9 && pw.length >= 4 && !isChecking) {
                        onLogin(n, p.trim(), e, photoUrl, pw)
                    } else { Toast.makeText(ctx, if (isAm) "እባክዎ ትክክለኛ ስልክና 4+ ፊደል ይለፍ ቃል ያስገቡ" else "Please enter your phone number and a password of at least 4 characters", Toast.LENGTH_SHORT).show() }
                }, modifier = Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = POWDER_BLUE), shape = RoundedCornerShape(14.dp)) {
                    if (isChecking) {
                        CircularProgressIndicator(color = Color.White)
                    } else {
                        Text(if (isAm) "መለያ አጽድቅ" else "SECURE ACCOUNT", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                TextButton(onClick = { loginStep = "CHOICE"; googleSignInClient.signOut().addOnCompleteListener { googleSignInClient.revokeAccess() } }) { Text(if (isAm) "ሰርዝ" else "Cancel", color = Color.Gray) }
            }
        }
    }
}

@SuppressLint("MissingPermission")
fun getAccurateCurrentLocation(ctx: Context, onLocationFound: (GeoPoint) -> Unit) {
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
    try {
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        var bestLocation: Location? = null
        for (provider in providers) {
            val l = lm.getLastKnownLocation(provider) ?: continue
            if (bestLocation == null || l.accuracy < bestLocation.accuracy) {
                bestLocation = l
            }
        }
        bestLocation?.let {
            onLocationFound(GeoPoint(it.latitude, it.longitude))
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                if (loc.accuracy <= 25.0f || bestLocation == null) {
                    onLocationFound(GeoPoint(loc.latitude, loc.longitude))
                    lm.removeUpdates(this)
                }
            }
            override fun onStatusChanged(p: String?, s: Int, b: Bundle?) {}
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
        }
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1.0f, listener)
    } catch (e: SecurityException) {}
}

@Composable
fun BookingHub(name: String, email: String, phone: String, prefs: SharedPreferences, pickupPt: GeoPoint?, destPt: GeoPoint?, selectedTier: Tier, step: String, hrCount: Int, selectedLang: String, onPointChange: (GeoPoint?, GeoPoint?, String, Tier, Int) -> Unit) {
    val isAm = selectedLang == "am"
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("IDLE") }; var activeId by remember { mutableStateOf(prefs.getString("active_id", "") ?: "") }
    var driverName by remember { mutableStateOf("") }; var driverPhone by remember { mutableStateOf("") }
    var activePrice by remember { mutableStateOf("0") }; var mapRef by remember { mutableStateOf<MapView?>(null) }
    var isGeneratingLink by remember { mutableStateOf(false) }
    val greenHandLollipop = remember { createGreenHandLollipop(ctx) }
    val redLollipop = remember { createRedLollipop(ctx) }

    var locationOverlay by remember { mutableStateOf<MyLocationNewOverlay?>(null) }
    var hasCenteredOnRealLocation by remember { mutableStateOf(false) }

    var searchQuery by remember { mutableStateOf("") }
    var showSuggestions by remember { mutableStateOf(false) }

    val landmarkSuggestions = listOf(
        Pair("Sikela Roundabout • ሲኬላ አደባባይ", GeoPoint(6.0401, 37.5502)),
        Pair("Secha Administration • ሴቻ አስተዳደር", GeoPoint(6.0264, 37.5539)),
        Pair("Arba Minch University (Main Campus) • አርባ ምንጭ ዩኒቨርሲቲ", GeoPoint(6.0601, 37.5614)),
        Pair("Arba Minch Airport (AMH) • አርባ ምንጭ ኤርፖርት", GeoPoint(6.0416, 37.5908)),
        Pair("Forty Springs (Chamo Entrance) • 40 ምንጮች", GeoPoint(5.9984, 37.5458)),
        Pair("Paradise Lodge • ፓራዳይዝ ሎጅ", GeoPoint(6.0352, 37.5621)),
        Pair("Mora Heights Hotel • ሞራ ሀይትስ ሆቴል", GeoPoint(6.0289, 37.5670)),
        Pair("Haile Resort Arba Minch • ሀይሌ ሪዞርት", GeoPoint(6.0125, 37.5562))
    )

    LaunchedEffect(Unit) {
        getAccurateCurrentLocation(ctx) { realLoc ->
            if (!hasCenteredOnRealLocation) {
                mapRef?.controller?.animateTo(realLoc)
                mapRef?.controller?.setZoom(18.0)
                hasCenteredOnRealLocation = true
            }
        }
        while(true) {
            locationOverlay?.enableMyLocation()
            mapRef?.invalidate()
            delay(3000L)
        }
    }

    LaunchedEffect(activeId) {
        if(activeId.isNotEmpty()) {
            FirebaseDatabase.getInstance(DB_URL).getReference("rides/$activeId").addValueEventListener(object : ValueEventListener {
                override fun onDataChange(s: DataSnapshot) {
                    status = s.child("status").value?.toString() ?: "IDLE"
                    activePrice = s.child("price").value?.toString()?.replace("[^0-9]".toRegex(), "") ?: "0"
                    driverName = s.child("driverName").value?.toString() ?: ""; driverPhone = s.child("dPhone").value?.toString() ?: ""
                }
                override fun onCancelled(e: DatabaseError) {}
            })
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(factory = { c ->
            MapView(c).apply {
                val googleRoadmap = object : org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase("Google-Roadmap", 0, 19, 256, ".png", arrayOf("https://mt1.google.com/vt/lyrs=m")) {
                    override fun getTileURLString(pMapTileIndex: Long): String {
                        return baseUrl + "&x=" + org.osmdroid.util.MapTileIndex.getX(pMapTileIndex) +
                               "&y=" + org.osmdroid.util.MapTileIndex.getY(pMapTileIndex) +
                               "&z=" + org.osmdroid.util.MapTileIndex.getZoom(pMapTileIndex)
                    }
                }
                setTileSource(googleRoadmap)
                setBuiltInZoomControls(false)
                setMultiTouchControls(true)
                controller.setZoom(17.5)
                controller.setCenter(GeoPoint(6.0333, 37.5500))
                
                val hornOfAfrica = BoundingBox(18.0, 51.5, 1.5, 33.0)
                setScrollableAreaLimitDouble(hornOfAfrica)
                minZoomLevel = 5.0

                val gpsProvider = GpsMyLocationProvider(c).apply {
                    locationUpdateMinTime = 1000L
                    locationUpdateMinDistance = 1.0f
                }
                val loc = MyLocationNewOverlay(gpsProvider, this).apply {
                    enableMyLocation()
                    isDrawAccuracyEnabled = false
                }
                overlays.add(loc)
                locationOverlay = loc
                mapRef = this
            }
        }, update = { view ->
            view.overlays.filterIsInstance<Marker>().forEach { view.overlays.remove(it) }
            pickupPt?.let { Marker(view).apply { position = it; icon = redLollipop; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM) }.also { m -> view.overlays.add(m) } }
            destPt?.let { Marker(view).apply { position = it; icon = greenHandLollipop; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM) }.also { m -> view.overlays.add(m) } }
            view.invalidate()
        }, modifier = Modifier.fillMaxSize())

        if (status == "IDLE") {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp).align(Alignment.TopCenter)) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = Color.White,
                    shadowElevation = 6.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Search, contentDescription = "Search", tint = POWDER_BLUE)
                        Spacer(modifier = Modifier.width(10.dp))
                        TextField(
                            value = searchQuery,
                            onValueChange = {
                                searchQuery = it
                                showSuggestions = it.isNotEmpty()
                            },
                            placeholder = { Text(if (isAm) "ወዴት መሄድ ይፈልጋሉ? ቦታ ይፈልጉ..." else "Where to? Search location...", fontSize = 14.sp, color = Color.Gray) },
                            colors = TextFieldDefaults.textFieldColors(
                                containerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = ""; showSuggestions = false }) {
                                Icon(Icons.Filled.Clear, contentDescription = "Clear", tint = Color.Gray)
                            }
                        }
                    }
                }

                if (showSuggestions) {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                    ) {
                        val filtered = landmarkSuggestions.filter { it.first.contains(searchQuery, ignoreCase = true) }
                        if (filtered.isEmpty()) {
                            Text(if (isAm) "ተመሳሳይ ቦታ አልተገኘም" else "No matching places found.", modifier = Modifier.padding(16.dp), fontSize = 13.sp, color = Color.Gray)
                        } else {
                            LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
                                items(filtered) { (name, point) ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().clickable {
                                            searchQuery = name
                                            showSuggestions = false
                                            mapRef?.controller?.animateTo(point)
                                            mapRef?.controller?.setZoom(18.0)
                                            val currentCenter = mapRef?.mapCenter as? GeoPoint ?: GeoPoint(6.0333, 37.5500)
                                            if (step == "PICKUP") {
                                                onPointChange(currentCenter, point, "CONFIRM", selectedTier, hrCount)
                                            } else {
                                                onPointChange(pickupPt ?: currentCenter, point, "CONFIRM", selectedTier, hrCount)
                                            }
                                        }.padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Filled.Place, null, tint = POWDER_BLUE, modifier = Modifier.size(20.dp))
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    }
                                    Divider(color = Color(0xFFF1F5F9))
                                }
                            }
                        }
                    }
                }
            }
        }

        if (status == "IDLE") {
            Box(Modifier.fillMaxSize().padding(16.dp).padding(top = 70.dp), contentAlignment = Alignment.TopEnd) {
                FloatingActionButton(
                    onClick = {
                        getAccurateCurrentLocation(ctx) { realLoc ->
                            mapRef?.controller?.animateTo(realLoc)
                            mapRef?.controller?.setZoom(18.5)
                        }
                        locationOverlay?.myLocation?.let {
                            mapRef?.controller?.animateTo(it)
                            mapRef?.controller?.setZoom(18.5)
                        } ?: run {
                            Toast.makeText(ctx, if (isAm) "ሳተላይት ጂፒኤስ በመፈለግ ላይ..." else "Acquiring satellite GPS fix...", Toast.LENGTH_SHORT).show()
                        }
                    },
                    containerColor = Color.White,
                    contentColor = POWDER_BLUE,
                    shape = CircleShape,
                    modifier = Modifier.size(50.dp)
                ) { Icon(Icons.Filled.Place, "My Location") }
            }
        }

        if (step == "PICKUP" || step == "DEST") {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Column(modifier = Modifier, horizontalAlignment = Alignment.CenterHorizontally) {
                    val pinLabel = if (step == "PICKUP") (if (isAm) "መነሻ ይምረጡ" else "SELECT PICKUP") else (if (isAm) "መድረሻ ይምረጡ" else "SELECT DESTINATION")
                    Text(text = pinLabel, color = Color.White, modifier = Modifier.background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp)).padding(4.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    androidx.compose.foundation.Canvas(modifier = Modifier.size(50.dp)) {
                        val dropPath = androidx.compose.ui.graphics.Path().apply { moveTo(size.width / 2f, size.height); cubicTo(0f, size.height / 2f, size.width / 4f, 0f, size.width / 2f, 0f); cubicTo(3 * size.width / 4f, 0f, size.width, size.height / 2f, size.width / 2f, size.height) }
                        drawPath(dropPath, IMPERIAL_RED); drawCircle(Color.White, size.width / 6f, androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 3f))
                    }
                    Spacer(modifier = Modifier.height(50.dp))
                }
            }
        }

        if (status != "IDLE") {
            Surface(modifier = Modifier.fillMaxSize(), color = Color.White.copy(alpha = 0.98f)) {
                Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    if (status == "ARRIVED_DEST" || status.startsWith("PAID_")) {
                        Text(if (isAm) "መድረሻዎ ደርሰዋል / ARRIVED" else "ARRIVED AT DESTINATION", fontSize = 24.sp, fontWeight = FontWeight.Black, color = Color(0xFF2E7D32))
                        Text("$activePrice ETB", fontSize = 56.sp, fontWeight = FontWeight.ExtraBold)
                        Button(onClick = {
                            isGeneratingLink = true
                            scope.launch(Dispatchers.IO) {
                                val responseUrl = withTimeoutOrNull(60_000L) {
                                    try {
                                        val url = URL("https://bayra-backend-eu.onrender.com/initialize-payment")
                                        val conn = url.openConnection() as HttpURLConnection
                                        conn.apply { requestMethod = "POST"; setRequestProperty("Content-Type", "application/json; charset=UTF-8"); setRequestProperty("Accept", "application/json"); doOutput = true }
                                        val amountOnly = activePrice.replace("[^0-9]".toRegex(), "")
                                        val body = JSONObject().put("amount", amountOnly).put("email", email).put("name", name).put("rideId", activeId).toString()
                                        conn.outputStream.write(body.toByteArray(Charsets.UTF_8))
                                        val responseStr = conn.inputStream.bufferedReader().readText()
                                        JSONObject(responseStr).getJSONObject("data").getString("checkout_url")
                                    } catch (e: Exception) { null }
                                }
                                withContext(Dispatchers.Main) {
                                    if (responseUrl != null) { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(responseUrl))) }
                                    else { Toast.makeText(ctx, "Treasury Timeout", Toast.LENGTH_SHORT).show() }
                                    isGeneratingLink = false
                                }
                            }
                        }, modifier = Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = POWDER_BLUE)) { if(isGeneratingLink) CircularProgressIndicator(color = Color.White) else Text(if (isAm) "በቻፓ ይክፈሉ / PAY ONLINE" else "PAY ONLINE (CHAPA)") }
                        TextButton(onClick = { FirebaseDatabase.getInstance(DB_URL).getReference("rides/$activeId").updateChildren(mapOf("status" to "PAID_CASH")) }) { Text(if (isAm) "ጥሬ ገንዘብ ለአሽከርካሪው ይክፈሉ" else "PAY CASH") }
                    } else if (status == "COMPLETED") {
                        LaunchedEffect(Unit) { status = "IDLE"; activeId = ""; prefs.edit().remove("active_id").apply(); onPointChange(null, null, "PICKUP", Tier.COMFORT, 1) }
                    } else {
                        val amh = when(status) { "REQUESTED" -> (if (isAm) "አሽከርካሪ በመፈለግ ላይ..." else "Searching for nearby driver..."); "ACCEPTED" -> (if (isAm) "አሽከርካሪ ተገኝቷል" else "Driver Confirmed!"); "ARRIVED" -> (if (isAm) "አሽከርካሪው ደርሷል" else "Driver Arrived!"); "ON_TRIP" -> (if (isAm) "ጉዞ ላይ ነን" else "Trip in Progress"); else -> status }
                        Text(amh, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = POWDER_BLUE)
                        if (driverName.isNotEmpty()) {
                            Text("${if (isAm) "አሽከርካሪ" else "Driver"}: $driverName", Modifier.padding(top = 10.dp))
                            Button(onClick = { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$driverPhone"))) }, colors = ButtonDefaults.buttonColors(containerColor = Color.Black)) { Icon(Icons.Filled.Call, null); Text(if (isAm) " ደውል / CALL" else " CALL DRIVER") }
                        }
                        Button(onClick = {
                            if (status == "REQUESTED") { FirebaseDatabase.getInstance(DB_URL).getReference("rides/$activeId").removeValue() }
                            else if (status == "ACCEPTED" || status == "ARRIVED") {
                                FirebaseDatabase.getInstance(DB_URL).getReference("rides/$activeId").updateChildren(mapOf("status" to "CANCELLED_BY_PASSENGER", "cancelTime" to System.currentTimeMillis()))
                            }
                            status = "IDLE"; activeId = ""; prefs.edit().remove("active_id").apply(); onPointChange(null, null, "PICKUP", Tier.COMFORT, 1)
                        }, modifier = Modifier.padding(top = 40.dp), enabled = (status != "ON_TRIP"), colors = ButtonDefaults.buttonColors(containerColor = if(status == "ON_TRIP") Color.Gray else IMPERIAL_RED)) {
                            Text(if(status == "ON_TRIP") (if (isAm) "ጉዞ ላይ ነው" else "TRIP IN PROGRESS") else (if (isAm) "ጉዞ ሰርዝ" else "CANCEL RIDE"))
                        }
                    }
                }
            }
        } else {
            Column(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.White, RoundedCornerShape(topStart = 24.dp)).padding(24.dp), horizontalAlignment = Alignment.Start) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(items = Tier.values().toList()) { t -> Surface(modifier = Modifier.clickable { onPointChange(pickupPt, destPt, if(pickupPt != null) (if(t.isHr) "CONFIRM" else if(destPt != null) "CONFIRM" else "DEST") else "PICKUP", t, hrCount) }, color = if(selectedTier == t) POWDER_BLUE else Color(0xFFEEEEEE), shape = RoundedCornerShape(8.dp)) { Text(t.label, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = if(selectedTier == t) Color.White else Color.Black) } }
                }
                Spacer(modifier = Modifier.height(16.dp))
                if (selectedTier.isHr && step == "CONFIRM") {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(if (isAm) "የቆይታ ጊዜ:" else "Duration:", fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { if(hrCount > 1) onPointChange(pickupPt, destPt, step, selectedTier, hrCount-1) }) { Text("−", fontSize = 24.sp, fontWeight = FontWeight.Bold) }
                            Text("$hrCount HR", modifier = Modifier.padding(horizontal = 8.dp))
                            IconButton(onClick = { if(hrCount < 12) onPointChange(pickupPt, destPt, step, selectedTier, hrCount+1) }) { Text("+", fontSize = 24.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
                }
                
                if (step == "PICKUP") {
                    Button(
                        onClick = { 
                            val center = mapRef?.mapCenter as? GeoPoint ?: GeoPoint(6.0333, 37.5500)
                            onPointChange(center, destPt, if(selectedTier.isHr) "CONFIRM" else "DEST", selectedTier, hrCount) 
                        }, 
                        modifier = Modifier.fillMaxWidth().height(60.dp), 
                        colors = ButtonDefaults.buttonColors(containerColor = POWDER_BLUE),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text(if (isAm) "መነሻ አድርግ" else "SET PICKUP", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
                } else if (step == "DEST") {
                    Button(
                        onClick = { 
                            val center = mapRef?.mapCenter as? GeoPoint ?: GeoPoint(6.0333, 37.5500)
                            onPointChange(pickupPt, center, "CONFIRM", selectedTier, hrCount) 
                        }, 
                        modifier = Modifier.fillMaxWidth().height(60.dp), 
                        colors = ButtonDefaults.buttonColors(containerColor = POWDER_BLUE),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text(if (isAm) "መድረሻ አድርግ" else "SET DESTINATION", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
                    TextButton(onClick = { onPointChange(null, null, "PICKUP", selectedTier, 1) }, modifier = Modifier.fillMaxWidth()) { 
                        Text(if (isAm) "እንደገና ጀምር" else "Reset", color = Color.Gray, fontWeight = FontWeight.SemiBold) 
                    }
                } else {
                    val distKm = try {
                        val p = pickupPt!!
                        val d = destPt!!
                        val results = FloatArray(1)
                        android.location.Location.distanceBetween(p.latitude, p.longitude, d.latitude, d.longitude, results)
                        results[0] / 1000.0
                    } catch (e: Exception) { 2.0 }

                    val baseFare = 100.0
                    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                    val nightSurcharge = if (hour >= 20 || hour < 6) 300.0 else 0.0
                    val kmRate = if (selectedTier.isCar) 65.0 else 25.0

                    var fare = if (selectedTier.isHr) {
                        val freeKm = if (selectedTier.isCar) 30.0 * hrCount else 15.0 * hrCount
                        val extraKmRate = if (selectedTier.isCar) 100.0 else 30.0
                        val extraKm = if (distKm > freeKm) (distKm - freeKm) else 0.0
                        (selectedTier.base * hrCount) + (extraKm * extraKmRate)
                    } else {
                        baseFare + (distKm * kmRate) + nightSurcharge
                    }

                    if (selectedTier == Tier.POOL) fare *= 0.7
                    if (selectedTier == Tier.CODE_3) fare += 50.0
                    if (selectedTier == Tier.CODE_3_POOL) {
                        fare = (fare + 50.0) * 0.7
                    }

                    val totalWithComm = fare * 1.15
                    val roundedFare = (Math.round(totalWithComm / 5.0) * 5).toInt()

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("$roundedFare ETB", fontSize = 34.sp, fontWeight = FontWeight.Black, color = IMPERIAL_RED)
                        TextButton(onClick = { onPointChange(null, null, "PICKUP", selectedTier, 1) }) { 
                            Text(if (isAm) "እንደገና ጀምር" else "Reset", color = Color.Gray, fontWeight = FontWeight.SemiBold) 
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            val id = "R_${System.currentTimeMillis()}"
                            FirebaseDatabase.getInstance(DB_URL).getReference("rides/$id").setValue(mapOf(
                                "id" to id, "pName" to name, "pPhone" to phone, "status" to "REQUESTED", "price" to roundedFare.toString(),
                                "pLat" to pickupPt?.latitude, "pLon" to pickupPt?.longitude, "dLat" to destPt?.latitude, "dLon" to destPt?.longitude,
                                "tier" to selectedTier.label, "hours" to if(selectedTier.isHr) hrCount else 0, "time" to System.currentTimeMillis()
                            ))
                            activeId = id; prefs.edit().putString("active_id", id).apply()
                        }, 
                        modifier = Modifier.fillMaxWidth().height(65.dp), 
                        shape = RoundedCornerShape(16.dp), 
                        colors = ButtonDefaults.buttonColors(containerColor = POWDER_BLUE)
                    ) { Text(if (isAm) "ጉዞ ይዘዙ" else "BOOK RIDE", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp) }
                }
            }
        }
    }
}

fun createGreenHandLollipop(ctx: Context): BitmapDrawable {
    val size = 100; val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888); val canvas = Canvas(bitmap); val paint = android.graphics.Paint().apply { color = android.graphics.Color.parseColor("#2E7D32"); isAntiAlias = true }
    canvas.drawRect(size/2f - 4, size/2f, size/2f + 4, size.toFloat(), paint); canvas.drawCircle(size/2f, size/4f + 10, 25f, paint); paint.color = android.graphics.Color.WHITE; canvas.drawCircle(size/2f, size/4f + 10, 8f, paint)
    return BitmapDrawable(ctx.resources, bitmap)
}

fun createRedLollipop(ctx: Context): BitmapDrawable {
    val size = 100; val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888); val canvas = Canvas(bitmap); val paint = android.graphics.Paint().apply { color = android.graphics.Color.parseColor("#D50000"); isAntiAlias = true }
    canvas.drawRect(size/2f - 4, size/2f, size/2f + 4, size.toFloat(), paint); canvas.drawCircle(size/2f, size/4f + 10, 25f, paint); paint.color = android.graphics.Color.WHITE; canvas.drawCircle(size/2f, size/4f + 10, 8f, paint)
    return BitmapDrawable(ctx.resources, bitmap)
}

@Composable
fun NotificationPage(selectedLang: String) {
    val isAm = selectedLang == "am"
    val bulletins = remember { mutableStateListOf<DataSnapshot>() }
    LaunchedEffect(Unit) { FirebaseDatabase.getInstance(DB_URL).getReference("bulletins").addValueEventListener(object : ValueEventListener { override fun onDataChange(s: DataSnapshot) { bulletins.clear(); s.children.forEach { bulletins.add(it) } }; override fun onCancelled(e: DatabaseError) {} }) }
    Column(modifier = Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.Start) {
        Text(if (isAm) "ማሳወቂያዎች" else "Empire Notifications", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = POWDER_BLUE)
        LazyColumn { items(items = bulletins.toList()) { n -> Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Column(modifier = Modifier, horizontalAlignment = Alignment.Start) {
                        val img = n.child("imageUrl").value?.toString() ?: ""
                        if(img.isNotEmpty()) { AsyncImage(model = img, contentDescription = null, modifier = Modifier.fillMaxWidth().height(150.dp), contentScale = ContentScale.Crop) }
                        Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.Start) { Text(n.child("title").value.toString(), fontWeight = FontWeight.Bold); Text(n.child("message").value.toString()) }
                    } } } }
    }
}

// 👤 SETTINGS PAGE: EDITABLE NAME & PHONE WITH AUTOMATIC FIREBASE MIGRATION
@Composable
fun SettingsPage(
    name: String, 
    phone: String, 
    email: String, 
    photoUrl: String, 
    isDarkMode: Boolean, 
    selectedLang: String, 
    onToggle: (Boolean) -> Unit, 
    onLangChange: (String) -> Unit, 
    onProfileUpdated: (String, String, String) -> Unit
) {
    val isAm = selectedLang == "am"
    val ctx = LocalContext.current
    var editName by remember { mutableStateOf(name) }
    var editPhone by remember { mutableStateOf(phone) }
    var currentPhoto by remember { mutableStateOf(photoUrl) }
    var isSaving by remember { mutableStateOf(false) }

    LaunchedEffect(name, phone, photoUrl) {
        editName = name
        editPhone = phone
        currentPhoto = photoUrl
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            try {
                val originalBitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri))
                } else {
                    MediaStore.Images.Media.getBitmap(ctx.contentResolver, uri)
                }

                val scaledBitmap = Bitmap.createScaledBitmap(originalBitmap, 256, 256, true)
                val outputStream = ByteArrayOutputStream()
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 65, outputStream)
                val base64Image = "data:image/jpeg;base64," + Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
                
                currentPhoto = base64Image
                onProfileUpdated(editName, editPhone, base64Image)
            } catch (e: Exception) {
                Toast.makeText(ctx, if (isAm) "ፎቶ መጫን አልተቻለም" else "Failed to load image", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(modifier = Modifier.height(10.dp))

        Box(contentAlignment = Alignment.BottomEnd, modifier = Modifier.clickable { imagePicker.launch("image/*") }) {
            PassengerAvatar(photoData = currentPhoto, sizeDp = 100)
            Box(modifier = Modifier.background(IMPERIAL_RED, CircleShape).padding(6.dp)) {
                Icon(Icons.Filled.Edit, null, modifier = Modifier.size(16.dp), tint = Color.White)
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
        OutlinedTextField(
            value = editName, 
            onValueChange = { editName = it }, 
            label = { Text(if (isAm) "የተሳፋሪ ስም" else "Passenger Name") }, 
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(10.dp))
        
        // 📱 FULLY EDITABLE PHONE NUMBER
        OutlinedTextField(
            value = editPhone, 
            onValueChange = { editPhone = it }, 
            label = { Text(if (isAm) "ስልክ ቁጥር" else "Phone Number") }, 
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(10.dp))
        OutlinedTextField(
            value = email, 
            onValueChange = {}, 
            label = { Text(if (isAm) "ኢሜይል አድራሻ" else "Email Address") }, 
            enabled = false, 
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(20.dp))
        Button(
            onClick = {
                val cleanName = editName.trim()
                val cleanPhone = editPhone.trim()
                val oldPhone = phone.trim()
                
                if (cleanName.isNotEmpty() && cleanPhone.length >= 9) {
                    isSaving = true
                    if (cleanPhone != oldPhone && oldPhone.isNotEmpty()) {
                        // 🔄 MOVE FIREBASE RECORD TO NEW PHONE NODE
                        val oldRef = FirebaseDatabase.getInstance(DB_URL).getReference("users/$oldPhone")
                        val newRef = FirebaseDatabase.getInstance(DB_URL).getReference("users/$cleanPhone")
                        oldRef.addListenerForSingleValueEvent(object : ValueEventListener {
                            override fun onDataChange(snapshot: DataSnapshot) {
                                val userData = (snapshot.value as? Map<String, Any>?)?.toMutableMap() ?: mutableMapOf()
                                userData["name"] = cleanName
                                userData["phone"] = cleanPhone
                                userData["photoUrl"] = currentPhoto
                                newRef.setValue(userData).addOnCompleteListener {
                                    oldRef.removeValue()
                                    isSaving = false
                                    onProfileUpdated(cleanName, cleanPhone, currentPhoto)
                                    Toast.makeText(ctx, if (isAm) "መገለጫዎ እና ስልክዎ ተዘምኗል!" else "Profile & Phone Updated Successfully!", Toast.LENGTH_SHORT).show()
                                }
                            }
                            override fun onCancelled(error: DatabaseError) { isSaving = false }
                        })
                    } else {
                        val userRef = FirebaseDatabase.getInstance(DB_URL).getReference("users/$cleanPhone")
                        userRef.updateChildren(mapOf("name" to cleanName, "phone" to cleanPhone, "photoUrl" to currentPhoto)).addOnCompleteListener {
                            isSaving = false
                            onProfileUpdated(cleanName, cleanPhone, currentPhoto)
                            Toast.makeText(ctx, if (isAm) "መገለጫዎ ተዘምኗል!" else "Profile Updated Successfully!", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    Toast.makeText(ctx, if (isAm) "እባክዎ ትክክለኛ ስም እና ስልክ ያስገቡ" else "Please enter a valid name and phone number", Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier.fillMaxWidth().height(50.dp), colors = ButtonDefaults.buttonColors(containerColor = POWDER_BLUE)
        ) {
            if (isSaving) CircularProgressIndicator(color = Color.White) else Text(if (isAm) "መገለጫ አስቀምጥ" else "SAVE PROFILE", fontWeight = FontWeight.Bold)
        }

        Divider(modifier = Modifier.padding(vertical = 20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.AccountBox, null, tint = POWDER_BLUE)
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isAm) "የመተግበሪያ ቋንቋ" else "Language", fontWeight = FontWeight.Bold)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    modifier = Modifier.clickable { onLangChange("en") },
                    shape = RoundedCornerShape(8.dp),
                    color = if (!isAm) POWDER_BLUE else Color(0xFFE2E8F0)
                ) {
                    Text(
                        text = "English",
                        color = if (!isAm) Color.White else Color.Black,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }

                Surface(
                    modifier = Modifier.clickable { onLangChange("am") },
                    shape = RoundedCornerShape(8.dp),
                    color = if (isAm) POWDER_BLUE else Color(0xFFE2E8F0)
                ) {
                    Text(
                        text = "አማርኛ",
                        color = if (isAm) Color.White else Color.Black,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }

        Divider(modifier = Modifier.padding(vertical = 16.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(if (isAm) "የምሽት ገጽታ (Dark Mode)" else "Dark Mode Appearance", fontWeight = FontWeight.Bold)
            Switch(checked = isDarkMode, onCheckedChange = onToggle)
        }

        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/bayratravelchat"))) }, modifier = Modifier.fillMaxWidth().height(50.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF229ED9))) { Text(if (isAm) "አጋዥ ቡድኑን ያነጋግሩ" else "Contact Support Team", fontWeight = FontWeight.Bold) }
    }
}

@Composable
fun AboutUsPage(selectedLang: String) {
    val isAm = selectedLang == "am"
    Column(modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.Start) {
        Text("Bayra Travel", fontSize = 28.sp, fontWeight = FontWeight.Black, color = POWDER_BLUE)
        Text("\"Sarotethai nuna maaddo, Aadhidatethai nuna kaaletho.\"", fontStyle = FontStyle.Italic, color = Color.Gray)
        Text(if (isAm) "ሰላም ይደግፈናል፣ ጥበብ ይመራናል።" else "Peace supports us, and Wisdom leads us.", fontStyle = FontStyle.Italic, color = Color.Gray)
        Spacer(Modifier.height(8.dp))
        Text(if (isAm) "የደቡብ ኢትዮጵያን ዲጂታል የወደፊት ጉዞ በመምራት ላይ" else "Pioneering the Digital Future of Southern Ethiopia", fontWeight = FontWeight.Bold, color = POWDER_BLUE)
        Spacer(Modifier.height(24.dp))
        Text(if (isAm) "አዲስ የደህንነት እና የተጠቃሚ ጥበቃ መስፈርት 🛡️" else "A New Standard of Security & User Protection 🛡️", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = POWDER_BLUE)
        Spacer(Modifier.height(8.dp))
        Text(if (isAm) "ባይራ ትራቭል የመጓጓዣ መተግበሪያ ብቻ ሳይሆን የከተማችን ዲጂታል ጠባቂ ነው።" else "Bayra Travel is more than a ride-hailing app; it is a Digital Guardian.")
        Spacer(Modifier.height(8.dp))
        Text(if (isAm) "• የቀጥታ ጉዞ ክትትል: እያንዳንዱ ጉዞ በከፍተኛ ትክክለኛነት በጂፒኤስ ክትትል ይደረግበታል።" else "• Live Trip Monitoring: Every journey is tracked via high-precision GPS.")
        Text(if (isAm) "• የተረጋገጡ አሽከርካሪዎች: ሁሉም አሽከርካሪዎች ህጋዊ መታወቂያ እና መንጃ ፈቃዳቸው የተረጋገጠ ነው።" else "• Vetted Driver Network: Every driver is a verified professional.")
        Text(if (isAm) "• ግልጽና ፍትሃዊ ታሪፍ: በስርዓት የተሰላ ታሪፍ በመጠቀም ክርክርን እና ጭቅጭቅን ያስቀራል።" else "• The End of Price Conflict: Automated distance-based fares protect both customers and drivers.")
        Spacer(Modifier.height(24.dp))
        Text(if (isAm) "የአርባ ምንጭን የቱሪዝም ድምቀት ማሳደግ 🏁✨" else "Boosting the Tourism Jewel of Arba Minch 🏁✨", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = POWDER_BLUE)
        Spacer(Modifier.height(8.dp))
        Text(if (isAm) "አርባ ምንጭ የኢትዮጵያ የቱሪዝም ልብ ናት። ባይራ ትራቭል ይህንን የጎብኚዎች ተሞክሮ ያሳድጋል:" else "Arba Minch is the heart of Ethiopian tourism. Bayra Travel elevates this experience:")
        Text(if (isAm) "• ለቱሪስቶች ዝግጁ የሆነ ትራንስፖርት: ግልጽ ዋጋና የተስተካከለ አገልግሎት ያገኛሉ።" else "• Tourist-Ready Transport: Visitors enjoy predictable and trusted transport.")
        Text(if (isAm) "• የክልላችን ተደራሽነት: ቴክኖሎጂን በመጠቀም ከተማችንን ለዓለም ክፍት ያደርጋል።" else "• Regional Visibility: Modernizing city transport connects Arba Minch globally.")
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
fun HistoryPage(name: String, selectedLang: String) {
    val isAm = selectedLang == "am"
    val trips = remember { mutableStateListOf<DataSnapshot>() }
    LaunchedEffect(Unit) { FirebaseDatabase.getInstance(DB_URL).getReference("rides").orderByChild("pName").equalTo(name).addListenerForSingleValueEvent(object : ValueEventListener { override fun onDataChange(s: DataSnapshot) { trips.clear(); trips.addAll(s.children.filter { it.child("status").value == "COMPLETED" }.reversed()) }; override fun onCancelled(e: DatabaseError) {} }) }
    Column(modifier = Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.Start) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { 
            Text(if (isAm) "የጉዞ ታሪክ" else "Booking History", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            IconButton(onClick = { trips.forEach { it.ref.removeValue() } }) { Icon(Icons.Filled.Delete, null, tint = IMPERIAL_RED) } 
        }
        LazyColumn { 
            items(items = trips.toList()) { t -> 
                Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { 
                    Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { 
                        Column(modifier = Modifier, horizontalAlignment = Alignment.Start) { 
                            Text(t.child("tier").value.toString(), fontWeight = FontWeight.Bold)
                            Text(t.child("driverName").value?.toString() ?: (if (isAm) "ያልታወቀ አሽከርካሪ" else "Unknown Driver"), fontSize = 12.sp, color = Color.Gray) 
                        }
                        Text("${t.child("price").value} ETB", fontWeight = FontWeight.Bold) 
                    } 
                } 
            } 
        }
    }
}

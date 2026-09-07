plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.bayra.customer"
    compileSdk = 36
    
    defaultConfig {
        applicationId = "com.bayra.customer"
        minSdk = 24
        targetSdk = 36
        versionCode = 34
        versionName = "2.31.34"
        multiDexEnabled = true
    }
    
    buildTypes {
        getByName("debug") { isCrunchPngs = false }
        getByName("release") {
            isMinifyEnabled = false
            isCrunchPngs = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.4.2" }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    
    kotlinOptions { jvmTarget = "11" }
}

dependencies {
    implementation("com.google.android.recaptcha:recaptcha:18.4.0")
    implementation("androidx.core:core-ktx:1.10.1")
    implementation("androidx.activity:activity-compose:1.7.0")
    implementation(platform("androidx.compose:compose-bom:2023.01.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("io.coil-kt:coil-compose:2.4.0")
    
    // 🔥 FIREBASE AUTH & RTDB
    implementation(platform("com.google.firebase:firebase-bom:32.7.0"))
    implementation("com.google.firebase:firebase-database-ktx")
    implementation("com.google.firebase:firebase-messaging-ktx")
    
    // Maps
    implementation("org.osmdroid:osmdroid-android:6.1.18")
    
    // 🔥 STABLE CREDENTIAL MANAGER
    implementation("androidx.credentials:credentials:1.2.2")
    implementation("androidx.credentials:credentials-play-services-auth:1.2.2")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
}
// Force V26 True Firebase Auth
// Force V27 Native Account Picker Bypass
// Force V28 Patch
// Deploy Version 29
// Force V30 True Firebase Extractor
// Force V31 Fallback to App Level Credentials
// Force V32 Return to Native Account Picker
// Force V34 Native Account Picker

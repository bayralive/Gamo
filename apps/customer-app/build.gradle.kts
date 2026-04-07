plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}
android {
    namespace = "com.bayra.customer"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.bayra.customer"
        minSdk = 24
        targetSdk = 35
        versionCode = 8
        versionName = "1.7"
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
    composeOptions { kotlinCompilerExtensionVersion = "1.4.2" } // 🔥 FIXED: Match for Kotlin 1.8.10
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { jvmTarget = "11" }
}
dependencies {
    implementation("androidx.core:core-ktx:1.10.1")
    implementation("androidx.activity:activity-compose:1.7.0")
    implementation(platform("androidx.compose:compose-bom:2023.01.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("io.coil-kt:coil-compose:2.4.0")
    implementation("com.google.firebase:firebase-database-ktx:20.2.2")
    implementation("com.google.firebase:firebase-messaging-ktx:23.2.1")
    implementation("org.osmdroid:osmdroid-android:6.1.18")
}

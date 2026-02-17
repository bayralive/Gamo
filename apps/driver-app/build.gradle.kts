plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services") // 🔥 THE MISSING LINK FIXED
}

android {
    namespace = "com.bayra.driver"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.bayra.driver"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        vectorDrawables { useSupportLibrary = true }
    }
    buildTypes {
        release {
            isMinifyEnabled = false // Disabled for now to ensure OSMDroid stability
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { jvmTarget = "11" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.4.0" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.10.1")
    implementation("androidx.activity:activity-compose:1.7.0")
    implementation(platform("androidx.compose:compose-bom:2023.01.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.google.firebase:firebase-database-ktx:20.2.2")
    implementation("org.osmdroid:osmdroid-android:6.1.18")
}

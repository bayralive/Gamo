plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}
android {
    namespace = "com.bayra.customer"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.bayra.customer"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.4.0" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { 
        jvmTarget = "11" 
        freeCompilerArgs = freeCompilerArgs + "-P" + "plugin:androidx.compose.compiler.plugins.kotlin:suppressKotlinVersionCompatibilityCheck=1.8.0"
    }
}
dependencies {
    implementation("androidx.core:core-ktx:1.10.1")
    implementation("androidx.activity:activity-compose:1.7.0")
    implementation(platform("androidx.compose:compose-bom:2023.01.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("com.google.firebase:firebase-database-ktx:20.2.2")
    implementation("org.osmdroid:osmdroid-android:6.1.18")
}

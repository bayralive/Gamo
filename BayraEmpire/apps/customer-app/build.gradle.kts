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
        versionCode = 101
        versionName = "1.0.1-Sovereign"
        multiDexEnabled = true
    }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.4.0" }
    kotlinOptions { jvmTarget = "1.8" }
}
dependencies {
    implementation("org.osmdroid:osmdroid-android:6.1.18")
    implementation("com.google.android.gms:play-services-location:21.0.1")
    implementation(platform("androidx.compose:compose-bom:2023.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.7.2")
    // 🛡️ THE FIX: Correct Firebase group mapping
    implementation(platform("com.google.firebase:firebase-bom:32.7.0"))
    implementation("com.google.firebase:firebase-database-ktx")
}

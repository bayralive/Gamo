plugins { id("com.android.library"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.bayra.ui"
    compileSdk = 34
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.4.0" }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2023.06.01"))
    implementation("androidx.compose.material3:material3")
}

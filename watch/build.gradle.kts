plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.tbiliyor.watchsensors"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tbiliyor.watchsensors"
        minSdk = 30          // Wear OS 4 baseline
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")

    // Compose foundation + material — using Wear-Compose Material, NOT mobile material.
    implementation("androidx.compose.ui:ui:1.6.7")
    implementation("androidx.compose.foundation:foundation:1.6.7")

    // Wear-Compose: HorizontalPager, ScalingLazyColumn, rotary input, etc.
    implementation("androidx.wear.compose:compose-material:1.3.1")
    implementation("androidx.wear.compose:compose-foundation:1.3.1")
    implementation("androidx.wear.compose:compose-navigation:1.3.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Samsung Health Sensor SDK (Galaxy Watch BioActive sensor access).
    implementation(files("libs/samsung-health-sensor-api-1.4.1.aar"))
}

// Note: shared/SensorProtocol.kt is copied directly into each module's java
// source tree (under tb/sw/shared/). We do NOT add srcDir("../shared") here —
// that caused "Redeclaration: object SensorProtocol" because Kotlin compiled
// the same file twice (once per source root).

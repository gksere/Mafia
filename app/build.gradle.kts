plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.bluetoothdeduction"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.bluetoothdeduction"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0-prototype"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

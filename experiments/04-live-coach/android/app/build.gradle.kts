plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("app.cash.paparazzi") version "1.3.5"
}

android {
    buildFeatures { buildConfig = true }
    namespace = "com.monti.servecoach"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.monti.servecoach"
        minSdk = 29
        targetSdk = 35
        ndk { abiFilters += listOf("arm64-v8a") }       // the native part is built for it only
        versionCode = 42
        versionName = "0.10.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")   // sideloaded test build
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    androidResources { noCompress += "task" }            // the pose model, read straight from the APK
}

dependencies {
    implementation(project(":core"))
    implementation("com.google.mediapipe:tasks-vision:0.10.35")   // the pose model (PoseWatcher)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")          // the AI setup coach's WebSocket (GeminiCoach)
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.json:json:20240303")
}

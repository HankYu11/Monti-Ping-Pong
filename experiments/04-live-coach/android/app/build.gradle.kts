plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
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
        versionCode = 28
        versionName = "0.7.6"
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
}

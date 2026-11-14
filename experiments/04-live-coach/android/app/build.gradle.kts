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
        // The phone's own. -PemuAbi adds x86_64 as well, which is the only way to run this on
        // an emulator -- and an emulator is the only way to exercise the graphics chip's own
        // code (GlTap, assets/shaders) without a phone in hand. It doubles the APK, so it is
        // not what ships.
        ndk {
            abiFilters += listOf("arm64-v8a")
            if (project.hasProperty("emuAbi")) abiFilters += "x86_64"
        }
        versionCode = 68
        versionName = "0.13.12"
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

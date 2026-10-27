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
        versionCode = 18
        versionName = "0.6.6"
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
}

dependencies { implementation(project(":core")) }

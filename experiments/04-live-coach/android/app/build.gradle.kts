import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("app.cash.paparazzi") version "1.3.5"
}

// storeFile, storePassword, keyAlias, keyPassword: all four, or none (null = debug-signed release).
val releaseSigning: Map<String, String>? = run {
    val names = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    val propsFile = rootProject.file("keystore.properties")
    val values: Map<String, String?> = if (propsFile.exists()) {
        val p = Properties()
        propsFile.inputStream().use { input -> p.load(input) }
        names.associateWith { p.getProperty(it)?.trim() }
    } else {
        val env = mapOf("storeFile" to "SERVECOACH_KEYSTORE", "storePassword" to "SERVECOACH_KEYSTORE_PASSWORD",
                        "keyAlias" to "SERVECOACH_KEY_ALIAS", "keyPassword" to "SERVECOACH_KEY_PASSWORD")
        names.associateWith { System.getenv(env.getValue(it)) }
    }
    val missing = names.filter { values[it].isNullOrEmpty() }
    when {
        missing.isEmpty() -> values.mapValues { it.value!! }
        missing.size == names.size -> null
        // half a configuration is a mistake, not a choice: say so instead of quietly debug-signing
        else -> throw GradleException("Release signing: ${if (propsFile.exists()) "keystore.properties" else "SERVECOACH_* environment"} is missing $missing")
    }
}

if (releaseSigning == null) {
    // logger.quiet so that it shows under -q (build.sh); only when a release APK is actually packaged.
    tasks.matching { it.name == "packageRelease" }.configureEach {
        doFirst {
            logger.quiet("""
                |
                |WARNING: ********************************************************************
                |WARNING: This RELEASE APK is signed with the DEBUG key: no keystore.properties
                |WARNING: and no SERVECOACH_* variables. See README, "Signing a release".
                |WARNING: Fine for the phone on your desk; not for anyone else's.
                |WARNING: ********************************************************************
                |""".trimMargin())
        }
    }
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
        versionCode = 73
        versionName = "0.13.23"
    }
    // The release key (README, "Signing a release"): from android/keystore.properties, which git
    // ignores, or from SERVECOACH_* environment variables (CI). Without either, release builds are
    // signed with the debug key as before, so the offline build and CI keep working -- with a loud
    // warning, because a debug-signed APK cannot be updated by a properly signed one.
    signingConfigs {
        val s = releaseSigning
        if (s != null) create("release") {
            storeFile = rootProject.file(s.getValue("storeFile"))   // relative to android/, or absolute
            storePassword = s.getValue("storePassword")
            keyAlias = s.getValue("keyAlias")
            keyPassword = s.getValue("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (releaseSigning != null) "release" else "debug")
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

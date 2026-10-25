import org.jetbrains.kotlin.gradle.dsl.JvmTarget
// The phone-independent part: spotting a serve in the frames and packing it for the engine.
plugins { id("org.jetbrains.kotlin.jvm") }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

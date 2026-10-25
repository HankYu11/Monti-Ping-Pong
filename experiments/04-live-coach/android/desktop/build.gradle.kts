import org.jetbrains.kotlin.gradle.dsl.JvmTarget
// Runs the core on video files here (frames from ffmpeg), to test it without a phone.
plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
dependencies { implementation(project(":core")) }
application { mainClass.set("com.monti.servecoach.desktop.MainKt") }

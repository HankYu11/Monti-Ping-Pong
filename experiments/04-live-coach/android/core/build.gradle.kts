import org.jetbrains.kotlin.gradle.dsl.JvmTarget
// The phone-independent part: spotting a serve in the frames and packing it for the engine.
plugins { id("org.jetbrains.kotlin.jvm") }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

// Until 0.11.2 this module had no test dependency, so `gradle :core:test` ran nothing and said
// it had succeeded. The tracker, the window format and the prediction all live here.
dependencies { testImplementation("junit:junit:4.13.2") }
tasks.withType<Test> { testLogging { showStandardStreams = true } }

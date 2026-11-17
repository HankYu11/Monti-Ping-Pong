// The Android app's `core` (serve tracking, windows, setup check, unread causes), compiled for iOS.
// The sources are the Android module's own, not a copy: one tracker for both phones.
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework
plugins { kotlin("multiplatform") version "2.3.20" }

// The Android core's sources, copied with one change: the kotlin.math functions whose Float
// results differ on Kotlin/Native (src/iosMain/kotlin/jvmmath) are taken from there instead.
val jvmMath = listOf("hypot", "ln", "atan", "roundToInt")
fun jvmMathLine(line: String): String {
    var l = line
    for (f in jvmMath) {
        l = l.replace("import kotlin.math.$f\n", "import com.monti.servecoach.jvmmath.$f\n")
        if (l.trim() == "import kotlin.math.$f") l = "import com.monti.servecoach.jvmmath.$f"
        l = l.replace("kotlin.math.$f(", "com.monti.servecoach.jvmmath.$f(")
    }
    return l
}
val coreSrc = tasks.register<Sync>("androidCore") {
    from("../../android/core/src/main/kotlin")
    into(layout.buildDirectory.dir("generated/coresrc"))
    filter { line -> jvmMathLine(line) }
}

// The Android tests, copied with one change: Kotlin/Native refuses , . ; : / < > [ ] in a
// `backticked test name`, so those characters become _ in the names. The test bodies are untouched.
val testSrc = tasks.register<Sync>("androidTests") {
    from("../../android/core/src/test/kotlin")
    into(layout.buildDirectory.dir("generated/testsrc"))
    filter { line -> jvmMathLine(Regex("fun `([^`]*)`").replace(line) { m -> "fun `" + m.groupValues[1].replace(Regex("[,.;:/<>\\[\\]]"), "_") + "`" }) }
}
kotlin {
    val xcf = XCFramework("ServeCoachCore")
    listOf(iosArm64(), iosSimulatorArm64()).forEach {
        it.binaries.framework { baseName = "ServeCoachCore"; isStatic = true; binaryOption("bundleId", "com.monti.servecoach.core"); xcf.add(this) }
    }
    // in iosMain, not commonMain: the sources must see the JVM stand-ins in src/iosMain
    sourceSets {
        iosMain { kotlin.srcDir(coreSrc) }
        // the Android module's own tests, run on the iOS simulator over a JUnit stand-in (src/iosTest)
        // the tests' assert() is experimental on Kotlin/Native
        matching { it.name.endsWith("Test") }.all { languageSettings.optIn("kotlin.experimental.ExperimentalNativeApi") }
        iosTest {
            kotlin.srcDir(testSrc)
            kotlin.srcDir(layout.buildDirectory.dir("generated/testres"))
            dependencies { implementation(kotlin("test")) }
        }
    }
}

// where the tests' resource files are: the simulator reads the Mac's disk
val testRes = tasks.register("testResources") {
    val out = layout.buildDirectory.file("generated/testres/TestResources.kt")
    val dir = file("../../android/core/src/test/resources").absolutePath
    outputs.file(out)
    doLast { out.get().asFile.apply { parentFile.mkdirs(); writeText("package com.monti.servecoach.core\n\nconst val TEST_RESOURCES = \"$dir\"\nconst val FORMAT_FILE = \"${file("src/iosTest/resources/format-java.txt").absolutePath}\"\n") } }
}
tasks.matching { it.name.startsWith("compileTestKotlinIos") }.configureEach { dependsOn(testRes) }

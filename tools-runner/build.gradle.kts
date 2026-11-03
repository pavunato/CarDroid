plugins {
    id("org.jetbrains.kotlin.jvm")
    id("application")
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.cardroid.runner.MainKt")
}

dependencies {
    testImplementation(kotlin("test"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    // Mirror libcardroid — KotlinMfiSap uses Bouncy Castle for X25519.
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
}

// Pull cardroid's pure-Kotlin protocol files in directly so we run the SAME
// implementation as the Android app. We exclude the Android-only transports
// (IPhoneAdvertiser and CarDroidPipeline) which depend on Android APIs.
// Iap2Transport uses JVM Bluetooth stubs for stream-level regression tests.
sourceSets {
    main {
        kotlin {
            srcDir("../libcardroid/src/main/java")
            srcDir("../mfi-bridge/src/main/java")
        }
    }
}

// Drop only the files that pull in Android-specific transports/USB; we stub
// android.util.Log so files that only Log-log can compile unchanged.
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin") {
    exclude("**/IPhoneAdvertiser.kt")     // android.bluetooth.le.*
    exclude("**/CarDroidPipeline.kt")     // BluetoothAdapter parameter
    exclude("**/ExternalChipProvider.kt") // android.hardware.usb
}

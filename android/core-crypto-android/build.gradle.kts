plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.cryptum.crypto.android"
    compileSdk = 36

    defaultConfig {
        // 26 is a security floor, not a compatibility one: below it the
        // Keystore guarantees the token store relies on are best-effort.
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

// This module exists only to run core-crypto's vectors on a real device, where
// the provider is Conscrypt rather than the JDK's. It deliberately ships no
// production code of its own — anything added here would be code that the fast
// JVM test suite cannot reach.
dependencies {
    androidTestImplementation(project(":core-crypto"))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.cryptum.vault"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":core-crypto"))
    api(project(":core-api"))
    implementation(project(":feature-lock"))
    implementation(libs.kotlinx.serialization.json)
    // Raw ktor client for PUT/GET against blob SAS URIs — those calls bypass
    // ItemsApi entirely (docs/IMPLEMENTATION-PLAN.md 3.1), so core-api's
    // generated client doesn't cover them.
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.activity.compose)

    // The envelope and its mapping to the contract touch no Android API, so
    // they are proven on the JVM in seconds rather than on a booted emulator.
    // The instrumented test that 2.13 requires covers the screens, which do.
    testImplementation(kotlin("test"))

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(kotlin("test"))
    debugImplementation(libs.compose.ui.test.manifest)
}

plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    // Matches the JDK Android Studio ships and the toolchain CI will use.
    jvmToolchain(17)

    compilerOptions {
        // Warnings become errors here for the same reason TreatWarningsAsErrors
        // is set on the .NET side: a gate that tolerates warnings stops being
        // read, and in a crypto module the warnings worth reading are the ones
        // about unused results and platform types.
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

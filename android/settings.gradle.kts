// Repositories are declared here rather than per-project so no module can
// quietly introduce a source of dependencies nobody reviewed.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "cryptum-android"

// core-crypto deliberately has no Android dependency, so it is a plain Kotlin
// library: the AES-GCM primitives it uses come from the JDK/Android platform
// rather than from any Android API. That keeps its tests runnable on the JVM in
// under a second instead of requiring a booted emulator for every red-green
// cycle. An instrumented test still has to confirm the same vectors on-device,
// because Android's providers are Conscrypt rather than the JDK's — see the
// ticket referenced in CryptoCore's KDoc.
include(":core-crypto")

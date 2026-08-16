// Declared once here so every module resolves the same plugin versions. Without
// this, a module applying a plugin the root has not seen gets it "on the
// classpath with an unknown version" and Gradle refuses to check compatibility.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.android.library) apply false
}

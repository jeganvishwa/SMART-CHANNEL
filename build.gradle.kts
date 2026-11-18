// Top-level build file. Plugins are declared once here (apply false) and
// applied in the modules that need them.
//
// AGP 9 has built-in Kotlin support, so :app does NOT apply
// org.jetbrains.kotlin.android. Declaring it here with `apply false` only puts
// the chosen Kotlin Gradle Plugin version on the build classpath, so AGP, the
// Compose compiler plugin and KSP all use the same Kotlin version.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}

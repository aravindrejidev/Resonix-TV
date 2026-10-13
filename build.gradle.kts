// Top-level build file for ResonixTV.
// Plugin and library versions live in gradle/libs.versions.toml.

buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // AGP 9 has built-in Kotlin and ships its own default Kotlin Gradle plugin (KGP).
        // KGP is pinned here so the Kotlin compiler and the Compose compiler plugin always
        // run the same version. Keep it in sync with `kotlin` in gradle/libs.versions.toml.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

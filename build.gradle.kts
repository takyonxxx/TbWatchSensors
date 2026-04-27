// Top-level Gradle build file. Plugin versions only — module-level config in
// each module's own build.gradle.kts.

plugins {
    id("com.android.application") version "8.5.0" apply false
    id("org.jetbrains.kotlin.android") version "2.0.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.0" apply false
}

// Top-level build file: plugin versions live in gradle/libs.versions.toml.
// AGP 9 provides built-in Kotlin (KGP on its runtime classpath); request the
// pinned KGP version per https://developer.android.com/build/releases/past-releases/agp-9-0-0-release-notes#runtime-dependency-on-kotlin-gradle-plugin
buildscript {
    dependencies {
        classpath(libs.kotlin.gradle.plugin)
    }
}
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

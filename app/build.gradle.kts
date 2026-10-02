plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

import java.io.FileInputStream
import java.util.Properties

android {
    namespace = "org.ghostrain"
    // 37: Compose BOM 2026.08.00 (Compose 1.12.0 AARs) requires compiling
    // against API 37+. targetSdk stays 35 (no new runtime behavior opted into).
    compileSdk = 37

    defaultConfig {
        applicationId = "org.ghostrain"
        minSdk = 26
        targetSdk = 35
        versionCode = 23
        versionName = "4.1.0"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Compose dependencies land in Tasks 3-6; AndroidX flag is already on.
    buildFeatures {
        compose = true
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    // JVM unit tests (Task 2: pure-JVM prefs logic, no Android framework).
    testImplementation(libs.junit)
    // Compose settings host (Task 3+; versions managed by the BOM).
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui)
    implementation(libs.androidx.activity.compose)
    // Phase 3 (Task 9): SharedPreferences "matrix" -> DataStore Preferences
    // one-time migration; engine + Settings read via MatrixDataStore.
    implementation(libs.androidx.datastore.preferences)
}

// Built-in Kotlin (AGP 9+) defaults jvmTarget from compileOptions.targetCompatibility;
// pin it explicitly to preserve the old kotlinOptions behavior.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Automatic release signing: real credentials first, generated dev key fallback.
// - Real key: RELEASE_STORE_FILE / RELEASE_STORE_PASSWORD / RELEASE_KEY_ALIAS /
//   RELEASE_KEY_PASSWORD from local.properties (gitignored), else same-named env vars.
//   CI writes local.properties from secrets, so it keeps using the real key.
// - Fallback: a persistent local dev keystore is generated once under /keystore
//   (gitignored) and reused, so release builds are always signed with zero setup.
//   Dev-signed APKs cannot update a store-published install (signature mismatch).
val keystorePropertiesFile = rootProject.file("local.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    FileInputStream(keystorePropertiesFile).use { keystoreProperties.load(it) }
}

val releaseStoreFile: String? =
    keystoreProperties.getProperty("RELEASE_STORE_FILE") ?: System.getenv("RELEASE_STORE_FILE")
val releaseStorePassword: String? =
    keystoreProperties.getProperty("RELEASE_STORE_PASSWORD") ?: System.getenv("RELEASE_STORE_PASSWORD")
val releaseKeyAlias: String? =
    keystoreProperties.getProperty("RELEASE_KEY_ALIAS") ?: System.getenv("RELEASE_KEY_ALIAS")
val releaseKeyPassword: String? =
    keystoreProperties.getProperty("RELEASE_KEY_PASSWORD") ?: System.getenv("RELEASE_KEY_PASSWORD")

val hasReleaseSigning =
    !releaseStoreFile.isNullOrEmpty() &&
        !releaseStorePassword.isNullOrEmpty() &&
        !releaseKeyAlias.isNullOrEmpty() &&
        !releaseKeyPassword.isNullOrEmpty()

val devKeystoreFile = rootProject.file("keystore/dev-release.jks")
if (!hasReleaseSigning && !devKeystoreFile.exists()) {
    try {
        devKeystoreFile.parentFile.mkdirs()
        // providers.exec: Gradle 9 removed Project.exec; .result.get() runs
        // eagerly at configuration time and throws on nonzero exit, same as before.
        providers.exec {
            commandLine(
                "keytool", "-genkeypair",
                "-keystore", devKeystoreFile.absolutePath,
                "-storepass", "ghostrain-dev",
                "-keypass", "ghostrain-dev",
                "-alias", "ghostrain-dev",
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "10950",
                "-dname", "CN=Ghost Rain Dev, OU=Dev, O=Ghost Rain, L=Local, ST=Local, C=US"
            )
        }.result.get()
        logger.lifecycle("Generated dev release keystore at ${devKeystoreFile}.")
    } catch (e: Exception) {
        logger.warn("Dev release keystore could not be generated (${e.message}); release APK will be unsigned.")
    }
}

android {
    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = file(checkNotNull(releaseStoreFile))
                storePassword = checkNotNull(releaseStorePassword)
                keyAlias = checkNotNull(releaseKeyAlias)
                keyPassword = checkNotNull(releaseKeyPassword)
                logger.lifecycle("Signing release build with configured key.")
            } else if (devKeystoreFile.exists()) {
                storeFile = devKeystoreFile
                storePassword = "ghostrain-dev"
                keyAlias = "ghostrain-dev"
                keyPassword = "ghostrain-dev"
                logger.lifecycle("Signing release build with local dev key (not the published app signature).")
            }
        }
    }
    buildTypes {
        getByName("release") {
            if (hasReleaseSigning || devKeystoreFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}
// ACS_SIGNING_CONFIG_END

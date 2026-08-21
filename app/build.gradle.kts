plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * Build inputs that must never be committed: Gradle property first (`-PNAME=...`), then
 * environment variable, then the default. Nothing here is ever hardcoded in the repo.
 */
val secret: (String, String) -> String = { name, default ->
    (project.findProperty(name) as String?) ?: System.getenv(name) ?: default
}

val keystorePath = secret("KEYSTORE_FILE", "")
val hasKeystore = keystorePath.isNotBlank() && file(keystorePath).exists()

android {
    namespace = "com.barnamechi.betterpitch"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.barnamechi.betterpitch"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("release") {
            if (hasKeystore) {
                storeFile = file(keystorePath)
                storePassword = secret("KEYSTORE_PASSWORD", "")
                keyAlias = secret("KEY_ALIAS", "")
                keyPassword = secret("KEY_PASSWORD", "")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Without a keystore the release variant still builds, just unsigned.
            if (hasKeystore) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}

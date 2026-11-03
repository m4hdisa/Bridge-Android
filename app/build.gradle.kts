import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.m4hdisa.bridge"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.m4hdisa.bridge"
        // libbox.aar is built for Android 7 and up.
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    dependenciesInfo {
        includeInApk = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(files("libs/libbox.aar"))
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.core:core-ktx:1.16.0")

    testImplementation("junit:junit:4.13.2")
    // Android's org.json only exists on a device; unit tests run on the build machine.
    testImplementation("org.json:json:20250517")
}

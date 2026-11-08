import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The release key lives outside the repository. Without it, release APKs come out unsigned and are signed afterwards.
val releaseKeystore: String? = System.getenv("BRIDGE_KEYSTORE")?.takeIf { file(it).exists() }

android {
    namespace = "io.github.m4hdisa.bridge"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.m4hdisa.bridge"
        // libbox.aar is built for Android 7 and up.
        minSdk = 24
        targetSdk = 36
        versionCode = 5
        versionName = "0.4.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storeType = "pkcs12"
                storePassword = System.getenv("BRIDGE_KEYSTORE_PASSWORD")
                keyAlias = "bridge"
                keyPassword = System.getenv("BRIDGE_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    // One APK per processor type: sing-box and Xray are about 30 MB each for each. x86_64 is for the emulator test.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    packaging {
        // Compressed in the APK, so the download is much smaller.
        jniLibs {
            useLegacyPackaging = true
            // libxray.so is the Xray program itself (see .github/get-xray.sh), already stripped by its builders.
            keepDebugSymbols += "**/libxray.so"
            // Aether and Psiphon, for Free WARP (see .github/get-aether.sh), are programs too.
            keepDebugSymbols += "**/libaether.so"
            keepDebugSymbols += "**/libpsiphon.so"
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests {
            // For PageShots, which draws the pages on the build machine (Robolectric).
            isIncludeAndroidResources = true
            all {
                it.systemProperty("roborazzi.test.record", "true")
                System.getenv("BRIDGE_SHOTS")?.let { dir -> it.environment("BRIDGE_SHOTS", dir) }
            }
        }
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
    // appcompat only for the per-app language setting.
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    // QR codes: ZXing (Apache-2.0) draws them, and its Android screen reads them with the camera.
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0") { isTransitive = false }
    // Country flags: the Windows app's SVGs (country-flag-icons, MIT) in assets/flags, drawn with AndroidSVG (Apache-2.0).
    implementation("com.caverock:androidsvg-aar:1.4")

    testImplementation("junit:junit:4.13.2")
    // Android's org.json only exists on a device; unit tests run on the build machine.
    testImplementation("org.json:json:20250517")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.77.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.77.0")
    testImplementation(platform("androidx.compose:compose-bom:2026.06.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

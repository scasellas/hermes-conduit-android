plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("app.cash.paparazzi") version "2.0.0-alpha02"
}

android {
    namespace = "app.hermesconduit.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.hermesconduit.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The wake engine's native libraries (onnxruntime, sherpa-onnx) are large;
        // 32-bit x86 has no phones worth the extra ~40 MB.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    // One APK per ABI for sideloading (Play builds an App Bundle and splits it itself).
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        buildConfig = true
    }
    // The phone ↔ watch wire, shared with :wear.
    sourceSets["main"].java.srcDir("../wearwire/src/main/kotlin")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.05.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-process:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.navigation:navigation-compose:2.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.security:security-crypto:1.1.0-alpha07")
    implementation("androidx.browser:browser:1.8.0")
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.glance:glance-material3:1.1.1")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.6")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.33.0")
    implementation("com.mikepenz:multiplatform-markdown-renderer-code:0.33.0")
    // Health, calendar & phone, location sharing (Settings > On this device). Health Connect
    // 1.1.0 stable needs compileSdk 36; alpha12 is the last that builds against 35.
    implementation("androidx.health.connect:connect-client:1.1.0-alpha12")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    // X25519, HKDF and ChaCha20-Poly1305 for the sealed uploads (already pulled in by security-crypto).
    implementation("com.google.crypto.tink:tink-android:1.8.0")
    // Wake phrase: on-device keyword spotting, and unpacking the model archive (.tar.bz2).
    implementation("com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v1.13.8@aar")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

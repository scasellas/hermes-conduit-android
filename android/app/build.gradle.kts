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
    // The phone ↔ watch wire and the live voice protocols and sessions, shared with :wear.
    sourceSets["main"].java.srcDir("../wearwire/src/main/kotlin")
    sourceSets["main"].java.srcDir("../livewire/src/main/kotlin")
    // Robolectric integration tests read the merged manifest, resources and assets.
    testOptions { unitTests.isIncludeAndroidResources = true }
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
    // GPT-Live calls: one WebRTC peer connection (audio tracks plus the oai-events data channel).
    implementation("io.github.webrtc-sdk:android:137.7151.05")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.33.0")
    implementation("com.mikepenz:multiplatform-markdown-renderer-code:0.33.0")
    // Health, calendar & phone, location sharing (Settings > On this device). Health Connect
    // 1.1.0 stable needs compileSdk 36; alpha12 is the last that builds against 35.
    implementation("androidx.health.connect:connect-client:1.1.0-alpha12")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    // X25519, HKDF and ChaCha20-Poly1305 for the sealed uploads and pushes (security-crypto pulls in an
    // older one). 1.23.0 is what the UnifiedPush connector's Web Push decryption is built against.
    implementation("com.google.crypto.tink:tink-android:1.23.0")
    // Push wake-up through UnifiedPush (ntfy and other distributors). The connector depends on the
    // JVM `tink` artifact, which duplicates tink-android's classes: the Android one above serves it.
    implementation("org.unifiedpush.android:connector:3.3.5") {
        exclude(group = "com.google.crypto.tink", module = "tink")
    }
    // Wake phrase: on-device keyword spotting, and unpacking the model archive (.tar.bz2).
    implementation("com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v1.13.8@aar")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
    // Android Auto: the templated messaging app (chats, the voice screen) next to the notification path.
    implementation("androidx.car.app:app:1.7.0")
    implementation("androidx.car.app:app-projected:1.7.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation(composeBom)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    // Integration tests: the real Android framework on the JVM (no emulator needed).
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test:core-ktx:1.7.0")
    testImplementation("androidx.test.ext:junit-ktx:1.3.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.work:work-testing:2.10.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Robolectric integration tests (src/test/.../integration) drive the real app on the
// JVM. They get their own task with a fresh JVM per test class: the app and androidx
// (ProcessLifecycleOwner, startup initializers) keep per-process state that
// Robolectric cannot reset between tests, and Paparazzi's layoutlib JVM stays apart.
val integrationTests = "app/hermesconduit/android/integration/**"
tasks.withType<Test>().configureEach {
    if (name != "integrationTestDebug") exclude(integrationTests)
}
tasks.register<Test>("integrationTestDebug") {
    val debugUnitTest = tasks.named<Test>("testDebugUnitTest")
    description = "Runs the Robolectric integration tests against the debug build."
    group = "verification"
    testClassesDirs = files(debugUnitTest.map { it.testClassesDirs })
    classpath = files(debugUnitTest.map { it.classpath })
    include(integrationTests)
    forkEvery = 1
    maxHeapSize = "2g"
}

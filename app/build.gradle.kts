plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "hu.orajegyzet"
    compileSdk = 36

    defaultConfig {
        applicationId = "hu.orajegyzet"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging {
        resources.excludes += setOf("META-INF/NOTICE.md", "META-INF/LICENSE.md")
    }
}

// A Whisper-könyvtár a legújabb androidx.core-t húzná be (1.16.0), ami újabb
// Gradle plugint kérne. Lekényszerítjük a jelenlegi pluginhoz illő 1.13.1-re.
configurations.all {
    resolutionStrategy {
        force(
            "androidx.core:core:1.13.1",
            "androidx.core:core-ktx:1.13.1"
        )
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.navigation:navigation-compose:2.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Háttérmunka + beállítások
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Widget
    implementation("androidx.glance:glance-appwidget:1.1.0")

    // HTTP (Gemini API)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Offline STT (Vosk) — magyar modell külön letöltendő, lásd README
    implementation("com.alphacephei:vosk-android:0.3.47")
    implementation("net.java.dev.jna:jna:5.13.0@aar")

    // Offline STT (Whisper, large-v3-turbo) — helyi forrás-modul, magyarra patchelve
    // (jni.c: params.language = "hu"). Natív fordítás NDK + CMake szükséges.
    implementation(project(":whispercore"))

    // Offline LLM (Gemma a MediaPipe LLM Inference API-n) — modellfájl külön, lásd README
    implementation("com.google.mediapipe:tasks-genai:0.10.24")

    // Email (SMTP)
    implementation("com.sun.mail:android-mail:1.6.7")
    implementation("com.sun.mail:android-activation:1.6.7")
}

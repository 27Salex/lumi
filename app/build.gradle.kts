plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "io.github.salex27.lumi"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.salex27.lumi"
        minSdk = 29
        targetSdk = 36
        versionCode = 9
        versionName = "1.1.6"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // Releases are signed with the SAME key as the debug builds (~/.android/debug.keystore) so updates install over
    // the builds already on the phone. A missing keystore fails the build (it never silently makes a new key).
    signingConfigs {
        create("release") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isDebuggable = false
            signingConfig = signingConfigs.getByName("release")
            // No shrinking on purpose: LiteRT/LiteRT-LM/Vosk/Room rely on reflection and JNI names
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // LiteRT-LM ships large native libraries; they are extracted so the runtime can load them
        jniLibs {
            useLegacyPackaging = true
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi"
        )
    }
}

dependencies {
    // AndroidX & Core
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-service:2.10.0")
    implementation("androidx.activity:activity-compose:1.12.4")

    // Jetpack Compose & Material 3
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Room DB
    val roomVersion = "2.8.5"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // Jetpack Glance (Home Screen Widget)
    val glanceVersion = "1.2.0"
    implementation("androidx.glance:glance-appwidget:$glanceVersion")
    implementation("androidx.glance:glance-material3:$glanceVersion")

    // ── AI engines (see domain/ai/AssistantEngine.kt) ─────────────────────────
    // Gemini Nano through AICore (only devices with the Prompt API)
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta4")
    // On-device Gemma (LiteRT-LM), model downloaded at runtime
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.17.1")
    // Vosk: offline speech model, used for the "Train my voice" voice print (Apache 2.0)
    implementation("com.alphacephei:vosk-android:0.3.75")
    // "Oye Lumi" with openWakeWord: melspectrogram/embedding models in TFLite
    implementation("com.google.ai.edge.litert:litert:1.4.0")
    // Gemini cloud and Google Tasks are called over REST (HttpURLConnection) → no extra SDK
    // OAuth for Google Tasks (Google Identity Services Authorization API)
    implementation("com.google.android.gms:play-services-auth:22.0.0")
    // Place reminders (geofences) and "Save here"
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.antigravity.gemininanotaskmanager"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.antigravity.gemininanotaskmanager"
        minSdk = 29
        targetSdk = 36
        versionCode = 19
        versionName = "3.7.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
        // LiteRT-LM trae librerías nativas grandes; se extraen para que el runtime pueda cargarlas
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

    // Jetpack Compose & Material 3 (Dark Theme Default)
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

    // ── Motores de IA (ver domain/ai/AssistantEngine.kt) ─────────────────────
    // Gemini Nano vía AICore (solo dispositivos con Prompt API)
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta4")
    // Gemma on-device (LiteRT-LM), modelo descargado en tiempo de ejecución
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.17.1")
    // «Oye Lumi»: reconocimiento offline de la palabra de activación (Apache 2.0, gratis)
    implementation("com.alphacephei:vosk-android:0.3.75")
    // «Oye Lumi» con openWakeWord: modelos melspectrogram/embedding en TFLite
    implementation("com.google.ai.edge.litert:litert:1.4.0")
    // Gemini cloud y Google Tasks se llaman por REST (HttpURLConnection) → sin SDK extra
    // OAuth para Google Tasks (Authorization API de Google Identity Services)
    implementation("com.google.android.gms:play-services-auth:22.0.0")
    // Avisos por lugar (geovallas) y «Guardar aquí»
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val buildNumber = System.getenv("BUILD_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.myassistant.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.myassistant.app"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = System.getenv("VERSION_NAME")?.removePrefix("v")?.ifBlank { null } ?: "0.1.$buildNumber"
        // Honor 400 and almost all modern phones are 64-bit ARM; keeps the APK small.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // One fixed key so every new APK installs as an update (your memory and settings are kept).
    signingConfigs {
        create("stable") {
            storeFile = file("signing/assistant.keystore")
            storePassword = "assistant"
            keyAlias = "assistant"
            keyPassword = "assistant"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("stable")
        }
        debug {
            signingConfig = signingConfigs.getByName("stable")
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
    packaging {
        resources {
            excludes += setOf("META-INF/NOTICE.md", "META-INF/LICENSE.md", "META-INF/NOTICE", "META-INF/LICENSE", "META-INF/DEPENDENCIES")
        }
    }
    testOptions {
        unitTests.all {
            it.systemProperty("gemmaDir", System.getenv("GEMMA_TEST_DIR") ?: "")
            it.testLogging {
                events("failed", "standard_out")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
    implementation("com.sun.mail:android-mail:1.6.7")
    implementation("com.sun.mail:android-activation:1.6.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    testImplementation("junit:junit:4.13.2")
    // Desktop ONNX Runtime so CI can check the on-device model code on a normal computer.
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.22.0")
}

configurations.configureEach {
    if (name.endsWith("UnitTestRuntimeClasspath")) {
        exclude(group = "com.microsoft.onnxruntime", module = "onnxruntime-android")
    }
}

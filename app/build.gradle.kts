plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.piperreader"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.piperreader"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // Ограничиваем сборку только современной 64-битной архитектурой ARM.
        // Это уберет x86/32-битные библиотеки и уменьшит размер APK со 150 до ~50 МБ.
        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    // ИСПРАВЛЕНИЕ 2: Добавляем этот блок для решения ошибки mergeDebugNativeLibs
    packaging {
        jniLibs.pickFirsts.add("**/libc++_shared.so")
        jniLibs.pickFirsts.add("**/libonnxruntime.so")
        jniLibs.pickFirsts.add("**/libonnxruntime_providers_shared.so")
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(files("libs/sherpa-onnx.aar"))
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.26.0")
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
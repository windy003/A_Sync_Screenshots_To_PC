plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.screenshotsync"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.screenshotsync"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "2026/6/6-2"
    }

    compileOptions {
        // jcifs-ng 使用了部分 java.time API，开启脱糖兼容低版本
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        viewBinding = true
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("eu.agno3.jcifs:jcifs-ng:2.1.10")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
}

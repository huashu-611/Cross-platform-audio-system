plugins {
    id("com.android.application")
}

android {
    namespace = "com.audiohub.source"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.audiohub.source"
        minSdk = 29          // AudioPlaybackCapture 的最低要求
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
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
}

// 刻意没有任何 dependencies：纯平台 API，构建不需要联网。

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.goodload.videodl"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.goodload.videodl"
        minSdk = 29
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
        ndk { abiFilters += "arm64-v8a" }   // 갤럭시 등 최신 폰용 (용량 절약)
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // yt-dlp/python 바이너리를 실행하려면 네이티브 라이브러리를 압축 해제해야 함
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    implementation("io.github.junkfood02.youtubedl-android:library:0.17.4")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.17.4")
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.lagulee.studylock"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lagulee.studylock"
        minSdk = 29 // 안드로이드 10 이상 (갤럭시 S22·탭 S10+ 모두 해당)
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // 기기 안에서 사진 속 글씨를 읽는 무료 모델(인터넷 불필요, 한국어·숫자 인식)
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
    // 카메라 앱에 사진 저장 위치를 넘겨주는 FileProvider
    implementation("androidx.core:core:1.13.1")

    testImplementation("junit:junit:4.13.2")
}

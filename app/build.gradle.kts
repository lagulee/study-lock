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
        // GitHub Actions 실행 번호를 versionCode 로 쓴다: 빌드할 때마다 커져서 항상 '업데이트'로 설치된다
        val run = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
        versionCode = maxOf(3, run ?: 3)
        versionName = "0.6.1" + (run?.let { " (빌드 $it)" } ?: "")
    }

    // 서명 키: CI 에서는 캐시해 둔 키 파일을 직접 지정한다.
    // (지정하지 않으면 빌드할 때마다 새 키가 만들어져 기존 앱 위에 업데이트 설치가 안 된다 — v0.6.0 까지의 문제)
    signingConfigs {
        getByName("debug") {
            System.getenv("SIGN_KEYSTORE")?.let { storeFile = file(it) }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources {
            // SDK가 가져오는 라이브러리들의 라이선스·메타 파일이 겹치는 것을 정리
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/INDEX.LIST",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA",
            )
        }
    }
}

dependencies {
    // Claude API 공식 SDK (설정에 키를 넣었을 때 사진 판단에 사용)
    implementation("com.anthropic:anthropic-java:3.13.0")
    // 카메라 앱에 사진 저장 위치를 넘겨주는 FileProvider
    implementation("androidx.core:core:1.13.1")
    // 연동 서버 소켓(Anthropic SDK 가 이미 쓰는 것과 같은 버전)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    // 안드로이드 기본 org.json 은 단위 테스트에서 빈 껍데기라 진짜 구현을 넣는다
    testImplementation("org.json:json:20250517")
}

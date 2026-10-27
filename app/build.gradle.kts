plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.fastreader.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fastreader.app"
        minSdk = 26 // Android 8.0 Oreo
        targetSdk = 35
        // CI 는 실행 번호/태그로 버전을 정한다 (덮어 설치하려면 versionCode 가 계속 커져야 한다)
        versionCode = System.getenv("APP_VERSION_CODE")?.toIntOrNull()?.takeIf { it > 2 } ?: 2
        versionName = System.getenv("APP_VERSION_NAME")?.removePrefix("v")?.takeIf { it.isNotBlank() } ?: "1.1.0"
    }

    // 고정 서명 키: CI 가 Secrets 로 만든 키 파일 경로와 비밀번호를 환경 변수로 넘긴다
    val releaseStore = System.getenv("SIGNING_STORE_FILE")?.let { file(it) }?.takeIf { it.isFile }
    signingConfigs {
        if (releaseStore != null) {
            create("release") {
                storeFile = releaseStore
                storePassword = System.getenv("SIGNING_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 고정 키가 없으면(로컬 빌드, 포크 등) 디버그 키로 서명해 설치 가능한 APK를 만든다.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
        viewBinding = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
}

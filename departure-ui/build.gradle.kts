import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// 2번 '출발 전' 화면 모듈(원본: 2번 저장소 MyApplication/departure-ui, 이 파일만 1번 빌드 환경에 맞춤).
// 의존: :departure-core 만. :app·Pleos SDK import 금지.
// 1번 환경: AGP 8.8.2 / Kotlin 2.0.0 / Compose BOM 2024.04.01 / JVM 17 — :app 과 같은 값으로 맞춘다.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.pleos.departure.ui"
    compileSdk = 35

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":departure-core"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.material3)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.lifecycle.runtime.compose)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

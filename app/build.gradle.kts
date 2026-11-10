plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val targetAndroidSdk = 35
val sampleVersionMajor = 1
val sampleVersionMinor = 0
val sampleVersionPatch = 0

android {
    namespace = "ai.pleos.playground.sample.vehicle"
    compileSdk = targetAndroidSdk
    // AAOS android.car(CarUxRestrictions) 스텁 연결 — 주행 중 UX 제한 상태용. 추가 의존성 없음.
    // 없으면 import android.car.* 컴파일 실패(MyApplication/docs/pleos/api-notes/vehicle-state.md §13.1)
    useLibrary("android.car")

    defaultConfig {
        applicationId = "ai.pleos.playground.sample.vehicle.diagnostic"
        // 23 → 28: Pleos SDK 요구(API 28 이상) + 2번 :departure-core 의 java.time(API 26+) 사용
        minSdk = 28
        targetSdk = targetAndroidSdk
        versionCode =
            targetAndroidSdk * 1_000_000 +
                sampleVersionMajor * 10_000 +
                sampleVersionMinor * 100 +
                sampleVersionPatch
        versionName =
            "$sampleVersionMajor.$sampleVersionMinor.$sampleVersionPatch-sdk$targetAndroidSdk"

        buildConfigField(
            "String",
            "VEHICLE_SDK_VERSION",
            "\"${libs.versions.vehicleSdk.get()}\""
        )

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.vehicle.sdk)
    implementation(libs.kotlin.reflect)
    // 2번 출발 전 연동: NaviHelper SDK(내비 목적지·경로 상태·ETA·위치 조회) + 핵심 로직 모듈
    implementation(libs.navihelper.sdk)
    implementation(project(":departure-core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    // Room Database
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

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

    // [컨디션 판단] 속도·기어는 AAOS 표준 Car API(CarPropertyManager)로 받는다 (Pleos SDK에 없음)
    // [출발 전] 주행 중 UX 제한(CarUxRestrictionsManager)도 같은 android.car 스텁을 쓴다(MyApplication/docs/pleos/api-notes/vehicle-state.md §13.1)
    useLibrary("android.car")

    // [컨디션 판단] 단위 테스트 옵션
    //  -PwriteDemo=true      : 시연 파일(assets/condition/*.json)을 생성기로 다시 만든다
    //  -PshowTestOutput=true : 테스트의 println(결과 표)을 콘솔에 출력한다
    testOptions {
        unitTests.all {
            it.systemProperty("conditionAssetsDir", file("src/main/assets/condition").absolutePath)
            it.systemProperty("writeDemo", project.findProperty("writeDemo") ?: "false")
            it.testLogging { showStandardStreams = project.findProperty("showTestOutput") == "true" }
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

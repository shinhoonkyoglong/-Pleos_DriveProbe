import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// 2번 '출발 전' 핵심 로직 모듈(원본: 2번 저장소 MyApplication/departure-core, 이 파일만 1번 빌드 환경에 맞춤).
// 순수 Kotlin/JVM 모듈: Android·Pleos SDK 의존성 금지 (허용: kotlinx-coroutines-core, java.time)
// 1번 환경: AGP 8.8.2 / Kotlin 2.0.0 / Gradle 8.10.2 / JVM 17 — :app 과 같은 바이트코드 수준(17)으로 맞춘다.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // 포트 시그니처(Flow)에 노출되므로 api: core 를 쓰는 :app 도 같은 코루틴을 본다.
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    // 세션(session/DepartureSession) 시간 흐름 테스트용 가상 시간. 테스트 전용이라 런타임 의존성은 그대로다.
    testImplementation(libs.kotlinx.coroutines.test)
}

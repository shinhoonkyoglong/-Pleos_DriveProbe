pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://nexus-playground.pleos.ai/repository/maven-releases/")
    }
}

rootProject.name = "VehicleSampleApp"
include(":app")
include(":departure-core") // 2번 출발 전 핵심 로직(순수 Kotlin/JVM)
include(":departure-ui") // 2번 출발 전 화면(Compose, :departure-core 에만 의존)

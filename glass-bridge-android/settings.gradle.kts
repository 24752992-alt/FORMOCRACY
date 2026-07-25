pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        // Rokid 官方 Maven 仓库（CXR-L SDK 从这里拉取）
        maven { url = uri("https://maven.rokid.com/repository/maven-public/") }
    }
}

rootProject.name = "FormocracyGlassBridge"
include(":app")

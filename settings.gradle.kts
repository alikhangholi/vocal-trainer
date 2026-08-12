pluginManagement {
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.myket.ir") }
        maven { url = uri("https://en-mirror.ir") }
        maven { url = uri("https://mirror.kargadan.ir/repository/gradle-group/") }
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.myket.ir") }
        maven { url = uri("https://en-mirror.ir") }
        maven { url = uri("https://mirror.kargadan.ir/repository/maven-central-group/") }
        google()
        mavenCentral()
    }
}
rootProject.name = "VocalTrainer"
include(":app")

pluginManagement {
    // GitHub's runners reach gradlePluginPortal()/google()/mavenCentral() directly, so try them
    // first there; the regional mirrors stay first for local (Iran) builds.
    val isCi = System.getenv("GITHUB_ACTIONS") != null
    repositories {
        if (isCi) {
            gradlePluginPortal()
            google()
            mavenCentral()
        }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.myket.ir") }
        maven { url = uri("https://en-mirror.ir") }
        maven { url = uri("https://mirror.kargadan.ir/repository/gradle-group/") }
        if (!isCi) {
            gradlePluginPortal()
            google()
            mavenCentral()
        }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    val isCi = System.getenv("GITHUB_ACTIONS") != null
    repositories {
        if (isCi) {
            google()
            mavenCentral()
        }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.myket.ir") }
        maven { url = uri("https://en-mirror.ir") }
        maven { url = uri("https://mirror.kargadan.ir/repository/maven-central-group/") }
        if (!isCi) {
            google()
            mavenCentral()
        }
    }
}
rootProject.name = "betterPitch"
include(":app")

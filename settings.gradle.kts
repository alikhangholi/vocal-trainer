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
        // Poolakey (Cafe Bazaar billing) ships only via JitPack, which none of the mirrors above
        // carry - so it sits after them, where it can't affect resolution of anything else.
        maven { url = uri("https://jitpack.io") }
        if (!isCi) {
            google()
            mavenCentral()
        }
    }
}
rootProject.name = "betterPitch"
include(":app")

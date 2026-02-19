pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Guardian Project raw GitHub Maven (hosts info.guardianproject:arti-mobile-ex)
        maven { url = uri("https://raw.githubusercontent.com/guardianproject/gpmaven/master") }
        // JitPack for kbase58, zxing-android-embedded
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "bitchat-android"
include(":app")
// Using published Arti AAR; local module not included

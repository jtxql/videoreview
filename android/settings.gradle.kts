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
        maven("https://yuroyami.github.io/maven") {
            content { includeGroup("io.github.yuroyami") }
        }
    }
}
rootProject.name = "TourBoxUsbDiagnostics"
include(":app", ":player-mpv", ":video-export")

// Gradle settings for the Pulse Android project.
//
// Modules:
//   :core  Pure Kotlin/JVM: sensor interface, simulator, coaching engine. No Android.
//   :app   (Milestone 2) Android application: Polar SDK, foreground service, Room, UI.
//          Add it with `include(":app")` below; nothing else needs restructuring.

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
    // All repositories are declared here, never in module build files.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "pulse"

include(":core")

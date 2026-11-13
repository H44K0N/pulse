// Gradle settings for the Pulse Android project.
//
// Modules:
//   :core  Pure Kotlin/JVM: sensor interface, simulator, coaching engine. No Android.
//   :app   Android application: Polar SDK, foreground service, Room, settings, UI.

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
        // The Polar BLE SDK is only published on JitPack. JitPack builds ANY public GitHub
        // repository on request, so an unrestricted JitPack entry would let a typo or a
        // hijacked group name pull arbitrary code into the app. `exclusiveContent` makes it a
        // two-way lock: com.github.polarofficial may ONLY come from JitPack, and JitPack may
        // ONLY serve com.github.polarofficial. Nothing else can ever resolve from it.
        exclusiveContent {
            forRepository {
                maven("https://jitpack.io") { name = "JitPack" }
            }
            filter {
                includeGroup("com.github.polarofficial")
            }
        }
    }
}

rootProject.name = "pulse"

include(":core")
include(":app")

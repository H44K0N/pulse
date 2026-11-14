import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult

// :app is the Android application: Polar H10 connection, the foreground service that runs a
// session, Room recording, DataStore settings and the Compose UI. All coaching logic lives in
// the pure-Kotlin :core module.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "dev.tischbein.pulse"
    // compileSdk 37 (Android 17, SDK platform "android-37.0") only changes which APIs the
    // compiler and lint know about; the newest AndroidX releases require it. It does not
    // change how the app behaves on the phone (targetSdk does, see below).
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.tischbein.pulse"
        // 33 (Android 13) is the minimum of the Polar BLE SDK 8.x README. It also means the
        // modern Bluetooth permissions (BLUETOOTH_SCAN/CONNECT, Android 12+) and the
        // notification permission (Android 13+) always apply: no legacy location-permission path.
        minSdk = 33
        // Deliberately still 36 while compileSdk is 37. Raising targetSdk opts the app into
        // Android 17's behaviour changes (background/foreground-service, Bluetooth and audio
        // rules may differ), so it must wait until the owner has tested a 37 build on the
        // real phone and strap. Then bump it and read the "behavior changes: apps targeting
        // Android 17" page.
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.1"
    }

    buildTypes {
        release {
            // Kept off until Milestone 6 sets up release signing; the Polar SDK ships its own
            // consumer ProGuard rules, so turning it on later is a one-line change plus testing.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        // Robolectric needs the merged Android resources/manifest in JVM unit tests.
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

roborazzi {
    // Design mockups and screen snapshots live next to the tests, committed to git.
    outputDir.set(file("src/test/screenshots"))
}

room {
    // Schema JSON per database version, committed to git. Needed to write and test migrations.
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)

    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.polar.ble.sdk)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
}

// Guard rail for the brief's "no Google Play Services" rule: fails the build if anything
// (directly or transitively) brings in Play Services, Firebase or Play Core. Runs before
// every build via preBuild, so a new dependency that sneaks GMS in cannot go unnoticed.
val checkNoGooglePlayServices =
    tasks.register("checkNoGooglePlayServices") {
        group = "verification"
        description = "Fails if the release runtime classpath contains Google Play Services or Firebase."
        val root =
            configurations
                .named("releaseRuntimeClasspath")
                .flatMap { it.incoming.resolutionResult.rootComponent }
        val forbidden = listOf("com.google.android.gms", "com.google.firebase", "com.google.android.play")
        doLast {
            val modules = mutableSetOf<String>()

            fun walk(component: ResolvedComponentResult) {
                for (dependency in component.dependencies) {
                    if (dependency !is ResolvedDependencyResult) continue
                    val id = dependency.selected.moduleVersion ?: continue
                    if (modules.add("${id.group}:${id.name}")) walk(dependency.selected)
                }
            }
            walk(root.get())
            val offenders = modules.filter { module -> forbidden.any { module.startsWith(it) } }
            if (offenders.isNotEmpty()) {
                throw GradleException("Google Play Services / Firebase found on the classpath:\n  " + offenders.joinToString("\n  "))
            }
            logger.lifecycle("checkNoGooglePlayServices: ${modules.size} modules checked, none from GMS/Firebase.")
        }
    }

tasks.named("preBuild") {
    dependsOn(checkNoGooglePlayServices)
}

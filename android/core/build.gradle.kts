import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :core is plain Kotlin/JVM on purpose: the coaching engine, the sensor interface and the
// simulator must run in fast JVM unit tests without an Android device or emulator.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Compile to Java 17 bytecode (what Android's toolchain targets) while building on any JDK 17+.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // The only runtime dependency allowed in :core.
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnit()
}

// Guard rail: :core must never depend on Android. If someone (or an AI assistant) adds an
// `import android.x` or `import androidx.x` here, `./gradlew :core:check` fails.
val checkNoAndroidImports = tasks.register("checkNoAndroidImports") {
    group = "verification"
    description = "Fails if any :core source file imports android.* or androidx.*."
    val sources = fileTree("src") { include("**/*.kt", "**/*.java") }
    inputs.files(sources)
    doLast {
        val androidImport = Regex("""^\s*import\s+androidx?\.""", RegexOption.MULTILINE)
        val offenders = sources.files.filter { androidImport.containsMatchIn(it.readText()) }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                ":core must stay pure Kotlin/JVM, but these files import Android classes:\n" +
                    offenders.joinToString("\n") { "  $it" },
            )
        }
    }
}

tasks.named("check") {
    dependsOn(checkNoAndroidImports)
}

// Root build file. Plugins are declared here (not applied) so every module uses the
// same versions from gradle/libs.versions.toml.
//
// Milestone 2 adds `alias(libs.plugins.android.application) apply false` and
// `alias(libs.plugins.kotlin.compose) apply false` here when it creates :app.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}

plugins {
    alias(libs.plugins.androidGradleApplication) apply false
    alias(libs.plugins.androidGradleLibrary) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.plugin.serialization) apply false
    alias(libs.plugins.kover) apply false
}

val clean by tasks.registering(Delete::class) {
    delete(rootProject.buildDir)
}
